package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node for trigonometric functions with per-input caching.
 *
 * <p><b>Purpose</b>: avoid recomputation of expensive trig on identical inputs within the same
 * {@code evaluate()} call. The cache is addressed by a stable {@code cacheIndex} keyed from the driving
 * accessor, so repeated trig of the same accessor value hits in O(1).</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>If input/output locals are already registered in
 *       {@link CompilerContext#trigInputLocals} / {@link CompilerContext#trigOutputLocals}:
 *     <ul>
 *       <li>Emit the new input, store to a temporary local.</li>
 *       <li>Compare against cached input ({@code FCMPL}). If equal, {@code FLOAD} cached output; otherwise recompute,
 *           update both cached input and cached output, then leave output on the stack.</li>
 *     </ul>
 *   </li>
 *   <li>On first encounter:
 *     <ul>
 *       <li>Emit input, store as the cached input local.</li>
 *       <li>Compute the trig function (optionally converting degrees-&gt;radians), {@code DUP} the float result, and
 *           store it as the cached output.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>Registration & layout</b>:</p>
 * <ul>
 *   <li>Caller assigns a stable {@code cacheIndex} via
 *       {@link CompilerContext#buildCachedTrigIR(IR, String)} for accessor-backed inputs.</li>
 *   <li>Locals are allocated on demand and remembered in the context maps.</li>
 * </ul>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: constant input collapses to a {@code ConstantIR} directly.</li>
 *   <li><b>Accessor value caching</b>: when fed by {@link AccessorIR}, both the input load and trig are cached.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: caches are method-local; no cross-thread sharing.</p>
 *
 * <p><b>Bytecode summary</b> (cache-hit/compute path):</p>
 * <pre>
 *   // candidate input
 *   ...emit(input)...
 *   FSTORE newIn
 *   // compare with cached input
 *   FLOAD newIn
 *   FLOAD in_cache
 *   FCMPL
 *   IFNE Lcompute
 *   // hit
 *   FLOAD out_cache
 *   GOTO Lend
 *   Lcompute:
 *   FLOAD newIn
 *   F2D
 *   [optional] LDC radians-per-degree; DMUL
 *   INVOKESTATIC Math.&lt;func&gt; (D)D
 *   D2F
 *   DUP
 *   FSTORE out_cache
 *   FLOAD newIn
 *   FSTORE in_cache
 *   Lend:
 * </pre>
 *
 * @implNote {@code convertInputToRadians} is true for {@code sin}/{@code cos}; other trig variants operate on
 * unitless ratios and skip the conversion.
 */
public record CachedTrigIR(IR input, String funcName, int cacheIndex, boolean convertInputToRadians) implements IR {
    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        Integer cachedInputLocal = ctx.trigInputLocals.get(cacheIndex);
        Integer cachedOutputLocal = ctx.trigOutputLocals.get(cacheIndex);

        if (cachedInputLocal != null && cachedOutputLocal != null) {
            Label computeLabel = new Label();
            Label endLabel = new Label();

            ctx.emitIR(input, mv);
            int newInputLocal = ctx.allocateLocal();
            mv.visitVarInsn(Opcodes.FSTORE, newInputLocal);

            mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
            mv.visitVarInsn(Opcodes.FLOAD, cachedInputLocal);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFNE, computeLabel);

            mv.visitVarInsn(Opcodes.FLOAD, cachedOutputLocal);
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);

            mv.visitLabel(computeLabel);
            emitInvocation(mv, newInputLocal, cachedOutputLocal);

            mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
            mv.visitVarInsn(Opcodes.FSTORE, cachedInputLocal);

            mv.visitLabel(endLabel);

            ctx.releaseLocal(newInputLocal);
        } else {
            ctx.emitIR(input, mv);
            int inputLocal = ctx.allocateLocal();
            mv.visitVarInsn(Opcodes.FSTORE, inputLocal);

            emitInvocation(mv, inputLocal, null);

            int outputLocal = ctx.allocateLocal();
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.FSTORE, outputLocal);

            ctx.trigInputLocals.put(cacheIndex, inputLocal);
            ctx.trigOutputLocals.put(cacheIndex, outputLocal);
        }
    }

    private void emitInvocation(MethodVisitor mv, int inputLocal, Integer cachedOutputLocal) {
        mv.visitVarInsn(Opcodes.FLOAD, inputLocal);
        mv.visitInsn(Opcodes.F2D);
        if (convertInputToRadians) {
            mv.visitLdcInsn(DEGREES_TO_RADIANS);
            mv.visitInsn(Opcodes.DMUL);
        }
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
        mv.visitInsn(Opcodes.D2F);
        if (cachedOutputLocal != null) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.FSTORE, cachedOutputLocal);
        }
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        input.collectAccessors(ctx);
    }
}
