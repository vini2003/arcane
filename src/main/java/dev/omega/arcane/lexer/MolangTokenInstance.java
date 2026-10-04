package dev.omega.arcane.lexer;

/**
 * A lexed token. {@code start} (inclusive) and {@code end} (exclusive) are offsets into the lexed source, or
 * {@code -1} for tokens built by hand.
 */
public record MolangTokenInstance(MolangTokenType type, String lexeme, Object value, int start, int end) {

    public MolangTokenInstance(MolangTokenType type, String lexeme) {
        this(type, lexeme, null);
    }

    public MolangTokenInstance(MolangTokenType type, String lexeme, Object value) {
        this(type, lexeme, value, -1, -1);
    }

    /**
     * Returns {@code true} if this token knows where it came from in the lexed source.
     */
    public boolean hasSpan() {
        return start >= 0 && end >= start;
    }
}
