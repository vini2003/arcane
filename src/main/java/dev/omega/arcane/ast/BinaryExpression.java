package dev.omega.arcane.ast;

import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.lexer.MolangTokenType;

public record BinaryExpression(MolangExpression left, MolangExpression right, MolangTokenType operator) implements MolangExpression {

    @Override
    public float evaluate() {
        float leftValue = left.evaluate();
        float rightValue = right.evaluate();

        return switch (operator) {
            case LESS_THAN -> leftValue < rightValue ? 1.0f : 0.0f;
            case GREATER_THAN -> leftValue > rightValue ? 1.0f : 0.0f;
            case LESS_THAN_OR_EQUAL -> leftValue <= rightValue ? 1.0f : 0.0f;
            case GREATER_THAN_OR_EQUAL -> leftValue >= rightValue ? 1.0f : 0.0f;
            case DOUBLE_EQUAL -> floatsEqual(leftValue, rightValue) ? 1.0f : 0.0f;
            case BANG_EQUAL -> floatsEqual(leftValue, rightValue) ? 0.0f : 1.0f;
            case DOUBLE_AMPERSAND -> {
                if (leftValue == 0.0f || rightValue == 0.0f) {
                    yield 0.0f;
                }
                yield 1.0f;
            }
            case DOUBLE_PIPE -> {
                if (leftValue != 0.0f || rightValue != 0.0f) {
                    yield 1.0f;
                }
                yield 0.0f;
            }
            default -> throw new RuntimeException("Operator type '" + operator + "' is not supported for binary operations.");
        };
    }

    @Override
    public MolangExpression simplify() {
        MolangExpression leftSimplified = left.simplify();
        MolangExpression rightSimplified = right.simplify();
        BinaryExpression simplifiedExpression = new BinaryExpression(leftSimplified, rightSimplified, operator);

        if (leftSimplified instanceof ConstantExpression && rightSimplified instanceof ConstantExpression) {
            return new ConstantExpression(simplifiedExpression.evaluate());
        }

        return simplifiedExpression;
    }

    @Override
    public MolangExpression bind(ExpressionBindingContext context, Object... values) {
        return new BinaryExpression(
                left.bind(context, values),
                right.bind(context, values),
                operator
        );
    }

    private static boolean floatsEqual(float leftValue, float rightValue) {
        if (Float.isNaN(leftValue) || Float.isNaN(rightValue)) {
            return false;
        }
        return leftValue == rightValue;
    }
}
