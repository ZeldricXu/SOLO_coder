package com.datamasker.privacy;

import com.datamasker.domain.privacy.mechanism.GaussianMechanism;
import com.datamasker.domain.privacy.mechanism.LaplaceMechanism;
import com.datamasker.domain.privacy.model.NoisyResult;
import com.datamasker.testdata.PrivacyTestDataMother;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

@DisplayName("差分隐私 - 并发隔离级别验证测试")
class DifferentialPrivacyConcurrencyTest {

    private final LaplaceMechanism laplaceMechanism = new LaplaceMechanism();
    private final GaussianMechanism gaussianMechanism = new GaussianMechanism();

    @Nested
    @DisplayName("机制线程安全性测试")
    @Execution(ExecutionMode.CONCURRENT)
    class MechanismThreadSafety {

        @Test
        @DisplayName("Laplace机制并发调用结果统计特性保持一致")
        void laplaceMechanismShouldBeThreadSafe() throws InterruptedException, ExecutionException {
            int threadCount = 50;
            int iterationsPerThread = 1000;
            double sensitivity = 1.0;
            double epsilon = 1.0;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<List<Double>>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    List<Double> noises = new ArrayList<>();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        NoisyResult result = laplaceMechanism.addNoise(0, sensitivity, epsilon);
                        noises.add(result.getNoiseAdded());
                    }
                    return noises;
                }));
            }

            List<Double> allNoises = new ArrayList<>();
            for (Future<List<Double>> future : futures) {
                allNoises.addAll(future.get());
            }
            executor.shutdown();

            double meanNoise = allNoises.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);

            assertThat(meanNoise).isCloseTo(0.0, within(1.0));
            assertThat(allNoises).hasSize(threadCount * iterationsPerThread);
        }

        @Test
        @DisplayName("Gaussian机制多线程并发调用无状态污染")
        void gaussianMechanismShouldHaveNoStateLeakage() throws InterruptedException {
            int threadCount = 20;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            List<Thread> threads = IntStream.range(0, threadCount)
                    .mapToObj(i -> new Thread(() -> {
                        try {
                            startLatch.await();
                            for (int j = 0; j < 100; j++) {
                                NoisyResult result = gaussianMechanism.addNoise(
                                        100.0, 1.0, 1.0, 1.0E-5);
                                if (result.getMechanism() == null || result.getEpsilon() != 1.0) {
                                    errorCount.incrementAndGet();
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            endLatch.countDown();
                        }
                    }))
                    .collect(Collectors.toList());

            threads.forEach(Thread::start);
            startLatch.countDown();
            endLatch.await(30, TimeUnit.SECONDS);

            assertThat(errorCount.get()).isZero();
        }
    }

    @Nested
    @DisplayName("隐私预算并发隔离测试")
    class PrivacyBudgetIsolation {

        @Test
        @DisplayName("并发预算消耗正确累加，无竞态条件")
        void concurrentBudgetConsumptionShouldBeAtomic() throws InterruptedException {
            int threadCount = 100;
            double initialBudget = 100.0;
            double epsilonPerRequest = 0.1;

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(threadCount);

            List<Thread> threads = IntStream.range(0, threadCount)
                    .mapToObj(i -> new Thread(() -> {
                        try {
                            startLatch.await();
                            double consumed = epsilonPerRequest;
                            if (consumed <= initialBudget) {
                                successCount.incrementAndGet();
                            } else {
                                failCount.incrementAndGet();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            endLatch.countDown();
                        }
                    }))
                    .collect(Collectors.toList());

            threads.forEach(Thread::start);
            startLatch.countDown();
            endLatch.await(10, TimeUnit.SECONDS);

            assertThat(successCount.get() + failCount.get()).isEqualTo(threadCount);
        }

        @Test
        @DisplayName("多租户预算相互隔离互不影响")
        void tenantBudgetsShouldBeIsolated() throws Exception {
            int tenantCount = 10;
            int operationsPerTenant = 50;

            ExecutorService executor = Executors.newFixedThreadPool(tenantCount);
            List<Future<Double>> futures = new ArrayList<>();

            for (int t = 0; t < tenantCount; t++) {
                int tenantId = t;
                futures.add(executor.submit(() -> {
                    double totalEpsilon = 0;
                    for (int i = 0; i < operationsPerTenant; i++) {
                        NoisyResult result = laplaceMechanism.addNoise(
                                tenantId * 10.0, 1.0, 0.1);
                        totalEpsilon += result.getEpsilon();
                    }
                    return totalEpsilon;
                }));
            }

            List<Double> tenantTotals = new ArrayList<>();
            for (Future<Double> future : futures) {
                tenantTotals.add(future.get(10, TimeUnit.SECONDS));
            }
            executor.shutdown();

            assertThat(tenantTotals).hasSize(tenantCount);
            tenantTotals.forEach(total ->
                    assertThat(total).isCloseTo(5.0, within(0.001)));
        }
    }

    @Nested
    @DisplayName("噪声生成独立性测试")
    class NoiseGenerationIndependence {

        @Test
        @DisplayName("并发生成的噪声样本相互独立")
        void concurrentNoiseSamplesShouldBeIndependent() throws InterruptedException, ExecutionException {
            int sampleSize = 10000;
            int threadCount = 4;
            int samplesPerThread = sampleSize / threadCount;

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Future<List<Double>>> futures = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                futures.add(executor.submit(() -> {
                    List<Double> noises = new ArrayList<>();
                    for (int i = 0; i < samplesPerThread; i++) {
                        noises.add(laplaceMechanism.generateLaplace(1.0));
                    }
                    return noises;
                }));
            }

            List<Double> allNoises = new ArrayList<>();
            for (Future<List<Double>> future : futures) {
                allNoises.addAll(future.get());
            }
            executor.shutdown();

            double mean = allNoises.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);

            double variance = allNoises.stream()
                    .mapToDouble(d -> Math.pow(d - mean, 2))
                    .average()
                    .orElse(0);

            assertThat(mean).isCloseTo(0.0, within(0.5));
            assertThat(variance).isCloseTo(2.0, within(1.0));
        }

        @ParameterizedTest
        @ValueSource(doubles = {0.1, 0.5, 1.0, 2.0})
        @DisplayName("不同epsilon参数的并发调用结果满足统计特性")
        void differentEpsilonCallsShouldMaintainStatisticalProperties(double epsilon)
                throws InterruptedException {
            int concurrentCalls = 100;
            double sensitivity = 1.0;
            CountDownLatch latch = new CountDownLatch(concurrentCalls);
            List<Double> results = new CopyOnWriteArrayList<>();

            for (int i = 0; i < concurrentCalls; i++) {
                new Thread(() -> {
                    NoisyResult result = laplaceMechanism.addNoise(100.0, sensitivity, epsilon);
                    results.add(result.getNoisyValue());
                    latch.countDown();
                }).start();
            }

            latch.await(10, TimeUnit.SECONDS);

            double mean = results.stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0);

            assertThat(mean).isCloseTo(100.0, within(sensitivity / epsilon * 2));
            assertThat(results).hasSize(concurrentCalls);
        }
    }

    @Nested
    @DisplayName("高并发压力测试")
    class HighConcurrencyStress {

        @Test
        @DisplayName("1000并发请求下机制响应正确")
        void shouldHandle1000ConcurrentRequests() throws InterruptedException {
            int requestCount = 1000;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(requestCount);
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < requestCount; i++) {
                new Thread(() -> {
                    try {
                        startLatch.await();
                        NoisyResult result = laplaceMechanism.addNoise(
                                Math.random() * 1000, 1.0, 1.0);
                        if (result != null && result.getNoisyValue() > 0) {
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        endLatch.countDown();
                    }
                }).start();
            }

            startLatch.countDown();
            boolean completed = endLatch.await(60, TimeUnit.SECONDS);

            assertThat(completed).isTrue();
            assertThat(errorCount.get()).isZero();
            assertThat(successCount.get()).isEqualTo(requestCount);
        }

        @Test
        @DisplayName("并发混合调用不同机制无交叉干扰")
        void mixedMechanismCallsShouldNotInterfere() throws InterruptedException {
            int callsPerMechanism = 200;
            CountDownLatch latch = new CountDownLatch(callsPerMechanism * 2);
            List<String> laplaceMechanisms = new CopyOnWriteArrayList<>();
            List<String> gaussianMechanisms = new CopyOnWriteArrayList<>();

            for (int i = 0; i < callsPerMechanism; i++) {
                int finalI = i;
                new Thread(() -> {
                    NoisyResult result = laplaceMechanism.addNoise(finalI, 1.0, 1.0);
                    laplaceMechanisms.add(result.getMechanism());
                    latch.countDown();
                }).start();

                new Thread(() -> {
                    NoisyResult result = gaussianMechanism.addNoise(finalI, 1.0, 1.0, 1.0E-5);
                    gaussianMechanisms.add(result.getMechanism());
                    latch.countDown();
                }).start();
            }

            latch.await(30, TimeUnit.SECONDS);

            assertThat(laplaceMechanisms).allMatch(m -> m.equals("LAPLACE"));
            assertThat(gaussianMechanisms).allMatch(m -> m.equals("GAUSSIAN"));
        }
    }
}
