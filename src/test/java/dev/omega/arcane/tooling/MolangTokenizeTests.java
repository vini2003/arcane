package dev.omega.arcane.tooling;

import dev.omega.arcane.exception.MolangLexException;
import dev.omega.arcane.lexer.MolangLexer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.omega.arcane.tooling.MolangTokenKind.*;

public class MolangTokenizeTests {

    @Test
    public void Tokenize_QueryReference_SpansAndKinds() {
        List<MolangToken> tokens = MolangTooling.tokenize("q.world_time * 2");

        assertToken(tokens.get(0), NAMESPACE, 0, 1, "q");
        Assertions.assertEquals(MolangNamespace.QUERY, tokens.get(0).namespace());
        assertToken(tokens.get(1), PUNCTUATION, 1, 2, ".");
        assertToken(tokens.get(2), MEMBER, 2, 12, "world_time");
        Assertions.assertEquals(MolangNamespace.QUERY, tokens.get(2).namespace());
        assertToken(tokens.get(3), OPERATOR, 13, 14, "*");
        assertToken(tokens.get(4), NUMBER, 15, 16, "2");
        Assertions.assertEquals(5, tokens.size());
    }

    @Test
    public void Tokenize_TwoCharacterOperators_SingleTokens() {
        List<MolangToken> tokens = MolangTooling.tokenize("a >= b && !c || d ?? e != f == g <= h");

        Assertions.assertEquals(List.of("a", ">=", "b", "&&", "!", "c", "||", "d", "??", "e", "!=", "f", "==", "g", "<=", "h"),
                tokens.stream().map(MolangToken::text).toList());
        assertToken(tokens.get(1), OPERATOR, 2, 4, ">=");
        assertToken(tokens.get(3), OPERATOR, 7, 9, "&&");
    }

    @Test
    public void Tokenize_MathCall_MembersCarryTheirNamespace() {
        List<MolangToken> tokens = MolangTooling.tokenize("math.clamp(v.x, 0, 1)");

        assertToken(tokens.get(0), NAMESPACE, 0, 4, "math");
        assertToken(tokens.get(2), MEMBER, 5, 10, "clamp");
        Assertions.assertEquals(MolangNamespace.MATH, tokens.get(2).namespace());
        assertToken(tokens.get(3), PUNCTUATION, 10, 11, "(");
        assertToken(tokens.get(6), MEMBER, 13, 14, "x");
        Assertions.assertEquals(MolangNamespace.VARIABLE, tokens.get(6).namespace());
    }

    @Test
    public void Tokenize_KeywordsAndUnsupportedNamespaces_Highlighted() {
        List<MolangToken> tokens = MolangTooling.tokenize("return t.x + c.y;");

        assertToken(tokens.get(0), KEYWORD, 0, 6, "return");
        assertToken(tokens.get(1), NAMESPACE, 7, 8, "t");
        Assertions.assertEquals(MolangNamespace.TEMP, tokens.get(1).namespace());
        Assertions.assertEquals(MolangNamespace.TEMP, tokens.get(3).namespace());
        Assertions.assertEquals(MolangNamespace.CONTEXT, tokens.get(5).namespace());
        assertToken(tokens.get(8), PUNCTUATION, 16, 17, ";");
    }

    @Test
    public void Tokenize_NamespaceWithoutDot_StillNamespace() {
        Assertions.assertEquals(NAMESPACE, MolangTooling.tokenize("query").get(0).kind());
        Assertions.assertEquals(IDENTIFIER, MolangTooling.tokenize("queries").get(0).kind());
    }

    @Test
    public void Tokenize_String_IncludesQuotes() {
        assertToken(MolangTooling.tokenize("\"abc\" + 1").get(0), STRING, 0, 5, "\"abc\"");
    }

    @Test
    public void Tokenize_InvalidInput_ProducesInvalidTokensWithoutThrowing() {
        List<MolangToken> tokens = MolangTooling.tokenize("q.a & $b _c # \"open");

        assertToken(tokens.get(3), INVALID, 4, 5, "&");
        assertToken(tokens.get(4), INVALID, 6, 8, "$b");
        assertToken(tokens.get(5), INVALID, 9, 11, "_c");
        assertToken(tokens.get(6), INVALID, 12, 13, "#");
        assertToken(tokens.get(7), STRING, 14, 19, "\"open");
    }

    @Test
    public void Tokenize_EveryPrefixOfPartialInput_SpansStayOrderedAndInBounds() {
        String source = "math.clamp(q.anim_time * 2.5, -v.min, v.max) > 0.5 && !(q.is_on_ground || t.x == \"a\") ? 1 : 0 &| $ _";

        for (int length = 0; length <= source.length(); length++) {
            String prefix = source.substring(0, length);
            int previousEnd = 0;

            for (MolangToken token : MolangTooling.tokenize(prefix)) {
                Assertions.assertTrue(token.start() >= previousEnd, prefix);
                Assertions.assertTrue(token.end() > token.start(), prefix);
                Assertions.assertTrue(token.end() <= prefix.length(), prefix);
                Assertions.assertEquals(prefix.substring(token.start(), token.end()), token.text());
                previousEnd = token.end();
            }
        }
    }

    @Test
    public void Tokenize_ValidInput_MatchesArcaneLexerSpans() throws MolangLexException {
        for (String source : List.of(
                "1 + 2",
                "q.a * math.sin(v.b) > 0 ? 1 : 0",
                "1.5..2 + 3.",
                "!(q.a && q.b) || q.c != 2 <= 3 >= 4",
                "\"text\" ?? x; {a[0]}",
                "math.pi")) {
            var arcane = MolangLexer.lex(source).tokens();
            var tooling = MolangTooling.tokenize(source);

            Assertions.assertEquals(arcane.size(), tooling.size(), source);

            for (int index = 0; index < arcane.size(); index++) {
                Assertions.assertEquals(arcane.get(index).start(), tooling.get(index).start(), source);
                Assertions.assertEquals(arcane.get(index).end(), tooling.get(index).end(), source);
                Assertions.assertEquals(arcane.get(index).lexeme(), tooling.get(index).text(), source);
            }
        }
    }

    private static void assertToken(MolangToken token, MolangTokenKind kind, int start, int end, String text) {
        Assertions.assertEquals(kind, token.kind(), token.toString());
        Assertions.assertEquals(start, token.start(), token.toString());
        Assertions.assertEquals(end, token.end(), token.toString());
        Assertions.assertEquals(text, token.text(), token.toString());
    }
}
