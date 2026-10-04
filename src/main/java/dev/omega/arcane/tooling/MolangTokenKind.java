package dev.omega.arcane.tooling;

/**
 * What a {@link MolangToken} is, at the granularity editors highlight.
 */
public enum MolangTokenKind {
    NUMBER,
    STRING,
    /** Arithmetic, comparison, logical, assignment and conditional operators, including {@code :}. */
    OPERATOR,
    /** Parentheses, brackets, braces, {@code .}, {@code ,} and {@code ;}. */
    PUNCTUATION,
    /** {@code return}, {@code loop}, {@code for_each}, {@code break}, {@code continue} and {@code this}. */
    KEYWORD,
    /** A namespace such as {@code query} or {@code q}; see {@link MolangToken#namespace()}. */
    NAMESPACE,
    /** The name after {@code namespace.}, such as a query, variable or math function name. */
    MEMBER,
    IDENTIFIER,
    /** Text Arcane cannot lex, such as {@code $}, a single {@code &} or an identifier starting with {@code _}. */
    INVALID
}
