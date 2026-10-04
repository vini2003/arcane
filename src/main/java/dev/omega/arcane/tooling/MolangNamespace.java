package dev.omega.arcane.tooling;

import dev.omega.arcane.reference.ReferenceType;
import org.jetbrains.annotations.Nullable;

/**
 * The Molang namespaces a reference can start with, by full name and short alias ({@code query.x} or {@code q.x}).
 * Arcane evaluates {@link #QUERY}, {@link #VARIABLE} and {@link #MATH}; {@link #TEMP} and {@link #CONTEXT} are
 * recognised for highlighting and completion but rejected by the parser.
 */
public enum MolangNamespace {
    QUERY("query", "q", ReferenceType.QUERY),
    VARIABLE("variable", "v", ReferenceType.VARIABLE),
    TEMP("temp", "t", null),
    CONTEXT("context", "c", null),
    MATH("math", "m", null);

    private final String fullName;
    private final String alias;
    private final @Nullable ReferenceType referenceType;

    MolangNamespace(String fullName, String alias, @Nullable ReferenceType referenceType) {
        this.fullName = fullName;
        this.alias = alias;
        this.referenceType = referenceType;
    }

    public String fullName() {
        return fullName;
    }

    public String alias() {
        return alias;
    }

    /**
     * The binding type Arcane resolves this namespace's members with, or {@code null} for math and unsupported
     * namespaces.
     */
    public @Nullable ReferenceType referenceType() {
        return referenceType;
    }

    /**
     * Whether Arcane's parser accepts references in this namespace.
     */
    public boolean supported() {
        return this == QUERY || this == VARIABLE || this == MATH;
    }

    /**
     * The namespace written as {@code name} (full name or alias, case-sensitive like Arcane), or {@code null}.
     */
    public static @Nullable MolangNamespace of(String name) {
        for (MolangNamespace namespace : values()) {
            if (namespace.fullName.equals(name) || namespace.alias.equals(name)) {
                return namespace;
            }
        }

        return null;
    }
}
