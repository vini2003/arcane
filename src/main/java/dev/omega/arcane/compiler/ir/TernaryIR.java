package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node for ternary selection {@code cond ? trueValue : falseValue} with float predicates.
 *
 * <p><b>Purpose</b>: branches based on Molang truthiness where non-zero is true. The condition is compared
 * against {@code 0.0f} via {@code FCMPL} and {@code IFEQ} to choose which arm to evaluate/leave on stack.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>Emit {@code condition}, compare to {@code 0.0f} with {@code FCMPL}, {@code IFEQ} to false-arm label.</li>
 *   <li>Emit {@code trueValue}, jump past the false arm; then emit {@code falseValue}.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>: delegates to all three children; no accessor/capture state.</p>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: constant condition short-circuits to the corresponding arm.</li>
 *   <li><b>Common subexpression</b>: outer caching can store condition/arms when reused elsewhere.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: pure local control flow.</p>
 *
 * <p><b>Bytecode summary</b>:</p>
 * <pre>
 *   ...emit(condition)...
 *   FCONST_0
 *   FCMPL
 *   IFEQ Lfalse
 *   ...emit(trueValue)...
 *   GOTO Lend
 *   Lfalse:
 *   ...emit(falseValue)...
 *   Lend:
 * </pre>
 *
 * @implNote Only the selected arm is evaluated at runtime, preserving short-circuit behavior.
 * @see CompilerContext#constantFold(IR)
 */
public final class TernaryIR extends IR {
    public final IR condition;
    public final IR trueValue;
    public final IR falseValue;

    public TernaryIR(IR condition, IR trueValue, IR falseValue) {
        this.condition = condition;
        this.trueValue = trueValue;
        this.falseValue = falseValue;
        condition.refCount++;
        trueValue.refCount++;
        falseValue.refCount++;
    }

    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        ctx.emitIR(condition, mv);
        IR.pushFloat(mv, 0.0f);
        mv.visitInsn(Opcodes.FCMPL);

        Label falseLabel = new Label();
        Label endLabel = new Label();
        mv.visitJumpInsn(Opcodes.IFEQ, falseLabel);

        ctx.emitIR(trueValue, mv);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(falseLabel);
        ctx.emitIR(falseValue, mv);

        mv.visitLabel(endLabel);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        condition.collectAccessors(ctx);
        trueValue.collectAccessors(ctx);
        falseValue.collectAccessors(ctx);
    }
}
