package dev.omega.arcane.compiler;

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
import dev.omega.arcane.compiler.ir.AccessorIR;
import dev.omega.arcane.compiler.ir.AccessorInfo;
import dev.omega.arcane.compiler.ir.BinaryOpIR;
import dev.omega.arcane.compiler.ir.CachedTrigIR;
import dev.omega.arcane.compiler.ir.ComparisonIR;
import dev.omega.arcane.compiler.ir.ComplexMathIR;
import dev.omega.arcane.compiler.ir.ConstantIR;
import dev.omega.arcane.compiler.ir.FallbackIR;
import dev.omega.arcane.compiler.ir.IR;
import dev.omega.arcane.compiler.ir.MathIR;
import dev.omega.arcane.compiler.ir.TernaryIR;
import dev.omega.arcane.compiler.ir.UnaryOpIR;
import dev.omega.arcane.lexer.MolangTokenType;
import dev.omega.arcane.reference.BoundFloatAccessorExpression;
import dev.omega.arcane.reference.FloatAccessor;
import org.jetbrains.annotations.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful builder/optimizer/codegen driver for Molang expression compilation.
 *
 * <p><b>Purpose</b>: orchestrates the entire pipeline from AST to executable bytecode:
 * IR construction, local/metadata management, peephole optimizations, accessor registration,
 * class emission, and evaluation method generation. A single {@code CompilerContext} instance
 * is used per compilation episode and is not thread-safe.</p>
 *
 * <p><b>High-level pipeline</b>:</p>
 * <ol>
 *   <li><b>Build IR</b>: {@link #buildIR(dev.omega.arcane.ast.MolangExpression)} converts the AST to a compact,
 *       float-only IR graph; nodes are memoized in {@link #irCache} to preserve DAG structure and enable
 *       reference counting.</li>
 *   <li><b>Optimize</b>: {@link #optimize(dev.omega.arcane.compiler.ir.IR)} applies
 *       {@link #constantFold(dev.omega.arcane.compiler.ir.IR)} and
 *       {@link #algebraicSimplify(dev.omega.arcane.compiler.ir.IR)} to reduce work and shrink codegen.</li>
 *   <li><b>Collect accessors</b>: each IR node visits {@link #registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)}
 *       through {@code collectAccessors}, populating {@link #accessors}, {@link #targets}, and {@link #accessorInfos}
 *       for constructor wiring and fast paths.</li>
 *   <li><b>Assign locals</b>: {@link #assignLocals(dev.omega.arcane.compiler.ir.IR)} places per-evaluation memoization
 *       slots on IR nodes with {@code refCount > 1} (tracked in {@link #nodeStates}).</li>
 *   <li><b>Emit class</b>: {@link #compile()} drives bytecode generation:
 *       <ul>
 *         <li>Defines a unique class name in {@link #internalName} and emits fields for captured expressions,
 *             accessors/targets, and any specialized per-accessor fields.</li>
 *         <li>{@link #emitConstructor(org.objectweb.asm.ClassWriter)} wires arrays/fields from constructor args
 *             and copies specialized pairs to {@code accessor$i/target$i}.</li>
 *         <li>{@link #emitEvaluate(org.objectweb.asm.ClassWriter, dev.omega.arcane.compiler.ir.IR)} emits the
 *             evaluator method; {@link #emitIR(dev.omega.arcane.compiler.ir.IR, org.objectweb.asm.MethodVisitor)}
 *             handles memoization and delegates node-specific bytecode to {@code IR.emit}.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p><b>Key data structures</b>:</p>
 * <ul>
 *   <li>{@link #irCache}: AST→IR identity map ensuring each AST node is lowered once and enabling accurate
 *       reference counts for common-subexpression caching.</li>
 *   <li>{@link #nodeStates}: per-IR compilation metadata (refCount, assigned local, init flag). Keeps IR nodes
 *       simple and reusable while centralizing mutability in the context.</li>
 *   <li>{@link #captured}: non-compiled AST fragments used by {@code FallbackIR}; passed to the generated class.</li>
 *   <li>{@link #accessors}, {@link #targets}, {@link #accessorInfos}, {@link #accessorIndexMap}:
 *       stable indexing, constructor wiring, and optional specialization for query accessors.</li>
 *   <li>{@link #accessorValueLocals}, {@link #trigInputLocals}, {@link #trigOutputLocals}:
 *       method-local caches enabling one-eval-per-frame behavior for accessors and cached trig.</li>
 *   <li>{@link #freeLocals}, {@link #nextLocal}: simple linear-scan local allocator for the evaluate method.</li>
 * </ul>
 *
 * <p><b>Evaluation-time memoization</b>:</p>
 * <ul>
 *   <li>Nodes with {@code refCount > 1} receive a {@code local} via {@link #assignLocals(IR)}; on first execution,
 *       {@link #emitIR(IR, MethodVisitor)} stores the computed float and marks it initialized in {@link #nodeStates}.
 *       Subsequent uses load via {@code FLOAD}.</li>
 *   <li>Accessor values cache into {@link #accessorValueLocals}. Trig nodes cache both input and output locals
 *       by stable cache index derived from their driving accessor ({@link #accessorTrigCacheMap}, {@link #nextTrigCacheIndex}).</li>
 * </ul>
 *
 * <p><b>Math folding</b>:</p>
 * <ul>
 *   <li>{@link #computeMathFunc(String, float, boolean)} and {@link #computeComplexMath(String, java.util.List)}
 *       centralize safe constant evaluation for scalar and composite math, returning {@code NaN} to indicate
 *       “no fold.”</li>
 * </ul>
 *
 * <p><b>Bytecode emission contract</b>:</p>
 * <ul>
 *   <li>{@link #emitIR(IR, MethodVisitor)} is the single entry point. It:
 *     <ol>
 *       <li>Checks the node’s {@code local} and initialization state in {@link #nodeStates}.</li>
 *       <li>Delegates to {@code IR.emit} when a recompute is needed.</li>
 *       <li>Optionally {@code DUP}/{@code FSTORE} to the assigned local on first use.</li>
 *     </ol>
 *   </li>
 *   <li>IR nodes must leave exactly one float on the stack; temporary locals should be obtained via
 *       {@link #allocateLocal()} and returned with {@link #releaseLocal(int)} when appropriate.</li>
 * </ul>
 *
 * <p><b>Error handling</b>:</p>
 * <ul>
 *   <li>{@link #compile()} may return {@code null} if codegen cannot proceed (e.g., reflective construction fails).</li>
 *   <li>Constant folding guards with try/catch and uses {@code NaN} sentinel to avoid mis-folding on domain errors.</li>
 * </ul>
 *
 * <p><b>Threading & lifetime</b>:</p>
 * <ul>
 *   <li>Instances are single-use and not thread-safe. Generated evaluator objects are independent and may be used
 *       concurrently. All per-evaluation caches are method-local.</li>
 * </ul>
 *
 * <p><b>Extensibility tips</b>:</p>
 * <ul>
 *   <li>To add a new IR node: provide a factory (mirroring {@link #binary(IR, IR, int)} etc.), register refCounts via
 *       {@link #registerNode(IR, IR...)}, extend {@link #assignLocals(IR)} traversal, and add cases to
 *       {@link #constantFold(IR)} / {@link #algebraicSimplify(IR)} as needed.</li>
 *   <li>To add an optimization pass: chain it inside {@link #optimize(IR)}; prefer analysis/rewrite functions that
 *       reuse existing factories to maintain node-state invariants.</li>
 * </ul>
 *
 * @implNote The context owns all mutable compilation metadata; IR classes remain lightweight value carriers.
 * @see #buildIR(dev.omega.arcane.ast.MolangExpression)
 * @see #optimize(dev.omega.arcane.compiler.ir.IR)
 * @see #assignLocals(dev.omega.arcane.compiler.ir.IR)
 * @see #emitIR(dev.omega.arcane.compiler.ir.IR, org.objectweb.asm.MethodVisitor)
 * @see #registerAccessor(dev.omega.arcane.reference.FloatAccessor, Object)
 * @see #computeMathFunc(String, float, boolean)
 * @see #computeComplexMath(String, java.util.List)
 */
public final class CompilerContext {
    public final MolangExpression root;
    public final List<MolangExpression> captured = new ArrayList<>();
    public final List<FloatAccessor<?>> accessors = new ArrayList<>();
    public final List<Object> targets = new ArrayList<>();
    public final List<AccessorInfo> accessorInfos = new ArrayList<>();
    public final Map<FloatAccessor<?>, Integer> accessorIndexMap = new IdentityHashMap<>();
    public final Map<MolangExpression, IR> irCache = new IdentityHashMap<>();

    private static final class NodeState {
        int refCount;
        int local = -1;
        boolean localInitialized;
    }

    private final IdentityHashMap<IR, NodeState> nodeStates = new IdentityHashMap<>();

    public final Map<Integer, Integer> accessorTrigCacheMap = new HashMap<>();
    public int nextTrigCacheIndex = 0;

    public String internalName;
    public final Map<Integer, Integer> accessorValueLocals = new HashMap<>();
    public final Map<Integer, Integer> trigInputLocals = new HashMap<>();
    public final Map<Integer, Integer> trigOutputLocals = new HashMap<>();

    public int nextLocal = 1;
    public final List<Integer> freeLocals = new ArrayList<>();

    CompilerContext(MolangExpression root) {
        this.root = root;
    }

    public int allocateLocal() {
        if (!freeLocals.isEmpty()) {
            return freeLocals.remove(freeLocals.size() - 1);
        }
        return nextLocal++;
    }

    public void releaseLocal(int local) {
        if (local > 0) {
            freeLocals.add(local);
        }
    }

    private NodeState state(IR ir) {
        return nodeStates.computeIfAbsent(ir, key -> new NodeState());
    }

    private void registerNode(IR ir) {
        state(ir);
    }

    private void registerNode(IR ir, IR... children) {
        registerNode(ir);
        for (IR child : children) {
            incrementRefCount(child);
        }
    }

    private void registerNode(IR ir, Iterable<? extends IR> children) {
        registerNode(ir);
        for (IR child : children) {
            incrementRefCount(child);
        }
    }

    private void incrementRefCount(IR ir) {
        state(ir).refCount++;
    }

    private int refCountOf(IR ir) {
        return state(ir).refCount;
    }

    private int localOf(IR ir) {
        return state(ir).local;
    }

    private void assignLocal(IR ir, int local) {
        state(ir).local = local;
    }

    private boolean evaluateComparison(float left, float right, int jumpOpcode) {
        boolean leftNaN = Float.isNaN(left);
        boolean rightNaN = Float.isNaN(right);
        if (jumpOpcode == Opcodes.IFNE) {
            if (leftNaN || rightNaN) {
                return true;
            }
        } else if (leftNaN || rightNaN) {
            return false;
        }

        return switch (jumpOpcode) {
            case Opcodes.IFLT -> left < right;
            case Opcodes.IFLE -> left <= right;
            case Opcodes.IFGT -> left > right;
            case Opcodes.IFGE -> left >= right;
            case Opcodes.IFEQ -> left == right;
            case Opcodes.IFNE -> left != right;
            default -> false;
        };
    }

    private ConstantIR constant(float value) {
        ConstantIR node = new ConstantIR(value);
        registerNode(node);
        return node;
    }

    private AccessorIR accessor(FloatAccessor<?> accessor, Object target, int accessorIndex) {
        AccessorIR node = new AccessorIR(accessor, target, accessorIndex);
        registerNode(node);
        return node;
    }

    private BinaryOpIR binary(IR left, IR right, int opcode) {
        BinaryOpIR node = new BinaryOpIR(left, right, opcode);
        registerNode(node, left, right);
        return node;
    }

    private UnaryOpIR unary(IR operand, MolangTokenType operator) {
        UnaryOpIR node = new UnaryOpIR(operand, operator);
        registerNode(node, operand);
        return node;
    }

    private ComparisonIR comparison(IR left, IR right, int jumpOpcode) {
        ComparisonIR node = new ComparisonIR(left, right, jumpOpcode);
        registerNode(node, left, right);
        return node;
    }

    private TernaryIR ternary(IR condition, IR trueValue, IR falseValue) {
        TernaryIR node = new TernaryIR(condition, trueValue, falseValue);
        registerNode(node, condition, trueValue, falseValue);
        return node;
    }

    private MathIR math(IR input, String funcName, boolean needsRadians) {
        MathIR node = new MathIR(input, funcName, needsRadians);
        registerNode(node, input);
        return node;
    }

    private CachedTrigIR cachedTrig(IR input, String funcName, int cacheIndex, boolean convertToRadians) {
        CachedTrigIR node = new CachedTrigIR(input, funcName, cacheIndex, convertToRadians);
        registerNode(node, input);
        return node;
    }

    private ComplexMathIR complexMath(List<IR> operands, String type) {
        ComplexMathIR node = new ComplexMathIR(operands, type);
        registerNode(node, operands);
        return node;
    }

    private FallbackIR fallback(MolangExpression expression, int captureIndex) {
        FallbackIR node = new FallbackIR(expression, captureIndex);
        registerNode(node);
        return node;
    }

    @Nullable
    public CompiledEvaluator compile() throws ReflectiveOperationException {
        IR rootIR = buildIR(root);
        rootIR = optimize(rootIR);
        rootIR.collectAccessors(this);
        assignLocals(rootIR);

        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        internalName = "dev/omega/arcane/generated/CompiledExpression$" + Compiler.CLASS_COUNTER.incrementAndGet();

        writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, internalName, null,
                "java/lang/Object", new String[]{Compiler.COMPILED_EVALUATOR_INTERNAL});

        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "captured", "[" + Compiler.MOLANG_EXPRESSION_DESC, null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "accessors", "[L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "targets", "[Ljava/lang/Object;", null, null).visitEnd();

        for (int i = 0; i < accessorInfos.size(); i++) {
            AccessorInfo info = accessorInfos.get(i);
            if (info.isSpecialized) {
                writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        "accessor$" + i, "L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";", null, null).visitEnd();
                writer.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
                        "target$" + i, "Ljava/lang/Object;", null, null).visitEnd();
            }
        }

        emitConstructor(writer);
        emitEvaluate(writer, rootIR);

        writer.visitEnd();

        byte[] bytecode = writer.toByteArray();
        Class<?> defined = Compiler.CLASS_LOADER.define(internalName.replace('/', '.'), bytecode);
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

    public IR optimize(IR ir) {
        ir = constantFold(ir);
        ir = algebraicSimplify(ir);
        return ir;
    }

    public IR constantFold(IR ir) {
        if (ir instanceof BinaryOpIR binary) {
            IR left = constantFold(binary.left());
            IR right = constantFold(binary.right());

            if (left instanceof ConstantIR lc && right instanceof ConstantIR rc) {
                float result = switch (binary.opcode()) {
                    case Opcodes.FADD -> lc.value() + rc.value();
                    case Opcodes.FSUB -> lc.value() - rc.value();
                    case Opcodes.FMUL -> lc.value() * rc.value();
                    case Opcodes.FDIV -> lc.value() / rc.value();
                    case Opcodes.FREM -> lc.value() % rc.value();
                    default -> Float.NaN;
                };
                if (!Float.isNaN(result)) {
                    return constant(result);
                }
            }

            if (left != binary.left() || right != binary.right()) {
                return binary(left, right, binary.opcode());
            }
        } else if (ir instanceof UnaryOpIR unary) {
            IR operand = constantFold(unary.operand());

            if (operand instanceof ConstantIR c) {
                float result = switch (unary.operator()) {
                    case MINUS -> -c.value();
                    case PLUS -> c.value();
                    case BANG -> c.value() == 0.0f ? 1.0f : 0.0f;
                    default -> Float.NaN;
                };
                if (!Float.isNaN(result)) {
                    return constant(result);
                }
            }

            if (operand != unary.operand()) {
                return unary(operand, unary.operator());
            }
        } else if (ir instanceof MathIR mathFunc) {
            IR input = constantFold(mathFunc.input());

            if (input instanceof ConstantIR c) {
                float result = computeMathFunc(mathFunc.funcName(), c.value(), mathFunc.needsRadians());
                if (!Float.isNaN(result)) {
                    return constant(result);
                }
            }

            if (input != mathFunc.input()) {
                return math(input, mathFunc.funcName(), mathFunc.needsRadians());
            }
        } else if (ir instanceof ComparisonIR comparison) {
            IR left = constantFold(comparison.left());
            IR right = constantFold(comparison.right());

            if (left instanceof ConstantIR lc && right instanceof ConstantIR rc) {
                boolean result = evaluateComparison(lc.value(), rc.value(), comparison.jumpOpcode());
                return constant(result ? 1.0f : 0.0f);
            }

            if (left != comparison.left() || right != comparison.right()) {
                return comparison(left, right, comparison.jumpOpcode());
            }
        } else if (ir instanceof TernaryIR ternary) {
            IR condition = constantFold(ternary.condition());
            IR trueValue = constantFold(ternary.trueValue());
            IR falseValue = constantFold(ternary.falseValue());

            if (condition instanceof ConstantIR c) {
                return c.value() != 0.0f ? trueValue : falseValue;
            }

            if (condition != ternary.condition() || trueValue != ternary.trueValue() || falseValue != ternary.falseValue()) {
                return ternary(condition, trueValue, falseValue);
            }
        } else if (ir instanceof ComplexMathIR complexMath) {
            List<IR> foldedOperands = new ArrayList<>();
            boolean changed = false;

            for (IR operand : complexMath.operands()) {
                IR folded = constantFold(operand);
                foldedOperands.add(folded);
                if (folded != operand) changed = true;
            }

            if (foldedOperands.stream().allMatch(op -> op instanceof ConstantIR)) {
                float result = computeComplexMath(complexMath.type(), foldedOperands);
                if (!Float.isNaN(result)) {
                    return constant(result);
                }
            }

            if (changed) {
                return complexMath(foldedOperands, complexMath.type());
            }
        } else if (ir instanceof CachedTrigIR cachedTrig) {
            IR input = constantFold(cachedTrig.input());

            if (input instanceof ConstantIR c) {
                double argument = cachedTrig.convertInputToRadians() ? Math.toRadians(c.value()) : c.value();
                float result = (float) switch (cachedTrig.funcName()) {
                    case "sin" -> Math.sin(argument);
                    case "cos" -> Math.cos(argument);
                    case "asin" -> Math.asin(argument);
                    case "acos" -> Math.acos(argument);
                    case "atan" -> Math.atan(argument);
                    default -> Double.NaN;
                };
                if (!Float.isNaN(result)) {
                    return constant(result);
                }
            }

            if (input != cachedTrig.input()) {
                return cachedTrig(input, cachedTrig.funcName(), cachedTrig.cacheIndex(), cachedTrig.convertInputToRadians());
            }
        }

        return ir;
    }

    public IR algebraicSimplify(IR ir) {
        if (ir instanceof BinaryOpIR binary) {
            IR left = algebraicSimplify(binary.left());
            IR right = algebraicSimplify(binary.right());

            if (binary.opcode() == Opcodes.FMUL) {
                if (right instanceof ConstantIR rc && rc.value() == 0.0f) {
                    return constant(0.0f);
                }
                if (left instanceof ConstantIR lc && lc.value() == 0.0f) {
                    return constant(0.0f);
                }

                if (right instanceof ConstantIR rc && rc.value() == 1.0f) {
                    return left;
                }
                if (left instanceof ConstantIR lc && lc.value() == 1.0f) {
                    return right;
                }

                if (right instanceof ConstantIR rc && rc.value() == 2.0f) {
                    return binary(left, left, Opcodes.FADD);
                }
                if (left instanceof ConstantIR lc && lc.value() == 2.0f) {
                    return binary(right, right, Opcodes.FADD);
                }
            } else if (binary.opcode() == Opcodes.FADD) {
                if (right instanceof ConstantIR rc && rc.value() == 0.0f) {
                    return left;
                }
                if (left instanceof ConstantIR lc && lc.value() == 0.0f) {
                    return right;
                }
            } else if (binary.opcode() == Opcodes.FSUB) {
                if (right instanceof ConstantIR rc && rc.value() == 0.0f) {
                    return left;
                }

                if (left instanceof ConstantIR lc && lc.value() == 0.0f) {
                    return unary(right, MolangTokenType.MINUS);
                }
            } else if (binary.opcode() == Opcodes.FDIV) {
                if (right instanceof ConstantIR rc && rc.value() == 1.0f) {
                    return left;
                }

                if (left instanceof ConstantIR lc && lc.value() == 0.0f) {
                    return constant(0.0f);
                }
            }

            if (left != binary.left() || right != binary.right()) {
                return binary(left, right, binary.opcode());
            }
        } else if (ir instanceof UnaryOpIR unary) {
            IR operand = algebraicSimplify(unary.operand());

            if (unary.operator() == MolangTokenType.MINUS && operand instanceof UnaryOpIR inner) {
                if (inner.operator() == MolangTokenType.MINUS) {
                    return inner.operand();
                }
            }

            if (operand != unary.operand()) {
                return unary(operand, unary.operator());
            }
        } else if (ir instanceof TernaryIR ternary) {
            return ternary(
                    algebraicSimplify(ternary.condition()),
                    algebraicSimplify(ternary.trueValue()),
                    algebraicSimplify(ternary.falseValue())
            );
        } else if (ir instanceof MathIR mathFunc) {
            return math(algebraicSimplify(mathFunc.input()), mathFunc.funcName(), mathFunc.needsRadians());
        } else if (ir instanceof CachedTrigIR cachedTrig) {
            return cachedTrig(algebraicSimplify(cachedTrig.input()), cachedTrig.funcName(), cachedTrig.cacheIndex(), cachedTrig.convertInputToRadians());
        } else if (ir instanceof ComplexMathIR complexMath) {
            List<IR> simplified = new ArrayList<>();
            for (IR op : complexMath.operands()) {
                simplified.add(algebraicSimplify(op));
            }
            return complexMath(simplified, complexMath.type());
        } else if (ir instanceof ComparisonIR comparison) {
            return comparison(
                    algebraicSimplify(comparison.left()),
                    algebraicSimplify(comparison.right()),
                    comparison.jumpOpcode()
            );
        }

        return ir;
    }

    public float computeMathFunc(String funcName, float value, boolean needsRadians) {
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
                case "asin" -> Math.asin(value);
                case "acos" -> Math.acos(value);
                case "atan" -> Math.atan(value);
                default -> Float.NaN;
            };
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    public float computeComplexMath(String type, List<IR> operands) {
        try {
            List<Float> values = operands.stream()
                    .map(op -> ((ConstantIR) op).value())
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

    public IR buildIR(MolangExpression expression) {
        IR cached = irCache.get(expression);
        if (cached != null) {
            incrementRefCount(cached);
            return cached;
        }

        IR result;

        if (expression instanceof ConstantExpression constant) {
            result = constant(constant.value());
        } else if (expression instanceof BoundFloatAccessorExpression<?> bfa) {
            int accessorIndex = registerAccessor(bfa.accessor(), bfa.boundValue());
            result = accessor(bfa.accessor(), bfa.boundValue(), accessorIndex);
        } else if (expression instanceof AdditionExpression add) {
            result = binary(buildIR(add.left()), buildIR(add.right()), Opcodes.FADD);
        } else if (expression instanceof SubtractionExpression sub) {
            result = binary(buildIR(sub.left()), buildIR(sub.right()), Opcodes.FSUB);
        } else if (expression instanceof MultiplicationExpression mul) {
            result = binary(buildIR(mul.left()), buildIR(mul.right()), Opcodes.FMUL);
        } else if (expression instanceof DivisionExpression div) {
            result = binary(buildIR(div.left()), buildIR(div.right()), Opcodes.FDIV);
        } else if (expression instanceof UnaryExpression unary) {
            result = unary(buildIR(unary.expression()), unary.operator());
        } else if (expression instanceof BinaryExpression binary) {
            result = buildBinaryIR(binary);
        } else if (expression instanceof TernaryExpression ternary) {
            result = ternary(
                    buildIR(ternary.condition()),
                    buildIR(ternary.left()),
                    buildIR(ternary.right())
            );
        } else if (expression instanceof ArithmeticExpression math) {
            result = buildMathIR(math);
        } else if (expression instanceof ReferenceExpression) {
            result = constant(0.0f);
        } else {
            int captureIndex = captured.size();
            captured.add(expression);
            result = fallback(expression, captureIndex);
        }

        irCache.put(expression, result);
        return result;
    }

    public IR buildBinaryIR(BinaryExpression binary) {
        MolangTokenType op = binary.operator();
        IR left = buildIR(binary.left());
        IR right = buildIR(binary.right());

        return switch (op) {
            case LESS_THAN -> comparison(left, right, Opcodes.IFLT);
            case GREATER_THAN -> comparison(left, right, Opcodes.IFGT);
            case LESS_THAN_OR_EQUAL -> comparison(left, right, Opcodes.IFLE);
            case GREATER_THAN_OR_EQUAL -> comparison(left, right, Opcodes.IFGE);
            case DOUBLE_EQUAL -> comparison(left, right, Opcodes.IFEQ);
            case BANG_EQUAL -> comparison(left, right, Opcodes.IFNE);
            case DOUBLE_AMPERSAND -> buildLogicalAnd(left, right);
            case DOUBLE_PIPE -> buildLogicalOr(left, right);
            default -> {
                int captureIndex = captured.size();
                captured.add(binary);
                yield fallback(binary, captureIndex);
            }
        };
    }

    public IR buildLogicalAnd(IR left, IR right) {
        IR leftCmp = comparison(left, constant(0.0f), Opcodes.IFNE);
        IR rightCmp = comparison(right, constant(0.0f), Opcodes.IFNE);
        return ternary(leftCmp, rightCmp, constant(0.0f));
    }

    public IR buildLogicalOr(IR left, IR right) {
        IR leftCmp = comparison(left, constant(0.0f), Opcodes.IFNE);
        IR rightCmp = comparison(right, constant(0.0f), Opcodes.IFNE);
        return ternary(leftCmp, constant(1.0f), rightCmp);
    }

    public IR buildMathIR(ArithmeticExpression math) {
        if (math instanceof MathExpression.Abs abs) {
            return math(buildIR(abs.input()), "abs", false);
        } else if (math instanceof MathExpression.Ceil ceil) {
            return math(buildIR(ceil.input()), "ceil", false);
        } else if (math instanceof MathExpression.Floor floor) {
            return math(buildIR(floor.input()), "floor", false);
        } else if (math instanceof MathExpression.Sqrt sqrt) {
            return math(buildIR(sqrt.input()), "sqrt", false);
        } else if (math instanceof MathExpression.Exp exp) {
            return math(buildIR(exp.input()), "exp", false);
        } else if (math instanceof MathExpression.Ln ln) {
            return math(buildIR(ln.input()), "log", false);
        } else if (math instanceof MathExpression.Trunc trunc) {
            return math(buildIR(trunc.input()), "trunc", false);
        } else if (math instanceof MathExpression.Round round) {
            return math(buildIR(round.input()), "round", false);
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
            return complexMath(List.of(buildIR(min.a()), buildIR(min.b())), "min");
        } else if (math instanceof MathExpression.Max max) {
            return complexMath(List.of(buildIR(max.a()), buildIR(max.b())), "max");
        } else if (math instanceof MathExpression.Pow pow) {
            return complexMath(List.of(buildIR(pow.base()), buildIR(pow.exponent())), "pow");
        } else if (math instanceof MathExpression.Atan2 atan2) {
            return complexMath(List.of(buildIR(atan2.y()), buildIR(atan2.x())), "atan2");
        } else if (math instanceof MathExpression.Clamp clamp) {
            return complexMath(List.of(
                    buildIR(clamp.input()),
                    buildIR(clamp.min()),
                    buildIR(clamp.max())
            ), "clamp");
        } else if (math instanceof MathExpression.Lerp lerp) {
            return complexMath(List.of(
                    buildIR(lerp.input()),
                    buildIR(lerp.end()),
                    buildIR(lerp.zeroToOne())
            ), "lerp");
        } else if (math instanceof MathExpression.HermiteBlend hermite) {
            return complexMath(List.of(buildIR(hermite.input())), "hermiteBlend");
        } else if (math instanceof MathExpression.MinAngle minAngle) {
            return complexMath(List.of(buildIR(minAngle.input())), "minAngle");
        } else if (math instanceof MathExpression.Random random) {
            return complexMath(List.of(buildIR(random.low()), buildIR(random.high())), "random");
        } else if (math instanceof MathExpression.RandomInteger randomInt) {
            return complexMath(List.of(buildIR(randomInt.low()), buildIR(randomInt.high())), "randomInteger");
        } else if (math instanceof MathExpression.Mod mod) {
            return binary(buildIR(mod.value()), buildIR(mod.denominator()), Opcodes.FREM);
        } else if (math instanceof MathExpression.Pi) {
            return constant((float) Math.PI);
        } else {
            int captureIndex = captured.size();
            captured.add((MolangExpression) math);
            return fallback((MolangExpression) math, captureIndex);
        }
    }

    private boolean trigInputUsesDegrees(String funcName) {
        return "sin".equals(funcName) || "cos".equals(funcName);
    }

    public IR buildCachedTrigIR(IR input, String funcName) {
        boolean convertToRadians = trigInputUsesDegrees(funcName);
        if (input instanceof AccessorIR accessorIR) {
            Integer cacheIndex = accessorTrigCacheMap.get(accessorIR.accessorIndex());
            if (cacheIndex == null) {
                cacheIndex = nextTrigCacheIndex++;
                accessorTrigCacheMap.put(accessorIR.accessorIndex(), cacheIndex);
            }
            return cachedTrig(input, funcName, cacheIndex, convertToRadians);
        }

        return math(input, funcName, convertToRadians);
    }

    public void assignLocals(IR ir) {
        if (refCountOf(ir) > 1 && localOf(ir) == -1) {
            assignLocal(ir, allocateLocal());
        }

        if (ir instanceof BinaryOpIR binary) {
            assignLocals(binary.left());
            assignLocals(binary.right());
        } else if (ir instanceof UnaryOpIR unary) {
            assignLocals(unary.operand());
        } else if (ir instanceof ComparisonIR comparison) {
            assignLocals(comparison.left());
            assignLocals(comparison.right());
        } else if (ir instanceof TernaryIR ternary) {
            assignLocals(ternary.condition());
            assignLocals(ternary.trueValue());
            assignLocals(ternary.falseValue());
        } else if (ir instanceof MathIR mathFunc) {
            assignLocals(mathFunc.input());
        } else if (ir instanceof CachedTrigIR cachedTrig) {
            assignLocals(cachedTrig.input());
        } else if (ir instanceof ComplexMathIR complexMath) {
            for (IR operand : complexMath.operands()) {
                assignLocals(operand);
            }
        }
    }

    public int registerAccessor(FloatAccessor<?> accessor, Object target) {
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

    public void emitConstructor(ClassWriter writer) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "<init>",
                "([" + Compiler.MOLANG_EXPRESSION_DESC +
                        "[L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";" +
                        "[Ljava/lang/Object;" +
                        ")V", null, null);
        mv.visitCode();

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "captured", "[" + Compiler.MOLANG_EXPRESSION_DESC);

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 2);
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "accessors", "[L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";");

        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitVarInsn(Opcodes.ALOAD, 3);
        mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "targets", "[Ljava/lang/Object;");

        for (int i = 0; i < accessorInfos.size(); i++) {
            AccessorInfo info = accessorInfos.get(i);
            if (info.isSpecialized) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 2);
                IR.pushInt(mv, i);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "accessor$" + i, "L" + Compiler.FLOAT_ACCESSOR_INTERNAL + ";");

                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitVarInsn(Opcodes.ALOAD, 3);
                IR.pushInt(mv, i);
                mv.visitInsn(Opcodes.AALOAD);
                mv.visitFieldInsn(Opcodes.PUTFIELD, internalName, "target$" + i, "Ljava/lang/Object;");
            }
        }

        mv.visitInsn(Opcodes.RETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    public void emitEvaluate(ClassWriter writer, IR rootIR) {
        MethodVisitor mv = writer.visitMethod(Opcodes.ACC_PUBLIC, "evaluate", "()F", null, null);
        mv.visitCode();

        emitIR(rootIR, mv);

        mv.visitInsn(Opcodes.FRETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();
    }

    public void emitIR(IR ir, MethodVisitor mv) {
        NodeState state = state(ir);
        int local = state.local;

        // If this IR has an assigned local and it's already initialized, load it
        if (local != -1 && state.localInitialized) {
            mv.visitVarInsn(Opcodes.FLOAD, local);
            return;
        }

        // Emit the IR node
        ir.emit(mv, this);

        // If this IR has an assigned local, store the result and mark as initialized
        if (local != -1 && !state.localInitialized) {
            mv.visitInsn(Opcodes.DUP);
            mv.visitVarInsn(Opcodes.FSTORE, local);
            state.localInitialized = true;
        }
    }
}
