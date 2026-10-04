package dev.omega.arcane.tooling;

public enum MolangSeverity {
    /** Arcane cannot parse the expression, or would silently ignore part of it. */
    ERROR,
    /** The expression parses but likely does not do what was meant, such as reading an unknown query. */
    WARNING,
    INFO
}
