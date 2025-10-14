package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.util.List;

/**
 * IR node for composite math utilities: min, max, pow, atan2, clamp, lerp, hermiteBlend,
 * minAngle, random, randomInteger.
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
                ctx.emitIR(operands.get(1), mv);
                mv.visitInsn(Opcodes.F2D);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "atan2", "(DD)D", false);
                mv.visitInsn(Opcodes.D2F);
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
