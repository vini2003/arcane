package dev.omega.arcane.compiler;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.reference.ExpressionBindingContext;

public record CompiledExpression(MolangExpression source, CompiledEvaluator evaluator) implements MolangExpression {
    @Override
    public float evaluate() {
        return evaluator.evaluate();
    }

    @Override
    public MolangExpression simplify() {
        return this;
    }

    @Override
    public MolangExpression bind(ExpressionBindingContext context, Object... values) {
        // When binding, we must update the source expression; not this one.
        var bound = source.bind(context, values);

        if (bound == source) {
            return this;
        }

        return Compiler.compile(bound);
    }
}
