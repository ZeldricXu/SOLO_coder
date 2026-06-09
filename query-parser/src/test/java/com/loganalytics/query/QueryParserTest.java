package com.loganalytics.query;

import com.loganalytics.query.ast.ASTNode;
import com.loganalytics.query.ast.SqlTranslator;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class QueryParserTest {

    @Test
    void testParseSimpleFieldFilter() {
        String query = "service:payment AND level:ERROR";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getAst());
        assertEquals("payment", result.getFieldValue("service"));
        assertEquals("ERROR", result.getFieldValue("level"));
    }

    @Test
    void testParseWithTimeRange() {
        String query = "service:payment AND level:ERROR SINCE 30m AGO";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getStartTime());
        assertNull(result.getEndTime());

        Instant thirtyMinutesAgo = Instant.now().minusSeconds(1800);
        assertTrue(result.getStartTime().isAfter(thirtyMinutesAgo.minusSeconds(60)));
        assertTrue(result.getStartTime().isBefore(thirtyMinutesAgo.plusSeconds(60)));
    }

    @Test
    void testParseWithQuotedString() {
        String query = "pattern:\"connection refused\" AND service:gateway";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertEquals("connection refused", result.getFieldValue("pattern"));
        assertEquals("gateway", result.getFieldValue("service"));
    }

    @Test
    void testParseWithOrOperator() {
        String query = "(service:payment OR service:order) AND level:ERROR";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getAst());
    }

    @Test
    void testParseWithNumericComparison() {
        String query = "service:payment AND duration > 500";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getAst());
    }

    @Test
    void testParseWithBetween() {
        String query = "BETWEEN 2024-01-15T00:00:00Z AND 2024-01-16T00:00:00Z AND service:payment";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getStartTime());
        assertNotNull(result.getEndTime());
    }

    @Test
    void testParseWithNaturalTime() {
        String query = "SINCE yesterday AND level:ERROR";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getStartTime());
    }

    @Test
    void testSqlTranslation() {
        String query = "service:payment AND level:ERROR AND pattern:\"connection refused\" SINCE 1h AGO";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());

        ASTNode ast = result.getAst();
        String sql = SqlTranslator.translate(ast);

        assertNotNull(sql);
        assertFalse(sql.isEmpty());
        assertTrue(sql.contains("SELECT"));
        assertTrue(sql.contains("WHERE"));
        assertTrue(sql.contains("service"));
        assertTrue(sql.contains("level"));
    }

    @Test
    void testParseInvalidQuery() {
        assertThrows(Exception.class, () -> {
            QueryParser.parse("service:payment AND :invalid:");
        });
    }

    @Test
    void testParseComplexQuery() {
        String query = "service:payment AND (level:ERROR OR level:WARN) AND NOT pattern:healthcheck SINCE 2h AGO";
        QueryParser.ParseResult result = QueryParser.parse(query);

        assertNotNull(result);
        assertTrue(result.isValid());
        assertNotNull(result.getAst());
        assertNotNull(result.getSql());
        assertNotNull(result.getStartTime());
    }
}
