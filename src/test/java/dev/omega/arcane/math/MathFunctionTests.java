package dev.omega.arcane.math;

import dev.omega.arcane.testing.ExpressionAssertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

@DisplayName("Math function semantics")
class MathFunctionTests {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("Degree_Trig_Cases")
    void Sin_And_Cos_Interpret_Degrees(String expression, double expected) {
        ExpressionAssertions.Assert_Evaluates_To(expression, expected);
    }

    private static Stream<Arguments> Degree_Trig_Cases() {
        return Stream.of(
                Arguments.of("math.sin(0)", Math.sin(Math.toRadians(0))),
                Arguments.of("math.sin(180)", Math.sin(Math.toRadians(180))),
                Arguments.of("math.cos(60)", Math.cos(Math.toRadians(60))),
                Arguments.of("math.cos(270)", Math.cos(Math.toRadians(270)))
        );
    }
}
