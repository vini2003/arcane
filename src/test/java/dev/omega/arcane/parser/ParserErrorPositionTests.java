package dev.omega.arcane.parser;

import dev.omega.arcane.exception.MolangException;
import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.exception.MolangParseException;
import dev.omega.arcane.lexer.MolangLexer;
import dev.omega.arcane.lexer.MolangTokenInstance;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

public class ParserErrorPositionTests {

    @Test
    public void Lex_Tokens_CarrySourceSpans() throws MolangLexException {
        List<MolangTokenInstance> tokens = MolangLexer.lex("q.a >= 10").tokens();

        Assertions.assertEquals(List.of(0, 1, 2, 4, 7), tokens.stream().map(MolangTokenInstance::start).toList());
        Assertions.assertEquals(List.of(1, 2, 3, 6, 9), tokens.stream().map(MolangTokenInstance::end).toList());
        Assertions.assertEquals(">=", tokens.get(3).lexeme());
    }

    @Test
    public void Lex_String_LexemeAndValue() throws MolangLexException {
        MolangTokenInstance token = MolangLexer.lex("\"abc\"").tokens().get(0);

        Assertions.assertEquals("\"abc\"", token.lexeme());
        Assertions.assertEquals("abc", token.value());
    }

    @Test
    public void Lex_InvalidCharacter_ExceptionCarriesPosition() {
        MolangLexException exception = Assertions.assertThrows(MolangLexException.class, () -> MolangLexer.lex("1 # 2"));

        Assertions.assertEquals(2, exception.start());
        Assertions.assertEquals(3, exception.end());
    }

    @Test
    public void Lex_TrailingComparison_DoesNotCrash() throws MolangLexException {
        Assertions.assertEquals(2, MolangLexer.lex("5 >").tokens().size());
        Assertions.assertEquals(1, MolangLexer.lex("!").tokens().size());
    }

    @Test
    public void Parse_UnexpectedToken_ExceptionCarriesItsSpan() {
        MolangParseException exception = Assertions.assertThrows(MolangParseException.class,
                () -> MolangParser.parse("1 + )", MolangParser.FLAG_NONE));

        Assertions.assertEquals(4, exception.start());
        Assertions.assertEquals(5, exception.end());
    }

    @Test
    public void Parse_MissingValue_PointsAtTheEnd() {
        MolangException exception = Assertions.assertThrows(MolangParseException.class,
                () -> MolangParser.parse("5 >", MolangParser.FLAG_NONE));

        Assertions.assertEquals(3, exception.start());
        Assertions.assertEquals(3, exception.end());
    }

    @Test
    public void Parse_EmptyInput_ParseException() {
        Assertions.assertThrows(MolangParseException.class, () -> MolangParser.parse("", MolangParser.FLAG_NONE));
    }

    @Test
    public void Parse_UnknownNamespace_ParseExceptionAtTheName() {
        MolangParseException exception = Assertions.assertThrows(MolangParseException.class,
                () -> MolangParser.parse("1 + foo.bar", MolangParser.FLAG_NONE));

        Assertions.assertEquals(4, exception.start());
        Assertions.assertEquals(7, exception.end());
    }

    @Test
    public void ParseLeading_ReportsConsumedTokens() throws MolangLexException, MolangParseException {
        Assertions.assertEquals(3, MolangParser.parseLeading(MolangLexer.lex("1 - 2 + 3")).consumedTokens());
        Assertions.assertEquals(5, MolangParser.parseLeading(MolangLexer.lex("1 + 2 - 3")).consumedTokens());
    }

    @Test
    public void Parse_ValidExpressions_EvaluateAsBefore() throws MolangLexException, MolangParseException {
        // The ignored tail is long-standing behaviour; tooling flags it rather than the parser changing it.
        Assertions.assertEquals(-1.0F, MolangParser.parse("1 - 2 + 3", MolangParser.FLAG_NONE).evaluate());
        Assertions.assertEquals(0.5F, MolangParser.parse("math.clamp(0.5, 0, 1)", MolangParser.FLAG_NONE).evaluate());
    }
}
