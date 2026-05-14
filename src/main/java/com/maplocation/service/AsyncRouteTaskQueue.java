package com.maplocation.service;

import com.maplocation.dto.RoutePlanRequest;
import lombok.Builder;
import lombok.Data;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class AsyncRouteTaskQueue {

    private final Queue<RouteTask> taskQueue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queuedCount = new AtomicInteger(0);

    @Data
    @Builder
    public static class RouteTask {
        private String taskId;
        private RoutePlanRequest request;
        private String routeId;
        private Instant submittedAt;
        private TaskStatus status;
    }

    public enum TaskStatus {
        PENDING,
        PROCESSING,
        COMPLETED,
        FAILED
    }

    public RouteTask submitTask(RoutePlanRequest request, String routeId) {
        RouteTask task = RouteTask.builder()
                .taskId("task_" + System.currentTimeMillis())
                .request(request)
                .routeId(routeId)
                .submittedAt(Instant.now())
                .status(TaskStatus.PENDING)
                .build();

        taskQueue.offer(task);
        queuedCount.incrementAndGet();
        return task;
    }

    public RouteTask pollTask() {
        RouteTask task = taskQueue.poll();
        if (task != null) {
            queuedCount.decrementAndGet();
            task.setStatus(TaskStatus.PROCESSING);
        }
        return task;
    }

    public int getQueuedCount() {
        return queuedCount.get();
    }

    public boolean isEmpty() {
        return taskQueue.isEmpty();
    }
}
