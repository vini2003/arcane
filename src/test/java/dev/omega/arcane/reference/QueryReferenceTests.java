package dev.omega.arcane.reference;

import dev.omega.arcane.ast.MolangExpression;
import dev.omega.arcane.ast.ReferenceExpression;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.parser.MolangParser;
import dev.omega.arcane.reference.BoundFloatAccessorExpression;
import dev.omega.arcane.reference.FloatAccessor;
import dev.omega.arcane.util.DummyEntityObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class QueryReferenceTests {

    private final ExpressionBindingContext context = ExpressionBindingContext.create()
            .registerReferenceResolver(
                    ReferenceType.QUERY,
                    "age",
                    DummyEntityObject.class,
                    (FloatAccessor<DummyEntityObject>) entity -> entity.getAge()
            );

    @Test
    public void QueryReference_Parse_ReturnsExpression() throws MolangLexException, MolangParseException {
        Assertions.assertInstanceOf(ReferenceExpression.class, MolangParser.parse("query.age"));
    }

    @Test
    public void QueryContextReference_Parse_ReturnsExpression() throws MolangLexException, MolangParseException {
        DummyEntityObject entity = new DummyEntityObject();
        entity.incrementAge();

        // parse with respect to (1) context for mapping, and (2) entity for value
        MolangExpression expression = MolangParser.parse("query.age").bind(context, entity);
        Assertions.assertInstanceOf(BoundFloatAccessorExpression.class, expression);
    }
}
