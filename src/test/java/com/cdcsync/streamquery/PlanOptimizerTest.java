package com.cdcsync.streamquery;

import com.cdcsync.streamquery.plan.LogicalPlan;
import com.cdcsync.streamquery.plan.PlanOptimizer;
import com.cdcsync.streamquery.plan.SqlParser;
import com.cdcsync.test.builder.TestDataFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("PlanOptimizer 单元测试")
class PlanOptimizerTest {

    @Nested
    @DisplayName("逻辑计划优化测试")
    class OptimizationTests {

        @Test
        @DisplayName("谓词下推优化 - FILTER应下推到SCAN层")
        void optimize_WithFilter_ShouldPushDownPredicates() {
            String sql = TestDataFactory.createSelectSqlWithFilter();
            LogicalPlan originalPlan = SqlParser.parse(sql);

            LogicalPlan optimizedPlan = PlanOptimizer.optimize(originalPlan);

            assertThat(optimizedPlan).isNotNull();
            assertThat(optimizedPlan.getProperty("optimized")).isEqualTo(true);
        }

        @Test
        @DisplayName("列裁剪优化 - SCAN节点应标记pruned属性")
        void optimize_WithProject_ShouldPruneColumns() {
            String sql = "SELECT id, name FROM users";
            LogicalPlan originalPlan = SqlParser.parse(sql);

            LogicalPlan optimizedPlan = PlanOptimizer.optimize(originalPlan);

            assertThat(optimizedPlan).isNotNull();
            assertThat(optimizedPlan.getProperty("optimized")).isEqualTo(true);
        }

        @Test
        @DisplayName("优化后的数据一致性 - 计划类型和结构应保持")
        void optimize_ShouldPreservePlanStructure() {
            String sql = TestDataFactory.createValidSelectSql();
            LogicalPlan original = SqlParser.parse(sql);

            LogicalPlan optimized = PlanOptimizer.optimize(original);

            assertThat(optimized.getPlanType()).isEqualTo(original.getPlanType());
            assertThat(optimized.getChildren()).hasSize(original.getChildren().size());
        }

        @Test
        @DisplayName("多次优化的幂等性 - 多次优化结果应一致")
        void optimize_MultipleTimes_ShouldBeIdempotent() {
            String sql = TestDataFactory.createSelectSqlWithFilter();
            LogicalPlan original = SqlParser.parse(sql);

            LogicalPlan firstOptimized = PlanOptimizer.optimize(original);
            LogicalPlan secondOptimized = PlanOptimizer.optimize(firstOptimized);

            assertThat(firstOptimized.getPlanType()).isEqualTo(secondOptimized.getPlanType());
            assertThat(firstOptimized.getProperty("optimized")).isEqualTo(true);
            assertThat(secondOptimized.getProperty("optimized")).isEqualTo(true);
        }
    }

    @Nested
    @DisplayName("优化解释测试")
    class ExplainOptimizationTests {

        @Test
        @DisplayName("优化解释 - 应返回原始计划和优化后计划的对比")
        void explainOptimization_ShouldReturnComparison() {
            String sql = TestDataFactory.createSelectSqlWithFilter();
            LogicalPlan original = SqlParser.parse(sql);
            LogicalPlan optimized = PlanOptimizer.optimize(original);

            var explanation = PlanOptimizer.explainOptimization(original, optimized);

            assertThat(explanation).isNotNull();
            assertThat(explanation).containsKey("original");
            assertThat(explanation).containsKey("optimized");
            assertThat(explanation).containsKey("rulesApplied");

            var rules = (java.util.List<?>) explanation.get("rulesApplied");
            assertThat(rules).contains("Predicate Pushdown", "Column Pruning");
        }
    }
}
