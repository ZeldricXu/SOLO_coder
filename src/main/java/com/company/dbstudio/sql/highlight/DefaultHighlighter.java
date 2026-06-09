package com.company.dbstudio.sql.highlight;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.*;

public class DefaultHighlighter implements Highlighter {

    private static final Map<TokenType, String> STYLE_MAP = new EnumMap<>(TokenType.class);

    static {
        STYLE_MAP.put(TokenType.KEYWORD, "sql-keyword");
        STYLE_MAP.put(TokenType.FUNCTION, "sql-function");
        STYLE_MAP.put(TokenType.IDENTIFIER, "sql-identifier");
        STYLE_MAP.put(TokenType.TABLE_NAME, "sql-table-name");
        STYLE_MAP.put(TokenType.COLUMN_NAME, "sql-column-name");
        STYLE_MAP.put(TokenType.STRING, "sql-string");
        STYLE_MAP.put(TokenType.NUMBER, "sql-number");
        STYLE_MAP.put(TokenType.COMMENT, "sql-comment");
        STYLE_MAP.put(TokenType.OPERATOR, "sql-operator");
        STYLE_MAP.put(TokenType.WHITESPACE, "sql-whitespace");
        STYLE_MAP.put(TokenType.UNKNOWN, "sql-unknown");
    }

    private Set<String> tableNames = new HashSet<>();
    private Set<String> columnNames = new HashSet<>();

    @Override
    public StyleSpans<Collection<String>> highlight(List<Token> tokens) {
        StyleSpansBuilder<Collection<String>> spansBuilder = new StyleSpansBuilder<>();

        if (tokens == null || tokens.isEmpty()) {
            spansBuilder.add(Collections.emptyList(), 0);
            return spansBuilder.create();
        }

        int lastEnd = 0;

        for (Token token : tokens) {
            if (token.getStart() > lastEnd) {
                spansBuilder.add(Collections.emptyList(), token.getStart() - lastEnd);
            }

            Collection<String> styleClasses = getStyleClassesForToken(token);
            spansBuilder.add(styleClasses, token.getEnd() - token.getStart());

            lastEnd = token.getEnd();
        }

        return spansBuilder.create();
    }

    private Collection<String> getStyleClassesForToken(Token token) {
        List<String> styles = new ArrayList<>();

        TokenType type = token.getType();
        String text = token.getText();

        if (type == TokenType.IDENTIFIER) {
            String lowerText = text.toLowerCase();
            if (tableNames.contains(lowerText)) {
                styles.add(STYLE_MAP.get(TokenType.TABLE_NAME));
            } else if (columnNames.contains(lowerText)) {
                styles.add(STYLE_MAP.get(TokenType.COLUMN_NAME));
            } else {
                styles.add(STYLE_MAP.getOrDefault(type, STYLE_MAP.get(TokenType.UNKNOWN)));
            }
        } else {
            styles.add(STYLE_MAP.getOrDefault(type, STYLE_MAP.get(TokenType.UNKNOWN)));
        }

        return styles;
    }

    @Override
    public String getStyleClass(TokenType type) {
        return STYLE_MAP.getOrDefault(type, STYLE_MAP.get(TokenType.UNKNOWN));
    }

    @Override
    public void setTableNames(List<String> tableNames) {
        this.tableNames = new HashSet<>();
        if (tableNames != null) {
            for (String name : tableNames) {
                this.tableNames.add(name.toLowerCase());
            }
        }
    }

    @Override
    public void setColumnNames(List<String> columnNames) {
        this.columnNames = new HashSet<>();
        if (columnNames != null) {
            for (String name : columnNames) {
                this.columnNames.add(name.toLowerCase());
            }
        }
    }

    public boolean isTableName(String name) {
        return name != null && tableNames.contains(name.toLowerCase());
    }

    public boolean isColumnName(String name) {
        return name != null && columnNames.contains(name.toLowerCase());
    }

    public Set<String> getTableNames() {
        return Collections.unmodifiableSet(tableNames);
    }

    public Set<String> getColumnNames() {
        return Collections.unmodifiableSet(columnNames);
    }
}
