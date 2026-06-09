package com.company.dbstudio.sql.highlight;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public abstract class BaseLexer implements Lexer {

    protected abstract Set<String> initKeywords();

    protected abstract Set<String> initFunctions();

    private final Set<String> keywords;
    private final Set<String> functions;

    protected BaseLexer() {
        this.keywords = initKeywords();
        this.functions = initFunctions();
    }

    @Override
    public Set<String> getKeywords() {
        return keywords;
    }

    @Override
    public Set<String> getFunctions() {
        return functions;
    }

    @Override
    public List<Token> tokenize(String sql) {
        List<Token> tokens = new ArrayList<>();
        if (sql == null || sql.isEmpty()) {
            return tokens;
        }

        int pos = 0;
        int length = sql.length();

        while (pos < length) {
            char c = sql.charAt(pos);

            if (Character.isWhitespace(c)) {
                int start = pos;
                while (pos < length && Character.isWhitespace(sql.charAt(pos))) {
                    pos++;
                }
                tokens.add(new Token(TokenType.WHITESPACE, sql.substring(start, pos), start, pos));
                continue;
            }

            if (c == '-' && pos + 1 < length && sql.charAt(pos + 1) == '-') {
                int start = pos;
                while (pos < length && sql.charAt(pos) != '\n') {
                    pos++;
                }
                tokens.add(new Token(TokenType.COMMENT, sql.substring(start, pos), start, pos));
                continue;
            }

            if (c == '/' && pos + 1 < length && sql.charAt(pos + 1) == '*') {
                int start = pos;
                pos += 2;
                while (pos < length - 1 && !(sql.charAt(pos) == '*' && sql.charAt(pos + 1) == '/')) {
                    pos++;
                }
                if (pos < length - 1) {
                    pos += 2;
                } else {
                    pos = length;
                }
                tokens.add(new Token(TokenType.COMMENT, sql.substring(start, pos), start, pos));
                continue;
            }

            if (c == '\'') {
                int start = pos;
                pos++;
                while (pos < length) {
                    if (sql.charAt(pos) == '\'') {
                        if (pos + 1 < length && sql.charAt(pos + 1) == '\'') {
                            pos += 2;
                        } else {
                            pos++;
                            break;
                        }
                    } else {
                        pos++;
                    }
                }
                tokens.add(new Token(TokenType.STRING, sql.substring(start, pos), start, pos));
                continue;
            }

            if (c == '"') {
                int start = pos;
                pos++;
                while (pos < length && sql.charAt(pos) != '"') {
                    if (sql.charAt(pos) == '\\' && pos + 1 < length) {
                        pos += 2;
                    } else {
                        pos++;
                    }
                }
                if (pos < length) {
                    pos++;
                }
                tokens.add(new Token(TokenType.STRING, sql.substring(start, pos), start, pos));
                continue;
            }

            if (Character.isDigit(c) || (c == '.' && pos + 1 < length && Character.isDigit(sql.charAt(pos + 1)))) {
                int start = pos;
                if (c == '0' && pos + 1 < length && (sql.charAt(pos + 1) == 'x' || sql.charAt(pos + 1) == 'X')) {
                    pos += 2;
                    while (pos < length && isHexDigit(sql.charAt(pos))) {
                        pos++;
                    }
                } else {
                    while (pos < length && (Character.isDigit(sql.charAt(pos)) || sql.charAt(pos) == '.')) {
                        pos++;
                    }
                    if (pos < length && (sql.charAt(pos) == 'e' || sql.charAt(pos) == 'E')) {
                        pos++;
                        if (pos < length && (sql.charAt(pos) == '+' || sql.charAt(pos) == '-')) {
                            pos++;
                        }
                        while (pos < length && Character.isDigit(sql.charAt(pos))) {
                            pos++;
                        }
                    }
                }
                tokens.add(new Token(TokenType.NUMBER, sql.substring(start, pos), start, pos));
                continue;
            }

            if (isOperatorStart(c)) {
                int start = pos;
                while (pos < length && isOperatorChar(sql.charAt(pos))) {
                    pos++;
                }
                tokens.add(new Token(TokenType.OPERATOR, sql.substring(start, pos), start, pos));
                continue;
            }

            if (isIdentifierStart(c)) {
                int start = pos;
                while (pos < length && isIdentifierPart(sql.charAt(pos))) {
                    pos++;
                }
                String word = sql.substring(start, pos);
                String upperWord = word.toUpperCase();

                if (isFunction(upperWord)) {
                    tokens.add(new Token(TokenType.FUNCTION, word, start, pos));
                } else if (isKeyword(upperWord)) {
                    tokens.add(new Token(TokenType.KEYWORD, word, start, pos));
                } else {
                    tokens.add(new Token(TokenType.IDENTIFIER, word, start, pos));
                }
                continue;
            }

            if (c == '`') {
                int start = pos;
                pos++;
                while (pos < length && sql.charAt(pos) != '`') {
                    pos++;
                }
                if (pos < length) {
                    pos++;
                }
                tokens.add(new Token(TokenType.IDENTIFIER, sql.substring(start, pos), start, pos));
                continue;
            }

            tokens.add(new Token(TokenType.UNKNOWN, String.valueOf(c), pos, pos + 1));
            pos++;
        }

        return tokens;
    }

    private boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private boolean isOperatorStart(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' ||
               c == '=' || c == '<' || c == '>' || c == '!' ||
               c == '&' || c == '|' || c == '^' || c == '~' ||
               c == '(' || c == ')' || c == ',' || c == ';' ||
               c == '.' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    private boolean isOperatorChar(char c) {
        return c == '+' || c == '-' || c == '*' || c == '/' || c == '%' ||
               c == '=' || c == '<' || c == '>' || c == '!' ||
               c == '&' || c == '|' || c == '^' || c == '~' ||
               c == '(' || c == ')' || c == ',' || c == ';' ||
               c == '.' || c == '[' || c == ']' || c == '{' || c == '}';
    }

    private boolean isIdentifierStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private boolean isIdentifierPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.';
    }
}
