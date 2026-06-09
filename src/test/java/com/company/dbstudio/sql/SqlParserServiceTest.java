package com.company.dbstudio.sql;

import com.company.dbstudio.core.model.Result;
import com.company.dbstudio.sql.service.SqlParserService;
import com.company.dbstudio.test.TestConstants;
import net.sf.jsqlparser.JSQLParserException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("SQL解析服务测试")
class SqlParserServiceTest {

    private SqlParserService parserService;

    @BeforeEach
    void setUp() {
        parserService = new SqlParserService();
    }

    @Test
    @DisplayName("提取表名 - 简单SELECT")
    void extractTables_SimpleSelect_ShouldReturnCorrectTables() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_SIMPLE_SELECT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactly("users");
    }

    @Test
    @DisplayName("提取表名 - 带WHERE条件")
    void extractTables_SelectWithWhere_ShouldReturnCorrectTables() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_SELECT_WHERE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactly("users");
    }

    @Test
    @DisplayName("提取表名 - JOIN查询")
    void extractTables_SelectWithJoin_ShouldReturnAllTables() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_SELECT_JOIN);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactlyInAnyOrder("users", "orders");
    }

    @Test
    @DisplayName("提取表名 - INSERT语句")
    void extractTables_InsertStatement_ShouldReturnCorrectTable() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_INSERT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactly("users");
    }

    @Test
    @DisplayName("提取表名 - UPDATE语句")
    void extractTables_UpdateStatement_ShouldReturnCorrectTable() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_UPDATE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactly("users");
    }

    @Test
    @DisplayName("提取表名 - DELETE语句")
    void extractTables_DeleteStatement_ShouldReturnCorrectTable() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_DELETE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactly("users");
    }

    @Test
    @DisplayName("提取表名 - 复杂查询")
    void extractTables_ComplexQuery_ShouldReturnAllTables() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_COMPLEX);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).containsExactlyInAnyOrder("users", "orders");
    }

    @Test
    @DisplayName("提取列名 - 简单SELECT")
    void extractColumns_SimpleSelect_ShouldReturnCorrectColumns() {
        Result<Set<String>> result = parserService.extractColumns(TestConstants.SQL_SIMPLE_SELECT);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("提取列名 - 带WHERE条件")
    void extractColumns_SelectWithWhere_ShouldReturnAllColumns() {
        Result<Set<String>> result = parserService.extractColumns(TestConstants.SQL_SELECT_WHERE);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).contains("id", "name", "age");
    }

    @Test
    @DisplayName("SQL语法错误 - 应返回失败结果")
    void parse_SyntaxError_ShouldReturnFailure() {
        Result<Set<String>> result = parserService.extractTables(TestConstants.SQL_SYNTAX_ERROR);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotEmpty();
    }

    @Test
    @DisplayName("SQL语法错误 - 标记错误位置")
    void getErrorPosition_SyntaxError_ShouldReturnErrorLocation() {
        int[] position = parserService.getErrorPosition(TestConstants.SQL_SYNTAX_ERROR);

        assertThat(position).isNotNull();
        assertThat(position[0]).isGreaterThanOrEqualTo(0);
        assertThat(position[1]).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("正确SQL - 无错误位置")
    void getErrorPosition_ValidSql_ShouldReturnMinusOne() {
        int[] position = parserService.getErrorPosition(TestConstants.SQL_SIMPLE_SELECT);

        assertThat(position).isEqualTo(new int[]{-1, -1});
    }

    @Test
    @DisplayName("SQL格式化")
    void formatSql_ShouldFormatSql() {
        String sql = "SELECT * FROM users WHERE age > 18 ORDER BY name";
        Result<String> result = parserService.formatSql(sql);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).isNotEmpty();
        assertThat(result.getData()).containsIgnoringCase("SELECT");
        assertThat(result.getData()).containsIgnoringCase("FROM");
    }

    @Test
    @DisplayName("SQL格式化 - 复杂查询")
    void formatSql_ComplexQuery_ShouldFormatNicely() {
        Result<String> result = parserService.formatSql(TestConstants.SQL_COMPLEX);

        assertThat(result.isSuccess()).isTrue();
        String formatted = result.getData();
        assertThat(formatted).contains("\n");
    }

    @Test
    @DisplayName("SQL格式化 - 语法错误")
    void formatSql_SyntaxError_ShouldReturnOriginal() {
        Result<String> result = parserService.formatSql(TestConstants.SQL_SYNTAX_ERROR);

        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("验证SQL语法 - 正确SQL")
    void validateSql_ValidSql_ShouldReturnSuccess() {
        Result<Void> result = parserService.validateSql(TestConstants.SQL_SIMPLE_SELECT);

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("验证SQL语法 - 错误SQL")
    void validateSql_InvalidSql_ShouldReturnFailure() {
        Result<Void> result = parserService.validateSql(TestConstants.SQL_SYNTAX_ERROR);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).isNotEmpty();
    }

    @Test
    @DisplayName("获取自动补全建议 - 关键字")
    void getAutoCompleteSuggestions_KeywordPrefix_ShouldIncludeKeywords() {
        String sql = "SEL";
        int cursorPos = 3;

        Result<List<String>> result = parserService.getAutoCompleteSuggestions(sql, cursorPos, "testdb");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).contains("SELECT");
    }

    @Test
    @DisplayName("获取自动补全建议 - 表名上下文")
    void getAutoCompleteSuggestions_TableContext_ShouldIncludeTables() {
        String sql = "SELECT * FROM ";
        int cursorPos = sql.length();

        Result<List<String>> result = parserService.getAutoCompleteSuggestions(sql, cursorPos, "testdb");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("获取自动补全建议 - 列名上下文")
    void getAutoCompleteSuggestions_ColumnContext_ShouldIncludeColumns() {
        String sql = "SELECT  FROM users";
        int cursorPos = 7;

        Result<List<String>> result = parserService.getAutoCompleteSuggestions(sql, cursorPos, "testdb");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("分割多语句SQL")
    void splitStatements_MultipleStatements_ShouldSplitCorrectly() {
        String sql = "SELECT * FROM users; SELECT * FROM orders;";

        Result<List<String>> result = parserService.splitStatements(sql);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).hasSize(2);
    }

    @Test
    @DisplayName("分割单语句SQL")
    void splitStatements_SingleStatement_ShouldReturnSingle() {
        Result<List<String>> result = parserService.splitStatements(TestConstants.SQL_SIMPLE_SELECT);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getData()).hasSize(1);
    }

    @Test
    @DisplayName("获取SQL类型")
    void getStatementType_ShouldReturnCorrectType() {
        assertThat(parserService.getStatementType(TestConstants.SQL_SIMPLE_SELECT)).isEqualTo("SELECT");
        assertThat(parserService.getStatementType(TestConstants.SQL_INSERT)).isEqualTo("INSERT");
        assertThat(parserService.getStatementType(TestConstants.SQL_UPDATE)).isEqualTo("UPDATE");
        assertThat(parserService.getStatementType(TestConstants.SQL_DELETE)).isEqualTo("DELETE");
    }

    @Test
    @DisplayName("检测只读查询")
    void isReadOnlyQuery_Select_ShouldReturnTrue() {
        assertThat(parserService.isReadOnlyQuery(TestConstants.SQL_SIMPLE_SELECT)).isTrue();
        assertThat(parserService.isReadOnlyQuery(TestConstants.SQL_SELECT_WHERE)).isTrue();
        assertThat(parserService.isReadOnlyQuery(TestConstants.SQL_INSERT)).isFalse();
        assertThat(parserService.isReadOnlyQuery(TestConstants.SQL_UPDATE)).isFalse();
        assertThat(parserService.isReadOnlyQuery(TestConstants.SQL_DELETE)).isFalse();
    }

    @Test
    @DisplayName("检测SELECT * 查询")
    void hasSelectAll_ShouldReturnTrueForStar() {
        assertThat(parserService.hasSelectAll(TestConstants.SQL_SIMPLE_SELECT)).isTrue();
        assertThat(parserService.hasSelectAll(TestConstants.SQL_SELECT_WHERE)).isFalse();
    }

    @Test
    @DisplayName("检测前导通配符LIKE")
    void hasLeadingWildcardLike_ShouldReturnTrue() {
        String sql = "SELECT * FROM users WHERE name LIKE '%test%'";
        assertThat(parserService.hasLeadingWildcardLike(sql)).isTrue();

        sql = "SELECT * FROM users WHERE name LIKE 'test%'";
        assertThat(parserService.hasLeadingWildcardLike(sql)).isFalse();
    }

    @Test
    @DisplayName("检测OR条件")
    void hasOrCondition_ShouldReturnTrue() {
        String sql = "SELECT * FROM users WHERE age > 18 OR name = 'test'";
        assertThat(parserService.hasOrCondition(sql)).isTrue();

        sql = "SELECT * FROM users WHERE age > 18 AND name = 'test'";
        assertThat(parserService.hasOrCondition(sql)).isFalse();
    }

    @Test
    @DisplayName("空SQL处理")
    void parse_EmptySql_ShouldReturnFailure() {
        Result<Set<String>> result = parserService.extractTables("");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("empty");
    }

    @Test
    @DisplayName("NULL SQL处理")
    void parse_NullSql_ShouldReturnFailure() {
        Result<Set<String>> result = parserService.extractTables(null);

        assertThat(result.isSuccess()).isFalse();
    }
}
