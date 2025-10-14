package dev.omega.arcane.reference;

@FunctionalInterface
public interface FloatAccessor<T> {

    float apply(T value);
}
