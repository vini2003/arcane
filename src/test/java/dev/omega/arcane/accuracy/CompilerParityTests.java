package dev.omega.arcane.accuracy;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.FloatAccessor;
import dev.omega.arcane.reference.ReferenceType;
import dev.omega.arcane.testing.ExpressionAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.stream.Stream;

@DisplayName("Interpreter vs compiler parity (benchmark corpus)")
class CompilerParityTests {

    private static final String[] CONSTANT_EXPRESSIONS = {
            "1 + 2",
            "5 - 3",
            "4 * 7",
            "10 / 2",
            "3.0 * 5.0 - 2.0",
            "10 + 20 + 30",
            "(5 + 5) * (3 - 1)",
            "42 * 0",
            "42 * 1",
            "42 + 0",
            "42 - 0",
            "42 / 1",
            "0 - 42",
            "-5",
            "!0",
            "!1",
            "-(-5)",
            "3 < 5",
            "5 > 3",
            "3 <= 3",
            "5 >= 5",
            "5 > 10",
            "10 < 5",
            "1 && 1",
            "1 && 0",
            "0 && 1",
            "0 && 0",
            "1 || 1",
            "1 || 0",
            "0 || 1",
            "0 || 0",
            "1 ? 10 : 20",
            "0 ? 10 : 20",
            "(5 > 3) ? math.abs(-10) : math.abs(10)",
            "math.abs(-5)",
            "math.abs(5)",
            "math.ceil(3.2)",
            "math.floor(3.8)",
            "math.round(3.5)",
            "math.round(3.4)",
            "math.trunc(3.9)",
            "math.trunc(-3.9)",
            "math.sin(0)",
            "math.sin(90)",
            "math.cos(0)",
            "math.cos(90)",
            "math.sin(45.0)",
            "math.cos(45.0)",
            "math.sqrt(16)",
            "math.sqrt(2)",
            "math.exp(0)",
            "math.exp(1)",
            "math.ln(1)",
            "math.ln(2.718281828)",
            "math.pow(2, 3)",
            "math.pow(5, 0)",
            "math.pow(5, 1)",
            "math.min(5, 10)",
            "math.min(10, 5)",
            "math.max(5, 10)",
            "math.max(10, 5)",
            "math.mod(7, 3)",
            "math.mod(10, 3)",
            "math.clamp(5, 0, 10)",
            "math.clamp(-5, 0, 10)",
            "math.clamp(15, 0, 10)",
            "math.lerp(0, 10, 0.5)",
            "math.lerp(0, 10, 0)",
            "math.lerp(0, 10, 1)",
            "math.pi",
            "math.min_angle(0)",
            "math.min_angle(190)",
            "math.min_angle(-190)",
            "math.hermite_blend(0)",
            "math.hermite_blend(0.5)",
            "math.hermite_blend(1)",
            "math.sin(45) + math.cos(45)",
            "math.sqrt(math.pow(3, 2) + math.pow(4, 2))",
            "math.lerp(math.min(5, 10), math.max(5, 10), 0.5)",
            "((1 + 2) * (3 + 4)) / ((5 - 2) + 1)",
            "math.floor(math.sqrt(math.abs(-16)))",
            "math.clamp(math.sin(30) * 100, 0, 50)",
            "math.sqrt(16) + math.sqrt(16)",
            "math.abs(-5) * math.abs(-5)",
            "5 * 5 * 5",
            "0 / 1",
            "1.0e10 + 1.0e10",
            "-0.0",
            "1.0e-10 * 1.0e10"
    };

    private static final String[] QUERY_EXPRESSIONS = {
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
            "math.cos(query.value) + math.cos(query.value)",
            "query.value * query.value"
    };

    private static final float[] QUERY_VALUES = {
            0f, 1f, -1f, 42f, 100f, -100f, 0.5f, 3.14159f, 1000f, -1000f
    };

    private static final ExpressionBindingContext BINDING_CONTEXT = ExpressionBindingContext.create()
            .registerReferenceResolver(ReferenceType.QUERY, "value", TestContext.class,
                    (FloatAccessor<TestContext>) TestContext::value);

    @ParameterizedTest(name = "{0}")
    @MethodSource("Constant_Expressions")
    void Constants_Match_Interpreter(String expression) {
        ExpressionAssertions.Assert_Compiled_Matches_Interpreter(expression);
    }

    private static Stream<String> Constant_Expressions() {
        return Stream.of(CONSTANT_EXPRESSIONS);
    }

    @ParameterizedTest(name = "{0} @ value={1}")
    @MethodSource("Query_Cases")
    void Queries_Match_Interpreter(String expression, float value) throws MolangLexException, MolangParseException {
        TestContext context = new TestContext(value);

        MolangExpression interpreted = MolangParser.parse(expression,
                        MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY)
                .bind(BINDING_CONTEXT, context);

        MolangExpression compiled = MolangParser.parse(expression,
                        MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE)
                .bind(BINDING_CONTEXT, context);

        float interpretedResult = interpreted.evaluate();
        float compiledResult = compiled.evaluate();

        float epsilon = 1e-5f;
        boolean matches = Math.abs(interpretedResult - compiledResult) < epsilon
                || (Float.isNaN(interpretedResult) && Float.isNaN(compiledResult))
                || (Float.isInfinite(interpretedResult) && Float.isInfinite(compiledResult)
                && Math.signum(interpretedResult) == Math.signum(compiledResult));

        if (!matches) {
            throw new AssertionError(String.format(
                    "Mismatch for '%s' with value=%f -> interpreted=%f compiled=%f",
                    expression, value, interpretedResult, compiledResult));
        }
    }

    private static Stream<Arguments> Query_Cases() {
        return Stream.of(QUERY_EXPRESSIONS)
                .flatMap(expr -> java.util.stream.IntStream.range(0, QUERY_VALUES.length)
                        .mapToObj(i -> Arguments.of(expr, QUERY_VALUES[i])));
    }

    public record TestContext(float value) {}
}
