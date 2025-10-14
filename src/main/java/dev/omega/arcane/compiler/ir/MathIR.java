package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node that delegates to {@link Math} for scalar functions (abs, ceil, floor, sqrt, exp, log,
 * trunc, round, sin, cos, asin, acos, atan).
 *
 * <p><b>Purpose</b>: emit efficient math calls while handling float/double conversions and, when required,
 * degrees-to-radians conversion controlled by {@code needsRadians}.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>Emit input.</li>
 *   <li>For intrinsics with float overloads or integer intermediates:
 *     <ul>
 *       <li>{@code abs(float)}: {@code INVOKESTATIC Math.abs (F)F}.</li>
 *       <li>{@code trunc}: {@code F2I} then {@code I2F}.</li>
 *       <li>{@code round}: {@code Math.round(F)I} then {@code I2F}.</li>
 *     </ul>
 *   </li>
 *   <li>Otherwise:
 *     <ul>
 *       <li>{@code F2D}; if {@code needsRadians}, multiply by {@code Math.toRadians(1)} constant.</li>
 *       <li>{@code INVOKESTATIC Math.&lt;func&gt; (D)D}, then {@code D2F}.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p><b>Registration & layout</b>: delegates to the input; no accessor/capture state.</p>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: constant input computes to {@code ConstantIR} using
 *       {@link CompilerContext#computeMathFunc(String, float, boolean)}.</li>
 *   <li><b>Trig caching upgrade</b>: plain trig calls may be replaced upstream by
 *       {@link CachedTrigIR} when fed by accessor-driven inputs.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: pure function calls; no shared state.</p>
 *
 * <p><b>Bytecode summary</b> (generic double path):</p>
 * <pre>
 *   ...emit(input)...
 *   F2D
 *   LDC 0.017453292519943295  // if needsRadians
 *   DMUL                       // if needsRadians
 *   INVOKESTATIC java/lang/Math.&lt;func&gt; (D)D
 *   D2F
 * </pre>
 *
 * @implNote Choice of {@code F2D/D2F} preserves precision typical of JVM Math while returning float.
 * @see CompilerContext#computeMathFunc(String, float, boolean)
 */
public record MathIR(IR input, String funcName, boolean needsRadians) implements IR {
    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        ctx.emitIR(input, mv);

        if ("abs".equals(funcName)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
        } else if ("trunc".equals(funcName)) {
            mv.visitInsn(Opcodes.F2I);
            mv.visitInsn(Opcodes.I2F);
        } else if ("round".equals(funcName)) {
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(F)I", false);
            mv.visitInsn(Opcodes.I2F);
        } else {
            mv.visitInsn(Opcodes.F2D);
            if (needsRadians) {
                mv.visitLdcInsn(0.017453292519943295);
                mv.visitInsn(Opcodes.DMUL);
            }
            mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
            mv.visitInsn(Opcodes.D2F);
        }
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        input.collectAccessors(ctx);
    }
}
