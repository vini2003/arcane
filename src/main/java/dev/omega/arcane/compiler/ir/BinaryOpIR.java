package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;

/**
 * IR node for primitive float binary arithmetic ({@code +, -, *, /, %}).
 *
 * <p><b>Purpose</b>: fuses two numeric sub-expressions and emits the corresponding single-slot
 * JVM float arithmetic instruction.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>Emit {@code left}, then {@code right}, then the selected opcode
 *       ({@code FADD, FSUB, FMUL, FDIV, FREM}).</li>
 *   <li>Local caching of subtrees (when beneficial) is orchestrated by
 *       {@link CompilerContext#emitIR(IR, org.objectweb.asm.MethodVisitor)} based on
 *       {@link IR#refCount} and assigned {@link IR#local}.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>: delegates to child nodes; no accessor/capture state.</p>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: two {@code ConstantIR} children are reduced to a new {@code ConstantIR}.</li>
 *   <li><b>Algebraic simplification</b>: canonical identities are applied (e.g., {@code x*1->x}, {@code x+0->x},
 *       {@code 2*x->x+x}, {@code x/1->x}, {@code 0-x->-x}).</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: pure numeric operations; no shared state.</p>
 *
 * <p><b>Bytecode summary</b>:</p>
 * <pre>
 *   // left
 *   ...emit(left)...
 *   // right
 *   ...emit(right)...
 *   // operation
 *   FADD | FSUB | FMUL | FDIV | FREM
 * </pre>
 *
 * @implNote Division by zero follows JVM float semantics (Infinity/NaN).
 * @see CompilerContext#constantFold(IR)
 * @see CompilerContext#algebraicSimplify(IR)
 */
public final class BinaryOpIR extends IR {
    public final IR left;
    public final IR right;
    public final int opcode;

    public BinaryOpIR(IR left, IR right, int opcode) {
        this.left = left;
        this.right = right;
        this.opcode = opcode;
        left.refCount++;
        right.refCount++;
    }

    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        ctx.emitIR(left, mv);
        ctx.emitIR(right, mv);
        mv.visitInsn(opcode);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
        left.collectAccessors(ctx);
        right.collectAccessors(ctx);
    }
}
