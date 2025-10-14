package dev.omega.arcane.compiler;

import dev.omega.arcane.Molang;
import dev.omega.arcane.ast.ConstantExpression;
import dev.omega.arcane.ast.MolangExpression;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Highly optimized JIT compiler for Molang expressions.
 */
public final class Compiler {

    public static final AtomicInteger CLASS_COUNTER = new AtomicInteger();

    public static final String MOLANG_EXPRESSION_INTERNAL = "dev/omega/arcane/ast/MolangExpression";
    public static final String MOLANG_EXPRESSION_DESC = "L" + MOLANG_EXPRESSION_INTERNAL + ";";
    public static final String COMPILED_EVALUATOR_INTERNAL = "dev/omega/arcane/compiler/CompiledEvaluator";
    public static final String RANDOM_FIELD_OWNER = "dev/omega/arcane/Molang";
    public static final String RANDOM_FIELD_DESC = "Ljava/util/Random;";
    public static final String FLOAT_ACCESSOR_INTERNAL = "dev/omega/arcane/reference/FloatAccessor";

    public static final ExpressionClassLoader CLASS_LOADER = new ExpressionClassLoader(Compiler.class.getClassLoader());

    private Compiler() {
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

    public static final class ExpressionClassLoader extends ClassLoader {
        ExpressionClassLoader(ClassLoader parent) {
            super(parent);
        }

        public Class<?> define(String name, byte[] bytecode) {
            return defineClass(name, bytecode, 0, bytecode.length);
        }
    }
}