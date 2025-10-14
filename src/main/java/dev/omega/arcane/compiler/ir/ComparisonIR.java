package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node for numeric comparisons producing a float predicate ({@code 0.0f} or {@code 1.0f}).
 *
 * <p><b>Purpose</b>: implements ordered relations by compiling {@code left ? right} into an {@code FCMPL}
 * followed by a conditional jump. True yields {@code 1.0f}, false yields {@code 0.0f}.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>Emit {@code left}, then {@code right}, then {@code FCMPL}.</li>
 *   <li>Branch using a provided {@code jumpOpcode} ({@code IFLT, IFGT, IFLE, IFGE, IFEQ, IFNE}).</li>
 *   <li>Materialize the predicate on the stack by pushing {@code 0.0f} or {@code 1.0f} with labels.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>: delegates to both operands; no accessor/capture state.</p>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: two constant children are compared at compile time and replaced by {@code ConstantIR(0|1)}.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: pure control flow; no shared state.</p>
 *
 * <p><b>Bytecode summary</b>:</p>
 * <pre>
 *   ...emit(left)...
 *   ...emit(right)...
 *   FCMPL
 *   IF&lt;cond&gt; Ltrue
 *   FCONST_0
 *   GOTO Lend
 *   Ltrue:
 *   FCONST_1
 *   Lend:
 * </pre>
 *
 * @implNote {@code FCMPL} orders NaN as less; the chosen opcode defines final semantics.
 * @see CompilerContext#constantFold(IR)
 */
public record ComparisonIR(IR left, IR right, int jumpOpcode) implements IR {
    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        ctx.emitIR(left, mv);
        ctx.emitIR(right, mv);
        mv.visitInsn(Opcodes.FCMPL);

        Label trueLabel = new Label();
        Label endLabel = new Label();
        mv.visitJumpInsn(jumpOpcode, trueLabel);

        IR.pushFloat(mv, 0.0f);
        mv.visitJumpInsn(Opcodes.GOTO, endLabel);

        mv.visitLabel(trueLabel);
        IR.pushFloat(mv, 1.0f);

        mv.visitLabel(endLabel);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        left.collectAccessors(ctx);
        right.collectAccessors(ctx);
    }
}
