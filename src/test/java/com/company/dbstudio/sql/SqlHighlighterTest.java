package com.company.dbstudio.sql;

import com.company.dbstudio.sql.highlight.*;
import org.fxmisc.richtext.model.StyleSpans;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SqlHighlighterTest {

    @Test
    void testHighlight() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT id, name FROM users WHERE id = 1";
        List<Token> tokens = lexer.tokenize(sql);

        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
        assertTrue(spans.getSpanCount() > 0);
    }

    @Test
    void testStyleClassMapping() {
        Highlighter highlighter = new DefaultHighlighter();

        assertEquals("sql-keyword", highlighter.getStyleClass(TokenType.KEYWORD));
        assertEquals("sql-function", highlighter.getStyleClass(TokenType.FUNCTION));
        assertEquals("sql-identifier", highlighter.getStyleClass(TokenType.IDENTIFIER));
        assertEquals("sql-string", highlighter.getStyleClass(TokenType.STRING));
        assertEquals("sql-number", highlighter.getStyleClass(TokenType.NUMBER));
        assertEquals("sql-comment", highlighter.getStyleClass(TokenType.COMMENT));
        assertEquals("sql-operator", highlighter.getStyleClass(TokenType.OPERATOR));
    }

    @Test
    void testTableNameHighlighting() {
        Lexer lexer = new MySqlLexer();
        DefaultHighlighter highlighter = new DefaultHighlighter();

        highlighter.setTableNames(List.of("users", "orders", "products"));

        String sql = "SELECT * FROM users, orders";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testColumnNameHighlighting() {
        Lexer lexer = new MySqlLexer();
        DefaultHighlighter highlighter = new DefaultHighlighter();

        highlighter.setColumnNames(List.of("id", "name", "email", "created_at"));

        String sql = "SELECT id, name, email FROM users";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testHighlightWithEmptyTokens() {
        Highlighter highlighter = new DefaultHighlighter();
        StyleSpans<Collection<String>> spans = highlighter.highlight(List.of());

        assertNotNull(spans);
        assertEquals(1, spans.getSpanCount());
    }

    @Test
    void testHighlightWithNullTokens() {
        Highlighter highlighter = new DefaultHighlighter();
        StyleSpans<Collection<String>> spans = highlighter.highlight(null);

        assertNotNull(spans);
        assertEquals(1, spans.getSpanCount());
    }

    @Test
    void testTableAndColumnNames() {
        DefaultHighlighter highlighter = new DefaultHighlighter();

        highlighter.setTableNames(List.of("users", "orders"));
        highlighter.setColumnNames(List.of("id", "name"));

        assertTrue(highlighter.isTableName("users"));
        assertFalse(highlighter.isTableName("unknown"));
        assertTrue(highlighter.isColumnName("id"));
        assertFalse(highlighter.isColumnName("unknown"));
    }

    @Test
    void testWhitespaceHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT   *   FROM   users";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
        assertTrue(spans.getSpanCount() > 1);
    }

    @Test
    void testStringHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT * FROM users WHERE name = 'John'";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testCommentHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT * FROM users -- this is a comment";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testFunctionHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT COUNT(*), MAX(price) FROM products";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testNumberHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT * FROM orders WHERE total > 100.50";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testOperatorHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = "SELECT * FROM users WHERE age >= 18 AND status = 'active'";
        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
    }

    @Test
    void testComplexSqlHighlighting() {
        Lexer lexer = new MySqlLexer();
        Highlighter highlighter = new DefaultHighlighter();

        String sql = """
            SELECT u.id, u.name, COUNT(o.id) as order_count
            FROM users u
            LEFT JOIN orders o ON u.id = o.user_id
            WHERE u.created_at >= '2024-01-01'
            GROUP BY u.id, u.name
            HAVING COUNT(o.id) > 5
            ORDER BY order_count DESC
            LIMIT 10
            """;

        List<Token> tokens = lexer.tokenize(sql);
        StyleSpans<Collection<String>> spans = highlighter.highlight(tokens);

        assertNotNull(spans);
        assertTrue(spans.getSpanCount() > 10);
    }

    @Test
    void testUnknownTokenType() {
        Highlighter highlighter = new DefaultHighlighter();
        String style = highlighter.getStyleClass(TokenType.UNKNOWN);
        assertEquals("sql-unknown", style);
    }
}
