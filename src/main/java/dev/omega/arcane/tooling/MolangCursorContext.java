package dev.omega.arcane.tooling;

import org.jetbrains.annotations.Nullable;

/**
 * What is being typed at a caret, from {@link MolangTooling#contextAt(String, int)}; drives completion and
 * signature help.
 *
 * @param kind whether the caret is on a bare identifier, a namespace member, or somewhere nothing completes
 * @param namespace for {@link Kind#MEMBER}, the namespace before the dot
 * @param namespaceText for {@link Kind#MEMBER}, the namespace as written ({@code q} or {@code query}); otherwise empty
 * @param prefix the part of the name before the caret
 * @param replaceStart where an accepted completion starts replacing
 * @param replaceEnd where an accepted completion stops replacing; the end of the whole name under the caret
 * @param call the innermost function call whose argument list contains the caret, or {@code null}
 */
public record MolangCursorContext(
        Kind kind,
        @Nullable MolangNamespace namespace,
        String namespaceText,
        String prefix,
        int replaceStart,
        int replaceEnd,
        @Nullable Call call) {

    public enum Kind {
        /** Inside a number or string, or otherwise where no name can be typed. */
        NONE,
        /** On a bare name (possibly empty, after an operator), such as {@code que} or a namespace being typed. */
        IDENTIFIER,
        /** On the name after {@code namespace.}, possibly empty right after the dot. */
        MEMBER
    }

    /**
     * A call around the caret, such as {@code math.clamp(x, |)}.
     *
     * @param namespace the callee's namespace, or {@code null} when the parentheses only group
     * @param name the callee's name, or empty when the parentheses only group
     * @param argument the zero-based index of the argument holding the caret
     * @param openParen the offset of the call's {@code (}
     */
    public record Call(@Nullable MolangNamespace namespace, String name, int argument, int openParen) {
    }
}
