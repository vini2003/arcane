package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * IR node for composite math utilities: min, max, pow, atan2 (deg inputs), clamp, lerp, hermiteBlend,
 * minAngle, random, randomInteger.
 *
 * <p><b>Purpose</b>: consolidate multi-operand math that either maps to single intrinsics or to short
 * inlined sequences that avoid boxing and reduce JNI/Math overhead.</p>
 *
 * <p><b>Emission strategy</b> (by {@code type}):</p>
 * <ul>
 *   <li><b>min/max</b>: emit two operands; {@code INVOKESTATIC Math.min/max (FF)F}.</li>
 *   <li><b>pow</b>: {@code F2D} both operands; {@code Math.pow(DD)D}; {@code D2F}.</li>
 *   <li><b>atan2</b>: convert both inputs from degreesÃƒÂ¢Ã¢â‚¬Â Ã¢â‚¬â„¢radians in double precision; call {@code Math.atan2(DD)D}; {@code D2F}.</li>
 *   <li><b>clamp</b>: compute {@code max(min, min(value, max))} using {@code Math.max/min(FF)F} with locals.</li>
 *   <li><b>lerp</b>: {@code a + (b - a) * t} with three float locals to minimize stack traffic.</li>
 *   <li><b>hermiteBlend</b>: inlined {@code 3t^2 - 2t^3} using two temporaries (tÃƒâ€šÃ‚Â², tÃƒâ€šÃ‚Â³).</li>
 *   <li><b>minAngle</b>: clamp input to {@code [-180, 180]} in-place via {@code Math.max/min}.</li>
 *   <li><b>random</b>: {@code low + Random.nextFloat() * (high - low)} using {@code Molang.RANDOM}.</li>
 *   <li><b>randomInteger</b>: inclusive-high integer: {@code floor(low + rand*(high+0.999 - low))} then {@code I2F}.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>: delegates to all operands; no accessor/capture state.</p>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: when all operands are {@code ConstantIR}, the compiler computes the result with
 *       {@link CompilerContext#computeComplexMath(String, List)}.</li>
 *   <li><b>Local caching</b>: high-arity cases (clamp, lerp, hermiteBlend) use temporaries to reduce re-evaluation.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>:</p>
 * <ul>
 *   <li>All deterministic ops are pure.</li>
 *   <li>Random ops read from {@code Molang.RANDOM}; callers should assume thread-safety properties of that RNG.</li>
 * </ul>
 *
 * <p><b>Bytecode summary</b> (examples):</p>
 * <pre>
 *   // min(a,b):
 *   ...emit(a)... ...emit(b)...
 *   INVOKESTATIC java/lang/Math.min (FF)F
 *
 *   // lerp(a,b,t):
 *   ...emit(a)... FSTORE la
 *   ...emit(b)... FSTORE lb
 *   ...emit(t)... FSTORE lt
 *   FLOAD lb
 *   FLOAD la
 *   FSUB
 *   FLOAD lt
 *   FMUL
 *   FLOAD la
 *   FADD
 * </pre>
 *
 * @implNote {@code atan2} treats inputs as degrees and converts to radians before delegation to {@code Math.atan2}.
 * @see CompilerContext#computeComplexMath(String, List)
 */
public record ComplexMathIR(List<IR> operands, String type) implements IR {
    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        switch (type) {
            case "min":
            case "max":
                ctx.emitIR(operands.get(0), mv);
                ctx.emitIR(operands.get(1), mv);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", type, "(FF)F", false);
                break;
            case "pow":
                ctx.emitIR(operands.get(0), mv);
                mv.visitInsn(Opcodes.F2D);
                ctx.emitIR(operands.get(1), mv);
                mv.visitInsn(Opcodes.F2D);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "pow", "(DD)D", false);
                mv.visitInsn(Opcodes.D2F);
                break;
            case "atan2":
                ctx.emitIR(operands.get(0), mv);
                mv.visitInsn(Opcodes.F2D);
                mv.visitLdcInsn(0.017453292519943295);
                mv.visitInsn(Opcodes.DMUL);
                int yLocal = ctx.allocateLocal();
                mv.visitInsn(Opcodes.DUP2);
                mv.visitVarInsn(Opcodes.DSTORE, yLocal);

                ctx.emitIR(operands.get(1), mv);
                mv.visitInsn(Opcodes.F2D);
                mv.visitLdcInsn(0.017453292519943295);
                mv.visitInsn(Opcodes.DMUL);

                mv.visitVarInsn(Opcodes.DLOAD, yLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "atan2", "(DD)D", false);
                mv.visitInsn(Opcodes.D2F);

                ctx.releaseLocal(yLocal);
                ctx.releaseLocal(yLocal + 1);
                break;
            case "clamp":
                ctx.emitIR(operands.get(0), mv);
                int valueLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, valueLocal);

                ctx.emitIR(operands.get(1), mv);
                int minLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, minLocal);

                ctx.emitIR(operands.get(2), mv);
                int maxLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, maxLocal);

                mv.visitVarInsn(Opcodes.FLOAD, valueLocal);
                mv.visitVarInsn(Opcodes.FLOAD, minLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
                mv.visitVarInsn(Opcodes.FLOAD, maxLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(FF)F", false);

                ctx.releaseLocal(valueLocal);
                ctx.releaseLocal(minLocal);
                ctx.releaseLocal(maxLocal);
                break;
            case "lerp":
                ctx.emitIR(operands.get(0), mv);
                int aLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, aLocal);

                ctx.emitIR(operands.get(1), mv);
                int bLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, bLocal);

                ctx.emitIR(operands.get(2), mv);
                int tLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, tLocal);

                mv.visitVarInsn(Opcodes.FLOAD, bLocal);
                mv.visitVarInsn(Opcodes.FLOAD, aLocal);
                mv.visitInsn(Opcodes.FSUB);
                mv.visitVarInsn(Opcodes.FLOAD, tLocal);
                mv.visitInsn(Opcodes.FMUL);
                mv.visitVarInsn(Opcodes.FLOAD, aLocal);
                mv.visitInsn(Opcodes.FADD);

                ctx.releaseLocal(aLocal);
                ctx.releaseLocal(bLocal);
                ctx.releaseLocal(tLocal);
                break;
            case "hermiteBlend":
                ctx.emitIR(operands.get(0), mv);
                int tLocal2 = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, tLocal2);

                mv.visitVarInsn(Opcodes.FLOAD, tLocal2);
                mv.visitVarInsn(Opcodes.FLOAD, tLocal2);
                mv.visitInsn(Opcodes.FMUL);
                int squaredLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, squaredLocal);

                mv.visitVarInsn(Opcodes.FLOAD, squaredLocal);
                mv.visitVarInsn(Opcodes.FLOAD, tLocal2);
                mv.visitInsn(Opcodes.FMUL);
                int cubedLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, cubedLocal);

                IR.pushFloat(mv, 3.0f);
                mv.visitVarInsn(Opcodes.FLOAD, squaredLocal);
                mv.visitInsn(Opcodes.FMUL);

                IR.pushFloat(mv, 2.0f);
                mv.visitVarInsn(Opcodes.FLOAD, cubedLocal);
                mv.visitInsn(Opcodes.FMUL);

                mv.visitInsn(Opcodes.FSUB);

                ctx.releaseLocal(tLocal2);
                ctx.releaseLocal(squaredLocal);
                ctx.releaseLocal(cubedLocal);
                break;
            case "minAngle":
                ctx.emitIR(operands.get(0), mv);
                int midLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, midLocal);

                IR.pushFloat(mv, -180.0f);
                mv.visitVarInsn(Opcodes.FLOAD, midLocal);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
                IR.pushFloat(mv, 180.0f);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(FF)F", false);

                ctx.releaseLocal(midLocal);
                break;
            case "random":
                ctx.emitIR(operands.get(0), mv);
                int lowLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, lowLocal);

                ctx.emitIR(operands.get(1), mv);
                int highLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, highLocal);

                mv.visitFieldInsn(Opcodes.GETSTATIC, dev.omega.arcane.compiler.Compiler.RANDOM_FIELD_OWNER, "RANDOM", dev.omega.arcane.compiler.Compiler.RANDOM_FIELD_DESC);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Random", "nextFloat", "()F", false);

                mv.visitVarInsn(Opcodes.FLOAD, highLocal);
                mv.visitVarInsn(Opcodes.FLOAD, lowLocal);
                mv.visitInsn(Opcodes.FSUB);
                mv.visitInsn(Opcodes.FMUL);
                mv.visitVarInsn(Opcodes.FLOAD, lowLocal);
                mv.visitInsn(Opcodes.FADD);

                ctx.releaseLocal(lowLocal);
                ctx.releaseLocal(highLocal);
                break;
            case "randomInteger":
                ctx.emitIR(operands.get(0), mv);
                int lowLocal2 = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, lowLocal2);

                ctx.emitIR(operands.get(1), mv);
                int highLocal2 = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, highLocal2);

                mv.visitFieldInsn(Opcodes.GETSTATIC, dev.omega.arcane.compiler.Compiler.RANDOM_FIELD_OWNER, "RANDOM", dev.omega.arcane.compiler.Compiler.RANDOM_FIELD_DESC);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Random", "nextFloat", "()F", false);

                mv.visitVarInsn(Opcodes.FLOAD, highLocal2);
                IR.pushFloat(mv, 0.999f);
                mv.visitInsn(Opcodes.FADD);
                mv.visitVarInsn(Opcodes.FLOAD, lowLocal2);
                mv.visitInsn(Opcodes.FSUB);
                mv.visitInsn(Opcodes.FMUL);
                mv.visitVarInsn(Opcodes.FLOAD, lowLocal2);
                mv.visitInsn(Opcodes.FADD);
                mv.visitInsn(Opcodes.F2I);
                mv.visitInsn(Opcodes.I2F);

                ctx.releaseLocal(lowLocal2);
                ctx.releaseLocal(highLocal2);
                break;
        }
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        for (IR op : operands) {
            op.collectAccessors(ctx);
        }
    }
}
