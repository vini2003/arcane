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

public final class CompilerContext {
    public final MolangExpression root;
    public final List<MolangExpression> captured = new ArrayList<>();
    public final List<FloatAccessor<?>> accessors = new ArrayList<>();
    public final List<Object> targets = new ArrayList<>();
    public final List<AccessorInfo> accessorInfos = new ArrayList<>();
    public final Map<FloatAccessor<?>, Integer> accessorIndexMap = new IdentityHashMap<>();
    public final Map<MolangExpression, IR> irCache = new IdentityHashMap<>();
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
        } else if (ir instanceof MathIR mathFunc) {
            IR input = constantFold(mathFunc.input);

            if (input instanceof ConstantIR c) {
                float result = computeMathFunc(mathFunc.funcName, c.value, mathFunc.needsRadians);
                if (!Float.isNaN(result)) {
                    return new ConstantIR(result);
                }
            }

            if (input != mathFunc.input) {
                return new MathIR(input, mathFunc.funcName, mathFunc.needsRadians);
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

    public IR algebraicSimplify(IR ir) {
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
        } else if (ir instanceof MathIR mathFunc) {
            return new MathIR(algebraicSimplify(mathFunc.input), mathFunc.funcName, mathFunc.needsRadians);
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
                case "asin" -> Math.asin(needsRadians ? Math.toRadians(value) : value);
                case "acos" -> Math.acos(needsRadians ? Math.toRadians(value) : value);
                case "atan" -> Math.atan(needsRadians ? Math.toRadians(value) : value);
                default -> Float.NaN;
            };
        } catch (Exception e) {
            return Float.NaN;
        }
    }

    public float computeComplexMath(String type, List<IR> operands) {
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

    public IR buildIR(MolangExpression expression) {
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

    public IR buildBinaryIR(BinaryExpression binary) {
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

    public IR buildLogicalAnd(IR left, IR right) {
        IR leftCmp = new ComparisonIR(left, new ConstantIR(0.0f), Opcodes.IFGT);
        IR rightCmp = new ComparisonIR(right, new ConstantIR(0.0f), Opcodes.IFGT);
        return new TernaryIR(leftCmp, rightCmp, new ConstantIR(0.0f));
    }

    public IR buildLogicalOr(IR left, IR right) {
        IR leftCmp = new ComparisonIR(left, new ConstantIR(0.0f), Opcodes.IFNE);
        IR rightCmp = new ComparisonIR(right, new ConstantIR(0.0f), Opcodes.IFNE);
        return new TernaryIR(leftCmp, new ConstantIR(1.0f), rightCmp);
    }

    public IR buildMathIR(ArithmeticExpression math) {
        if (math instanceof MathExpression.Abs abs) {
            return new MathIR(buildIR(abs.input()), "abs", false);
        } else if (math instanceof MathExpression.Ceil ceil) {
            return new MathIR(buildIR(ceil.input()), "ceil", false);
        } else if (math instanceof MathExpression.Floor floor) {
            return new MathIR(buildIR(floor.input()), "floor", false);
        } else if (math instanceof MathExpression.Sqrt sqrt) {
            return new MathIR(buildIR(sqrt.input()), "sqrt", false);
        } else if (math instanceof MathExpression.Exp exp) {
            return new MathIR(buildIR(exp.input()), "exp", false);
        } else if (math instanceof MathExpression.Ln ln) {
            return new MathIR(buildIR(ln.input()), "log", false);
        } else if (math instanceof MathExpression.Trunc trunc) {
            return new MathIR(buildIR(trunc.input()), "trunc", false);
        } else if (math instanceof MathExpression.Round round) {
            return new MathIR(buildIR(round.input()), "round", false);
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

    public IR buildCachedTrigIR(IR input, String funcName) {
        if (input instanceof AccessorIR accessorIR) {
            Integer cacheIndex = accessorTrigCacheMap.get(accessorIR.accessorIndex);
            if (cacheIndex == null) {
                cacheIndex = nextTrigCacheIndex++;
                accessorTrigCacheMap.put(accessorIR.accessorIndex, cacheIndex);
            }
            return new CachedTrigIR(input, funcName, cacheIndex);
        }

        return new MathIR(input, funcName, true);
    }

    public void assignLocals(IR ir) {
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
        } else if (ir instanceof MathIR mathFunc) {
            assignLocals(mathFunc.input);
        } else if (ir instanceof CachedTrigIR cachedTrig) {
            assignLocals(cachedTrig.input);
        } else if (ir instanceof ComplexMathIR complexMath) {
            for (IR operand : complexMath.operands) {
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
