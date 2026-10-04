package dev.omega.arcane.tooling;

import dev.omega.arcane.parser.MolangParser;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MolangValidateTests {

    @Test
    public void Validate_ValidExpressions_NoDiagnostics() {
        for (String source : List.of(
                "1 + 2",
                "q.a * math.sin(v.b) > 0 ? 1 : 0",
                "math.pi",
                "math.clamp(1, 2, 3)",
                "-q.x",
                "!(q.a && q.b)",
                "query.anim_time * 2 / 3")) {
            Assertions.assertEquals(List.of(), MolangTooling.validate(source), source);
        }
    }

    @Test
    public void Validate_Empty_ReportsEmptyExpression() {
        assertError(MolangTooling.validate("  "), 0, 2, "empty");
        assertError(MolangTooling.validate(null), 0, 0, "empty");
    }

    @Test
    public void Validate_MissingOperand_PointsAtTheEnd() {
        assertError(MolangTooling.validate("1 +"), 3, 3, "end of the expression");
        assertError(MolangTooling.validate("q.a >"), 5, 5, "end of the expression");
    }

    @Test
    public void Validate_MathArity_PointsAtTheOffendingToken() {
        assertError(MolangTooling.validate("math.clamp(1, 2)"), 15, 16, "takes 3 arguments");
        assertError(MolangTooling.validate("math.sin(1, 2)"), 10, 11, "takes 1 argument");
    }

    @Test
    public void Validate_UnknownNames_PointAtTheName() {
        assertError(MolangTooling.validate("1 + math.foo(1)"), 9, 12, "Unknown math function 'foo'");
        assertError(MolangTooling.validate("foo.bar"), 0, 3, "Unknown namespace 'foo'");
        assertError(MolangTooling.validate("t.x + 1"), 0, 1, "'t' references are not supported");
        assertError(MolangTooling.validate("2 * foo"), 4, 7, "Unknown name 'foo'");
        assertError(MolangTooling.validate("q."), 2, 2, "name");
    }

    @Test
    public void Validate_Parentheses_PointAtTheUnbalancedOne() {
        assertError(MolangTooling.validate("q.a + (1"), 6, 7, "Unclosed '('");
        assertError(MolangTooling.validate("1)"), 1, 2, "Unmatched ')'");
    }

    @Test
    public void Validate_TrailingTokens_CoverTheIgnoredText() {
        assertError(MolangTooling.validate("1 2"), 2, 3, "Unexpected '2'");
        assertError(MolangTooling.validate("v.a = 1"), 4, 7, "'=='");
        assertError(MolangTooling.validate("q.a; q.b"), 3, 8, "Statements");
    }

    @Test
    public void Validate_LexProblems_AllReportedInOrder() {
        var diagnostics = MolangTooling.validate("q.a & $b");

        Assertions.assertEquals(2, diagnostics.size());
        assertError(diagnostics.subList(0, 1), 4, 5, "'&&'");
        assertError(diagnostics.subList(1, 2), 6, 8, "start with a letter");
        assertError(MolangTooling.validate("\"abc"), 0, 4, "Unterminated string");
    }

    @Test
    public void Validate_Whitespace_DependsOnFlags() {
        assertError(MolangTooling.validate("q.a\n+ 1"), 3, 4, "spaces");
        Assertions.assertEquals(List.of(), MolangTooling.validate("q.a\n\t+ 1", MolangTooling.VALIDATE_ANY_WHITESPACE));
    }

    @Test
    public void Validate_UnsupportedSyntax_Explained() {
        assertError(MolangTooling.validate("return 1"), 0, 6, "'return' is not supported");
        assertError(MolangTooling.validate("\"abc\""), 0, 5, "Strings");
    }

    @Test
    public void Validate_PartialInput_NeverThrows() {
        String source = "math.clamp(q.anim_time * 2.5, -v.min, v.max) > 0.5 && !(q.is_on_ground || t.x == \"a\") ? 1 : 0 &| $ _";

        for (int length = 0; length <= source.length(); length++) {
            String prefix = source.substring(0, length);

            for (MolangDiagnostic diagnostic : MolangTooling.validate(prefix)) {
                Assertions.assertTrue(diagnostic.end() <= prefix.length(), prefix + " " + diagnostic);
                Assertions.assertFalse(diagnostic.message().isBlank(), prefix);
            }
        }
    }

    @Test
    public void Validate_NoDiagnostics_ExactlyWhenArcaneParsesEverything() {
        for (String source : List.of("1 + 2", "q.a ? 1 : 2", "math.min(1, q.b)", "1 - 2 + 3", "(", "q.a b")) {
            boolean parses;

            try {
                MolangParser.parse(source, MolangParser.FLAG_NONE);
                parses = true;
            } catch (Exception exception) {
                parses = false;
            }

            boolean clean = MolangTooling.validate(source).isEmpty();

            // Arcane also "parses" input with ignored trailing tokens; validation must still flag those.
            Assertions.assertTrue(!clean || parses, source);
        }
    }

    @Test
    public void MathFunctions_EveryListedFunctionParsesWithItsArity() {
        for (MolangFunction function : MolangTooling.mathFunctions()) {
            var arguments = String.join(", ", Collections.nCopies(function.parameters().size(), "1"));
            var source = "math." + function.name() + (function.call() ? "(" + arguments + ")" : "");

            Assertions.assertEquals(List.of(), MolangTooling.validate(source), source);

            if (function.call()) {
                var extra = new ArrayList<>(Collections.nCopies(function.parameters().size() + 1, "1"));
                Assertions.assertFalse(MolangTooling.validate("math." + function.name() + "(" + String.join(", ", extra) + ")").isEmpty(), function.name());
            }
        }
    }

    private static void assertError(List<MolangDiagnostic> diagnostics, int start, int end, String message) {
        Assertions.assertFalse(diagnostics.isEmpty(), "Expected a diagnostic containing: " + message);
        var diagnostic = diagnostics.get(0);
        Assertions.assertEquals(MolangSeverity.ERROR, diagnostic.severity(), diagnostic.toString());
        Assertions.assertEquals(start, diagnostic.start(), diagnostic.toString());
        Assertions.assertEquals(end, diagnostic.end(), diagnostic.toString());
        Assertions.assertTrue(diagnostic.message().contains(message), diagnostic.toString());
    }
}
