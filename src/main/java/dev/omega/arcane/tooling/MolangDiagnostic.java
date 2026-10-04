package dev.omega.arcane.tooling;

/**
 * A problem in Molang source. {@code start} is inclusive and {@code end} exclusive; an empty span
 * ({@code start == end}) marks a position, such as the end of an incomplete expression.
 */
public record MolangDiagnostic(MolangSeverity severity, int start, int end, String message) {

    public MolangDiagnostic {
        start = Math.max(0, start);
        end = Math.max(start, end);
        message = message != null ? message : "";
    }

    public static MolangDiagnostic error(int start, int end, String message) {
        return new MolangDiagnostic(MolangSeverity.ERROR, start, end, message);
    }

    public static MolangDiagnostic warning(int start, int end, String message) {
        return new MolangDiagnostic(MolangSeverity.WARNING, start, end, message);
    }

    public int length() {
        return end - start;
    }

    public boolean isError() {
        return severity == MolangSeverity.ERROR;
    }
}
