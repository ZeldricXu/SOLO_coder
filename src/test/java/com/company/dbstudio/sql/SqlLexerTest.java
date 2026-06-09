package com.company.dbstudio.sql;

import com.company.dbstudio.sql.highlight.*;
import com.company.dbstudio.connection.model.ConnectionType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class SqlLexerTest {

    @Test
    void testTokenizeKeywords() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users WHERE id = 1";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .filter(t -> t.getType() == TokenType.KEYWORD)
                .anyMatch(t -> "SELECT".equalsIgnoreCase(t.getText())));
        assertTrue(tokens.stream()
                .filter(t -> t.getType() == TokenType.KEYWORD)
                .anyMatch(t -> "FROM".equalsIgnoreCase(t.getText())));
        assertTrue(tokens.stream()
                .filter(t -> t.getType() == TokenType.KEYWORD)
                .anyMatch(t -> "WHERE".equalsIgnoreCase(t.getText())));
    }

    @Test
    void testTokenizeStringLiteral() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users WHERE name = 'John Doe'";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.STRING && "'John Doe'".equals(t.getText())));
    }

    @Test
    void testTokenizeNumber() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users WHERE id = 42 AND price = 99.99";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.NUMBER && "42".equals(t.getText())));
        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.NUMBER && "99.99".equals(t.getText())));
    }

    @Test
    void testTokenizeSingleLineComment() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users -- This is a comment\nWHERE id = 1";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.COMMENT && t.getText().startsWith("--")));
    }

    @Test
    void testTokenizeMultiLineComment() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users /* This is a\nmulti-line comment */ WHERE id = 1";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.COMMENT && t.getText().startsWith("/*")));
    }

    @Test
    void testTokenizeOperator() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users WHERE id >= 100 AND status != 'inactive'";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.OPERATOR && ">=".equals(t.getText())));
        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.OPERATOR && "!=".equals(t.getText())));
    }

    @Test
    void testTokenizeIdentifier() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT id, name, email FROM users";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.IDENTIFIER && "users".equals(t.getText())));
    }

    @Test
    void testTokenizeWhitespace() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT   *\tFROM\nusers";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.WHITESPACE));
    }

    @Test
    void testTokenizeFunction() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT COUNT(*), UPPER(name) FROM users";
        List<Token> tokens = lexer.tokenize(sql);

        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.FUNCTION && "COUNT".equalsIgnoreCase(t.getText())));
        assertTrue(tokens.stream()
                .anyMatch(t -> t.getType() == TokenType.FUNCTION && "UPPER".equalsIgnoreCase(t.getText())));
    }

    @Test
    void testMySqlKeywords() {
        Lexer lexer = new MySqlLexer();
        Set<String> keywords = lexer.getKeywords();
        assertTrue(keywords.contains("TINYTEXT"));
        assertTrue(keywords.contains("MEDIUMTEXT"));
        assertTrue(keywords.contains("LONGTEXT"));
        assertTrue(keywords.contains("TINYBLOB"));
    }

    @Test
    void testPostgreSqlKeywords() {
        Lexer lexer = new PostgreSqlLexer();
        Set<String> keywords = lexer.getKeywords();
        assertTrue(keywords.contains("BYTEA"));
        assertTrue(keywords.contains("JSONB"));
        assertTrue(keywords.contains("TSQUERY"));
    }

    @Test
    void testOracleKeywords() {
        Lexer lexer = new OracleLexer();
        Set<String> keywords = lexer.getKeywords();
        assertTrue(keywords.contains("VARCHAR2"));
        assertTrue(keywords.contains("NVARCHAR2"));
        assertTrue(keywords.contains("BFILE"));
        assertTrue(keywords.contains("ROWID"));
    }

    @Test
    void testSqlServerKeywords() {
        Lexer lexer = new SqlServerLexer();
        Set<String> keywords = lexer.getKeywords();
        assertTrue(keywords.contains("DATETIME2"));
        assertTrue(keywords.contains("DATETIMEOFFSET"));
        assertTrue(keywords.contains("HIERARCHYID"));
    }

    @Test
    void testLexerFactory() {
        assertTrue(LexerFactory.getLexer(ConnectionType.MYSQL) instanceof MySqlLexer);
        assertTrue(LexerFactory.getLexer(ConnectionType.POSTGRESQL) instanceof PostgreSqlLexer);
        assertTrue(LexerFactory.getLexer(ConnectionType.ORACLE) instanceof OracleLexer);
        assertTrue(LexerFactory.getLexer(ConnectionType.SQL_SERVER) instanceof SqlServerLexer);
    }

    @Test
    void testLexerFactoryCaching() {
        Lexer lexer1 = LexerFactory.getLexer(ConnectionType.MYSQL);
        Lexer lexer2 = LexerFactory.getLexer(ConnectionType.MYSQL);
        assertSame(lexer1, lexer2, "Should return cached instance");
    }

    @Test
    void testTokenPositions() {
        Lexer lexer = new MySqlLexer();
        String sql = "SELECT * FROM users";
        List<Token> tokens = lexer.tokenize(sql);

        Token selectToken = tokens.stream()
                .filter(t -> "SELECT".equalsIgnoreCase(t.getText()))
                .findFirst()
                .orElseThrow();

        assertEquals(0, selectToken.getStart());
        assertEquals(6, selectToken.getEnd());
    }

    @Test
    void testEmptySql() {
        Lexer lexer = new MySqlLexer();
        List<Token> tokens = lexer.tokenize("");
        assertTrue(tokens.isEmpty());
    }

    @Test
    void testNullSql() {
        Lexer lexer = new MySqlLexer();
        List<Token> tokens = lexer.tokenize(null);
        assertTrue(tokens.isEmpty());
    }
}
