package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.reference.FloatAccessor;

public final class AccessorInfo {
    public final FloatAccessor<?> accessor;
    public final Object target;
    public final boolean isSpecialized;
    public final String targetClass;

    public AccessorInfo(FloatAccessor<?> accessor, Object target) {
        this.accessor = accessor;
        this.target = target;
        this.targetClass = target.getClass().getName().replace('.', '/');
        this.isSpecialized = true;
    }
}
