package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.compiler.CompilerContext;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Abstract node in the compiler's Intermediate Representation (IR) graph.
 *
 * <p><b>Purpose</b>: provides a compact, optimization-friendly layer between the parsed
 * Molang AST and the emitted JVM bytecode. Each {@code IR} node models a float-producing
 * computation and can participate in common subexpression caching, constant folding,
 * algebraic simplification, and backend codegen.</p>
 *
 * <p><b>Lifecycle</b>:</p>
 * <ol>
 *   <li><b>Build</b>: created from AST via {@link CompilerContext#buildIR(dev.omega.arcane.ast.MolangExpression)}.
 *       Implementations must increment child {@link #refCount} in their constructors to enable reuse analysis.</li>
 *   <li><b>Optimize</b>: transformed by {@link CompilerContext#optimize(IR)}
 *       using {@link CompilerContext#constantFold(IR)} and
 *       {@link CompilerContext#algebraicSimplify(IR)}.</li>
 *   <li><b>Accessor collection</b>: {@link #collectAccessors(CompilerContext)} walks the IR
 *       to register query accessors/targets before codegen (populates accessor arrays/fields in the generated class).</li>
 *   <li><b>Local assignment</b>: {@link CompilerContext#assignLocals(IR)}
 *       assigns {@link #local} for nodes whose {@link #refCount} &gt; 1 to enable per-evaluation memoization.</li>
 *   <li><b>Emission</b>: {@link CompilerContext#emitIR(IR, org.objectweb.asm.MethodVisitor)}
 *       orchestrates evaluation order and caching; it delegates the actual opcode sequence to {@link #emit}.</li>
 * </ol>
 *
 * <p><b>Stack & side-effects contract</b>:</p>
 * <ul>
 *   <li><b>Result</b>: {@link #emit} must leave exactly one {@code float} on the operand stack.</li>
 *   <li><b>Purity</b>: IR nodes should avoid observable side effects; any caching uses method locals
 *       (allocated via the context) or context bookkeeping maps—never static state.</li>
 *   <li><b>Caching</b>: when {@link #local} is assigned, the driver may {@code DUP}/{@code FSTORE} the first
 *       result and thereafter {@code FLOAD} it. Implementations should not perform their own duplication unless
 *       explicitly part of the node’s semantics.</li>
 * </ul>
 *
 * <p><b>Fields</b>:</p>
 * <ul>
 *   <li>{@link #local}: index of the method-local slot reserved for memoizing this node’s result
 *       (set during {@code assignLocals}; {@code -1} means “no local”).</li>
 *   <li>{@link #refCount}: number of incoming references from parent nodes. Implementations must increment
 *       children’s {@code refCount} during construction to enable reuse detection.</li>
 *   <li>{@link #localInitialized}: set by the driver once the memoized value has been computed and stored;
 *       subsequent emissions may load from {@code local} instead of recomputing.</li>
 * </ul>
 *
 * <p><b>Responsibilities of implementors</b>:</p>
 * <ul>
 *   <li><b>emit</b>: push the node’s float value using the provided {@link org.objectweb.asm.MethodVisitor}. Use
 *       {@link CompilerContext} helpers (e.g., allocate/release locals, push constants) as needed.
 *       Do not assume your {@link #emit} will always be called—memoized nodes may be loaded by the driver instead.</li>
 *   <li><b>collectAccessors</b>: visit children and register any bound accessors via
 *       {@link CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)}.
 *       Non-accessor nodes should simply recurse into their operands.</li>
 *   <li><b>refCount management</b>: in your constructor, increment each child’s {@code refCount} exactly once.</li>
 * </ul>
 *
 * <p><b>Interaction with optimizations</b>:</p>
 * <ul>
 *   <li><b>Constant folding</b>: nodes providing complete constant contexts should return new {@code ConstantIR} instances.</li>
 *   <li><b>Algebraic simplification</b>: prefer building simpler IR (e.g., rewrite {@code 2*x} to {@code x+x}) so
 *       codegen can emit fewer ops.</li>
 *   <li><b>Common subexpressions</b>: the driver assigns locals based on {@link #refCount}; implementations do not
 *       allocate their own long-lived locals for reuse.</li>
 * </ul>
 *
 * <p><b>Threading</b>: IR graphs and generated evaluators are immutable after construction; all runtime caches are
 * method-local, making evaluation reentrant across instances.</p>
 *
 * <p><b>Typical emission flow</b> (handled by the driver):</p>
 * <pre>
 *   if (ir.local != -1 &amp;&amp; ir.localInitialized) {
 *     FLOAD ir.local
 *   } else {
 *     ir.emit(...)
 *     if (ir.local != -1 &amp;&amp; !ir.localInitialized) {
 *       DUP
 *       FSTORE ir.local
 *       ir.localInitialized = true
 *     }
 *   }
 * </pre>
 *
 * @implSpec Implementations must leave exactly one float on the stack and must recurse to children
 * in a deterministic order to preserve semantics for short-circuiting nodes.
 * @implNote {@link #collectAccessors(CompilerContext)} is invoked after optimization and before
 * codegen; it should not depend on {@link #local} assignment.
 * @see CompilerContext#buildIR(dev.omega.arcane.ast.MolangExpression)
 * @see CompilerContext#optimize(IR)
 * @see CompilerContext#assignLocals(IR)
 * @see CompilerContext#emitIR(IR, org.objectweb.asm.MethodVisitor)
 */
public abstract class IR {
    public int local = -1;
    public int refCount = 0;

    public boolean localInitialized = false;

    public static void pushFloat(MethodVisitor mv, float value) {
        if (value == 0.0f) {
            mv.visitInsn(Opcodes.FCONST_0);
        } else if (value == 1.0f) {
            mv.visitInsn(Opcodes.FCONST_1);
        } else if (value == 2.0f) {
            mv.visitInsn(Opcodes.FCONST_2);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public static void pushInt(MethodVisitor mv, int value) {
        if (value == -1) {
            mv.visitInsn(Opcodes.ICONST_M1);
        } else if (value >= 0 && value <= 5) {
            mv.visitInsn(Opcodes.ICONST_0 + value);
        } else if (value <= Byte.MAX_VALUE && value >= Byte.MIN_VALUE) {
            mv.visitIntInsn(Opcodes.BIPUSH, value);
        } else if (value <= Short.MAX_VALUE && value >= Short.MIN_VALUE) {
            mv.visitIntInsn(Opcodes.SIPUSH, value);
        } else {
            mv.visitLdcInsn(value);
        }
    }

    public abstract void emit(MethodVisitor mv, CompilerContext ctx);

    public abstract void collectAccessors(CompilerContext ctx);
}
