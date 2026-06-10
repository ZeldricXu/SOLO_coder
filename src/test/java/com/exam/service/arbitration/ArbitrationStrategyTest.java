package com.exam.service.arbitration;

import com.exam.entity.ExamAnswer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("仲裁策略单元测试")
class ArbitrationStrategyTest {

    private ExamAnswer answer;

    @BeforeEach
    void setUp() {
        answer = new ExamAnswer();
        answer.setQuestionScore(new BigDecimal("10"));
    }

    @Nested
    @DisplayName("均值仲裁策略")
    class AverageStrategyTests {

        private AverageArbitrationStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new AverageArbitrationStrategy();
        }

        @Test
        @DisplayName("两位评分差在阈值内取平均值")
        void shouldReturnAverageWhenDiffWithinThreshold() {
            answer.setFirstGraderScore(new BigDecimal("8.0"));
            answer.setSecondGraderScore(new BigDecimal("8.5"));

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("8.25");
        }

        @Test
        @DisplayName("分差超过阈值返回null触发仲裁")
        void shouldReturnNullWhenDiffExceedsThreshold() {
            answer.setFirstGraderScore(new BigDecimal("5.0"));
            answer.setSecondGraderScore(new BigDecimal("9.0"));

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("只有一位老师评分时直接返回")
        void shouldReturnSingleScoreWhenOnlyOneGrader() {
            answer.setFirstGraderScore(new BigDecimal("7.5"));
            answer.setSecondGraderScore(null);

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("7.5");
        }

        @Test
        @DisplayName("两位都没评时返回0")
        void shouldReturnZeroWhenNoGrader() {
            answer.setFirstGraderScore(null);
            answer.setSecondGraderScore(null);

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("0");
        }

        @Test
        @DisplayName("分差刚好在阈值边缘")
        void shouldWorkAtThresholdBoundary() {
            answer.setFirstGraderScore(new BigDecimal("7.0"));
            answer.setSecondGraderScore(new BigDecimal("9.0"));

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("最高分仲裁策略")
    class MaxScoreStrategyTests {

        private MaxScoreArbitrationStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new MaxScoreArbitrationStrategy();
        }

        @Test
        @DisplayName("取两位评分中的较高分")
        void shouldReturnMaxOfTwoScores() {
            answer.setFirstGraderScore(new BigDecimal("6.0"));
            answer.setSecondGraderScore(new BigDecimal("8.5"));

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("8.5");
        }

        @Test
        @DisplayName("只有一位老师时直接返回")
        void shouldReturnSingleScoreWhenOnlyOneGrader() {
            answer.setFirstGraderScore(null);
            answer.setSecondGraderScore(new BigDecimal("7.0"));

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("7.0");
        }

        @Test
        @DisplayName("两位都没评时返回0")
        void shouldReturnZeroWhenNoGrader() {
            answer.setFirstGraderScore(null);
            answer.setSecondGraderScore(null);

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("0");
        }
    }

    @Nested
    @DisplayName("人工终裁策略")
    class ManualStrategyTests {

        private ManualArbitrationStrategy strategy;

        @BeforeEach
        void setUp() {
            strategy = new ManualArbitrationStrategy();
        }

        @Test
        @DisplayName("没有仲裁分时返回null需要人工")
        void shouldReturnNullWhenNoFinalScore() {
            answer.setFirstGraderScore(new BigDecimal("6.0"));
            answer.setSecondGraderScore(new BigDecimal("8.0"));
            answer.setFinalScore(null);

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isNull();
        }

        @Test
        @DisplayName("已有仲裁分时直接返回仲裁分")
        void shouldReturnFinalScoreWhenPresent() {
            answer.setFirstGraderScore(new BigDecimal("6.0"));
            answer.setSecondGraderScore(new BigDecimal("8.0"));
            answer.setFinalScore(new BigDecimal("7.5"));

            BigDecimal result = strategy.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("7.5");
        }
    }

    @Nested
    @DisplayName("策略管理器")
    class StrategyManagerTests {

        private ArbitrationStrategyManager manager;

        @BeforeEach
        void setUp() {
            manager = new ArbitrationStrategyManager();
        }

        @Test
        @DisplayName("默认策略为均值仲裁")
        void shouldUseAverageAsDefault() {
            answer.setFirstGraderScore(new BigDecimal("8.0"));
            answer.setSecondGraderScore(new BigDecimal("8.4"));

            BigDecimal result = manager.arbitrate(answer);

            assertThat(result).isEqualByComparingTo("8.20");
        }

        @Test
        @DisplayName("根据策略代码获取对应策略")
        void shouldGetStrategyByCode() {
            answer.setFirstGraderScore(new BigDecimal("5.0"));
            answer.setSecondGraderScore(new BigDecimal("9.0"));

            BigDecimal maxResult = manager.arbitrate(answer, "MAX_SCORE");
            BigDecimal avgResult = manager.arbitrate(answer, "AVERAGE");
            BigDecimal manualResult = manager.arbitrate(answer, "MANUAL");

            assertThat(maxResult).isEqualByComparingTo("9.0");
            assertThat(avgResult).isNull();
            assertThat(manualResult).isNull();
        }

        @Test
        @DisplayName("未知策略代码回退到默认")
        void shouldFallbackToDefaultForUnknownCode() {
            answer.setFirstGraderScore(new BigDecimal("8.0"));
            answer.setSecondGraderScore(new BigDecimal("8.0"));

            BigDecimal result = manager.arbitrate(answer, "UNKNOWN");

            assertThat(result).isEqualByComparingTo("8.00");
        }
    }
}
