package dev.omega.arcane.benchmark;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.FloatAccessor;
import dev.omega.arcane.reference.ReferenceType;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

@State(Scope.Benchmark)
public class MolangEvaluationBenchmark {
    private static final String ARITHMETIC_EXPRESSION = "math.sin(45.0) + 3.0 * 5.0 - 2.0";
    private static final String QUERY_EXPRESSION = "math.cos(query.value) + math.cos(query.value) + math.cos(query.value)";

    private MolangExpression interpretedArithmetic;
    private MolangExpression compiledArithmetic;

    private MolangExpression interpretedQuery;
    private MolangExpression compiledQuery;

    private BenchmarkContext benchmarkContext;

    @Setup
    public void setup() throws MolangLexException, MolangParseException {
        interpretedArithmetic = MolangParser.parse(ARITHMETIC_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY);
        compiledArithmetic = MolangParser.parse(ARITHMETIC_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

        benchmarkContext = new BenchmarkContext();
        ExpressionBindingContext context = ExpressionBindingContext.create()
                .registerReferenceResolver(ReferenceType.QUERY, "value", BenchmarkContext.class,
                        (FloatAccessor<BenchmarkContext>) BenchmarkContext::currentValue);

        interpretedQuery = MolangParser.parse(QUERY_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY)
                .bind(context, benchmarkContext);
        compiledQuery = MolangParser.parse(QUERY_EXPRESSION, MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE)
                .bind(context, benchmarkContext);
    }

    @Benchmark
    public float interpretedArithmetic() {
        return interpretedArithmetic.evaluate();
    }

    @Benchmark
    public float compiledArithmetic() {
        return compiledArithmetic.evaluate();
    }

    @Benchmark
    public float interpretedQuery() {
        return interpretedQuery.evaluate();
    }

    @Benchmark
    public float compiledQuery() {
        return compiledQuery.evaluate();
    }

    @Benchmark
    public float nativeArithmetic() {
        return (float) (Math.sin(Math.toRadians(45.0)) + 3.0 * 5.0 - 2.0);
    }

    @Benchmark
    public float nativeQuery() {
        float value = benchmarkContext.currentValue();
        double radians = Math.toRadians(value);
        double cosValue = Math.cos(radians);
        return (float) (cosValue + cosValue + cosValue);
    }

    /**
     * Verifies that compiled expressions produce identical results to interpreted expressions.
     * Tests a comprehensive suite of Molang expressions covering all operations.
     *
     * @return true if all tests pass
     */
    public static boolean verifyCompilerAccuracy() {
        String[] testExpressions = {
                // Basic arithmetic
                "1 + 2",
                "5 - 3",
                "4 * 7",
                "10 / 2",

                // Constant folding
                "3.0 * 5.0 - 2.0",
                "10 + 20 + 30",
                "(5 + 5) * (3 - 1)",

                // Algebraic identities
                "42 * 0",
                "42 * 1",
                "42 + 0",
                "42 - 0",
                "42 / 1",
                "0 - 42",

                // Unary operators
                "-5",
                "!0",
                "!1",
                "-(-5)",

                // Comparisons
                "3 < 5",
                "5 > 3",
                "3 <= 3",
                "5 >= 5",
                "5 > 10",
                "10 < 5",

                // Logical operators
                "1 && 1",
                "1 && 0",
                "0 && 1",
                "0 && 0",
                "1 || 1",
                "1 || 0",
                "0 || 1",
                "0 || 0",

                // Ternary
                "1 ? 10 : 20",
                "0 ? 10 : 20",
                "(5 > 3) ? 100 : 200",

                // Math functions - basic
                "math.abs(-5)",
                "math.abs(5)",
                "math.ceil(3.2)",
                "math.floor(3.8)",
                "math.round(3.5)",
                "math.round(3.4)",
                "math.trunc(3.9)",
                "math.trunc(-3.9)",

                // Math functions - trig (constant folding should apply)
                "math.sin(0)",
                "math.sin(90)",
                "math.cos(0)",
                "math.cos(90)",
                "math.sin(45.0)",
                "math.cos(45.0)",

                // Math functions - advanced
                "math.sqrt(16)",
                "math.sqrt(2)",
                "math.exp(0)",
                "math.exp(1)",
                "math.ln(1)",
                "math.ln(2.718281828)",
                "math.pow(2, 3)",
                "math.pow(5, 0)",
                "math.pow(5, 1)",

                // Math functions - two argument
                "math.min(5, 10)",
                "math.min(10, 5)",
                "math.max(5, 10)",
                "math.max(10, 5)",
                "math.mod(7, 3)",
                "math.mod(10, 3)",

                // Math functions - three argument
                "math.clamp(5, 0, 10)",
                "math.clamp(-5, 0, 10)",
                "math.clamp(15, 0, 10)",
                "math.lerp(0, 10, 0.5)",
                "math.lerp(0, 10, 0)",
                "math.lerp(0, 10, 1)",

                // Math functions - special
                "math.pi",
                "math.min_angle(0)",
                "math.min_angle(190)",
                "math.min_angle(-190)",
                "math.hermite_blend(0)",
                "math.hermite_blend(0.5)",
                "math.hermite_blend(1)",

                // Complex expressions
                "math.sin(45) + math.cos(45)",
                "math.sqrt(math.pow(3, 2) + math.pow(4, 2))",
                "(3 < 5) ? math.abs(-10) : math.abs(10)",
                "math.lerp(math.min(5, 10), math.max(5, 10), 0.5)",

                // Nested operations
                "((1 + 2) * (3 + 4)) / ((5 - 2) + 1)",
                "math.floor(math.sqrt(math.abs(-16)))",
                "math.clamp(math.sin(30) * 100, 0, 50)",

                // Multiple of same operation (CSE test)
                "math.sqrt(16) + math.sqrt(16)",
                "math.abs(-5) * math.abs(-5)",
                "5 * 5 * 5",

                // Edge cases
                "0 / 1",
                "1.0e10 + 1.0e10",
                "-0.0",
                "1.0e-10 * 1.0e10",
        };

        int passed = 0;
        int failed = 0;
        StringBuilder failures = new StringBuilder();

        for (String expr : testExpressions) {
            try {
                // Parse as interpreted
                MolangExpression interpreted = MolangParser.parse(expr,
                        MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY);

                // Parse as compiled
                MolangExpression compiled = MolangParser.parse(expr,
                        MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

                float interpretedResult = interpreted.evaluate();
                float compiledResult = compiled.evaluate();

                // Allow small floating point error
                float epsilon = 1e-5f;
                boolean matches = Math.abs(interpretedResult - compiledResult) < epsilon
                        || (Float.isNaN(interpretedResult) && Float.isNaN(compiledResult))
                        || (Float.isInfinite(interpretedResult) && Float.isInfinite(compiledResult)
                        && Math.signum(interpretedResult) == Math.signum(compiledResult));

                if (matches) {
                    passed++;
                } else {
                    failed++;
                    failures.append(String.format("FAIL: '%s' -> interpreted: %f, compiled: %f\n",
                            expr, interpretedResult, compiledResult));
                }

            } catch (Exception e) {
                failed++;
                failures.append(String.format("ERROR: '%s' -> %s\n", expr, e.getMessage()));
            }
        }

        System.out.println("=== Molang Compiler Accuracy Verification ===");
        System.out.println("Total tests: " + testExpressions.length);
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.out.println("\n=== Failures ===");
            System.out.println(failures.toString());
        } else {
            System.out.println("\n✓ All tests passed!");
        }

        return failed == 0;
    }

    /**
     * Verifies accuracy with dynamic query values.
     */
    public static boolean verifyQueryAccuracy() {
        String[] queryExpressions = {
                "query.value",
                "query.value + 10",
                "query.value * 2",
                "query.value / 2",
                "math.sin(query.value)",
                "math.cos(query.value)",
                "math.sqrt(query.value)",
                "math.abs(query.value)",
                "math.clamp(query.value, 0, 100)",
                "query.value > 50 ? 1 : 0",
                "math.cos(query.value) + math.cos(query.value)", // CSE test
                "query.value * query.value", // Accessor reuse test
        };

        float[] testValues = {0, 1, -1, 42, 100, -100, 0.5f, 3.14159f, 1000, -1000};

        int passed = 0;
        int failed = 0;
        StringBuilder failures = new StringBuilder();

        for (String expr : queryExpressions) {
            for (float testValue : testValues) {
                try {
                    TestContext context = new TestContext(testValue);
                    ExpressionBindingContext bindingContext = ExpressionBindingContext.create()
                            .registerReferenceResolver(ReferenceType.QUERY, "value", TestContext.class,
                                    (FloatAccessor<TestContext>) TestContext::getValue);

                    MolangExpression interpreted = MolangParser.parse(expr,
                                    MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY)
                            .bind(bindingContext, context);

                    MolangExpression compiled = MolangParser.parse(expr,
                                    MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE)
                            .bind(bindingContext, context);

                    float interpretedResult = interpreted.evaluate();
                    float compiledResult = compiled.evaluate();

                    float epsilon = 1e-5f;
                    boolean matches = Math.abs(interpretedResult - compiledResult) < epsilon
                            || (Float.isNaN(interpretedResult) && Float.isNaN(compiledResult))
                            || (Float.isInfinite(interpretedResult) && Float.isInfinite(compiledResult));

                    if (matches) {
                        passed++;
                    } else {
                        failed++;
                        failures.append(String.format("FAIL: '%s' with value=%f -> interpreted: %f, compiled: %f\n",
                                expr, testValue, interpretedResult, compiledResult));
                    }

                } catch (Exception e) {
                    failed++;
                    failures.append(String.format("ERROR: '%s' with value=%f -> %s\n", expr, testValue, e.getMessage()));
                }
            }
        }

        System.out.println("\n=== Molang Query Accuracy Verification ===");
        System.out.println("Total tests: " + (queryExpressions.length * testValues.length));
        System.out.println("Passed: " + passed);
        System.out.println("Failed: " + failed);

        if (failed > 0) {
            System.out.println("\n=== Failures ===");
            System.out.println(failures.toString());
        } else {
            System.out.println("\n✓ All query tests passed!");
        }

        return failed == 0;
    }

    /**
     * Run all accuracy tests. Call this from a main method or test suite.
     */
    public static boolean verifyAll() {
        boolean constantsPass = verifyCompilerAccuracy();
        boolean queriesPass = verifyQueryAccuracy();

        System.out.println("\n=== Overall Result ===");
        if (constantsPass && queriesPass) {
            System.out.println("✓✓✓ All accuracy tests passed! ✓✓✓");
            return true;
        } else {
            System.out.println("✗✗✗ Some tests failed ✗✗✗");
            return false;
        }
    }

    /**
     * Simple benchmark context supplying a mutable query value.
     */
    public static final class BenchmarkContext {
        public float currentValue() {
            return 42.0f;
        }
    }

    /**
     * Test context with configurable value for accuracy testing.
     */
    public static final class TestContext {
        private final float value;

        public TestContext(float value) {
            this.value = value;
        }

        public float getValue() {
            return value;
        }
    }

    // Add a main method to run the tests
    public static void main(String[] args) {
        verifyAll();
    }
}