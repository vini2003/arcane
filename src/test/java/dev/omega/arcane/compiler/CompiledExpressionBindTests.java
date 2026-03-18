package dev.omega.arcane.compiler;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.ExpressionBindingContext;
import dev.omega.arcane.reference.FloatAccessor;
import dev.omega.arcane.reference.ReferenceType;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CompiledExpressionBindTests {
    private static final ExpressionBindingContext CONTEXT = ExpressionBindingContext.create()
            .registerReferenceResolver(
                    ReferenceType.QUERY,
                    "value",
                    BindContext.class,
                    (FloatAccessor<BindContext>) BindContext::value
            );

    @Test
    void Binding_CompiledExpression_ReusesCompiledShapeAfterFirstBind() throws MolangLexException, MolangParseException {
        MolangExpression compiled = MolangParser.parse(
                "query.value * 2 + 1",
                MolangParser.FLAG_SIMPLIFY | MolangParser.FLAG_COMPILE
        );

        int before = Compiler.CLASS_COUNTER.get();
        int afterFirstBind = -1;

        for (int i = 0; i < 256; i++) {
            MolangExpression bound = compiled.bind(CONTEXT, new BindContext(i));
            float expected = i * 2.0f + 1.0f;
            Assertions.assertEquals(expected, bound.evaluate(), 1.0e-5f);

            if (i == 0) {
                Assertions.assertInstanceOf(CompiledExpression.class, bound);
                afterFirstBind = Compiler.CLASS_COUNTER.get();
            }
        }

        int after = Compiler.CLASS_COUNTER.get();

        Assertions.assertTrue(afterFirstBind >= before, "First bind should not decrease class counter");
        Assertions.assertEquals(afterFirstBind, after, "Subsequent binds should reuse compiled shape and avoid new class generation");
    }

    private record BindContext(float value) {
    }
}
