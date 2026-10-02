package dev.omega.arcane.math;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.random.MolangRandomSource;
import dev.omega.arcane.reference.ExpressionBindingContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class RandomSourceBindingTests {
    private static final ExpressionBindingContext CONTEXT = ExpressionBindingContext.create()
            .registerRandomSource(SequenceRandom.class, SequenceRandom::source);

    @Test
    void Bound_Random_Source_Drives_Interpreted_Math_Random() throws MolangLexException, MolangParseException {
        var random = new SequenceRandom(0.25F, 0.75F);
        var expression = MolangParser.parse("m.random(0, 10) + m.random(0, 10)",
                MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY);

        var bound = expression.bind(CONTEXT, random);

        assertEquals(10.0F, bound.evaluate(), 1.0E-6F);
    }

    @Test
    void Bound_Random_Source_Drives_Compiled_Math_Random() throws MolangLexException, MolangParseException {
        var random = new SequenceRandom(0.25F, 0.75F);
        var expression = MolangParser.parse("m.random(0, 10) + m.random(0, 10)",
                MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

        var bound = expression.bind(CONTEXT, random);

        assertEquals(10.0F, bound.evaluate(), 1.0E-6F);
    }

    @Test
    void Bound_Random_Source_Drives_Compiled_Random_Integer() throws MolangLexException, MolangParseException {
        var random = new SequenceRandom(0.0F, 0.999F);
        var expression = MolangParser.parse("m.random_integer(2, 4) + m.random_integer(2, 4)",
                MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

        var bound = expression.bind(CONTEXT, random);

        assertEquals(6.0F, bound.evaluate(), 1.0E-6F);
    }

    @Test
    void Bound_Random_Source_Drives_Compiled_Die_Roll_Fallback() throws MolangLexException, MolangParseException {
        var random = new SequenceRandom(0.25F, 0.75F);
        var expression = MolangParser.parse("m.die_roll(2, 1, 4)",
                MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

        var bound = expression.bind(CONTEXT, random);

        assertEquals(6.0F, bound.evaluate(), 1.0E-6F);
        assertEquals(2, random.index());
    }

    @Test
    void Bound_Random_Source_Is_Not_Cached_As_A_Reference() throws MolangLexException, MolangParseException {
        var random = new SequenceRandom(0.1F, 0.2F, 0.3F);
        var expression = MolangParser.parse("m.random(0, 1) + m.random(0, 1) + m.random(0, 1)",
                MolangParser.FLAG_CACHE | MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);

        var bound = expression.bind(CONTEXT, random);

        assertEquals(0.6F, bound.evaluate(), 1.0E-6F);
        assertEquals(3, random.index());
    }

    private static final class SequenceRandom {
        private final float[] values;
        private int index;

        private SequenceRandom(float... values) {
            this.values = values;
        }

        private MolangRandomSource source() {
            return this::nextFloat;
        }

        private float nextFloat() {
            return values[index++ % values.length];
        }

        private int index() {
            return index;
        }
    }
}
