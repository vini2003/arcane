package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;

/**
 * IR node that emits a constant {@code float} literal.
 *
 * <p><b>Purpose</b>: represents an immutable numeric leaf in the expression graph. It is the canonical
 * sink for constant folding and algebraic simplifications so downstream passes can replace computed
 * subtrees with a single literal.</p>
 *
 * <p><b>Emission strategy</b>:</p>
 * <ul>
 *   <li>Emits the literal using {@code FCONST_0/1/2} where applicable; otherwise loads via {@code LDC}.</li>
 *   <li>No locals are allocated by this node directly; caching is handled by the surrounding
 *       {@link CompilerContext#emitIR(IR, org.objectweb.asm.MethodVisitor)}
 *       when reference counts warrant it.</li>
 * </ul>
 *
 * <p><b>Interaction with other optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: binary/unary/math/trig folders aggressively replace subgraphs with {@code ConstantIR}.</li>
 *   <li><b>Algebraic simplification</b>: identities like {@code x*0 -> 0} or {@code x+0 -> x} collapse into or through constants.</li>
 * </ul>
 *
 * <p><b>Threading & safety</b>: the value is embedded in bytecode; no shared state.</p>
 *
 * <p><b>Bytecode summary</b>:</p>
 * <pre>
 *   // Example for 0.0f, 1.0f, 2.0f fast paths
 *   FCONST_0 | FCONST_1 | FCONST_2
 *   // Otherwise:
 *   LDC &lt;float literal&gt;
 * </pre>
 *
 * @implNote This node cannot throw and never allocates at runtime.
 * @see CompilerContext#constantFold(IR)
 * @see CompilerContext#algebraicSimplify(IR)
 */
public final class ConstantIR extends IR {
    public final float value;

    public ConstantIR(float value) {
        this.value = value;
    }

    @Override
    public void emit(MethodVisitor mv, CompilerContext ctx) {
        IR.pushFloat(mv, value);
    }

    @Override
    public void collectAccessors(CompilerContext ctx) {

    }
}
