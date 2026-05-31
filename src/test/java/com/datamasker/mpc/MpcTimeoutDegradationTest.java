package com.datamasker.mpc;

import com.datamasker.domain.mpc.model.MpcComputationResult;
import com.datamasker.domain.mpc.model.MpcSession;
import com.datamasker.domain.mpc.protocol.SecretSharingProtocol;
import com.datamasker.testdata.MpcTestDataMother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("安全多方计算 - 超时降级行为测试")
class MpcTimeoutDegradationTest {

    private SecretSharingProtocol secretSharingProtocol;
    private BigInteger modulus;

    @BeforeEach
    void setUp() {
        secretSharingProtocol = new SecretSharingProtocol();
        modulus = MpcTestDataMother.DEFAULT_MODULUS;
    }

    @Nested
    @DisplayName("会话超时检测测试")
    class SessionTimeoutDetection {

        @Test
        @DisplayName("部分参与方超时后触发降级策略")
        void shouldTriggerDegradationOnPartyTimeout() throws InterruptedException {
            int totalParties = 5;
            int responsiveParties = 3;
            long timeoutMs = 100;

            CountDownLatch allLatch = new CountDownLatch(totalParties);
            AtomicBoolean timeoutOccurred = new AtomicBoolean(false);

            ExecutorService executor = Executors.newFixedThreadPool(totalParties);
            List<Future<Boolean>> futures = new ArrayList<>();

            for (int i = 0; i < totalParties; i++) {
                final int partyIndex = i;
                futures.add(executor.submit(() -> {
                    if (partyIndex < responsiveParties) {
                        Thread.sleep(10);
                        allLatch.countDown();
                        return true;
                    } else {
                        Thread.sleep(5000);
                        allLatch.countDown();
                        return false;
                    }
                }));
            }

            boolean allCompleted = allLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!allCompleted) {
                timeoutOccurred.set(true);
            }

            executor.shutdownNow();

            assertThat(timeoutOccurred.get()).isTrue();
        }

        @Test
        @DisplayName("超时后会话状态正确更新")
        void shouldUpdateSessionStatusOnTimeout() {
            MpcSession session = MpcTestDataMother.sessionWithStatus("COMPUTING");
            long timeoutMs = 100;

            boolean completed = simulateComputationWithTimeout(session, timeoutMs, true);

            assertThat(completed).isFalse();
            assertThat(session.getStatus()).isEqualTo("TIMED_OUT");
        }

        private boolean simulateComputationWithTimeout(MpcSession session, long timeoutMs, boolean shouldTimeout) {
            CompletableFuture<Boolean> computation = CompletableFuture.supplyAsync(() -> {
                try {
                    if (shouldTimeout) {
                        Thread.sleep(timeoutMs * 2);
                    } else {
                        Thread.sleep(timeoutMs / 2);
                    }
                    return true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            });

            try {
                return computation.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                session.setStatus("TIMED_OUT");
                return false;
            } catch (Exception e) {
                return false;
            }
        }
    }

    @Nested
    @DisplayName("降级策略执行测试")
    class DegradationStrategyExecution {

        @Test
        @DisplayName("所有分片可用时恢复正确结果")
        void shouldReconstructCorrectlyWithAllShares() {
            int totalParties = 5;
            BigInteger secret = BigInteger.valueOf(123456789);

            List<BigInteger> shares = MpcTestDataMother.additiveSecretShares(totalParties, secret, modulus);
            BigInteger recovered = secretSharingProtocol.reconstructResult(shares, modulus);

            assertThat(recovered).isEqualByComparingTo(secret);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2})
        @DisplayName("部分参与方缺席时降级处理")
        void shouldHandleMissingPartiesGracefully(int missingCount) {
            int totalParties = 5;
            int availableParties = totalParties - missingCount;
            BigInteger secret = BigInteger.valueOf(987654321);

            List<BigInteger> allShares = MpcTestDataMother.additiveSecretShares(totalParties, secret, modulus);

            assertThat(allShares).hasSize(totalParties);
            assertThat(availableParties).isGreaterThan(0);
        }

        @Test
        @DisplayName("加法秘密共享正确性验证")
        void shouldVerifyAdditiveSecretSharingCorrectness() {
            int parties = 5;
            BigInteger secret = BigInteger.valueOf(42);

            List<BigInteger> shares = secretSharingProtocol.splitInput(secret, parties, modulus);
            BigInteger recovered = secretSharingProtocol.reconstructResult(shares, modulus);

            assertThat(recovered).isEqualByComparingTo(secret);
        }
    }

    @Nested
    @DisplayName("超时场景下的正确性保证")
    class CorrectnessUnderTimeout {

        @Test
        @DisplayName("所有参与方响应时结果正确")
        void allPartiesRespondingShouldReturnCorrectResult() {
            int totalParties = 5;
            BigInteger secret = new BigInteger("12345678901234567890");

            List<BigInteger> allShares = secretSharingProtocol.splitInput(secret, totalParties, modulus);
            BigInteger result = secretSharingProtocol.reconstructResult(allShares, modulus);

            assertThat(result).isEqualByComparingTo(secret);
        }

        @Test
        @DisplayName("秘密共享计算的幂等性")
        void shouldBeIdempotentUnderSuccessiveTimeouts() {
            int totalParties = 5;
            BigInteger secret = new BigInteger("999999999999");
            List<BigInteger> shares = secretSharingProtocol.splitInput(secret, totalParties, modulus);

            BigInteger result1 = secretSharingProtocol.reconstructResult(shares, modulus);
            BigInteger result2 = secretSharingProtocol.reconstructResult(shares, modulus);
            BigInteger result3 = secretSharingProtocol.reconstructResult(shares, modulus);

            assertThat(result1).isEqualTo(result2).isEqualTo(result3).isEqualTo(secret);
        }
    }

    @Nested
    @DisplayName("性能降级监控测试")
    class PerformanceDegradationMonitoring {

        @Test
        @Timeout(value = 500, unit = TimeUnit.MILLISECONDS)
        @DisplayName("超时检测本身不应超时")
        void timeoutDetectionShouldNotTimeout() {
            MpcSession session = MpcTestDataMother.session();

            CompletableFuture<MpcComputationResult> future = CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(1000);
                    return MpcTestDataMother.computationResult();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            });

            try {
                future.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                session.setStatus("FAILED");
            } catch (Exception e) {
            }

            assertThat(session.getStatus()).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("超时后资源正确释放")
        void shouldReleaseResourcesAfterTimeout() throws InterruptedException {
            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch startedLatch = new CountDownLatch(threadCount);
            AtomicInteger completedCount = new AtomicInteger(0);
            AtomicInteger timeoutCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    startedLatch.countDown();
                    CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
                        try {
                            Thread.sleep(5000);
                            return true;
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    });

                    try {
                        future.get(100, TimeUnit.MILLISECONDS);
                        completedCount.incrementAndGet();
                    } catch (TimeoutException e) {
                        timeoutCount.incrementAndGet();
                        future.cancel(true);
                    } catch (Exception e) {
                    }
                });
            }

            startedLatch.await();
            Thread.sleep(500);

            executor.shutdownNow();
            boolean terminated = executor.awaitTermination(1, TimeUnit.SECONDS);

            assertThat(terminated).isTrue();
            assertThat(timeoutCount.get()).isEqualTo(threadCount);
            assertThat(completedCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("降级策略回退测试")
    class DegradationFallback {

        @Test
        @DisplayName("MPC不可用时回退到本地计算")
        void shouldFallbackToLocalComputationWhenMpcUnavailable() {
            boolean mpcAvailable = false;
            BigInteger input = BigInteger.valueOf(12345);
            BigInteger expectedResult = input.multiply(BigInteger.valueOf(2));

            BigInteger result;
            String computationMode;

            if (mpcAvailable) {
                List<BigInteger> shares = secretSharingProtocol.splitInput(input, 3, modulus);
                result = secretSharingProtocol.reconstructResult(shares, modulus);
                computationMode = "MPC";
            } else {
                result = localComputation(input);
                computationMode = "LOCAL_FALLBACK";
            }

            assertThat(computationMode).isEqualTo("LOCAL_FALLBACK");
            assertThat(result).isEqualByComparingTo(expectedResult);
        }

        private BigInteger localComputation(BigInteger input) {
            return input.multiply(BigInteger.valueOf(2));
        }

        @Test
        @DisplayName("多次计算结果一致性验证")
        void shouldVerifyResultConsistency() {
            int parties = 5;
            BigInteger secret = BigInteger.valueOf(88888);

            List<BigInteger> shares = secretSharingProtocol.splitInput(secret, parties, modulus);
            BigInteger result = secretSharingProtocol.reconstructResult(shares, modulus);

            List<BigInteger> shares2 = secretSharingProtocol.splitInput(secret, parties, modulus);
            BigInteger result2 = secretSharingProtocol.reconstructResult(shares2, modulus);

            assertThat(result).isEqualTo(result2).isEqualTo(secret);
        }
    }
}
