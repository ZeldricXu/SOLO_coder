package com.cicd.server.pipeline;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringBootTest
@ActiveProfiles("test")
class JobSchedulerConcurrencyTest {

    @Autowired
    private JobScheduler jobScheduler;

    @Test
    void testConcurrentJobCompletionsNoRaceCondition() throws InterruptedException {
        int concurrentPipelines = 50;
        int parallelJobsPerPipeline = 10;
        int totalJobs = concurrentPipelines * parallelJobsPerPipeline;
        CountDownLatch completionLatch = new CountDownLatch(totalJobs * 2);
        AtomicInteger processedCount = new AtomicInteger(0);
        Set<String> processedJobs = Collections.synchronizedSet(new HashSet<>());

        TestOrchestrator testOrchestrator = new TestOrchestrator(processedCount, completionLatch, processedJobs);
        jobScheduler.setOrchestrator(testOrchestrator);

        ExecutorService executor = Executors.newFixedThreadPool(20);
        for (int pipeline = 0; pipeline < concurrentPipelines; pipeline++) {
            long stageId = 1000L + pipeline;
            for (int job = 0; job < parallelJobsPerPipeline; job++) {
                long jobId = pipeline * 100L + job;
                long finalPipeline = pipeline;
                executor.submit(() -> {
                    jobScheduler.onJobCompleted(jobId, true, "output");
                    jobScheduler.processNextStage(finalPipeline + 1L, stageId);
                });
            }
        }

        boolean completed = completionLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertTrue(completed, "All jobs should be processed within 30s");
        assertEquals(totalJobs, processedCount.get(),
            "All jobs should be processed exactly once");
        assertEquals(totalJobs, processedJobs.size(),
            "No duplicate job processing");
    }

    @Test
    void testDependencyCompletionRaceCondition() throws InterruptedException {
        int dependentJobs = 100;
        CountDownLatch latch = new CountDownLatch(dependentJobs * 2);
        AtomicInteger triggerCount = new AtomicInteger(0);
        AtomicInteger doubleTriggerCount = new AtomicInteger(0);

        Map<Long, List<Long>> dependencyMap = new ConcurrentHashMap<>();
        for (long i = 1; i <= dependentJobs; i++) {
            dependencyMap.put(i, Arrays.asList(100L, 200L));
        }

        TestDependencyOrchestrator orch = new TestDependencyOrchestrator(
            latch, triggerCount, doubleTriggerCount, dependencyMap);
        jobScheduler.setOrchestrator(orch);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (int i = 0; i < 50; i++) {
            executor.submit(() -> jobScheduler.onJobCompleted(100L, true, "done"));
            executor.submit(() -> jobScheduler.onJobCompleted(200L, true, "done"));
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(0, doubleTriggerCount.get(), "No job should be triggered twice");
        assertTrue(triggerCount.get() >= 1, "Dependent jobs should be triggered");
    }

    @Test
    void testEventQueueOrderPreservation() throws InterruptedException {
        int events = 1000;
        List<Long> processedOrder = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch latch = new CountDownLatch(events);

        TestOrchestrator orch = new TestOrchestrator(
            new AtomicInteger(0), latch, new HashSet<>(), processedOrder);
        jobScheduler.setOrchestrator(orch);

        for (long i = 1; i <= events; i++) {
            jobScheduler.onJobCompleted(i, true, "out");
        }

        latch.await(10, TimeUnit.SECONDS);

        assertEquals(events, processedOrder.size());
        for (int i = 0; i < events - 1; i++) {
            assertTrue(processedOrder.get(i) < processedOrder.get(i + 1),
                "Events should be processed in FIFO order: " + processedOrder.get(i) + " >= " + processedOrder.get(i + 1));
        }
    }

    @Test
    void testSingleThreadedEventProcessing() throws InterruptedException {
        int events = 500;
        AtomicInteger concurrentProcessing = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(events);

        TestConcurrencyOrchestrator orch = new TestConcurrencyOrchestrator(
            latch, concurrentProcessing, maxConcurrent);
        jobScheduler.setOrchestrator(orch);

        ExecutorService executor = Executors.newFixedThreadPool(10);
        for (long i = 1; i <= events; i++) {
            long jobId = i;
            executor.submit(() -> jobScheduler.onJobCompleted(jobId, true, "output"));
        }

        latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(1, maxConcurrent.get(),
            "JobScheduler should process events single-threaded, max concurrent was: " + maxConcurrent.get());
    }

    private static class TestOrchestrator extends PipelineOrchestrator {
        private final AtomicInteger counter;
        private final CountDownLatch latch;
        private final Set<String> processed;
        private final List<Long> order;

        TestOrchestrator(AtomicInteger c, CountDownLatch l, Set<String> p) {
            this(c, l, p, null);
        }

        TestOrchestrator(AtomicInteger c, CountDownLatch l, Set<String> p, List<Long> o) {
            super(null, null, null, null, null, null, null);
            this.counter = c;
            this.latch = l;
            this.processed = p;
            this.order = o;
        }

        @Override
        public void handleJobCompletedInternal(Long jobId, boolean success, String output) {
            String key = jobId + ":" + success;
            if (!processed.add(key)) {
                throw new AssertionError("Duplicate processing for job " + jobId);
            }
            counter.incrementAndGet();
            if (order != null) order.add(jobId);
            latch.countDown();
        }

        @Override
        public void checkStageCompletionInternal(Long execId, Long stageId) {
            latch.countDown();
        }
    }

    private static class TestDependencyOrchestrator extends PipelineOrchestrator {
        private final CountDownLatch latch;
        private final AtomicInteger triggerCount;
        private final AtomicInteger doubleTriggerCount;
        private final Map<Long, List<Long>> dependencyMap;
        private final Map<Long, Set<Long>> completedDeps = new ConcurrentHashMap<>();
        private final Map<Long, Boolean> triggered = new ConcurrentHashMap<>();

        TestDependencyOrchestrator(CountDownLatch l, AtomicInteger t, AtomicInteger dt,
                                   Map<Long, List<Long>> deps) {
            super(null, null, null, null, null, null, null);
            this.latch = l;
            this.triggerCount = t;
            this.doubleTriggerCount = dt;
            this.dependencyMap = deps;
        }

        @Override
        public void handleJobCompletedInternal(Long jobId, boolean success, String output) {
            for (Map.Entry<Long, List<Long>> e : dependencyMap.entrySet()) {
                if (e.getValue().contains(jobId)) {
                    completedDeps.computeIfAbsent(e.getKey(), k -> ConcurrentHashMap.newKeySet()).add(jobId);
                    if (completedDeps.get(e.getKey()).containsAll(e.getValue())) {
                        if (triggered.putIfAbsent(e.getKey(), true) != null) {
                            doubleTriggerCount.incrementAndGet();
                        } else {
                            triggerCount.incrementAndGet();
                        }
                    }
                    latch.countDown();
                }
            }
        }
    }

    private static class TestConcurrencyOrchestrator extends PipelineOrchestrator {
        private final CountDownLatch latch;
        private final AtomicInteger concurrentProcessing;
        private final AtomicInteger maxConcurrent;

        TestConcurrencyOrchestrator(CountDownLatch l, AtomicInteger cp, AtomicInteger mc) {
            super(null, null, null, null, null, null, null);
            this.latch = l;
            this.concurrentProcessing = cp;
            this.maxConcurrent = mc;
        }

        @Override
        public void handleJobCompletedInternal(Long jobId, boolean success, String output) {
            int current = concurrentProcessing.incrementAndGet();
            maxConcurrent.accumulateAndGet(current, Math::max);
            
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            
            concurrentProcessing.decrementAndGet();
            latch.countDown();
        }
    }
}
