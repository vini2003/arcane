package dev.omega.arcane.comparison;

import dev.omega.arcane.testing.ExpressionAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

@DisplayName("Comparison operators")
class ComparisonOperatorTests {

    @ParameterizedTest(name = "{0}")
    @MethodSource("Ordered_Operator_Cases")
    void Ordered_Comparisons_Matches_Interpreter(String expression) {
        ExpressionAssertions.Assert_Compiled_Matches_Interpreter(expression);
    }

    private static Stream<String> Ordered_Operator_Cases() {
        return Stream.of(
                "1.0 < 2.0",
                "2.0 < 2.0",
                "2.0 <= 2.0",
                "-5.0 <= -2.0",
                "3.0 > 1.0",
                "-2.0 > -3.0",
                "3.0 >= 3.0",
                "-1.0 >= -1.0"
        );
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("Equality_Cases")
    void Equality_Operators_Use_Expected_Truthiness(String expression, float expected) {
        ExpressionAssertions.Assert_Evaluates_To(expression, expected);
    }

    private static Stream<Arguments> Equality_Cases() {
        return Stream.of(
                Arguments.of("2.0 == 2.0", 1.0f),
                Arguments.of("2.0 == 3.0", 0.0f),
                Arguments.of("-4.0 != -4.0", 0.0f),
                Arguments.of("-4.0 != 5.0", 1.0f)
        );
    }

    @Test
    void Comparisons_Treat_Nan_As_False_Except_Not_Equal() {
        String nan = "math.acos(720.0)";
        ExpressionAssertions.Assert_Evaluates_To("0.0 < " + nan, 0.0);
        ExpressionAssertions.Assert_Evaluates_To("0.0 <= " + nan, 0.0);
        ExpressionAssertions.Assert_Evaluates_To("0.0 > " + nan, 0.0);
        ExpressionAssertions.Assert_Evaluates_To("0.0 >= " + nan, 0.0);
        ExpressionAssertions.Assert_Evaluates_To("0.0 == " + nan, 0.0);
        ExpressionAssertions.Assert_Evaluates_To("0.0 != " + nan, 1.0);
    }
}
