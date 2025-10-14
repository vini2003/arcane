package dev.omega.arcane.reference;

import dev.omega.arcane.ast.ObjectAwareExpression;

public final class BoundFloatAccessorExpression<T> extends ObjectAwareExpression<T> {

    private final FloatAccessor<T> accessor;

    public BoundFloatAccessorExpression(T value, FloatAccessor<T> accessor) {
        super(value);
        this.accessor = accessor;
    }

    @Override
    public float evaluate() {
        return accessor.apply(value);
    }

    public FloatAccessor<T> accessor() {
        return accessor;
    }

    public T boundValue() {
        return value;
    }
}
