package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node for trigonometric functions with per-input caching (sin, cos, asin, acos, atan) in degrees.
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
 *       <li>Compute degrees->radians, call {@code Math.func(D)D}, {@code D2F}, {@code DUP} to store as cached output local.</li>
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
 *   LDC 0.017453292519943295
 *   DMUL
 *   INVOKESTATIC Math.&lt;func&gt; (D)D
 *   D2F
 *   FSTORE out_cache
 *   FLOAD newIn
 *   FSTORE in_cache
 *   FLOAD out_cache
 *   Lend:
 * </pre>
 *
 * @implNote Degrees are converted via a baked constant to avoid method calls for {@code toRadians}.
 * @see CompilerContext#buildCachedTrigIR(IR, String)
 * @see CompilerContext#trigInputLocals
 * @see CompilerContext#trigOutputLocals
 */
public record CachedTrigIR(IR input, String funcName, int cacheIndex) implements IR {
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
            mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
            mv.visitInsn(Opcodes.F2D);
            mv.visitLdcInsn(0.017453292519943295);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
            mv.visitInsn(Opcodes.D2F);
            mv.visitVarInsn(Opcodes.FSTORE, cachedOutputLocal);

            mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
            mv.visitVarInsn(Opcodes.FSTORE, cachedInputLocal);

            mv.visitVarInsn(Opcodes.FLOAD, cachedOutputLocal);

            mv.visitLabel(endLabel);

            ctx.releaseLocal(newInputLocal);
        } else {
            ctx.emitIR(input, mv);
            int inputLocal = ctx.allocateLocal();
            mv.visitVarInsn(Opcodes.FSTORE, inputLocal);

            mv.visitVarInsn(Opcodes.FLOAD, inputLocal);
            mv.visitInsn(Opcodes.F2D);
            mv.visitLdcInsn(0.017453292519943295);
            mv.visitInsn(Opcodes.DMUL);
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
            mv.visitInsn(Opcodes.D2F);

            int outputLocal = ctx.allocateLocal();
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.FSTORE, outputLocal);

            ctx.trigInputLocals.put(cacheIndex, inputLocal);
            ctx.trigOutputLocals.put(cacheIndex, outputLocal);
        }
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        input.collectAccessors(ctx);
    }
}
