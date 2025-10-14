package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import dev.omega.arcane.lexer.MolangTokenType;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node for unary operators: {@code +x}, {@code -x}, and logical-not {@code !x} (0/1 semantics).
 *
 * <p><b>Purpose</b>: provide efficient unary transformations, including a boolean-like inversion that maps
 * {@code 0 -> 1}, {@code 1 -> 0}, and leaves all other values unchanged (evaluates to 0) per Molang rules.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>{@code +x}: pass-through; emits the operand unchanged.</li>
 *   <li>{@code -x}: {@code FNEG} over the operand.</li>
 *   <li>{@code !x}: stores the operand to a local, compares against {@code 0.0f} and {@code 1.0f} via
 *       {@code FCMPL} + conditional jumps, and pushes {@code 1.0f} or {@code 0.0f} accordingly.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>: delegates to the operand; no accessor/capture state.</p>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: {@code -C}, {@code +C}, and {@code !C} reduce to a {@code ConstantIR}.</li>
 *   <li><b>Algebraic simplification</b>: {@code -(-x) -> x} is recognized and collapsed.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: pure local control flow and arithmetic.</p>
 *
 * <p><b>Bytecode summary</b> (logical-not path):</p>
 * <pre>
 *   ...emit(operand)...
 *   FSTORE v
 *   // if v == 0 -> push 1
 *   FLOAD v
 *   FCONST_0
 *   FCMPL
 *   IFNE LcheckOne
 *   FCONST_1
 *   GOTO Lend
 *   LcheckOne:
 *   // if v == 1 -> push 0
 *   FLOAD v
 *   FCONST_1
 *   FCMPL
 *   IFNE Lend
 *   FCONST_0
 *   Lend:
 * </pre>
 *
 * @implNote Uses {@code FCMPL} to get well-defined NaN branch behavior consistent with JVM semantics.
 * @see CompilerContext#constantFold(IR)
 * @see CompilerContext#algebraicSimplify(IR)
 */
public final class UnaryOpIR extends IR {
    public final IR operand;
    public final MolangTokenType operator;

    public UnaryOpIR(IR operand, MolangTokenType operator) {
        this.operand = operand;
        this.operator = operator;
        operand.refCount++;
    }

    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        ctx.emitIR(operand, mv);

        if (operator == dev.omega.arcane.lexer.MolangTokenType.MINUS) {
            mv.visitInsn(Opcodes.FNEG);
        } else if (operator == MolangTokenType.PLUS) {
            // No-op
        } else if (operator == MolangTokenType.BANG) {
            int valueLocal = ctx.allocateLocal();
            mv.visitVarInsn(Opcodes.FSTORE, valueLocal);

            Label checkOne = new Label();
            Label end = new Label();

            mv.visitVarInsn(Opcodes.FLOAD, valueLocal);
            IR.pushFloat(mv, 0.0f);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFNE, checkOne);

            IR.pushFloat(mv, 1.0f);
            mv.visitJumpInsn(Opcodes.GOTO, end);

            mv.visitLabel(checkOne);
            mv.visitVarInsn(Opcodes.FLOAD, valueLocal);
            IR.pushFloat(mv, 1.0f);
            mv.visitInsn(Opcodes.FCMPL);
            mv.visitJumpInsn(Opcodes.IFNE, end);

            IR.pushFloat(mv, 0.0f);

            mv.visitLabel(end);

            ctx.releaseLocal(valueLocal);
        }
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        operand.collectAccessors(ctx);
    }
}
