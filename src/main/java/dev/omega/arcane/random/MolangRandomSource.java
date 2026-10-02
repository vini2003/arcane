package dev.omega.arcane.random;

/**
 * Evaluation-scoped source for Molang random functions.
 *
 * <p>Consumers can bind one of these through {@code ExpressionBindingContext}
 * when {@code m.random}, {@code m.random_integer}, or die-roll functions must
 * be replayable from caller-owned state instead of Arcane's process-global
 * fallback random.</p>
 */
@FunctionalInterface
public interface MolangRandomSource {
    float nextFloat();
}
