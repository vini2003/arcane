package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node for numeric comparisons producing a float predicate ({@code 0.0f} or {@code 1.0f}).
 *
 * <p><b>Purpose</b>: implements ordered relations by compiling {@code left ? right} into explicit comparison code
 * with consistent NaN handling (all comparisons involving NaN are {@code false} except {@code !=}).</p>
 */
public record ComparisonIR(IR left, IR right, int jumpOpcode) implements IR {
    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        int leftLocal = ctx.allocateLocal();
        int rightLocal = ctx.allocateLocal();

        ctx.emitIR(left, mv);
        mv.visitVarInsn(Opcodes.FSTORE, leftLocal);
        ctx.emitIR(right, mv);
        mv.visitVarInsn(Opcodes.FSTORE, rightLocal);

        boolean nanIsTrue = jumpOpcode == Opcodes.IFNE;
        Label trueLabel = new Label();
        Label falseLabel = new Label();
        Label endLabel = new Label();

        // NaN short-circuit
        mv.visitVarInsn(Opcodes.FLOAD, leftLocal);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "isNaN", "(F)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, nanIsTrue ? trueLabel : falseLabel);

        mv.visitVarInsn(Opcodes.FLOAD, rightLocal);
        mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "isNaN", "(F)Z", false);
        mv.visitJumpInsn(Opcodes.IFNE, nanIsTrue ? trueLabel : falseLabel);

        mv.visitVarInsn(Opcodes.FLOAD, leftLocal);
        mv.visitVarInsn(Opcodes.FLOAD, rightLocal);
        if (jumpOpcode == Opcodes.IFGT || jumpOpcode == Opcodes.IFGE) {
            mv.visitInsn(Opcodes.FCMPG);
        } else {
            mv.visitInsn(Opcodes.FCMPL);
        }
        mv.visitJumpInsn(jumpOpcode, trueLabel);

        mv.visitLabel(falseLabel);
        IR.pushFloat(mv, 0.0f);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(trueLabel);
        IR.pushFloat(mv, 1.0f);

        mv.visitLabel(endLabel);

        ctx.releaseLocal(leftLocal);
        ctx.releaseLocal(rightLocal);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        left.collectAccessors(ctx);
        right.collectAccessors(ctx);
    }
}
