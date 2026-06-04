package com.flowplatform.process;

import com.flowplatform.entity.ProcessTask;
import com.flowplatform.mapper.ProcessTaskMapper;
import com.flowplatform.service.ProcessInstanceService;
import com.flowplatform.test.BaseIntegrationTest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@ActiveProfiles("test")
@DisplayName("审批列表并发去重测试")
public class PendingTaskConcurrencyTest extends BaseIntegrationTest {

    @Autowired
    private ProcessInstanceService processInstanceService;

    @Autowired
    private ProcessTaskMapper taskMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private TransactionTemplate transactionTemplate;

    private Long testUserId = 1000L;
    private List<Long> createdTaskIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);

        for (int i = 0; i < 10; i++) {
            ProcessTask task = new ProcessTask();
            task.setInstanceId(1L);
            task.setProcessId(1L);
            task.setNodeId("node_" + i);
            task.setNodeName("审批节点" + i);
            task.setAssigneeId(testUserId);
            task.setStatus("PENDING");
            task.setCreateTime(LocalDateTime.now());
            taskMapper.insert(task);
            createdTaskIds.add(task.getId());
        }
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdTaskIds) {
            taskMapper.deleteById(id);
        }
        createdTaskIds.clear();
    }

    @Test
    @DisplayName("多线程并发查询 - 每个任务只能被一个线程获取")
    void testConcurrentQueryNoDuplicates() throws Exception {
        int threadCount = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        Set<Long> allTaskIds = Collections.synchronizedSet(new HashSet<>());
        Map<Integer, Set<Long>> threadResults = new ConcurrentHashMap<>();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            executor.submit(() -> {
                try {
                    List<ProcessTask> tasks = transactionTemplate.execute(status -> {
                        return processInstanceService.getPendingTasks(testUserId);
                    });
                    Set<Long> ids = new HashSet<>();
                    for (ProcessTask task : tasks) {
                        ids.add(task.getId());
                    }
                    threadResults.put(threadId, ids);
                    allTaskIds.addAll(ids);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "所有线程应在10秒内完成");
        executor.shutdown();

        assertEquals(10, allTaskIds.size(), "所有10个任务应被获取");

        for (int i = 0; i < threadCount; i++) {
            for (int j = i + 1; j < threadCount; j++) {
                Set<Long> tasks1 = threadResults.get(i);
                Set<Long> tasks2 = threadResults.get(j);
                if (tasks1 != null && tasks2 != null) {
                    Set<Long> intersection = new HashSet<>(tasks1);
                    intersection.retainAll(tasks2);
                    assertTrue(intersection.isEmpty(),
                            "线程" + i + "和" + j + "不应有重复任务: " + intersection);
                }
            }
        }
    }

    @Test
    @DisplayName("高并发下无重复数据 - FOR UPDATE SKIP LOCKED机制验证")
    void testHighConcurrencyNoDuplicates() throws Exception {
        int threadCount = 10;
        int iterations = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        AtomicInteger totalFetched = new AtomicInteger(0);
        AtomicInteger duplicatesFound = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(threadCount * iterations);

        Map<Long, Integer> fetchCountMap = new ConcurrentHashMap<>();

        for (int iter = 0; iter < iterations; iter++) {
            for (int t = 0; t < threadCount; t++) {
                executor.submit(() -> {
                    try {
                        List<ProcessTask> tasks = transactionTemplate.execute(status -> {
                            return processInstanceService.getPendingTasks(testUserId);
                        });
                        totalFetched.addAndGet(tasks.size());

                        for (ProcessTask task : tasks) {
                            int count = fetchCountMap.merge(task.getId(), 1, Integer::sum);
                            if (count > 1) {
                                duplicatesFound.incrementAndGet();
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }
            Thread.sleep(50);
        }

        boolean completed = latch.await(30, TimeUnit.SECONDS);
        assertTrue(completed, "所有请求应在30秒内完成");
        executor.shutdown();

        assertEquals(0, duplicatesFound.get(), "并发查询下不应有重复数据");

        assertEquals(10, fetchCountMap.size(), "每个任务至少被获取一次");
    }

    @Test
    @DisplayName("转交操作与查询并发 - 无重复读取")
    void testTransferAndQueryConcurrency() throws Exception {
        Long fromUserId = testUserId;
        Long toUserId = 2000L;

        ProcessTask transferTask = new ProcessTask();
        transferTask.setInstanceId(2L);
        transferTask.setProcessId(1L);
        transferTask.setNodeId("transfer_test");
        transferTask.setNodeName("转交测试节点");
        transferTask.setAssigneeId(fromUserId);
        transferTask.setStatus("PENDING");
        transferTask.setCreateTime(LocalDateTime.now());
        taskMapper.insert(transferTask);
        createdTaskIds.add(transferTask.getId());

        ExecutorService executor = Executors.newFixedThreadPool(3);
        CountDownLatch latch = new CountDownLatch(3);

        AtomicInteger queryCount = new AtomicInteger(0);
        AtomicInteger transferSuccess = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    List<ProcessTask> tasks = transactionTemplate.execute(status -> {
                        return processInstanceService.getPendingTasks(fromUserId);
                    });
                    queryCount.addAndGet(tasks.size());
                }
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                transactionTemplate.execute(status -> {
                    processInstanceService.transferTask(transferTask.getId(), fromUserId, toUserId, "转交测试");
                    return null;
                });
                transferSuccess.incrementAndGet();
            } finally {
                latch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                for (int i = 0; i < 5; i++) {
                    transactionTemplate.execute(status -> {
                        return processInstanceService.getPendingTasks(toUserId);
                    });
                }
            } finally {
                latch.countDown();
            }
        });

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertTrue(completed, "所有操作应在10秒内完成");
        executor.shutdown();

        assertEquals(1, transferSuccess.get(), "转交应成功");

        ProcessTask afterTransfer = taskMapper.selectById(transferTask.getId());
        assertEquals(toUserId, afterTransfer.getAssigneeId(), "任务应已转交给toUserId");
    }

    @Test
    @DisplayName("SKIP LOCKED行为验证 - 已锁定的任务被跳过")
    void testSkipLockedBehavior() throws Exception {
        CountDownLatch queryStarted = new CountDownLatch(1);
        CountDownLatch queryHold = new CountDownLatch(1);
        CountDownLatch secondQueryDone = new CountDownLatch(1);

        AtomicInteger firstQuerySize = new AtomicInteger(0);
        AtomicInteger secondQuerySize = new AtomicInteger(0);

        Thread firstThread = new Thread(() -> {
            transactionTemplate.execute(status -> {
                List<ProcessTask> tasks = processInstanceService.getPendingTasks(testUserId);
                firstQuerySize.set(tasks.size());
                queryStarted.countDown();
                try {
                    queryHold.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return null;
            });
        });

        Thread secondThread = new Thread(() -> {
            try {
                queryStarted.await(5, TimeUnit.SECONDS);
                List<ProcessTask> tasks = transactionTemplate.execute(status -> {
                    return processInstanceService.getPendingTasks(testUserId);
                });
                secondQuerySize.set(tasks.size());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                secondQueryDone.countDown();
            }
        });

        firstThread.start();
        secondThread.start();

        boolean done = secondQueryDone.await(10, TimeUnit.SECONDS);
        assertTrue(done, "第二个查询应完成");

        queryHold.countDown();
        firstThread.join(5000);
        secondThread.join(5000);

        assertEquals(10, firstQuerySize.get(), "第一个查询应获取所有10个任务");
        assertEquals(0, secondQuerySize.get(), "第二个查询应跳过所有被锁定的任务，返回0个");
    }
}
