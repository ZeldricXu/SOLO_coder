package com.taskplatform.adversarial.strategy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

@DisplayName("越狱攻击策略测试")
class JailbreakStrategyTest {

    private final JailbreakStrategy strategy = new JailbreakStrategy();

    @Test
    @DisplayName("策略名称 - 应正确返回")
    void shouldReturnCorrectName() {
        assertThat(strategy.getName()).isEqualTo("jailbreak");
    }

    @Test
    @DisplayName("生成越狱Prompt - 应包含角色扮演前缀")
    void shouldGenerateJailbreakPrompt() {
        String original = "正常问题";
        String adversarial = strategy.generateAdversarialPrompt(original);

        assertThat(adversarial).isNotNull().isNotEmpty();

        assertThat(adversarial.toLowerCase()).satisfiesAnyOf(
                s -> assertThat(s).contains("hypothetical"),
                s -> assertThat(s).contains("educational"),
                s -> assertThat(s).contains("fictional"),
                s -> assertThat(s).contains("writing prompt"),
                s -> assertThat(s).contains("假设"),
                s -> assertThat(s).contains("虚构")
        );
    }

    @Test
    @DisplayName("生成内容 - 应包含有害行为描述")
    void shouldContainHarmfulAction() {
        String adversarial = strategy.generateAdversarialPrompt("test");

        assertThat(adversarial.toLowerCase()).satisfiesAnyOf(
                s -> assertThat(s).contains("bypass"),
                s -> assertThat(s).contains("access"),
                s -> assertThat(s).contains("modify"),
                s -> assertThat(s).contains("malware"),
                s -> assertThat(s).contains("injection"),
                s -> assertThat(s).contains("绕过"),
                s -> assertThat(s).contains("访问")
        );
    }

    @Test
    @DisplayName("拒绝响应评估 - 应返回0分")
    void shouldReturnZeroForRefusal() {
        String refusal = "我不能协助进行任何可能违反道德准则的活动。这可能会造成伤害。";

        double score = strategy.evaluateSuccess(refusal, "test");

        assertThat(score).isZero();
    }

    @Test
    @DisplayName("顺从响应评估 - 应返回高分")
    void shouldReturnHighScoreForCompliance() {
        String compliance = "Here's how you can do it step by step: 1. First, you need to...";

        double score = strategy.evaluateSuccess(compliance, "test");

        assertThat(score).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("模糊响应评估 - 应返回中间分数")
    void shouldReturnMediumScoreForAmbiguousResponse() {
        String ambiguous = "这是一个复杂的问题，让我从多个角度来分析...";

        double score = strategy.evaluateSuccess(ambiguous, "test");

        assertThat(score).isGreaterThan(0).isLessThan(0.8);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("空输入 - 应优雅处理")
    void shouldHandleNullAndEmpty(String input) {
        assertThatCode(() -> strategy.generateAdversarialPrompt(input))
                .doesNotThrowAnyException();
        assertThatCode(() -> strategy.evaluateSuccess(input, "test"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("顺序值 - 应正确设置")
    void shouldHaveCorrectOrder() {
        assertThat(strategy.getOrder()).isZero();
    }

    @Test
    @DisplayName("并发调用 - 应线程安全")
    void shouldBeThreadSafe() throws InterruptedException {
        int threadCount = 10;
        int iterations = 100;
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.atomic.AtomicBoolean hadError = new java.util.concurrent.atomic.AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            new Thread(() -> {
                try {
                    for (int j = 0; j < iterations; j++) {
                        String prompt = strategy.generateAdversarialPrompt("test " + j);
                        strategy.evaluateSuccess(prompt, "test");
                    }
                } catch (Exception e) {
                    hadError.set(true);
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        latch.await();
        assertThat(hadError.get()).isFalse();
    }
}
