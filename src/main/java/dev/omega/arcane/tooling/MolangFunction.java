package dev.omega.arcane.tooling;

import java.util.List;

/**
 * A built-in function, such as {@code math.clamp}, for completion and signature help.
 *
 * @param name the name after the namespace
 * @param parameters parameter names in call order; empty for constants such as {@code math.pi}
 * @param call whether it is written with parentheses
 * @param description a short, user-facing explanation
 */
public record MolangFunction(String name, List<String> parameters, boolean call, String description) {

    public MolangFunction {
        parameters = List.copyOf(parameters);
    }

    /**
     * The function as it is called, such as {@code clamp(value, min, max)}, or just the name for constants.
     */
    public String signature() {
        return call ? name + "(" + String.join(", ", parameters) + ")" : name;
    }
}
