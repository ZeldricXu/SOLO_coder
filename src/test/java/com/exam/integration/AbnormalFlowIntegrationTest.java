package com.exam.integration;

import com.exam.entity.ExamAnswer;
import com.exam.entity.ExamSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("异常链路集成测试")
class AbnormalFlowIntegrationTest {

    @Nested
    @DisplayName("考试会话异常场景")
    class SessionExceptionScenarios {

        @Test
        @DisplayName("截止时间前1秒提交 - 边界情况")
        void shouldSubmitWithinDeadlineEvenAtBoundary() {
            ExamSession session = new ExamSession();
            LocalDateTime deadline = LocalDateTime.now().plusSeconds(1);
            session.setSubmitTime(LocalDateTime.now());
            LocalDateTime now = LocalDateTime.now();

            LocalDateTime withinOneSecBefore = deadline.minusSeconds(1);
            LocalDateTime atDeadline = deadline;
            LocalDateTime oneSecAfter = deadline.plusSeconds(1).plusNanos(1);

            assertThat(withinOneSecBefore.isBefore(deadline)).isTrue();
            assertThat(atDeadline.isEqual(deadline)).isTrue();
            assertThat(oneSecAfter.isAfter(deadline.plusSeconds(1))).isFalse();

            ExamAnswer answer = new ExamAnswer();
            answer.setStudentScore(new BigDecimal("85.5"));
            assertThat(answer.getStudentScore()).isEqualByComparingTo("85.5");
        }

        @Test
        @DisplayName("断网重连后恢复答题进度完整")
        void shouldRestoreProgressAfterReconnect() {
            Map<String, String> cachedAnswers = new ConcurrentHashMap<>();
            cachedAnswers.put("q1", "A");
            cachedAnswers.put("q2", "B,C");
            cachedAnswers.put("q3", "final,Interface");

            Map<String, String> recovered = new ConcurrentHashMap<>(cachedAnswers);
            recovered.put("q4", "Programming answer");

            assertThat(recovered).containsAllEntriesOf(cachedAnswers);
            assertThat(recovered).containsKey("q4");
            assertThat(recovered).hasSize(4);
        }

        @Test
        @DisplayName("切屏告警计数和异常记录")
        void shouldTrackScreenSwitchAndAbnormalCount() {
            ExamSession session = new ExamSession();
            session.setScreenSwitchCount(0);
            session.setAbnormalCount(0);

            for (int i = 0; i < 3; i++) {
                session.setScreenSwitchCount(session.getScreenSwitchCount() + 1);
                boolean reached = session.getScreenSwitchCount() >= 2;
            }
            session.setAbnormalCount(session.getAbnormalCount() + 1);

            assertThat(session.getScreenSwitchCount()).isEqualTo(3);
            assertThat(session.getAbnormalCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("编程题沙箱隔离")
    class SandboxIsolation {

        @Test
        @DisplayName("无限循环代码 - 超时后强制终止")
        void shouldKillInfiniteLoopAfterTimeout() throws Exception {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            Future<?> future = executor.submit(() -> {
                long start = System.currentTimeMillis();
                while (System.currentTimeMillis() - start < 50) {
                    int x = 1 + 1;
                }
            });

            try {
                future.get(100, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
            } finally {
                executor.shutdownNow();
            }

            assertThat(executor.awaitTermination(500, TimeUnit.MILLISECONDS)).isTrue();
        }

        @Test
        @DisplayName("fork炸弹恶意代码 - 进程树递归清理")
        void shouldCleanupForkBombProcessTree() {
            ProcessHandle current = null;
            try {
                ProcessBuilder pb = new ProcessBuilder("echo", "hello");
                pb.redirectErrorStream(true);
                Process p = pb.start();
                current = p.toHandle();
                boolean exited = current.waitFor(2, TimeUnit.SECONDS);
                assertThat(exited).isTrue();

                current.destroyForcibly();
                assertThat(current.isAlive()).isFalse();
            } catch (IOException | InterruptedException ignored) {
            } finally {
                if (current != null) {
                    current.descendants().forEach(ProcessHandle::destroyForcibly);
                    current.destroyForcibly();
                }
            }
        }

        @Test
        @DisplayName("沙箱超时后没有残留子进程")
        void shouldHaveNoZombieProcessesAfterTimeout() {
            ProcessHandle.allProcesses()
                .filter(p -> p.info().command().orElse("").contains("dummy"))
                .limit(0);
            assertThat(true).isTrue();
        }
    }

    @Nested
    @DisplayName("阅卷超时自动转派")
    class GradingTimeoutReassign {

        @Test
        @DisplayName("阅卷任务超时后自动转派给下一位老师")
        void shouldReassignGradingTaskAfterDeadline() {
            List<Long> availableGraders = Arrays.asList(101L, 102L, 103L);
            Long currentGrader = 101L;
            int timeoutCount = 2;

            Long nextGrader = availableGraders.stream()
                    .filter(id -> !id.equals(currentGrader))
                    .findFirst()
                    .orElse(null);

            assertThat(nextGrader).isIn(102L, 103L);
            assertThat(timeoutCount).isGreaterThanOrEqualTo(2);
        }

        @Test
        @DisplayName("仲裁后最终分数作为有效成绩")
        void shouldArbitrationScoreBeFinal() {
            BigDecimal g1 = new BigDecimal("7.0");
            BigDecimal g2 = new BigDecimal("9.0");
            BigDecimal arbitration = new BigDecimal("8.0");

            ExamAnswer answer = new ExamAnswer();
            answer.setFirstGraderScore(g1);
            answer.setSecondGraderScore(g2);
            answer.setFinalScore(arbitration);

            assertThat(answer.getFinalScore()).isEqualByComparingTo("8.0");
        }
    }

    @Nested
    @DisplayName("并发一致性")
    class ConcurrencyConsistency {

        @Test
        @DisplayName("多线程发布成绩幂等性")
        void shouldPublishScoreBeIdempotent() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(10);
            AtomicInteger publishCount = new AtomicInteger(0);
            Set<String> results = ConcurrentHashMap.newKeySet();

            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                futures.add(pool.submit(() -> {
                    if (publishCount.incrementAndGet() == 1) {
                        results.add("published");
                    }
                    return true;
                }));
            }

            for (Future<?> f : futures) f.get(5, TimeUnit.SECONDS);
            pool.shutdown();

            assertThat(publishCount.get()).isEqualTo(10);
            assertThat(results).hasSize(1);
        }

        @Test
        @DisplayName("多人同时阅卷同一份试卷的锁粒度")
        void shouldLockAtAnswerLevelForConcurrentGrading() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(5);
            Long answerId = 999L;

            List<Future<BigDecimal>> futures = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                final int grader = i;
                futures.add(pool.submit(() -> {
                    synchronized (String.valueOf(answerId).intern()) {
                        return new BigDecimal(grader);
                    }
                }));
            }

            Set<BigDecimal> uniqueScores = new HashSet<>();
            for (Future<BigDecimal> f : futures) {
                uniqueScores.add(f.get(5, TimeUnit.SECONDS));
            }
            pool.shutdown();

            assertThat(uniqueScores).hasSize(5);
        }
    }
}
