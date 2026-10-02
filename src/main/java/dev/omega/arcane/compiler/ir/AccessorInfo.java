package dev.omega.arcane.compiler.ir;

import dev.omega.arcane.reference.FloatAccessor;

import java.lang.reflect.Modifier;

public final class AccessorInfo {
    public final FloatAccessor<?> accessor;
    public final Object target;
    public final boolean isSpecialized;
    public final String targetClass;

    public AccessorInfo(FloatAccessor<?> accessor, Object target) {
        this.accessor = accessor;
        this.target = target;
        this.targetClass = accessibleTargetClass(target);
        this.isSpecialized = true;
    }

    private static String accessibleTargetClass(Object target) {
        if (target == null) {
            return "java/lang/Object";
        }

        var type = target.getClass();
        if (type.isHidden() || type.isSynthetic() || !Modifier.isPublic(type.getModifiers())) {
            return "java/lang/Object";
        }

        return type.getName().replace('.', '/');
    }
}
