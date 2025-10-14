package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.compiler.Compiler;
import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * IR node that defers evaluation to a captured, non-compiled {@link MolangExpression}.
 *
 * <p><b>Purpose</b>: provide a safe escape hatch when an AST construct is not (or not yet) supported by the
 * JIT pipeline. The original expression is preserved in a {@code captured[]} array and invoked directly.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>Load {@code this.captured[&lt;index&gt;]} and call {@code MolangExpression.evaluate()} via interface dispatch.</li>
 * </ul>
 *
 * <p><b>Registration & layout</b>:</p>
 * <ul>
 *   <li>During IR building, the expression is appended to {@link CompilerContext#captured} and the
 *       {@code captureIndex} is stored in this node.</li>
 *   <li>The generated class constructor wires the {@code captured[]} field from the provided array.</li>
 * </ul>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li>By design, this node is opaque to constant folding and algebraic simplification.</li>
 *   <li>Outer local caching may still memoize its result when the node is shared; the
 *       {@link CompilerContext} tracks that metadata.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: behavior is that of the delegated expression; this node adds no shared state.</p>
 *
 * <p><b>Bytecode summary</b>:</p>
 * <pre>
 *   ALOAD 0
 *   GETFIELD this.captured : [Ldev/omega/arcane/ast/MolangExpression;
 *   ICONST &lt;index&gt;
 *   AALOAD
 *   INVOKEINTERFACE dev/omega/arcane/ast/MolangExpression.evaluate ()F
 * </pre>
 *
 * @implNote Prefer extending the IR set over relying on this node in hot paths; it forfeits JIT inlining benefits.
 * @see CompilerContext#captured
 */
public record FallbackIR(MolangExpression expression, int captureIndex) implements IR {
    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "captured", "[" + Compiler.MOLANG_EXPRESSION_DESC);
        IR.pushInt(mv, captureIndex);
        mv.visitInsn(Opcodes.AALOAD);
        mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, Compiler.MOLANG_EXPRESSION_INTERNAL, "evaluate", "()F", true);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {
    }
}
