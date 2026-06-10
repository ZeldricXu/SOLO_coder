package com.company.dbstudio.sql.service;

import com.company.dbstudio.sql.model.MultiStatementResult;
import com.company.dbstudio.sql.model.StatementAnalysis;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SQL多语句事务处理 - 回归测试")
class MultiStatementTransactionTest {

    private SqlParserService parserService;

    @BeforeEach
    void setup() {
        parserService = SqlParserService.getInstance();
    }

    @Test
    @DisplayName("Bug修复验证: DDL后DML失败应回滚")
    void testMultiStatementRollbackOnFailure() {
        String sql = "DROP TABLE IF EXISTS test_t1; " +
                     "CREATE TABLE test_t1 (id INT PRIMARY KEY, name VARCHAR(100)); " +
                     "INSERT INTO test_t1 VALUES (1, 'test'); " +
                     "INSERT INTO test_t1 VALUES (1, 'duplicate');";

        List<String> statements = parserService.splitStatements(sql);
        assertThat(statements).hasSize(4);

        List<StatementAnalysis> analyses = parserService.analyzeStatements(statements);
        assertThat(analyses).hasSize(4);

        assertThat(analyses.get(0).getStatementType()).isEqualTo("DROP");
        assertThat(analyses.get(0).isDDL()).isTrue();
        assertThat(analyses.get(0).causesImplicitCommit()).isTrue();

        assertThat(analyses.get(1).getStatementType()).isEqualTo("CREATE");
        assertThat(analyses.get(1).isDDL()).isTrue();
        assertThat(analyses.get(1).causesImplicitCommit()).isTrue();

        assertThat(analyses.get(2).getStatementType()).isEqualTo("INSERT");
        assertThat(analyses.get(2).isDDL()).isFalse();
        assertThat(analyses.get(2).causesImplicitCommit()).isFalse();

        assertThat(analyses.get(3).getStatementType()).isEqualTo("INSERT");
        assertThat(analyses.get(3).isDDL()).isFalse();
        assertThat(analyses.get(3).causesImplicitCommit()).isFalse();
    }

    @Test
    @DisplayName("DDL隐式提交检测 - CREATE DATABASE")
    void testDetectImplicitCommitCreateDatabase() {
        String sql = "CREATE DATABASE test_db";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.isDDL()).isTrue();
        assertThat(analysis.causesImplicitCommit()).isTrue();
        assertThat(analysis.getWarnings()).isNotEmpty();
        assertThat(analysis.getWarnings().get(0)).contains("隐式提交");
    }

    @Test
    @DisplayName("DDL隐式提交检测 - ALTER TABLE")
    void testDetectImplicitCommitAlterTable() {
        String sql = "ALTER TABLE users ADD COLUMN email VARCHAR(255)";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.isDDL()).isTrue();
        assertThat(analysis.causesImplicitCommit()).isTrue();
        assertThat(analysis.hasWarnings()).isTrue();
    }

    @Test
    @DisplayName("DDL隐式提交检测 - DROP TABLE")
    void testDetectImplicitCommitDropTable() {
        String sql = "DROP TABLE IF EXISTS old_table";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.getStatementType()).isEqualTo("DROP");
        assertThat(analysis.causesImplicitCommit()).isTrue();
    }

    @Test
    @DisplayName("DML不触发隐式提交")
    void testDmlNoImplicitCommit() {
        String[] dmlStatements = {
            "INSERT INTO users VALUES (1, 'test')",
            "UPDATE users SET name = 'new' WHERE id = 1",
            "DELETE FROM users WHERE id = 1",
            "SELECT * FROM users"
        };

        for (String sql : dmlStatements) {
            StatementAnalysis analysis = parserService.analyzeStatement(sql);
            assertThat(analysis.causesImplicitCommit())
                    .as("语句 '%s' 不应触发隐式提交", sql)
                    .isFalse();
        }
    }

    @Test
    @DisplayName("CREATE TEMPORARY TABLE 不触发隐式提交")
    void testTemporaryTableNoImplicitCommit() {
        String sql = "CREATE TEMPORARY TABLE temp_table (id INT)";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.isDDL()).isTrue();
        assertThat(analysis.causesImplicitCommit())
                .as("CREATE TEMPORARY TABLE 不应触发隐式提交")
                .isFalse();
    }

    @Test
    @DisplayName("SET 语句不触发隐式提交")
    void testSetStatementNoImplicitCommit() {
        String sql = "SET autocommit = 1";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.causesImplicitCommit())
                .as("SET 语句不应触发隐式提交")
                .isFalse();
    }

    @Test
    @DisplayName("多语句检测隐式提交警告")
    void testMultiStatementImplicitCommitWarnings() {
        String sql = "START TRANSACTION; " +
                     "INSERT INTO users VALUES (1, 'test'); " +
                     "CREATE INDEX idx_name ON users(name); " +
                     "INSERT INTO users VALUES (2, 'test2');";

        List<String> statements = parserService.splitStatements(sql);
        List<String> warnings = parserService.getImplicitCommitWarnings(statements);

        assertThat(warnings).isNotEmpty();
        assertThat(warnings).anyMatch(w -> w.contains("CREATE INDEX"));
        assertThat(parserService.hasImplicitCommitStatements(statements)).isTrue();
    }

    @Test
    @DisplayName("纯DML多语句不触发隐式提交")
    void testPureDmlMultiStatementNoImplicitCommit() {
        String sql = "INSERT INTO users VALUES (1, 'a'); " +
                     "INSERT INTO users VALUES (2, 'b'); " +
                     "UPDATE users SET name = 'c' WHERE id = 1;";

        List<String> statements = parserService.splitStatements(sql);
        assertThat(parserService.hasImplicitCommitStatements(statements)).isFalse();
    }

    @Test
    @DisplayName("MultiStatementResult 构建测试")
    void testMultiStatementResultBuilder() {
        List<StatementAnalysis> analyses = parserService.analyzeStatements(
                parserService.splitStatements("SELECT 1; SELECT 2;")
        );

        MultiStatementResult result = MultiStatementResult.builder()
                .success(true)
                .rolledBack(false)
                .statementAnalyses(analyses)
                .executedCount(2)
                .successCount(2)
                .failedIndex(-1)
                .build();

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.isRolledBack()).isFalse();
        assertThat(result.getExecutedCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailedIndex()).isEqualTo(-1);
        assertThat(result.getStatementAnalyses()).hasSize(2);
    }

    @Test
    @DisplayName("MultiStatementResult 失败场景")
    void testMultiStatementResultFailure() {
        List<StatementAnalysis> analyses = parserService.analyzeStatements(
                parserService.splitStatements("INSERT INTO t VALUES(1); INSERT INTO t VALUES(1);")
        );

        MultiStatementResult result = MultiStatementResult.builder()
                .success(false)
                .rolledBack(true)
                .statementAnalyses(analyses)
                .executedCount(2)
                .successCount(1)
                .failedIndex(1)
                .errorMessage("Duplicate entry")
                .rollbackMessage("已回滚事务，所有 1 条成功语句的变更已撤销")
                .build();

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.isRolledBack()).isTrue();
        assertThat(result.getFailedIndex()).isEqualTo(1);
        assertThat(result.getErrorMessage()).isEqualTo("Duplicate entry");
        assertThat(result.getRollbackMessage()).contains("回滚");
        assertThat(result.getRollbackMessage()).contains("1");
    }

    @Test
    @DisplayName("StatementAnalysis DML类型检测")
    void testStatementAnalysisDmlTypes() {
        assertThat(parserService.analyzeStatement("SELECT * FROM t").getStatementType()).isEqualTo("SELECT");
        assertThat(parserService.analyzeStatement("INSERT INTO t VALUES(1)").getStatementType()).isEqualTo("INSERT");
        assertThat(parserService.analyzeStatement("UPDATE t SET c = 1").getStatementType()).isEqualTo("UPDATE");
        assertThat(parserService.analyzeStatement("DELETE FROM t").getStatementType()).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("语句拆分 - 处理注释和引号")
    void testSplitStatementsWithCommentsAndQuotes() {
        String sql = "SELECT 'hello;world' AS msg; -- this is a comment\n" +
                     "SELECT * FROM t WHERE c = 'test;value';";

        List<String> statements = parserService.splitStatements(sql);
        assertThat(statements).hasSize(2);
        assertThat(statements.get(0)).contains("hello;world");
        assertThat(statements.get(1)).contains("test;value");
    }

    @Test
    @DisplayName("GRANT语句触发隐式提交")
    void testGrantCausesImplicitCommit() {
        String sql = "GRANT SELECT ON db.* TO 'user'@'localhost'";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.causesImplicitCommit()).isTrue();
        assertThat(analysis.getWarnings()).anyMatch(w -> w.contains("隐式提交"));
    }

    @Test
    @DisplayName("TRUNCATE TABLE触发隐式提交")
    void testTruncateCausesImplicitCommit() {
        String sql = "TRUNCATE TABLE test_table";
        StatementAnalysis analysis = parserService.analyzeStatement(sql);

        assertThat(analysis.causesImplicitCommit()).isTrue();
        assertThat(analysis.isDDL()).isTrue();
    }
}
