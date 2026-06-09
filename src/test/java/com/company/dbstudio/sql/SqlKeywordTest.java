package com.company.dbstudio.sql;

import com.company.dbstudio.sql.model.SqlKeyword;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SQL关键字测试")
class SqlKeywordTest {

    @Test
    @DisplayName("关键字分类判断 - DML")
    void isDml_ShouldReturnTrueForDmlKeywords() {
        assertThat(SqlKeyword.SELECT.isDml()).isTrue();
        assertThat(SqlKeyword.INSERT.isDml()).isTrue();
        assertThat(SqlKeyword.UPDATE.isDml()).isTrue();
        assertThat(SqlKeyword.DELETE.isDml()).isTrue();
        assertThat(SqlKeyword.CREATE.isDml()).isFalse();
    }

    @Test
    @DisplayName("关键字分类判断 - DDL")
    void isDdl_ShouldReturnTrueForDdlKeywords() {
        assertThat(SqlKeyword.CREATE.isDdl()).isTrue();
        assertThat(SqlKeyword.ALTER.isDdl()).isTrue();
        assertThat(SqlKeyword.DROP.isDdl()).isTrue();
        assertThat(SqlKeyword.TRUNCATE.isDdl()).isTrue();
        assertThat(SqlKeyword.SELECT.isDdl()).isFalse();
    }

    @Test
    @DisplayName("关键字分类判断 - DCL")
    void isDcl_ShouldReturnTrueForDclKeywords() {
        assertThat(SqlKeyword.GRANT.isDcl()).isTrue();
        assertThat(SqlKeyword.REVOKE.isDcl()).isTrue();
        assertThat(SqlKeyword.SELECT.isDcl()).isFalse();
    }

    @Test
    @DisplayName("关键字分类判断 - TCL")
    void isTcl_ShouldReturnTrueForTclKeywords() {
        assertThat(SqlKeyword.COMMIT.isTcl()).isTrue();
        assertThat(SqlKeyword.ROLLBACK.isTcl()).isTrue();
        assertThat(SqlKeyword.BEGIN.isTcl()).isTrue();
        assertThat(SqlKeyword.SELECT.isTcl()).isFalse();
    }

    @Test
    @DisplayName("关键字分类判断 - 函数")
    void isFunction_ShouldReturnTrueForFunctionKeywords() {
        assertThat(SqlKeyword.COUNT.isFunction()).isTrue();
        assertThat(SqlKeyword.SUM.isFunction()).isTrue();
        assertThat(SqlKeyword.AVG.isFunction()).isTrue();
        assertThat(SqlKeyword.MAX.isFunction()).isTrue();
        assertThat(SqlKeyword.MIN.isFunction()).isTrue();
        assertThat(SqlKeyword.SELECT.isFunction()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT", "FROM", "WHERE", "JOIN", "ORDER BY", "GROUP BY"})
    @DisplayName("关键字名称匹配")
    void getKeyword_ShouldMatchKeywordName(String keyword) {
        SqlKeyword result = SqlKeyword.fromString(keyword);
        assertThat(result).isNotNull();
        assertThat(result.getKeyword()).isEqualTo(keyword);
    }

    @Test
    @DisplayName("关键字不区分大小写")
    void fromString_ShouldBeCaseInsensitive() {
        assertThat(SqlKeyword.fromString("select")).isEqualTo(SqlKeyword.SELECT);
        assertThat(SqlKeyword.fromString("Select")).isEqualTo(SqlKeyword.SELECT);
        assertThat(SqlKeyword.fromString("SELECT")).isEqualTo(SqlKeyword.SELECT);
    }

    @Test
    @DisplayName("获取所有DML关键字")
    void getAllDmlKeywords_ShouldReturnAllDml() {
        List<SqlKeyword> dmlKeywords = SqlKeyword.getAllByCategory("DML");
        assertThat(dmlKeywords).isNotEmpty();
        assertThat(dmlKeywords).allMatch(SqlKeyword::isDml);
    }

    @Test
    @DisplayName("获取所有函数关键字")
    void getAllFunctionKeywords_ShouldReturnAllFunctions() {
        List<SqlKeyword> functionKeywords = SqlKeyword.getAllByCategory("FUNCTION");
        assertThat(functionKeywords).isNotEmpty();
        assertThat(functionKeywords).allMatch(SqlKeyword::isFunction);
    }

    @Test
    @DisplayName("关键字描述")
    void getDescription_ShouldReturnMeaningfulDescription() {
        assertThat(SqlKeyword.SELECT.getDescription()).isNotEmpty();
        assertThat(SqlKeyword.CREATE.getDescription()).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "SELECT, DML",
            "CREATE, DDL",
            "GRANT,  DCL",
            "COMMIT, TCL",
            "COUNT,  FUNCTION"
    })
    @DisplayName("关键字分类验证")
    void getCategory_ShouldReturnCorrectCategory(String keyword, String expectedCategory) {
        SqlKeyword k = SqlKeyword.fromString(keyword);
        assertThat(k.getCategory()).isEqualTo(expectedCategory);
    }

    @Test
    @DisplayName("无效关键字返回null")
    void fromString_InvalidKeyword_ShouldReturnNull() {
        assertThat(SqlKeyword.fromString("INVALID_KEYWORD")).isNull();
        assertThat(SqlKeyword.fromString("")).isNull();
        assertThat(SqlKeyword.fromString(null)).isNull();
    }

    @Test
    @DisplayName("关键字总数验证")
    void values_ShouldContainAllKeywords() {
        SqlKeyword[] keywords = SqlKeyword.values();
        assertThat(keywords.length).isGreaterThanOrEqualTo(100);
    }

    @Test
    @DisplayName("自动补全候选列表 - 前缀匹配")
    void getSuggestions_ShouldReturnMatchingKeywords() {
        List<String> suggestions = SqlKeyword.getSuggestions("SEL");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions).contains("SELECT");
    }

    @Test
    @DisplayName("自动补全候选列表 - 前缀匹配不区分大小写")
    void getSuggestions_ShouldBeCaseInsensitive() {
        List<String> suggestions1 = SqlKeyword.getSuggestions("sel");
        List<String> suggestions2 = SqlKeyword.getSuggestions("SEL");
        assertThat(suggestions1).isEqualTo(suggestions2);
    }

    @Test
    @DisplayName("自动补全候选列表 - 按相关性排序")
    void getSuggestions_ShouldSortByRelevance() {
        List<String> suggestions = SqlKeyword.getSuggestions("OR");
        assertThat(suggestions).isNotEmpty();
        assertThat(suggestions.get(0)).isEqualTo("OR");
    }

    @Test
    @DisplayName("自动补全候选列表 - 限制数量")
    void getSuggestions_ShouldLimitResults() {
        List<String> suggestions = SqlKeyword.getSuggestions("C");
        assertThat(suggestions.size()).isLessThanOrEqualTo(50);
    }

    @Test
    @DisplayName("toString方法返回关键字")
    void toString_ShouldReturnKeyword() {
        assertThat(SqlKeyword.SELECT.toString()).isEqualTo("SELECT");
    }
}
