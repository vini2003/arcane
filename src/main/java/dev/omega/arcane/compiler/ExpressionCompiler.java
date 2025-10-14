package dev.omega.arcane.compiler;

import dev.omega.arcane.Molang;
import dev.omega.arcane.ast.ArithmeticExpression;
import dev.omega.arcane.ast.BinaryExpression;
import dev.omega.arcane.ast.ConstantExpression;
import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.ast.ReferenceExpression;
import dev.omega.arcane.ast.TernaryExpression;
import dev.omega.arcane.ast.UnaryExpression;
import dev.omega.arcane.ast.math.MathExpression;
import dev.omega.arcane.ast.operator.AdditionExpression;
import dev.omega.arcane.ast.operator.DivisionExpression;
import dev.omega.arcane.ast.operator.MultiplicationExpression;
import dev.omega.arcane.ast.operator.SubtractionExpression;
import dev.omega.arcane.lexer.MolangTokenType;
import dev.omega.arcane.reference.BoundFloatAccessorExpression;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.FloatAccessor;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Highly optimized JIT compiler for Molang expressions.
 */
public final class ExpressionCompiler {

    private static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    private static final String MOLANG_EXPRESSION_INTERNAL = "dev/omega/arcane/ast/MolangExpression";
    private static final String MOLANG_EXPRESSION_DESC = "L" + MOLANG_EXPRESSION_INTERNAL + ";";
    private static final String COMPILED_EVALUATOR_INTERNAL = "dev/omega/arcane/compiler/CompiledEvaluator";
    private static final String RANDOM_FIELD_OWNER = "dev/omega/arcane/Molang";
    private static final String RANDOM_FIELD_DESC = "Ljava/util/Random;";
    private static final String FLOAT_ACCESSOR_INTERNAL = "dev/omega/arcane/reference/FloatAccessor";

    private static final ExpressionClassLoader CLASS_LOADER = new ExpressionClassLoader(ExpressionCompiler.class.getClassLoader());

    private ExpressionCompiler() {
    }

    public static MolangExpression compile(MolangExpression expression) {
        // If an expression is already compiled; return itself.
        if (expression instanceof CompiledExpression) {
            return expression;
        }

        // If an expression is constant; return itself.
        if (expression instanceof ConstantExpression) {
            return expression;
        }

        try {
            // Evaluate and compile the expression.
            var context = new CompilerContext(expression);
            var evaluator = context.compile();

            // "evaluator" is the compiled/generated class. If compilation failed,
            // we return the expression itself to avoid stalling the pipeline.
            if (evaluator == null) {
                return expression;
            }

            // Otherwise, we have compiled an expression successfully.
            return new CompiledExpression(expression, evaluator);
        } catch (Throwable throwable) {
            Molang.LOGGER.log(Level.WARNING, "Failed to compile Molang expression, falling back to interpreter", throwable);
            return expression;
        }
    }

    private record CompiledExpression(MolangExpression source, CompiledEvaluator evaluator) implements MolangExpression {
        @Override
        public float evaluate() {
            return evaluator.evaluate();
        }

        @Override
        public MolangExpression simplify() {
            return this;
        }

        @Override
        public MolangExpression bind(ExpressionBindingContext context, Object... values) {
            // When binding, we must update the source expression; not this one.
            var bound = source.bind(context, values);

            if (bound == source) {
                return this;
            }

            return ExpressionCompiler.compile(bound);
        }
    }

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
     *   <li><b>Build</b>: created from AST via {@link ExpressionCompiler.CompilerContext#buildIR(dev.omega.arcane.ast.MolangExpression)}.
     *       Implementations must increment child {@link #refCount} in their constructors to enable reuse analysis.</li>
     *   <li><b>Optimize</b>: transformed by {@link ExpressionCompiler.CompilerContext#optimize(ExpressionCompiler.IR)}
     *       using {@link ExpressionCompiler.CompilerContext#constantFold(ExpressionCompiler.IR)} and
     *       {@link ExpressionCompiler.CompilerContext#algebraicSimplify(ExpressionCompiler.IR)}.</li>
     *   <li><b>Accessor collection</b>: {@link #collectAccessors(ExpressionCompiler.CompilerContext)} walks the IR
     *       to register query accessors/targets before codegen (populates accessor arrays/fields in the generated class).</li>
     *   <li><b>Local assignment</b>: {@link ExpressionCompiler.CompilerContext#assignLocals(ExpressionCompiler.IR)}
     *       assigns {@link #local} for nodes whose {@link #refCount} &gt; 1 to enable per-evaluation memoization.</li>
     *   <li><b>Emission</b>: {@link ExpressionCompiler.CompilerContext#emitIR(ExpressionCompiler.IR, org.objectweb.asm.MethodVisitor)}
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
     *       {@link ExpressionCompiler.CompilerContext} helpers (e.g., allocate/release locals, push constants) as needed.
     *       Do not assume your {@link #emit} will always be called—memoized nodes may be loaded by the driver instead.</li>
     *   <li><b>collectAccessors</b>: visit children and register any bound accessors via
     *       {@link ExpressionCompiler.CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)}.
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
     *           in a deterministic order to preserve semantics for short-circuiting nodes.
     * @implNote {@link #collectAccessors(ExpressionCompiler.CompilerContext)} is invoked after optimization and before
     *           codegen; it should not depend on {@link #local} assignment.
     * @see ExpressionCompiler.CompilerContext#buildIR(dev.omega.arcane.ast.MolangExpression)
     * @see ExpressionCompiler.CompilerContext#optimize(ExpressionCompiler.IR)
     * @see ExpressionCompiler.CompilerContext#assignLocals(ExpressionCompiler.IR)
     * @see ExpressionCompiler.CompilerContext#emitIR(ExpressionCompiler.IR, org.objectweb.asm.MethodVisitor)
     */
    public static abstract class IR {
        int local = -1;
        int refCount = 0;

        boolean localInitialized = false;

        abstract void emit(MethodVisitor mv, CompilerContext ctx);

        abstract void collectAccessors(CompilerContext ctx);
    }

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
     *       {@link ExpressionCompiler.CompilerContext#emitIR(ExpressionCompiler.IR, org.objectweb.asm.MethodVisitor)}
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
     * @see ExpressionCompiler.CompilerContext#constantFold(ExpressionCompiler.IR)
     * @see ExpressionCompiler.CompilerContext#algebraicSimplify(ExpressionCompiler.IR)
     */
    public static final class ConstantIR extends IR {
        final float value;

        ConstantIR(float value) {
            this.value = value;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            pushFloat(mv, value);
        }

        @Override
        void collectAccessors(CompilerContext ctx) {

        }
    }

    private static final class AccessorInfo {
        final FloatAccessor<?> accessor;
        final Object target;
        final boolean isSpecialized;
        final String targetClass;

        AccessorInfo(FloatAccessor<?> accessor, Object target) {
            this.accessor = accessor;
            this.target = target;
            this.targetClass = target.getClass().getName().replace('.', '/');
            this.isSpecialized = true;
        }
    }

    /**
     * IR node that reads a bound {@link dev.omega.arcane.reference.FloatAccessor} from its target object.
     *
     * <p><b>Purpose</b>: models {@code accessor.apply(target)} as part of the expression graph and emits bytecode
     * that retrieves the floating-point value efficiently, with two key optimizations:</p>
     * <ol>
     *   <li><b>Per-evaluation value caching</b>: the first time an accessor value is computed during a single
     *       {@code evaluate()} call, the result is stored into a method-local slot and remembered in
     *       {@link ExpressionCompiler.CompilerContext#accessorValueLocals}. Any subsequent uses of the same accessor
     *       index are compiled as a simple {@code FLOAD}, avoiding repeated interface dispatch.</li>
     *   <li><b>Field specialization</b>: for each distinct accessor/target pair registered via
     *       {@link ExpressionCompiler.CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)},
     *       the compiler records an {@code AccessorInfo}. When the accessor is specialized, the generated class owns
     *       private final fields {@code accessor$<index>} and {@code target$<index>}. Emission then loads from these
     *       fields instead of indexing shared arrays, reducing array traffic and enabling a precise {@code CHECKCAST}
     *       to the concrete target class if known.</li>
     * </ol>
     *
     * <p><b>Emission strategy</b> (<em>first-use</em> vs <em>repeat-use</em>):</p>
     * <ul>
     *   <li><b>Repeat-use</b>: if {@link ExpressionCompiler.CompilerContext#accessorValueLocals} already contains a local for
     *       {@code accessorIndex}, the node emits {@code FLOAD local} and returns.</li>
     *   <li><b>First-use (specialized)</b>: load {@code this.accessor$<index>} and {@code this.target$<index>};
     *       if the recorded target class is more specific than {@code java/lang/Object}, emit {@code CHECKCAST}
     *       to that class; then invoke {@code FloatAccessor.apply(Object)} to get the {@code float}.</li>
     *   <li><b>First-use (generic)</b>: load {@code this.accessors[<index>]} and {@code this.targets[<index>]}
     *       from the backing arrays; invoke {@code FloatAccessor.apply(Object)}.</li>
     *   <li>In both first-use paths, allocate a method-local slot via
     *       {@link ExpressionCompiler.CompilerContext#allocateLocal()}, {@code DUP} the computed value,
     *       {@code FSTORE} it, and record the mapping in {@code accessorValueLocals} so later nodes can {@code FLOAD} it.</li>
     * </ul>
     *
     * <p><b>Registration & layout</b>:</p>
     * <ul>
     *   <li>{@link #collectAccessors(ExpressionCompiler.CompilerContext)} calls
     *       {@link ExpressionCompiler.CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)}, which:
     *       <ul>
     *         <li>Assigns a stable {@code accessorIndex} for de-duplication.</li>
     *         <li>Appends entries to the per-class arrays/fields; constructor emission wires
     *             {@code accessors}, {@code targets}, and (if specialized) {@code accessor$<i>}/{@code target$<i>}.</li>
     *       </ul>
     *   </li>
     * </ul>
     *
     * <p><b>Interaction with other optimizations</b>:</p>
     * <ul>
     *   <li><b>Common subexpression reuse</b>: because each {@code AccessorIR} shares the same {@code accessorIndex}
     *       for identical accessors, the first-use cache ensures all subsequent uses in the same {@code evaluate()}
     *       are cheap loads.</li>
     *   <li><b>Trigonometric caching</b>: upstream, {@link ExpressionCompiler.CompilerContext#buildCachedTrigIR(ExpressionCompiler.IR, String)}
     *       detects {@code AccessorIR} inputs and assigns a stable trig cache slot per {@code accessorIndex}, allowing
     *       {@link ExpressionCompiler.CachedTrigIR} to reuse both input and output across multiple trig calls of the same accessor.</li>
     * </ul>
     *
     * <p><b>Threading & safety</b>:</p>
     * <ul>
     *   <li>The generated evaluator instance stores only immutable fields (captured arrays or specialized fields).
     *       All per-evaluation caches live in method locals, so concurrent calls on distinct instances are safe.</li>
     * </ul>
     *
     * <p><b>Bytecode summary</b> (first-use specialized path):</p>
     * <pre>
     *   ALOAD 0
     *   GETFIELD this.accessor$&lt;i&gt; : Ldev/omega/arcane/reference/FloatAccessor;
     *   ALOAD 0
     *   GETFIELD this.target$&lt;i&gt;   : Ljava/lang/Object;
     *   (optional) CHECKCAST &lt;concrete target class&gt;
     *   INVOKEINTERFACE FloatAccessor.apply (Ljava/lang/Object;)F
     *   DUP
     *   FSTORE &lt;local&gt;
     *   // leaves the float value on stack
     * </pre>
     *
     * @implNote This node does not perform null checks; it assumes the constructor populated accessors/targets or
     *           specialized fields correctly. If an accessor throws, the exception propagates to the caller.
     * @see ExpressionCompiler.CompilerContext#accessorValueLocals
     * @see ExpressionCompiler.CompilerContext#registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)
     * @see ExpressionCompiler.CachedTrigIR
     */

    private static final class AccessorIR extends IR {
        final FloatAccessor<?> accessor;
        final Object target;
        final int accessorIndex;

        AccessorIR(FloatAccessor<?> accessor, Object target, int accessorIndex) {
            this.accessor = accessor;
            this.target = target;
            this.accessorIndex = accessorIndex;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            Integer cachedLocal = ctx.accessorValueLocals.get(accessorIndex);
            if (cachedLocal != null) {
                mv.visitVarInsn(Opcodes.FLOAD, cachedLocal);
                return;
            }

            AccessorInfo info = ctx.accessorInfos.get(accessorIndex);
            if (info != null && info.isSpecialized) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "accessor$" + accessorIndex,
                        "L" + FLOAT_ACCESSOR_INTERNAL + ";");

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "target$" + accessorIndex,
                        "Ljava/lang/Object;");

                if (info.targetClass != null && !info.targetClass.equals("java/lang/Object")) {
                    mv.visitTypeInsn(Opcodes.CHECKCAST, info.targetClass);
                }

                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FLOAT_ACCESSOR_INTERNAL, "apply",
                        "(Ljava/lang/Object;)F", true);
            } else {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "accessors", "[L" + FLOAT_ACCESSOR_INTERNAL + ";");
                pushInt(mv, accessorIndex);
                mv.visitInsn(Opcodes.AALOAD);

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "targets", "[Ljava/lang/Object;");
                pushInt(mv, accessorIndex);
                mv.visitInsn(Opcodes.AALOAD);

                mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, FLOAT_ACCESSOR_INTERNAL, "apply", "(Ljava/lang/Object;)F", true);
            }

            int cacheLocal = ctx.allocateLocal();
            ctx.accessorValueLocals.put(accessorIndex, cacheLocal);
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.FSTORE, cacheLocal);
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
            ctx.registerAccessor(accessor, target);
        }
    }

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
     *       {@link ExpressionCompiler.CompilerContext#emitIR(ExpressionCompiler.IR, org.objectweb.asm.MethodVisitor)} based on
     *       {@link ExpressionCompiler.IR#refCount} and assigned {@link ExpressionCompiler.IR#local}.</li>
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
     * @see ExpressionCompiler.CompilerContext#constantFold(ExpressionCompiler.IR)
     * @see ExpressionCompiler.CompilerContext#algebraicSimplify(ExpressionCompiler.IR)
     */
    private static final class BinaryOpIR extends IR {
        final IR left;
        final IR right;
        final int opcode;

        BinaryOpIR(IR left, IR right, int opcode) {
            this.left = left;
            this.right = right;
            this.opcode = opcode;
            left.refCount++;
            right.refCount++;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            ctx.emitIR(left, mv);
            ctx.emitIR(right, mv);
            mv.visitInsn(opcode);
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
            left.collectAccessors(ctx);
            right.collectAccessors(ctx);
        }
    }

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
     * @see ExpressionCompiler.CompilerContext#constantFold(ExpressionCompiler.IR)
     * @see ExpressionCompiler.CompilerContext#algebraicSimplify(ExpressionCompiler.IR)
     */
    private static final class UnaryOpIR extends IR {
        final IR operand;
        final MolangTokenType operator;

        UnaryOpIR(IR operand, MolangTokenType operator) {
            this.operand = operand;
            this.operator = operator;
            operand.refCount++;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            ctx.emitIR(operand, mv);

            if (operator == MolangTokenType.MINUS) {
                mv.visitInsn(Opcodes.FNEG);
            } else if (operator == MolangTokenType.PLUS) {
                // No-op
            } else if (operator == MolangTokenType.BANG) {
                int valueLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, valueLocal);

                Label checkOne = new Label();
                Label end = new Label();

                mv.visitVarInsn(Opcodes.FLOAD, valueLocal);
                pushFloat(mv, 0.0f);
                mv.visitInsn(Opcodes.FCMPL);
                mv.visitJumpInsn(Opcodes.IFNE, checkOne);

                pushFloat(mv, 1.0f);
                mv.visitJumpInsn(Opcodes.GOTO, end);

                mv.visitLabel(checkOne);
                mv.visitVarInsn(Opcodes.FLOAD, valueLocal);
                pushFloat(mv, 1.0f);
                mv.visitInsn(Opcodes.FCMPL);
                mv.visitJumpInsn(Opcodes.IFNE, end);

                pushFloat(mv, 0.0f);

                mv.visitLabel(end);

                ctx.releaseLocal(valueLocal);
            }
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
            operand.collectAccessors(ctx);
        }
    }

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
     * @see ExpressionCompiler.CompilerContext#constantFold(ExpressionCompiler.IR)
     */
    private static final class ComparisonIR extends IR {
        final IR left;
        final IR right;
        final int jumpOpcode;

        ComparisonIR(IR left, IR right, int jumpOpcode) {
            this.left = left;
            this.right = right;
            this.jumpOpcode = jumpOpcode;
            left.refCount++;
            right.refCount++;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            ctx.emitIR(left, mv);
            ctx.emitIR(right, mv);
            mv.visitInsn(Opcodes.FCMPL);

            Label trueLabel = new Label();
            Label endLabel = new Label();
            mv.visitJumpInsn(jumpOpcode, trueLabel);

            pushFloat(mv, 0.0f);
            mv.visitJumpInsn(Opcodes.GOTO, endLabel);

            mv.visitLabel(trueLabel);
            pushFloat(mv, 1.0f);

            mv.visitLabel(endLabel);
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
            left.collectAccessors(ctx);
            right.collectAccessors(ctx);
        }
    }

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
     * @see ExpressionCompiler.CompilerContext#constantFold(ExpressionCompiler.IR)
     */
    private static final class TernaryIR extends IR {
        final IR condition;
        final IR trueValue;
        final IR falseValue;

        TernaryIR(IR condition, IR trueValue, IR falseValue) {
            this.condition = condition;
            this.trueValue = trueValue;
            this.falseValue = falseValue;
            condition.refCount++;
            trueValue.refCount++;
            falseValue.refCount++;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            ctx.emitIR(condition, mv);
            pushFloat(mv, 0.0f);
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
        void collectAccessors(CompilerContext ctx) {
            condition.collectAccessors(ctx);
            trueValue.collectAccessors(ctx);
            falseValue.collectAccessors(ctx);
        }
    }

    /**
     * IR node that delegates to {@link java.lang.Math} for scalar functions (abs, ceil, floor, sqrt, exp, log,
     * trunc, round, sin, cos, asin, acos, atan).
     *
     * <p><b>Purpose</b>: emit efficient math calls while handling float/double conversions and, when required,
     * degrees-to-radians conversion controlled by {@code needsRadians}.</p>
     *
     * <p><b>Emission strategy</b>:</p>
     * <ul>
     *   <li>Emit input.</li>
     *   <li>For intrinsics with float overloads or integer intermediates:
     *     <ul>
     *       <li>{@code abs(float)}: {@code INVOKESTATIC Math.abs (F)F}.</li>
     *       <li>{@code trunc}: {@code F2I} then {@code I2F}.</li>
     *       <li>{@code round}: {@code Math.round(F)I} then {@code I2F}.</li>
     *     </ul>
     *   </li>
     *   <li>Otherwise:
     *     <ul>
     *       <li>{@code F2D}; if {@code needsRadians}, multiply by {@code Math.toRadians(1)} constant.</li>
     *       <li>{@code INVOKESTATIC Math.&lt;func&gt; (D)D}, then {@code D2F}.</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p><b>Registration & layout</b>: delegates to the input; no accessor/capture state.</p>
     *
     * <p><b>Interaction with other optimizations</b>:</p>
     * <ul>
     *   <li><b>Constant folding</b>: constant input computes to {@code ConstantIR} using
     *       {@link ExpressionCompiler.CompilerContext#computeMathFunc(String, float, boolean)}.</li>
     *   <li><b>Trig caching upgrade</b>: plain trig calls may be replaced upstream by
     *       {@link ExpressionCompiler.CachedTrigIR} when fed by accessor-driven inputs.</li>
     * </ul>
     *
     * <p><b>Threading & safety</b>: pure function calls; no shared state.</p>
     *
     * <p><b>Bytecode summary</b> (generic double path):</p>
     * <pre>
     *   ...emit(input)...
     *   F2D
     *   LDC 0.017453292519943295  // if needsRadians
     *   DMUL                       // if needsRadians
     *   INVOKESTATIC java/lang/Math.&lt;func&gt; (D)D
     *   D2F
     * </pre>
     *
     * @implNote Choice of {@code F2D/D2F} preserves precision typical of JVM Math while returning float.
     * @see ExpressionCompiler.CompilerContext#computeMathFunc(String, float, boolean)
     */
    private static final class MathFuncIR extends IR {
        final IR input;
        final String funcName;
        final boolean needsRadians;

        MathFuncIR(IR input, String funcName, boolean needsRadians) {
            this.input = input;
            this.funcName = funcName;
            this.needsRadians = needsRadians;
            input.refCount++;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            ctx.emitIR(input, mv);

            if ("abs".equals(funcName)) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "abs", "(F)F", false);
            } else if ("trunc".equals(funcName)) {
                mv.visitInsn(Opcodes.F2I);
                mv.visitInsn(Opcodes.I2F);
            } else if ("round".equals(funcName)) {
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "round", "(F)I", false);
                mv.visitInsn(Opcodes.I2F);
            } else {
                mv.visitInsn(Opcodes.F2D);
                if (needsRadians) {
                    mv.visitLdcInsn(0.017453292519943295);
                    mv.visitInsn(Opcodes.DMUL);
                }
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
                mv.visitInsn(Opcodes.D2F);
            }
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
            input.collectAccessors(ctx);
        }
    }

    /**
     * IR node for trigonometric functions with per-input caching (sin, cos, asin, acos, atan) in degrees.
     *
     * <p><b>Purpose</b>: avoid recomputation of expensive trig on identical inputs within the same
     * {@code evaluate()} call. The cache is addressed by a stable {@code cacheIndex} keyed from the driving
     * accessor, so repeated trig of the same accessor value hits in O(1).</p>
     *
     * <p><b>Emission strategy</b>:</p>
     * <ul>
     *   <li>If input/output locals are already registered in
     *       {@link ExpressionCompiler.CompilerContext#trigInputLocals} / {@link ExpressionCompiler.CompilerContext#trigOutputLocals}:
     *     <ul>
     *       <li>Emit the new input, store to a temporary local.</li>
     *       <li>Compare against cached input ({@code FCMPL}). If equal, {@code FLOAD} cached output; otherwise recompute,
     *           update both cached input and cached output, then leave output on the stack.</li>
     *     </ul>
     *   </li>
     *   <li>On first encounter:
     *     <ul>
     *       <li>Emit input, store as the cached input local.</li>
     *       <li>Compute degrees→radians, call {@code Math.func(D)D}, {@code D2F}, {@code DUP} to store as cached output local.</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p><b>Registration & layout</b>:</p>
     * <ul>
     *   <li>Caller assigns a stable {@code cacheIndex} via
     *       {@link ExpressionCompiler.CompilerContext#buildCachedTrigIR(ExpressionCompiler.IR, String)} for accessor-backed inputs.</li>
     *   <li>Locals are allocated on demand and remembered in the context maps.</li>
     * </ul>
     *
     * <p><b>Interaction with other optimizations</b>:</p>
     * <ul>
     *   <li><b>Constant folding</b>: constant input collapses to a {@code ConstantIR} directly.</li>
     *   <li><b>Accessor value caching</b>: when fed by {@link ExpressionCompiler.AccessorIR}, both the input load and trig are cached.</li>
     * </ul>
     *
     * <p><b>Threading & safety</b>: caches are method-local; no cross-thread sharing.</p>
     *
     * <p><b>Bytecode summary</b> (cache-hit/compute path):</p>
     * <pre>
     *   // candidate input
     *   ...emit(input)...
     *   FSTORE newIn
     *   // compare with cached input
     *   FLOAD newIn
     *   FLOAD in_cache
     *   FCMPL
     *   IFNE Lcompute
     *   // hit
     *   FLOAD out_cache
     *   GOTO Lend
     *   Lcompute:
     *   FLOAD newIn
     *   F2D
     *   LDC 0.017453292519943295
     *   DMUL
     *   INVOKESTATIC Math.&lt;func&gt; (D)D
     *   D2F
     *   FSTORE out_cache
     *   FLOAD newIn
     *   FSTORE in_cache
     *   FLOAD out_cache
     *   Lend:
     * </pre>
     *
     * @implNote Degrees are converted via a baked constant to avoid method calls for {@code toRadians}.
     * @see ExpressionCompiler.CompilerContext#buildCachedTrigIR(ExpressionCompiler.IR, String)
     * @see ExpressionCompiler.CompilerContext#trigInputLocals
     * @see ExpressionCompiler.CompilerContext#trigOutputLocals
     */
    private static final class CachedTrigIR extends IR {
        final IR input;
        final String funcName;
        final int cacheIndex;

        CachedTrigIR(IR input, String funcName, int cacheIndex) {
            this.input = input;
            this.funcName = funcName;
            this.cacheIndex = cacheIndex;
            input.refCount++;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            Integer cachedInputLocal = ctx.trigInputLocals.get(cacheIndex);
            Integer cachedOutputLocal = ctx.trigOutputLocals.get(cacheIndex);

            if (cachedInputLocal != null && cachedOutputLocal != null) {
                Label computeLabel = new Label();
                Label endLabel = new Label();

                ctx.emitIR(input, mv);
                int newInputLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, newInputLocal);

                mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
                mv.visitVarInsn(Opcodes.FLOAD, cachedInputLocal);
                mv.visitInsn(Opcodes.FCMPL);
                mv.visitJumpInsn(Opcodes.IFNE, computeLabel);

                mv.visitVarInsn(Opcodes.FLOAD, cachedOutputLocal);
                mv.visitJumpInsn(Opcodes.GOTO, endLabel);

                mv.visitLabel(computeLabel);
                mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
                mv.visitInsn(Opcodes.F2D);
                mv.visitLdcInsn(0.017453292519943295);
                mv.visitInsn(Opcodes.DMUL);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
                mv.visitInsn(Opcodes.D2F);
                mv.visitVarInsn(Opcodes.FSTORE, cachedOutputLocal);

                mv.visitVarInsn(Opcodes.FLOAD, newInputLocal);
                mv.visitVarInsn(Opcodes.FSTORE, cachedInputLocal);

                mv.visitVarInsn(Opcodes.FLOAD, cachedOutputLocal);

                mv.visitLabel(endLabel);

                ctx.releaseLocal(newInputLocal);
            } else {
                ctx.emitIR(input, mv);
                int inputLocal = ctx.allocateLocal();
                mv.visitVarInsn(Opcodes.FSTORE, inputLocal);

                mv.visitVarInsn(Opcodes.FLOAD, inputLocal);
                mv.visitInsn(Opcodes.F2D);
                mv.visitLdcInsn(0.017453292519943295);
                mv.visitInsn(Opcodes.DMUL);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", funcName, "(D)D", false);
                mv.visitInsn(Opcodes.D2F);

                int outputLocal = ctx.allocateLocal();
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.FSTORE, outputLocal);

                ctx.trigInputLocals.put(cacheIndex, inputLocal);
                ctx.trigOutputLocals.put(cacheIndex, outputLocal);
            }
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
            input.collectAccessors(ctx);
        }
    }

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
     *   <li><b>atan2</b>: convert both inputs from degrees→radians in double precision; call {@code Math.atan2(DD)D}; {@code D2F}.</li>
     *   <li><b>clamp</b>: compute {@code max(min, min(value, max))} using {@code Math.max/min(FF)F} with locals.</li>
     *   <li><b>lerp</b>: {@code a + (b - a) * t} with three float locals to minimize stack traffic.</li>
     *   <li><b>hermiteBlend</b>: inlined {@code 3t^2 - 2t^3} using two temporaries (t², t³).</li>
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
     *       {@link ExpressionCompiler.CompilerContext#computeComplexMath(String, java.util.List)}.</li>
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
     * @see ExpressionCompiler.CompilerContext#computeComplexMath(String, java.util.List)
     */
    private static final class ComplexMathIR extends IR {
        final List<IR> operands;
        final String type;

        ComplexMathIR(List<IR> operands, String type) {
            this.operands = operands;
            this.type = type;
            for (IR op : operands) {
                op.refCount++;
            }
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
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

                    pushFloat(mv, 3.0f);
                    mv.visitVarInsn(Opcodes.FLOAD, squaredLocal);
                    mv.visitInsn(Opcodes.FMUL);

                    pushFloat(mv, 2.0f);
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

                    pushFloat(mv, -180.0f);
                    mv.visitVarInsn(Opcodes.FLOAD, midLocal);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Math", "max", "(FF)F", false);
                    pushFloat(mv, 180.0f);
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

                    mv.visitFieldInsn(Opcodes.GETSTATIC, RANDOM_FIELD_OWNER, "RANDOM", RANDOM_FIELD_DESC);
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

                    mv.visitFieldInsn(Opcodes.GETSTATIC, RANDOM_FIELD_OWNER, "RANDOM", RANDOM_FIELD_DESC);
                    mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/Random", "nextFloat", "()F", false);

                    mv.visitVarInsn(Opcodes.FLOAD, highLocal2);
                    pushFloat(mv, 0.999f);
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
        void collectAccessors(CompilerContext ctx) {
            for (IR op : operands) {
                op.collectAccessors(ctx);
            }
        }
    }

    /**
     * IR node that defers evaluation to a captured, non-compiled {@link dev.omega.arcane.ast.MolangExpression}.
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
     *   <li>During IR building, the expression is appended to {@link ExpressionCompiler.CompilerContext#captured} and the
     *       {@code captureIndex} is stored in this node.</li>
     *   <li>The generated class constructor wires the {@code captured[]} field from the provided array.</li>
     * </ul>
     *
     * <p><b>Interaction with other optimizations</b>:</p>
     * <ul>
     *   <li>By design, this node is opaque to constant folding and algebraic simplification.</li>
     *   <li>Outer local caching may still memoize its result if the node is shared (via {@link ExpressionCompiler.IR#local}).</li>
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
     * @see ExpressionCompiler.CompilerContext#captured
     */
    private static final class FallbackIR extends IR {
        final MolangExpression expression;
        final int captureIndex;

        FallbackIR(MolangExpression expression, int captureIndex) {
            this.expression = expression;
            this.captureIndex = captureIndex;
        }

        @Override
        void emit(MethodVisitor mv, CompilerContext ctx) {
            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitFieldInsn(Opcodes.GETFIELD, ctx.internalName, "captured", "[" + MOLANG_EXPRESSION_DESC);
            pushInt(mv, captureIndex);
            mv.visitInsn(Opcodes.AALOAD);
            mv.visitMethodInsn(Opcodes.INVOKEINTERFACE, MOLANG_EXPRESSION_INTERNAL, "evaluate", "()F", true);
        }

        @Override
        void collectAccessors(CompilerContext ctx) {
        }
    }

    public static final class CompilerContext {
        final MolangExpression root;
        final List<MolangExpression> captured = new ArrayList<>();
        final List<FloatAccessor<?>> accessors = new ArrayList<>();
        final List<Object> targets = new ArrayList<>();
        final List<AccessorInfo> accessorInfos = new ArrayList<>();
        final Map<FloatAccessor<?>, Integer> accessorIndexMap = new IdentityHashMap<>();
        final Map<MolangExpression, IR> irCache = new IdentityHashMap<>();
        final Map<Integer, Integer> accessorTrigCacheMap = new HashMap<>();
        int nextTrigCacheIndex = 0;

        String internalName;
        final Map<Integer, Integer> accessorValueLocals = new HashMap<>();
        final Map<Integer, Integer> trigInputLocals = new HashMap<>();
        final Map<Integer, Integer> trigOutputLocals = new HashMap<>();

        int nextLocal = 1;
        final List<Integer> freeLocals = new ArrayList<>();

        CompilerContext(MolangExpression root) {
            this.root = root;
        }

        int allocateLocal() {
            if (!freeLocals.isEmpty()) {
                return freeLocals.remove(freeLocals.size() - 1);
            }
            return nextLocal++;
        }

        void releaseLocal(int local) {
            if (local > 0) {
                freeLocals.add(local);
            }
        }

        @Nullable
        CompiledEvaluator compile() throws ReflectiveOperationException {
            IR rootIR = buildIR(root);
            rootIR = optimize(rootIR);
            rootIR.collectAccessors(this);
            assignLocals(rootIR);

            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
            internalName = "dev/omega/arcane/generated/CompiledExpression$" + CLASS_COUNTER.incrementAndGet();

            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null,
                    "java/lang/Object", new String[]{COMPILED_EVALUATOR_INTERNAL});

            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "captured", "[" + MOLANG_EXPRESSION_DESC, null, null).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "accessors", "[L" + FLOAT_ACCESSOR_INTERNAL + ";", null, null).visitEnd();
            writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "targets", "[Ljava/lang/Object;", null, null).visitEnd();

            for (int i = 0; i < accessorInfos.size(); i++) {
                AccessorInfo info = accessorInfos.get(i);
                if (info.isSpecialized) {
                    writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                            "accessor$" + i, "L" + FLOAT_ACCESSOR_INTERNAL + ";", null, null).visitEnd();
                    writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                            "target$" + i, "Ljava/lang/Object;", null, null).visitEnd();
                }
            }

            emitConstructor(writer);
            emitEvaluate(writer, rootIR);

            writer.visitEnd();

            byte[] bytecode = writer.toByteArray();
            Class<?> defined = CLASS_LOADER.define(internalName.replace('/', '.'), bytecode);
            Constructor<?> constructor = defined.getDeclaredConstructor(
                    MolangExpression[].class,
                    FloatAccessor[].class,
                    Object[].class
            );
            constructor.setAccessible(true);

            MolangExpression[] capturedArray = captured.toArray(new MolangExpression[0]);
            FloatAccessor<?>[] accessorArray = accessors.toArray(new FloatAccessor[0]);
            Object[] targetArray = targets.toArray(new Object[0]);

            return (CompiledEvaluator) constructor.newInstance(capturedArray, accessorArray, targetArray);
        }

        IR optimize(IR ir) {
            ir = constantFold(ir);
            ir = algebraicSimplify(ir);
            return ir;
        }

        IR constantFold(IR ir) {
            if (ir instanceof BinaryOpIR binary) {
                IR left = constantFold(binary.left);
                IR right = constantFold(binary.right);

                if (left instanceof ConstantIR lc && right instanceof ConstantIR rc) {
                    float result = switch (binary.opcode) {
                        case Opcodes.FADD -> lc.value + rc.value;
                        case Opcodes.FSUB -> lc.value - rc.value;
                        case Opcodes.FMUL -> lc.value * rc.value;
                        case Opcodes.FDIV -> lc.value / rc.value;
                        case Opcodes.FREM -> lc.value % rc.value;
                        default -> Float.NaN;
                    };
                    if (!Float.isNaN(result)) {
                        return new ConstantIR(result);
                    }
                }

                if (left != binary.left || right != binary.right) {
                    return new BinaryOpIR(left, right, binary.opcode);
                }
            } else if (ir instanceof UnaryOpIR unary) {
                IR operand = constantFold(unary.operand);

                if (operand instanceof ConstantIR c) {
                    float result = switch (unary.operator) {
                        case MINUS -> -c.value;
                        case PLUS -> c.value;
                        case BANG -> (c.value == 0.0f || c.value == 1.0f) ? (c.value == 0.0f ? 1.0f : 0.0f) : Float.NaN;
                        default -> Float.NaN;
                    };
                    if (!Float.isNaN(result)) {
                        return new ConstantIR(result);
                    }
                }

                if (operand != unary.operand) {
                    return new UnaryOpIR(operand, unary.operator);
                }
            } else if (ir instanceof MathFuncIR mathFunc) {
                IR input = constantFold(mathFunc.input);

                if (input instanceof ConstantIR c) {
                    float result = computeMathFunc(mathFunc.funcName, c.value, mathFunc.needsRadians);
                    if (!Float.isNaN(result)) {
                        return new ConstantIR(result);
                    }
                }

                if (input != mathFunc.input) {
                    return new MathFuncIR(input, mathFunc.funcName, mathFunc.needsRadians);
                }
            } else if (ir instanceof ComparisonIR comparison) {
                IR left = constantFold(comparison.left);
                IR right = constantFold(comparison.right);

                if (left instanceof ConstantIR lc && right instanceof ConstantIR rc) {
                    int cmp = Float.compare(lc.value, rc.value);
                    boolean result = switch (comparison.jumpOpcode) {
                        case Opcodes.IFLT -> cmp < 0;
                        case Opcodes.IFGT -> cmp > 0;
                        case Opcodes.IFLE -> cmp <= 0;
                        case Opcodes.IFGE -> cmp >= 0;
                        case Opcodes.IFEQ -> cmp == 0;
                        case Opcodes.IFNE -> cmp != 0;
                        default -> false;
                    };
                    return new ConstantIR(result ? 1.0f : 0.0f);
                }

                if (left != comparison.left || right != comparison.right) {
                    return new ComparisonIR(left, right, comparison.jumpOpcode);
                }
            } else if (ir instanceof TernaryIR ternary) {
                IR condition = constantFold(ternary.condition);
                IR trueValue = constantFold(ternary.trueValue);
                IR falseValue = constantFold(ternary.falseValue);

                if (condition instanceof ConstantIR c) {
                    return c.value != 0.0f ? trueValue : falseValue;
                }

                if (condition != ternary.condition || trueValue != ternary.trueValue || falseValue != ternary.falseValue) {
                    return new TernaryIR(condition, trueValue, falseValue);
                }
            } else if (ir instanceof ComplexMathIR complexMath) {
                List<IR> foldedOperands = new ArrayList<>();
                boolean changed = false;

                for (IR operand : complexMath.operands) {
                    IR folded = constantFold(operand);
                    foldedOperands.add(folded);
                    if (folded != operand) changed = true;
                }

                if (foldedOperands.stream().allMatch(op -> op instanceof ConstantIR)) {
                    float result = computeComplexMath(complexMath.type, foldedOperands);
                    if (!Float.isNaN(result)) {
                        return new ConstantIR(result);
                    }
                }

                if (changed) {
                    return new ComplexMathIR(foldedOperands, complexMath.type);
                }
            } else if (ir instanceof CachedTrigIR cachedTrig) {
                IR input = constantFold(cachedTrig.input);

                if (input instanceof ConstantIR c) {
                    double radians = Math.toRadians(c.value);
                    float result = (float) switch (cachedTrig.funcName) {
                        case "sin" -> Math.sin(radians);
                        case "cos" -> Math.cos(radians);
                        case "asin" -> Math.asin(radians);
                        case "acos" -> Math.acos(radians);
                        case "atan" -> Math.atan(radians);
                        default -> Double.NaN;
                    };
                    if (!Float.isNaN(result)) {
                        return new ConstantIR(result);
                    }
                }

                if (input != cachedTrig.input) {
                    return new CachedTrigIR(input, cachedTrig.funcName, cachedTrig.cacheIndex);
                }
            }

            return ir;
        }

        IR algebraicSimplify(IR ir) {
            if (ir instanceof BinaryOpIR binary) {
                IR left = algebraicSimplify(binary.left);
                IR right = algebraicSimplify(binary.right);

                if (binary.opcode == Opcodes.FMUL) {
                    if (right instanceof ConstantIR rc && rc.value == 0.0f) {
                        return new ConstantIR(0.0f);
                    }
                    if (left instanceof ConstantIR lc && lc.value == 0.0f) {
                        return new ConstantIR(0.0f);
                    }

                    if (right instanceof ConstantIR rc && rc.value == 1.0f) {
                        return left;
                    }
                    if (left instanceof ConstantIR lc && lc.value == 1.0f) {
                        return right;
                    }

                    if (right instanceof ConstantIR rc && rc.value == 2.0f) {
                        return new BinaryOpIR(left, left, Opcodes.FADD);
                    }
                    if (left instanceof ConstantIR lc && lc.value == 2.0f) {
                        return new BinaryOpIR(right, right, Opcodes.FADD);
                    }
                } else if (binary.opcode == Opcodes.FADD) {
                    if (right instanceof ConstantIR rc && rc.value == 0.0f) {
                        return left;
                    }
                    if (left instanceof ConstantIR lc && lc.value == 0.0f) {
                        return right;
                    }
                } else if (binary.opcode == Opcodes.FSUB) {
                    if (right instanceof ConstantIR rc && rc.value == 0.0f) {
                        return left;
                    }

                    if (left instanceof ConstantIR lc && lc.value == 0.0f) {
                        return new UnaryOpIR(right, MolangTokenType.MINUS);
                    }
                } else if (binary.opcode == Opcodes.FDIV) {
                    if (right instanceof ConstantIR rc && rc.value == 1.0f) {
                        return left;
                    }

                    if (left instanceof ConstantIR lc && lc.value == 0.0f) {
                        return new ConstantIR(0.0f);
                    }
                }

                if (left != binary.left || right != binary.right) {
                    return new BinaryOpIR(left, right, binary.opcode);
                }
            } else if (ir instanceof UnaryOpIR unary) {
                IR operand = algebraicSimplify(unary.operand);

                if (unary.operator == MolangTokenType.MINUS && operand instanceof UnaryOpIR inner) {
                    if (inner.operator == MolangTokenType.MINUS) {
                        return inner.operand;
                    }
                }

                if (operand != unary.operand) {
                    return new UnaryOpIR(operand, unary.operator);
                }
            } else if (ir instanceof TernaryIR ternary) {
                return new TernaryIR(
                        algebraicSimplify(ternary.condition),
                        algebraicSimplify(ternary.trueValue),
                        algebraicSimplify(ternary.falseValue)
                );
            } else if (ir instanceof MathFuncIR mathFunc) {
                return new MathFuncIR(algebraicSimplify(mathFunc.input), mathFunc.funcName, mathFunc.needsRadians);
            } else if (ir instanceof CachedTrigIR cachedTrig) {
                return new CachedTrigIR(algebraicSimplify(cachedTrig.input), cachedTrig.funcName, cachedTrig.cacheIndex);
            } else if (ir instanceof ComplexMathIR complexMath) {
                List<IR> simplified = new ArrayList<>();
                for (IR op : complexMath.operands) {
                    simplified.add(algebraicSimplify(op));
                }
                return new ComplexMathIR(simplified, complexMath.type);
            } else if (ir instanceof ComparisonIR comparison) {
                return new ComparisonIR(
                        algebraicSimplify(comparison.left),
                        algebraicSimplify(comparison.right),
                        comparison.jumpOpcode
                );
            }

            return ir;
        }

        float computeMathFunc(String funcName, float value, boolean needsRadians) {
            try {
                return (float) switch (funcName) {
                    case "abs" -> Math.abs(value);
                    case "ceil" -> Math.ceil(value);
                    case "floor" -> Math.floor(value);
                    case "sqrt" -> Math.sqrt(value);
                    case "exp" -> Math.exp(value);
                    case "log" -> Math.log(value);
                    case "trunc" -> (int) value;
                    case "round" -> Math.round(value);
                    case "sin" -> Math.sin(needsRadians ? Math.toRadians(value) : value);
                    case "cos" -> Math.cos(needsRadians ? Math.toRadians(value) : value);
                    case "asin" -> Math.asin(needsRadians ? Math.toRadians(value) : value);
                    case "acos" -> Math.acos(needsRadians ? Math.toRadians(value) : value);
                    case "atan" -> Math.atan(needsRadians ? Math.toRadians(value) : value);
                    default -> Float.NaN;
                };
            } catch (Exception e) {
                return Float.NaN;
            }
        }

        float computeComplexMath(String type, List<IR> operands) {
            try {
                List<Float> values = operands.stream()
                        .map(op -> ((ConstantIR) op).value)
                        .toList();

                return switch (type) {
                    case "min" -> Math.min(values.get(0), values.get(1));
                    case "max" -> Math.max(values.get(0), values.get(1));
                    case "pow" -> (float) Math.pow(values.get(0), values.get(1));
                    case "clamp" -> Math.max(values.get(1), Math.min(values.get(0), values.get(2)));
                    case "lerp" -> values.get(0) + (values.get(1) - values.get(0)) * values.get(2);
                    case "hermiteBlend" -> {
                        float t = values.get(0);
                        yield 3 * t * t - 2 * t * t * t;
                    }
                    case "minAngle" -> Math.max(-180.0f, Math.min(values.get(0), 180.0f));
                    default -> Float.NaN;
                };
            } catch (Exception e) {
                return Float.NaN;
            }
        }

        IR buildIR(MolangExpression expression) {
            IR cached = irCache.get(expression);
            if (cached != null) {
                cached.refCount++;
                return cached;
            }

            IR result;

            if (expression instanceof ConstantExpression constant) {
                result = new ConstantIR(constant.value());
            } else if (expression instanceof BoundFloatAccessorExpression<?> bfa) {
                int accessorIndex = registerAccessor(bfa.accessor(), bfa.boundValue());
                result = new AccessorIR(bfa.accessor(), bfa.boundValue(), accessorIndex);
            } else if (expression instanceof AdditionExpression add) {
                result = new BinaryOpIR(buildIR(add.left()), buildIR(add.right()), Opcodes.FADD);
            } else if (expression instanceof SubtractionExpression sub) {
                result = new BinaryOpIR(buildIR(sub.left()), buildIR(sub.right()), Opcodes.FSUB);
            } else if (expression instanceof MultiplicationExpression mul) {
                result = new BinaryOpIR(buildIR(mul.left()), buildIR(mul.right()), Opcodes.FMUL);
            } else if (expression instanceof DivisionExpression div) {
                result = new BinaryOpIR(buildIR(div.left()), buildIR(div.right()), Opcodes.FDIV);
            } else if (expression instanceof UnaryExpression unary) {
                result = new UnaryOpIR(buildIR(unary.expression()), unary.operator());
            } else if (expression instanceof BinaryExpression binary) {
                result = buildBinaryIR(binary);
            } else if (expression instanceof TernaryExpression ternary) {
                result = new TernaryIR(
                        buildIR(ternary.condition()),
                        buildIR(ternary.left()),
                        buildIR(ternary.right())
                );
            } else if (expression instanceof ArithmeticExpression math) {
                result = buildMathIR(math);
            } else if (expression instanceof ReferenceExpression) {
                result = new ConstantIR(0.0f);
            } else {
                int captureIndex = captured.size();
                captured.add(expression);
                result = new FallbackIR(expression, captureIndex);
            }

            irCache.put(expression, result);
            return result;
        }

        IR buildBinaryIR(BinaryExpression binary) {
            MolangTokenType op = binary.operator();
            IR left = buildIR(binary.left());
            IR right = buildIR(binary.right());

            return switch (op) {
                case LESS_THAN -> new ComparisonIR(left, right, Opcodes.IFLT);
                case GREATER_THAN -> new ComparisonIR(left, right, Opcodes.IFGT);
                case LESS_THAN_OR_EQUAL -> new ComparisonIR(left, right, Opcodes.IFLE);
                case GREATER_THAN_OR_EQUAL -> new ComparisonIR(left, right, Opcodes.IFGE);
                case DOUBLE_AMPERSAND -> buildLogicalAnd(left, right);
                case DOUBLE_PIPE -> buildLogicalOr(left, right);
                default -> {
                    int captureIndex = captured.size();
                    captured.add(binary);
                    yield new FallbackIR(binary, captureIndex);
                }
            };
        }

        IR buildLogicalAnd(IR left, IR right) {
            IR leftCmp = new ComparisonIR(left, new ConstantIR(0.0f), Opcodes.IFGT);
            IR rightCmp = new ComparisonIR(right, new ConstantIR(0.0f), Opcodes.IFGT);
            return new TernaryIR(leftCmp, rightCmp, new ConstantIR(0.0f));
        }

        IR buildLogicalOr(IR left, IR right) {
            IR leftCmp = new ComparisonIR(left, new ConstantIR(0.0f), Opcodes.IFNE);
            IR rightCmp = new ComparisonIR(right, new ConstantIR(0.0f), Opcodes.IFNE);
            return new TernaryIR(leftCmp, new ConstantIR(1.0f), rightCmp);
        }

        IR buildMathIR(ArithmeticExpression math) {
            if (math instanceof MathExpression.Abs abs) {
                return new MathFuncIR(buildIR(abs.input()), "abs", false);
            } else if (math instanceof MathExpression.Ceil ceil) {
                return new MathFuncIR(buildIR(ceil.input()), "ceil", false);
            } else if (math instanceof MathExpression.Floor floor) {
                return new MathFuncIR(buildIR(floor.input()), "floor", false);
            } else if (math instanceof MathExpression.Sqrt sqrt) {
                return new MathFuncIR(buildIR(sqrt.input()), "sqrt", false);
            } else if (math instanceof MathExpression.Exp exp) {
                return new MathFuncIR(buildIR(exp.input()), "exp", false);
            } else if (math instanceof MathExpression.Ln ln) {
                return new MathFuncIR(buildIR(ln.input()), "log", false);
            } else if (math instanceof MathExpression.Trunc trunc) {
                return new MathFuncIR(buildIR(trunc.input()), "trunc", false);
            } else if (math instanceof MathExpression.Round round) {
                return new MathFuncIR(buildIR(round.input()), "round", false);
            } else if (math instanceof MathExpression.Sin sin) {
                return buildCachedTrigIR(buildIR(sin.input()), "sin");
            } else if (math instanceof MathExpression.Cos cos) {
                return buildCachedTrigIR(buildIR(cos.input()), "cos");
            } else if (math instanceof MathExpression.Asin asin) {
                return buildCachedTrigIR(buildIR(asin.input()), "asin");
            } else if (math instanceof MathExpression.Acos acos) {
                return buildCachedTrigIR(buildIR(acos.input()), "acos");
            } else if (math instanceof MathExpression.Atan atan) {
                return buildCachedTrigIR(buildIR(atan.input()), "atan");
            } else if (math instanceof MathExpression.Min min) {
                return new ComplexMathIR(List.of(buildIR(min.a()), buildIR(min.b())), "min");
            } else if (math instanceof MathExpression.Max max) {
                return new ComplexMathIR(List.of(buildIR(max.a()), buildIR(max.b())), "max");
            } else if (math instanceof MathExpression.Pow pow) {
                return new ComplexMathIR(List.of(buildIR(pow.base()), buildIR(pow.exponent())), "pow");
            } else if (math instanceof MathExpression.Atan2 atan2) {
                return new ComplexMathIR(List.of(buildIR(atan2.y()), buildIR(atan2.x())), "atan2");
            } else if (math instanceof MathExpression.Clamp clamp) {
                return new ComplexMathIR(List.of(
                        buildIR(clamp.input()),
                        buildIR(clamp.min()),
                        buildIR(clamp.max())
                ), "clamp");
            } else if (math instanceof MathExpression.Lerp lerp) {
                return new ComplexMathIR(List.of(
                        buildIR(lerp.input()),
                        buildIR(lerp.end()),
                        buildIR(lerp.zeroToOne())
                ), "lerp");
            } else if (math instanceof MathExpression.HermiteBlend hermite) {
                return new ComplexMathIR(List.of(buildIR(hermite.input())), "hermiteBlend");
            } else if (math instanceof MathExpression.MinAngle minAngle) {
                return new ComplexMathIR(List.of(buildIR(minAngle.input())), "minAngle");
            } else if (math instanceof MathExpression.Random random) {
                return new ComplexMathIR(List.of(buildIR(random.low()), buildIR(random.high())), "random");
            } else if (math instanceof MathExpression.RandomInteger randomInt) {
                return new ComplexMathIR(List.of(buildIR(randomInt.low()), buildIR(randomInt.high())), "randomInteger");
            } else if (math instanceof MathExpression.Mod mod) {
                return new BinaryOpIR(buildIR(mod.value()), buildIR(mod.denominator()), Opcodes.FREM);
            } else if (math instanceof MathExpression.Pi) {
                return new ConstantIR((float) Math.PI);
            } else {
                int captureIndex = captured.size();
                captured.add((MolangExpression) math);
                return new FallbackIR((MolangExpression) math, captureIndex);
            }
        }

        IR buildCachedTrigIR(IR input, String funcName) {
            if (input instanceof AccessorIR accessorIR) {
                Integer cacheIndex = accessorTrigCacheMap.get(accessorIR.accessorIndex);
                if (cacheIndex == null) {
                    cacheIndex = nextTrigCacheIndex++;
                    accessorTrigCacheMap.put(accessorIR.accessorIndex, cacheIndex);
                }
                return new CachedTrigIR(input, funcName, cacheIndex);
            }

            return new MathFuncIR(input, funcName, true);
        }

        void assignLocals(IR ir) {
            if (ir.refCount > 1 && ir.local == -1) {
                ir.local = allocateLocal();
            }

            if (ir instanceof BinaryOpIR binary) {
                assignLocals(binary.left);
                assignLocals(binary.right);
            } else if (ir instanceof UnaryOpIR unary) {
                assignLocals(unary.operand);
            } else if (ir instanceof ComparisonIR comparison) {
                assignLocals(comparison.left);
                assignLocals(comparison.right);
            } else if (ir instanceof TernaryIR ternary) {
                assignLocals(ternary.condition);
                assignLocals(ternary.trueValue);
                assignLocals(ternary.falseValue);
            } else if (ir instanceof MathFuncIR mathFunc) {
                assignLocals(mathFunc.input);
            } else if (ir instanceof CachedTrigIR cachedTrig) {
                assignLocals(cachedTrig.input);
            } else if (ir instanceof ComplexMathIR complexMath) {
                for (IR operand : complexMath.operands) {
                    assignLocals(operand);
                }
            }
        }

        int registerAccessor(FloatAccessor<?> accessor, Object target) {
            Integer existing = accessorIndexMap.get(accessor);
            if (existing != null) {
                return existing;
            }

            int index = accessors.size();
            accessors.add(accessor);
            targets.add(target);
            accessorInfos.add(new AccessorInfo(accessor, target));
            accessorIndexMap.put(accessor, index);
            return index;
        }

        void emitConstructor(ClassWriter writer) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                    "([" + MOLANG_EXPRESSION_DESC +
                            "[L" + FLOAT_ACCESSOR_INTERNAL + ";" +
                            "[Ljava/lang/Object;" +
                            ")V", null, null);
            mv.visitCode();

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 1);
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "captured", "[" + MOLANG_EXPRESSION_DESC);

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 2);
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "accessors", "[L" + FLOAT_ACCESSOR_INTERNAL + ";");

            mv.visitVarInsn(Opcodes.ALOAD, 0);
            mv.visitVarInsn(Opcodes.ALOAD, 3);
            mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "targets", "[Ljava/lang/Object;");

            for (int i = 0; i < accessorInfos.size(); i++) {
                AccessorInfo info = accessorInfos.get(i);
                if (info.isSpecialized) {
                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 2);
                    pushInt(mv, i);
                    mv.visitInsn(Opcodes.AALOAD);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "accessor$" + i, "L" + FLOAT_ACCESSOR_INTERNAL + ";");

                    mv.visitVarInsn(Opcodes.ALOAD, 0);
                    mv.visitVarInsn(Opcodes.ALOAD, 3);
                    pushInt(mv, i);
                    mv.visitInsn(Opcodes.AALOAD);
                    mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "target$" + i, "Ljava/lang/Object;");
                }
            }

            mv.visitInsn(Opcodes.RETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        void emitEvaluate(ClassWriter writer, IR rootIR) {
            MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "evaluate", "()F", null, null);
            mv.visitCode();

            emitIR(rootIR, mv);

            mv.visitInsn(Opcodes.FRETURN);
            mv.visitMaxs(0, 0);
            mv.visitEnd();
        }

        void emitIR(IR ir, MethodVisitor mv) {
            // If this IR has an assigned local and it's already initialized, load it
            if (ir.local != -1 && ir.localInitialized) {
                mv.visitVarInsn(Opcodes.FLOAD, ir.local);
                return;
            }

            // Emit the IR node
            ir.emit(mv, this);

            // If this IR has an assigned local, store the result and mark as initialized
            if (ir.local != -1 && !ir.localInitialized) {
                mv.visitInsn(Opcodes.DUP);
                mv.visitVarInsn(Opcodes.FSTORE, ir.local);
                ir.localInitialized = true;
            }
        }
    }

    private static void pushFloat(MethodVisitor mv, float value) {
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

    private static void pushInt(MethodVisitor mv, int value) {
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

    private static final class ExpressionClassLoader extends ClassLoader {
        ExpressionClassLoader(ClassLoader parent) {
            super(parent);
        }

        Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}