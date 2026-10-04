package dev.omega.arcane.tooling;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * A lexer that never throws. Token boundaries match {@link dev.omega.arcane.lexer.MolangLexer} for anything that
 * lexer accepts; anything it rejects becomes an {@link MolangTokenKind#INVALID} token plus a problem.
 */
final class ToolingLexer {
    static final Set<String> KEYWORDS = Set.of("return", "loop", "for_each", "break", "continue", "this");

    private final String text;
    private final boolean anyWhitespace;
    private final List<MolangToken> tokens = new ArrayList<>();
    private final List<MolangDiagnostic> problems = new ArrayList<>();
    private int cursor;

    private ToolingLexer(String text, boolean anyWhitespace) {
        this.text = text;
        this.anyWhitespace = anyWhitespace;
    }

    record Result(List<MolangToken> tokens, List<MolangDiagnostic> problems) {
    }

    static Result lex(String text, boolean anyWhitespace) {
        var lexer = new ToolingLexer(text != null ? text : "", anyWhitespace);
        lexer.run();
        return new Result(classify(lexer.tokens), List.copyOf(lexer.problems));
    }

    private void run() {
        while (cursor < text.length()) {
            int start = cursor;
            char c = text.charAt(cursor++);

            switch (c) {
                case ' ' -> {
                }
                case '+', '-', '*', '/', ':' -> add(MolangTokenKind.OPERATOR, start);
                case '(', ')', '{', '}', '[', ']', '.', ',', ';' -> add(MolangTokenKind.PUNCTUATION, start);
                case '=', '>', '<', '!' -> {
                    consume('=');
                    add(MolangTokenKind.OPERATOR, start);
                }
                case '?' -> {
                    consume('?');
                    add(MolangTokenKind.OPERATOR, start);
                }
                case '&', '|' -> {
                    if (consume(c)) {
                        add(MolangTokenKind.OPERATOR, start);
                    } else {
                        invalid(start, "Use '" + c + c + "' for logical " + (c == '&' ? "AND" : "OR")
                                + "; bitwise '" + c + "' is not supported");
                    }
                }
                case '"' -> string(start);
                default -> {
                    if (Character.isWhitespace(c)) {
                        if (!anyWhitespace) {
                            problems.add(MolangDiagnostic.error(start, cursor,
                                    "Only spaces may separate tokens; Arcane rejects tabs and line breaks"));
                        }
                    } else if (Character.isDigit(c)) {
                        number(start);
                    } else if (Character.isAlphabetic(c)) {
                        while (cursor < text.length() && Character.isJavaIdentifierPart(text.charAt(cursor))) {
                            cursor++;
                        }

                        add(MolangTokenKind.IDENTIFIER, start);
                    } else if (Character.isJavaIdentifierStart(c)) {
                        // '_' and '$' would start a Java identifier but Arcane only accepts letters.
                        while (cursor < text.length() && Character.isJavaIdentifierPart(text.charAt(cursor))) {
                            cursor++;
                        }

                        invalid(start, "Names must start with a letter");
                    } else {
                        invalid(start, "Unexpected character '" + c + "'");
                    }
                }
            }
        }
    }

    private void string(int start) {
        int close = text.indexOf('"', cursor);

        if (close < 0) {
            cursor = text.length();
            add(MolangTokenKind.STRING, start);
            problems.add(MolangDiagnostic.error(start, cursor, "Unterminated string; add a closing '\"'"));
            return;
        }

        cursor = close + 1;
        add(MolangTokenKind.STRING, start);
    }

    // Same shape as MolangLexer: digits with at most one '.', where a second '.' ends the number.
    private void number(int start) {
        boolean decimal = false;

        while (cursor < text.length()) {
            char next = text.charAt(cursor);

            if (Character.isDigit(next)) {
                cursor++;
            } else if (next == '.' && !decimal) {
                decimal = true;
                cursor++;
            } else {
                break;
            }
        }

        var lexeme = text.substring(start, cursor);

        try {
            Float.parseFloat(lexeme);
            add(MolangTokenKind.NUMBER, start);
        } catch (NumberFormatException exception) {
            invalid(start, "Invalid number '" + lexeme + "'");
        }
    }

    private boolean consume(char expected) {
        if (cursor < text.length() && text.charAt(cursor) == expected) {
            cursor++;
            return true;
        }

        return false;
    }

    private void add(MolangTokenKind kind, int start) {
        tokens.add(new MolangToken(kind, start, cursor, text.substring(start, cursor), null));
    }

    private void invalid(int start, String message) {
        add(MolangTokenKind.INVALID, start);
        problems.add(MolangDiagnostic.error(start, cursor, message));
    }

    /**
     * Names become namespaces, members and keywords once their neighbours are known.
     */
    private static List<MolangToken> classify(List<MolangToken> raw) {
        var result = new ArrayList<MolangToken>(raw.size());

        for (int index = 0; index < raw.size(); index++) {
            var token = raw.get(index);

            if (token.kind() != MolangTokenKind.IDENTIFIER) {
                result.add(token);
                continue;
            }

            var owner = index >= 2 && raw.get(index - 1).text().equals(".") ? result.get(index - 2) : null;

            if (owner != null && owner.kind() == MolangTokenKind.NAMESPACE) {
                result.add(new MolangToken(MolangTokenKind.MEMBER, token.start(), token.end(), token.text(), owner.namespace()));
            } else if (MolangNamespace.of(token.text()) != null) {
                result.add(new MolangToken(MolangTokenKind.NAMESPACE, token.start(), token.end(), token.text(), MolangNamespace.of(token.text())));
            } else if (KEYWORDS.contains(token.text())) {
                result.add(new MolangToken(MolangTokenKind.KEYWORD, token.start(), token.end(), token.text(), null));
            } else {
                result.add(token);
            }
        }

        return List.copyOf(result);
    }
}
