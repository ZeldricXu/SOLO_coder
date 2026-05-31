package com.solocoder.platform.gpu.scheduler.impl;

import com.solocoder.platform.gpu.model.GpuTask;
import com.solocoder.platform.gpu.scheduler.TaskPriorityQueue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;

@Slf4j
@Component
public class TaskPriorityQueueImpl implements TaskPriorityQueue {

    private final PriorityBlockingQueue<GpuTask> queue;
    private final Map<String, GpuTask> taskIndex;

    public TaskPriorityQueueImpl() {
        this.queue = new PriorityBlockingQueue<>(64, (a, b) -> {
            int priorityCompare = Integer.compare(b.getPriority(), a.getPriority());
            if (priorityCompare != 0) return priorityCompare;
            return a.getSubmittedAt().compareTo(b.getSubmittedAt());
        });
        this.taskIndex = new ConcurrentHashMap<>();
    }

    @Override
    public void enqueue(GpuTask task) {
        GpuTask queuedTask = GpuTask.builder()
                .taskId(task.getTaskId())
                .taskName(task.getTaskName())
                .requiredMemoryMb(task.getRequiredMemoryMb())
                .requiredCudaCores(task.getRequiredCudaCores())
                .priority(task.getPriority())
                .status(GpuTask.TaskStatus.QUEUED)
                .submittedAt(task.getSubmittedAt() != null ? task.getSubmittedAt() : java.time.LocalDateTime.now())
                .metadata(task.getMetadata())
                .preemptible(task.isPreemptible())
                .build();
        queue.offer(queuedTask);
        taskIndex.put(task.getTaskId(), queuedTask);
        log.info("Task enqueued: id={}, name={}, priority={}", task.getTaskId(), task.getTaskName(), task.getPriority());
    }

    @Override
    public Optional<GpuTask> dequeue() {
        GpuTask task = queue.poll();
        if (task != null) {
            taskIndex.remove(task.getTaskId());
            return Optional.of(task);
        }
        return Optional.empty();
    }

    @Override
    public Optional<GpuTask> peek() {
        return Optional.ofNullable(queue.peek());
    }

    @Override
    public List<GpuTask> listPending() {
        return new ArrayList<>(queue);
    }

    @Override
    public boolean remove(String taskId) {
        GpuTask task = taskIndex.remove(taskId);
        if (task != null) {
            queue.remove(task);
            log.info("Task removed from queue: id={}", taskId);
            return true;
        }
        return false;
    }

    @Override
    public int size() {
        return queue.size();
    }

    @Override
    public void updatePriority(String taskId, int newPriority) {
        GpuTask existing = taskIndex.get(taskId);
        if (existing != null) {
            queue.remove(existing);
            GpuTask updated = GpuTask.builder()
                    .taskId(existing.getTaskId())
                    .taskName(existing.getTaskName())
                    .requiredMemoryMb(existing.getRequiredMemoryMb())
                    .requiredCudaCores(existing.getRequiredCudaCores())
                    .priority(newPriority)
                    .status(existing.getStatus())
                    .submittedAt(existing.getSubmittedAt())
                    .metadata(existing.getMetadata())
                    .preemptible(existing.isPreemptible())
                    .build();
            queue.offer(updated);
            taskIndex.put(taskId, updated);
            log.info("Task priority updated: id={}, newPriority={}", taskId, newPriority);
        }
    }
}
