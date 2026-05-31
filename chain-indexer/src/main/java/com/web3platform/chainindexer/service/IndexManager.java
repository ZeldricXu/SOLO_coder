package com.web3platform.chainindexer.service;

import com.web3platform.chainindexer.model.BlockIndexingTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class IndexManager {

    private final BlockIndexerService blockIndexerService;
    private final Map<String, BlockIndexingTask> taskStore = new ConcurrentHashMap<>();
    private final Map<String, AtomicBoolean> pauseFlags = new ConcurrentHashMap<>();

    public BlockIndexingTask createIndexingTask(String chainId, Long fromBlock, Long toBlock) {
        String taskId = UUID.randomUUID().toString();

        BlockIndexingTask task = BlockIndexingTask.builder()
                .taskId(taskId)
                .chainId(chainId)
                .fromBlock(fromBlock)
                .toBlock(toBlock)
                .status(BlockIndexingTask.STATUS_PENDING)
                .progress(0)
                .build();

        taskStore.put(taskId, task);
        pauseFlags.put(taskId, new AtomicBoolean(false));

        log.info("Created indexing task {} for chain {} from block {} to {}",
                taskId, chainId, fromBlock, toBlock);

        startTask(task);

        return task;
    }

    public BlockIndexingTask getTaskStatus(String taskId) {
        BlockIndexingTask task = taskStore.get(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }
        return task;
    }

    public void pauseTask(String taskId) {
        BlockIndexingTask task = taskStore.get(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }

        AtomicBoolean pauseFlag = pauseFlags.get(taskId);
        if (pauseFlag != null) {
            pauseFlag.set(true);
        }

        task.setStatus(BlockIndexingTask.STATUS_PAUSED);
        log.info("Paused indexing task {}", taskId);
    }

    public void resumeTask(String taskId) {
        BlockIndexingTask task = taskStore.get(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }

        AtomicBoolean pauseFlag = pauseFlags.get(taskId);
        if (pauseFlag != null) {
            pauseFlag.set(false);
        }

        if (BlockIndexingTask.STATUS_PAUSED.equals(task.getStatus())) {
            task.setStatus(BlockIndexingTask.STATUS_RUNNING);
            resumeTaskExecution(task);
            log.info("Resumed indexing task {}", taskId);
        }
    }

    private void startTask(BlockIndexingTask task) {
        new Thread(() -> {
            try {
                task.setStatus(BlockIndexingTask.STATUS_RUNNING);

                blockIndexerService.indexRangeAsync(
                        task.getChainId(),
                        task.getFromBlock(),
                        task.getToBlock(),
                        progress -> {
                            task.setProgress(progress);
                            if (progress >= 100) {
                                task.setStatus(BlockIndexingTask.STATUS_COMPLETED);
                                log.info("Indexing task {} completed", task.getTaskId());
                            }
                            checkPause(task.getTaskId());
                        }
                );

            } catch (Exception e) {
                log.error("Indexing task {} failed", task.getTaskId(), e);
                task.setStatus(BlockIndexingTask.STATUS_FAILED);
            }
        }, "indexer-task-" + task.getTaskId()).start();
    }

    private void resumeTaskExecution(BlockIndexingTask task) {
        long currentBlock = task.getFromBlock() +
                (long) ((task.getToBlock() - task.getFromBlock()) * task.getProgress() / 100.0);

        if (currentBlock < task.getToBlock()) {
            long finalCurrentBlock = currentBlock;
            new Thread(() -> {
                try {
                    blockIndexerService.indexRangeAsync(
                            task.getChainId(),
                            finalCurrentBlock,
                            task.getToBlock(),
                            progress -> {
                                int overallProgress = (int) ((finalCurrentBlock - task.getFromBlock() +
                                        (task.getToBlock() - finalCurrentBlock) * progress / 100.0) * 100
                                        / (task.getToBlock() - task.getFromBlock() + 1));
                                task.setProgress(Math.min(100, overallProgress));
                                if (progress >= 100) {
                                    task.setStatus(BlockIndexingTask.STATUS_COMPLETED);
                                    log.info("Indexing task {} completed", task.getTaskId());
                                }
                                checkPause(task.getTaskId());
                            }
                    );
                } catch (Exception e) {
                    log.error("Indexing task {} failed on resume", task.getTaskId(), e);
                    task.setStatus(BlockIndexingTask.STATUS_FAILED);
                }
            }, "indexer-task-resume-" + task.getTaskId()).start();
        }
    }

    private void checkPause(String taskId) {
        AtomicBoolean pauseFlag = pauseFlags.get(taskId);
        if (pauseFlag != null) {
            while (pauseFlag.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }
}
