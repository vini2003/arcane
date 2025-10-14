package dev.omega.arcane.testing;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangException;
import dev.omega.arcane.parser.MolangParser;
import org.junit.jupiter.api.Assertions;

/**
 * Shared assertions for Molang expression testing.
 */
public final class ExpressionAssertions {
    private static final double EPSILON = 1e-6;

    private ExpressionAssertions() {
    }

    public static void Assert_Evaluates_To(String expression, double expected) {
        Assert_Evaluates_To(expression, expected, EPSILON);
    }

    public static void Assert_Evaluates_To(String expression, double expected, double tolerance) {
        double interpreted = Evaluate(expression, false);
        Assertions.assertEquals(expected, interpreted, tolerance,
                () -> "Expression '" + expression + "' evaluated to " + interpreted + " (expected " + expected + ")");

        double compiled = Evaluate(expression, true);
        Assertions.assertEquals(interpreted, compiled, EPSILON,
                () -> "Compiled expression diverged from interpreter for '" + expression + "'");
    }

    public static void Assert_Compiled_Matches_Interpreter(String expression) {
        double interpreted = Evaluate(expression, false);
        double compiled = Evaluate(expression, true);
        Assertions.assertEquals(interpreted, compiled, EPSILON,
                () -> "Compiled expression diverged from interpreter for '" + expression + "'");
    }

    private static double Evaluate(String expression, boolean compiled) {
        try {
            int flags = MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY;
            if (compiled) {
                flags |= MolangParser.FLAG_COMPILE;
            }
            MolangExpression parsed = MolangParser.parse(expression, flags);
            return parsed.evaluate();
        } catch (MolangException exception) {
            throw new RuntimeException("Failed to parse expression '" + expression + "'", exception);
        }
    }
}
