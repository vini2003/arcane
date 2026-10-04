package dev.omega.arcane.tooling;

import org.jetbrains.annotations.Nullable;

/**
 * A token from {@link MolangTooling#tokenize(String)} with its source span: {@code start} inclusive, {@code end}
 * exclusive. {@code namespace} is set for {@link MolangTokenKind#NAMESPACE} tokens and for the
 * {@link MolangTokenKind#MEMBER} that follows one.
 */
public record MolangToken(MolangTokenKind kind, int start, int end, String text, @Nullable MolangNamespace namespace) {

    public int length() {
        return end - start;
    }

    /**
     * Whether {@code offset} falls inside this token or right after its last character, where a caret still
     * belongs to it.
     */
    public boolean touches(int offset) {
        return offset > start && offset <= end;
    }
}
