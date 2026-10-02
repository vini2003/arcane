package dev.omega.arcane.compiler;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.FloatAccessor;
import dev.omega.arcane.reference.ReferenceType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BranchCacheTests {
    private static final ExpressionBindingContext CONTEXT = ExpressionBindingContext.create()
            .registerReferenceResolver(ReferenceType.QUERY, "a", Values.class, (FloatAccessor<Values>) values -> values.a)
            .registerReferenceResolver(ReferenceType.QUERY, "b", Values.class, (FloatAccessor<Values>) values -> values.b)
            .registerReferenceResolver(ReferenceType.QUERY, "c", Values.class, (FloatAccessor<Values>) values -> values.c);
    private static final float[][] INPUTS = {
            {0.0f, 0.0f, 0.0f},
            {1.0f, 0.0f, 0.5f},
            {0.0f, 2.0f, 0.25f},
            {3.0f, 0.5f, 1.0f},
            {-1.0f, 4.0f, 2.0f},
    };

    @ParameterizedTest
    @ValueSource(strings = {
            "query.a > 0 && query.b > 0 ? math.clamp((query.a - query.c) / query.b, 0, 1) : 1",
            "query.a > 0 || query.b > 0 ? query.b * query.c : query.c",
            "(query.a > 0 ? query.b * 2 + query.c : 0) + (query.b * 2 + query.c)",
            "(query.a > 0 ? math.sin(query.b) : 0) + math.sin(query.b) + math.cos(query.b)",
            "query.a > 0 ? (query.b > 0 ? query.c : query.b) : query.c + query.b",
            "math.sin(query.b) + math.cos(query.b)",
            "math.cos(query.b) * 2 + math.sin(query.b) * math.cos(query.b)",
    })
    void Compiling_ValueFirstReadInsideBranch_CompilesAndMatchesInterpreter(String source)
            throws MolangLexException, MolangParseException {
        MolangExpression compiled = MolangParser.parse(source, MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE);
        MolangExpression interpreted = MolangParser.parse(source, MolangParser.FLAG_SIMPLIFY);

        for (float[] input : INPUTS) {
            Values values = new Values(input[0], input[1], input[2]);
            MolangExpression bound = compiled.bind(CONTEXT, values);

            Assertions.assertInstanceOf(CompiledExpression.class, bound, source);
            Assertions.assertEquals(interpreted.bind(CONTEXT, values).evaluate(), bound.evaluate(), 1.0e-5f, source);
        }
    }

    private record Values(float a, float b, float c) {
    }
}
