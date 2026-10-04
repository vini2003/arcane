package dev.omega.arcane.exception;

/**
 * Base class for Molang lexing and parsing failures. When known, {@link #start()} and {@link #end()} locate the
 * offending source text so tools can point at it; both are {@code -1} otherwise.
 */
public class MolangException extends Exception {

    private final int start;
    private final int end;

    public MolangException(String message) {
        this(message, -1, -1);
    }

    public MolangException(String message, int start, int end) {
        super(message);
        this.start = start;
        this.end = Math.max(start, end);
    }

    /**
     * The source offset (inclusive) where the problem starts, or {@code -1} if unknown.
     */
    public int start() {
        return start;
    }

    /**
     * The source offset (exclusive) where the problem ends, or {@code -1} if unknown. Equal to {@link #start()} for
     * problems at a position rather than over a range, such as an unexpected end of input.
     */
    public int end() {
        return end;
    }

    public boolean hasSpan() {
        return start >= 0;
    }
}
