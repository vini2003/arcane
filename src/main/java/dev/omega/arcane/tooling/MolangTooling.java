package dev.omega.arcane.tooling;

import dev.omega.arcane.exception.MolangException;
import dev.omega.arcane.lexer.LexedMolang;
import dev.omega.arcane.lexer.MolangLexer;
import dev.omega.arcane.lexer.MolangTokenInstance;
import dev.omega.arcane.parser.MolangParser;
import org.jetbrains.annotations.ApiStatus;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Editor support for Molang source: span-carrying tokens for highlighting, diagnostics that say where an expression
 * breaks, and caret context for completion. Nothing here changes how Arcane parses or evaluates; every method is
 * safe on partial or malformed input and never throws.
 */
@ApiStatus.AvailableSince("0.1.13")
public final class MolangTooling {

    /** Validate exactly what {@link MolangParser#parse(String)} accepts. */
    public static final int VALIDATE_DEFAULT = 0;

    /**
     * Treat tabs and line breaks as spaces, for callers that normalise whitespace before parsing (multi-line
     * editors). Arcane's lexer itself only accepts spaces.
     */
    public static final int VALIDATE_ANY_WHITESPACE = 1;

    private static final List<MolangFunction> MATH_FUNCTIONS = List.of(
            function("abs", "Absolute value.", "value"),
            function("acos", "Arc cosine.", "value"),
            function("asin", "Arc sine.", "value"),
            function("atan", "Arc tangent.", "value"),
            function("atan2", "Arc tangent of y / x.", "y", "x"),
            function("ceil", "Rounds up to the nearest whole number.", "value"),
            function("clamp", "Limits value to the range min to max.", "value", "min", "max"),
            function("cos", "Cosine of an angle in degrees.", "degrees"),
            function("die_roll", "Sum of count random values, each between low and high.", "count", "low", "high"),
            function("die_roll_integer", "Sum of count random whole numbers, each between low and high.", "count", "low", "high"),
            function("exp", "e raised to the power of value.", "value"),
            function("floor", "Rounds down to the nearest whole number.", "value"),
            function("hermite_blend", "Smooth 0 to 1 ease: 3t² - 2t³.", "t"),
            function("lerp", "Blends from start to end by t (0 to 1).", "start", "end", "t"),
            function("ln", "Natural logarithm.", "value"),
            function("max", "The larger of a and b.", "a", "b"),
            function("min", "The smaller of a and b.", "a", "b"),
            function("min_angle", "Wraps an angle into -180 to 180 degrees.", "degrees"),
            function("mod", "Remainder of value / divisor.", "value", "divisor"),
            new MolangFunction("pi", List.of(), false, "π, about 3.14159."),
            function("pow", "base raised to the power of exponent.", "base", "exponent"),
            function("random", "A random value between low and high.", "low", "high"),
            function("random_integer", "A random whole number from low to high, inclusive.", "low", "high"),
            function("round", "Rounds to the nearest whole number.", "value"),
            function("sin", "Sine of an angle in degrees.", "degrees"),
            function("sqrt", "Square root.", "value"),
            function("trunc", "Drops the fractional part, rounding toward zero.", "value"));

    private MolangTooling() {
    }

    /**
     * Splits {@code source} into tokens with source spans for highlighting. Whitespace produces no tokens; text
     * Arcane cannot lex becomes {@link MolangTokenKind#INVALID} tokens, so partially typed input always tokenizes.
     */
    public static List<MolangToken> tokenize(String source) {
        return ToolingLexer.lex(source, true).tokens();
    }

    /**
     * Same as {@link #validate(String, int)} with {@link #VALIDATE_DEFAULT}.
     */
    public static List<MolangDiagnostic> validate(String source) {
        return validate(source, VALIDATE_DEFAULT);
    }

    /**
     * Reports why Arcane would reject {@code source}, located in the source: lexing problems, unbalanced
     * parentheses, the first parse error, and any trailing text Arcane would silently ignore. An empty list means
     * {@link MolangParser#parse(String)} accepts the whole expression. Whether the referenced queries and
     * variables exist is up to the caller, which knows what it binds.
     *
     * @param source the Molang expression
     * @param flags {@link #VALIDATE_DEFAULT} or {@link #VALIDATE_ANY_WHITESPACE}
     * @return diagnostics ordered by position
     */
    public static List<MolangDiagnostic> validate(String source, int flags) {
        var text = source != null ? source : "";

        if (text.isBlank()) {
            return List.of(MolangDiagnostic.error(0, text.length(), "Expression is empty"));
        }

        var anyWhitespace = (flags & VALIDATE_ANY_WHITESPACE) != 0;
        var lexed = ToolingLexer.lex(text, anyWhitespace);

        if (!lexed.problems().isEmpty()) {
            return sorted(lexed.problems());
        }

        var parentheses = parentheses(lexed.tokens());

        if (!parentheses.isEmpty()) {
            return parentheses;
        }

        try {
            var tokens = MolangLexer.lex(anyWhitespace ? spaces(text) : text);
            var leading = MolangParser.parseLeading(tokens);

            if (leading.consumedTokens() < tokens.tokens().size()) {
                return List.of(trailing(tokens, leading.consumedTokens()));
            }

            return List.of();
        } catch (MolangException exception) {
            return List.of(diagnostic(text, lexed.tokens(), exception));
        } catch (RuntimeException exception) {
            // The tooling lexer should have caught anything Arcane trips over; never fail the caller's frame.
            return List.of(MolangDiagnostic.error(0, text.length(), String.valueOf(exception.getMessage())));
        }
    }

    /**
     * Describes what is being typed at {@code offset} in {@code source}.
     */
    public static MolangCursorContext contextAt(String source, int offset) {
        var text = source != null ? source : "";
        var caret = Math.max(0, Math.min(offset, text.length()));
        var tokens = tokenize(text);
        var call = call(tokens, caret);

        MolangToken touched = null;
        MolangToken previous = null;

        for (var token : tokens) {
            if (token.touches(caret)) {
                touched = token;
                break;
            }

            if (token.end() <= caret) {
                previous = token;
            }
        }

        if (touched != null) {
            var prefix = text.substring(touched.start(), caret);

            return switch (touched.kind()) {
                case MEMBER -> new MolangCursorContext(MolangCursorContext.Kind.MEMBER, touched.namespace(),
                        namespaceText(tokens, touched), prefix, touched.start(), touched.end(), call);
                case NAMESPACE, IDENTIFIER, KEYWORD -> new MolangCursorContext(MolangCursorContext.Kind.IDENTIFIER,
                        null, "", prefix, touched.start(), touched.end(), call);
                case PUNCTUATION -> touched.text().equals(".") ? afterDot(tokens, touched, caret, call) : empty(caret, call);
                case OPERATOR -> empty(caret, call);
                default -> new MolangCursorContext(MolangCursorContext.Kind.NONE, null, "", "", caret, caret, call);
            };
        }

        if (previous != null && previous.text().equals(".") && previous.end() == caret) {
            return afterDot(tokens, previous, caret, call);
        }

        return empty(caret, call);
    }

    /**
     * The math functions Arcane's parser accepts after {@code math.}, with parameter names and descriptions.
     */
    public static List<MolangFunction> mathFunctions() {
        return MATH_FUNCTIONS;
    }

    /**
     * Statement keywords from the Molang specification. Arcane parses expressions only, so it rejects all of them;
     * editors still highlight them.
     */
    public static Set<String> keywords() {
        return ToolingLexer.KEYWORDS;
    }

    private static MolangCursorContext empty(int caret, MolangCursorContext.Call call) {
        return new MolangCursorContext(MolangCursorContext.Kind.IDENTIFIER, null, "", "", caret, caret, call);
    }

    private static MolangCursorContext afterDot(List<MolangToken> tokens, MolangToken dot, int caret, MolangCursorContext.Call call) {
        var index = tokens.indexOf(dot);
        var owner = index > 0 ? tokens.get(index - 1) : null;

        if (owner == null || owner.kind() != MolangTokenKind.NAMESPACE) {
            return new MolangCursorContext(MolangCursorContext.Kind.NONE, null, "", "", caret, caret, call);
        }

        // A name right after the caret ("q.|name") is replaced as a whole.
        var next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        var end = next != null && next.start() == caret && next.kind() == MolangTokenKind.MEMBER ? next.end() : caret;

        return new MolangCursorContext(MolangCursorContext.Kind.MEMBER, owner.namespace(), owner.text(), "", caret, end, call);
    }

    private static String namespaceText(List<MolangToken> tokens, MolangToken member) {
        var index = tokens.indexOf(member);
        return index >= 2 ? tokens.get(index - 2).text() : "";
    }

    private static MolangCursorContext.Call call(List<MolangToken> tokens, int caret) {
        record Frame(MolangNamespace namespace, String name, int openParen, int[] argument) {
        }

        var frames = new ArrayDeque<Frame>();

        for (int index = 0; index < tokens.size(); index++) {
            var token = tokens.get(index);

            if (token.end() > caret) {
                break;
            }

            switch (token.text()) {
                case "(" -> {
                    var callee = index > 0 ? tokens.get(index - 1) : null;
                    var named = callee != null && (callee.kind() == MolangTokenKind.MEMBER || callee.kind() == MolangTokenKind.IDENTIFIER);
                    frames.push(new Frame(named ? callee.namespace() : null, named ? callee.text() : "", token.start(), new int[1]));
                }
                case ")" -> frames.poll();
                case "," -> {
                    if (!frames.isEmpty()) {
                        frames.peek().argument()[0]++;
                    }
                }
                default -> {
                }
            }
        }

        for (var frame : frames) {
            if (!frame.name().isEmpty()) {
                return new MolangCursorContext.Call(frame.namespace(), frame.name(), frame.argument()[0], frame.openParen());
            }
        }

        return null;
    }

    private static List<MolangDiagnostic> parentheses(List<MolangToken> tokens) {
        var open = new ArrayDeque<MolangToken>();
        var problems = new ArrayList<MolangDiagnostic>();

        for (var token : tokens) {
            if (token.text().equals("(")) {
                open.push(token);
            } else if (token.text().equals(")")) {
                if (open.isEmpty()) {
                    problems.add(MolangDiagnostic.error(token.start(), token.end(), "Unmatched ')'"));
                } else {
                    open.pop();
                }
            }
        }

        for (var token : open) {
            problems.add(MolangDiagnostic.error(token.start(), token.end(), "Unclosed '('; add a matching ')'"));
        }

        return sorted(problems);
    }

    private static MolangDiagnostic trailing(LexedMolang lexed, int consumed) {
        var tokens = lexed.tokens();
        MolangTokenInstance first = tokens.get(consumed);
        MolangTokenInstance last = tokens.get(tokens.size() - 1);

        var message = switch (first.type()) {
            case SEMICOLON -> "Statements are not supported; Arcane would ignore everything from ';'";
            case EQUAL -> "Assignments are not supported; did you mean '=='?";
            case QUESTION_QUESTION -> "'??' is not supported; Arcane would ignore everything from here";
            default -> "Unexpected '" + first.lexeme() + "'; Arcane would ignore everything from here";
        };

        return MolangDiagnostic.error(first.start(), last.end(), message);
    }

    private static MolangDiagnostic diagnostic(String text, List<MolangToken> tokens, MolangException exception) {
        if (!exception.hasSpan()) {
            return MolangDiagnostic.error(0, text.length(), String.valueOf(exception.getMessage()));
        }

        var start = Math.min(exception.start(), text.length());
        var end = Math.min(exception.end(), text.length());

        for (var token : tokens) {
            if (token.start() == start && token.kind() == MolangTokenKind.KEYWORD) {
                return MolangDiagnostic.error(start, end, "'" + token.text() + "' is not supported; Arcane only evaluates expressions");
            }
        }

        return MolangDiagnostic.error(start, end, String.valueOf(exception.getMessage()));
    }

    private static String spaces(String text) {
        var characters = text.toCharArray();

        for (int index = 0; index < characters.length; index++) {
            if (Character.isWhitespace(characters[index])) {
                characters[index] = ' ';
            }
        }

        return new String(characters);
    }

    private static List<MolangDiagnostic> sorted(List<MolangDiagnostic> diagnostics) {
        var result = new ArrayList<>(diagnostics);
        result.sort(Comparator.comparingInt(MolangDiagnostic::start).thenComparingInt(MolangDiagnostic::end));
        return List.copyOf(result);
    }

    private static MolangFunction function(String name, String description, String... parameters) {
        return new MolangFunction(name, List.of(parameters), true, description);
    }
}
