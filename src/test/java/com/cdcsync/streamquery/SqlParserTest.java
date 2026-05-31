package com.cdcsync.streamquery;

import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.streamquery.plan.LogicalPlan;
import com.cdcsync.streamquery.plan.SqlParser;
import com.cdcsync.test.builder.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.*;

@DisplayName("SqlParser 单元测试")
class SqlParserTest {

    @Nested
    @DisplayName("SQL解析功能测试")
    class ParseTests {

        @Test
        @DisplayName("解析有效SELECT语句 - 应成功返回逻辑计划")
        void parseValidSelect_ShouldReturnLogicalPlan() {
            String sql = TestDataFactory.createValidSelectSql();

            LogicalPlan plan = SqlParser.parse(sql);

            assertThat(plan).isNotNull();
            assertThat(plan.getPlanType()).isEqualTo("SORT");
            assertThat(plan.getChildren()).hasSize(1);

            LogicalPlan projectPlan = plan.getChildren().get(0);
            assertThat(projectPlan.getPlanType()).isEqualTo("PROJECT");
        }

        @Test
        @DisplayName("解析带WHERE条件的SELECT - 应生成FILTER节点")
        void parseSelectWithWhere_ShouldCreateFilterNode() {
            String sql = TestDataFactory.createSelectSqlWithFilter();

            LogicalPlan plan = SqlParser.parse(sql);

            assertThat(plan.getPlanType()).isEqualTo("PROJECT");
            assertThat(plan.getChildren().get(0).getPlanType()).isEqualTo("FILTER");
            assertThat(plan.getChildren().get(0).getProperty("condition")).isNotNull();
        }

        @Test
        @DisplayName("解析无效SQL - 应抛出BusinessException")
        void parseInvalidSql_ShouldThrowException() {
            String invalidSql = TestDataFactory.createInvalidSql();

            assertThatThrownBy(() -> SqlParser.parse(invalidSql))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Failed to parse SQL");
        }

        @Test
        @DisplayName("解析非SELECT语句 - 应抛出BusinessException")
        void parseNonSelectSql_ShouldThrowException() {
            String insertSql = TestDataFactory.createNonSelectSql();

            assertThatThrownBy(() -> SqlParser.parse(insertSql))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("Only SELECT statements are supported");
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "SELECT * FROM users",
                "SELECT id, name FROM users WHERE id = 1",
                "SELECT a, b FROM t ORDER BY a"
        })
        @DisplayName("参数化测试 - 多种SELECT语句应成功解析")
        void parseVariousSelectStatements(String sql) {
            assertThatCode(() -> SqlParser.parse(sql)).doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("JSON序列化/反序列化测试")
    class JsonSerializationTests {

        @Test
        @DisplayName("计划JSON序列化后反序列化 - 数据应保持一致")
        void toJsonAndFromJson_ShouldPreserveData() {
            String sql = TestDataFactory.createValidSelectSql();
            LogicalPlan original = SqlParser.parse(sql);

            String json = SqlParser.toJson(original);
            LogicalPlan deserialized = SqlParser.fromJson(json);

            assertThat(deserialized).isNotNull();
            assertThat(deserialized.getPlanType()).isEqualTo(original.getPlanType());
            assertThat(deserialized.getChildren()).hasSize(original.getChildren().size());
        }

        @Test
        @DisplayName("序列化空计划 - 应生成有效JSON")
        void toJson_WithSimplePlan_ShouldProduceValidJson() {
            LogicalPlan plan = new LogicalPlan("TEST") {};
            plan.setProperty("key", "value");

            String json = SqlParser.toJson(plan);

            assertThat(json).isNotNull().isNotEmpty();
            assertThat(json).contains("TEST").contains("key").contains("value");
        }
    }

    @Nested
    @DisplayName("执行计划解释测试")
    class ExplainTests {

        @Test
        @DisplayName("解释执行计划 - 应返回完整的计划结构")
        void explainPlan_ShouldReturnPlanStructure() {
            String sql = TestDataFactory.createSelectSqlWithFilter();
            LogicalPlan plan = SqlParser.parse(sql);

            var explanation = SqlParser.explain(plan);

            assertThat(explanation).isNotNull();
            assertThat(explanation).containsKey("planType");
            assertThat(explanation).containsKey("properties");
            assertThat(explanation).containsKey("children");
        }
    }
}
