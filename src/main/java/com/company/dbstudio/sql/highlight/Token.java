package com.company.dbstudio.sql.highlight;

public class Token {
    private final TokenType type;
    private final String text;
    private final int start;
    private final int end;

    public Token(TokenType type, String text, int start, int end) {
        this.type = type;
        this.text = text;
        this.start = start;
        this.end = end;
    }

    public TokenType getType() {
        return type;
    }

    public String getText() {
        return text;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public int getLength() {
        return end - start;
    }

    @Override
    public String toString() {
        return "Token{" +
                "type=" + type +
                ", text='" + text + '\'' +
                ", start=" + start +
                ", end=" + end +
                '}';
    }
}
