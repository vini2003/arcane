package dev.omega.arcane.logical;

import dev.omega.arcane.testing.ExpressionAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

@DisplayName("Truthiness semantics")
class TruthinessTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("Not_Cases")
    void Logical_Not_Uses_Zero_Vs_Non_Zero(String expression, float expected) {
        ExpressionAssertions.Assert_Evaluates_To(expression, expected);
    }

    private static Stream<Arguments> Not_Cases() {
        return Stream.of(
                Arguments.of("!0", 1.0f),
                Arguments.of("!(-0.0)", 1.0f),
                Arguments.of("!1", 0.0f),
                Arguments.of("!(-1.0)", 0.0f),
                Arguments.of("!2.5", 0.0f)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("andCases")
    void Logical_And_Treats_Negative_Values_As_Truthy(String expression, float expected) {
        ExpressionAssertions.Assert_Evaluates_To(expression, expected);
    }

    private static Stream<Arguments> andCases() {
        return Stream.of(
                Arguments.of("1.0 && 2.0", 1.0f),
                Arguments.of("-5.0 && -2.0", 1.0f),
                Arguments.of("-1.0 && 0.0", 0.0f),
                Arguments.of("0.0 && 3.0", 0.0f)
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("Or_Cases")
    void Logical_Or_Treats_Any_Non_Zero_As_Truthy(String expression, float expected) {
        ExpressionAssertions.Assert_Evaluates_To(expression, expected);
    }

    private static Stream<Arguments> Or_Cases() {
        return Stream.of(
                Arguments.of("0.0 || 2.0", 1.0f),
                Arguments.of("-4.0 || 0.0", 1.0f),
                Arguments.of("0.0 || 0.0", 0.0f),
                Arguments.of("2.0 || 3.0", 1.0f)
        );
    }
}
