package com.fitnesscenter.async;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.config.PlanAsyncConfig;
import com.fitnesscenter.dto.PlanRequest;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.model.Plan;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.repository.MemberRepository;
import com.fitnesscenter.repository.PlanRepository;
import com.fitnesscenter.repository.StatisticRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class PlanAsyncService {

    private final PlanRepository planRepository;
    private final MemberRepository memberRepository;
    private final HistoryRepository historyRepository;
    private final StatisticRepository statisticRepository;
    private final PlanAsyncConfig planAsyncConfig;
    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, PlanGenerationStatus> generationStatusMap = new ConcurrentHashMap<>();
    private final Map<String, Plan> generationResultMap = new ConcurrentHashMap<>();

    private final ExecutorService executorService = Executors.newCachedThreadPool();
    private final AtomicInteger planGenerationAttempts = new AtomicInteger(0);
    private final AtomicInteger planGenerationSuccesses = new AtomicInteger(0);
    private final AtomicInteger planGenerationFailures = new AtomicInteger(0);
    private final AtomicInteger planGenerationRetries = new AtomicInteger(0);

    private final AtomicBoolean workerRunning = new AtomicBoolean(false);
    private Thread workerThread;

    public PlanAsyncService(PlanRepository planRepository,
                            MemberRepository memberRepository,
                            HistoryRepository historyRepository,
                            StatisticRepository statisticRepository,
                            PlanAsyncConfig planAsyncConfig,
                            RedisTemplate<String, Object> redisTemplate,
                            ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.memberRepository = memberRepository;
        this.historyRepository = historyRepository;
        this.statisticRepository = statisticRepository;
        this.planAsyncConfig = planAsyncConfig;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void startWorker() {
        workerRunning.set(true);
        workerThread = new Thread(this::processQueue);
        workerThread.setName("plan-async-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    @PreDestroy
    public void stopWorker() {
        workerRunning.set(false);
        if (workerThread != null) {
            workerThread.interrupt();
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
        }
    }

    private void processQueue() {
        while (workerRunning.get()) {
            try {
                Object taskJson = redisTemplate.opsForList().leftPop(
                        planAsyncConfig.getQueueName(),
                        planAsyncConfig.getWorkerInterval(),
                        TimeUnit.MILLISECONDS
                );

                if (taskJson != null) {
                    PlanGenerationTask task = deserializeTask((String) taskJson);
                    if (task != null) {
                        redisTemplate.opsForList().rightPush(
                                planAsyncConfig.getProcessingQueueName(),
                                taskJson
                        );

                        updateGenerationStatus(task.getGenerationId(), "PROCESSING", null, null);

                        CompletableFuture.runAsync(() -> {
                            try {
                                Member member = memberRepository.findByMemberId(task.getMemberId()).orElse(null);
                                if (member == null) {
                                    updateGenerationStatus(task.getGenerationId(), "FAILED", null, "会员不存在");
                                    removeFromProcessingQueue(taskJson);
                                    return;
                                }

                                Plan plan = generatePlanWithRetry(task.getRequest(), member);
                                generationResultMap.put(task.getGenerationId(), plan);
                                updateGenerationStatus(task.getGenerationId(), "COMPLETED", plan.getPlanId(), null);
                            } catch (Exception e) {
                                updateGenerationStatus(task.getGenerationId(), "FAILED", null, e.getMessage());
                            } finally {
                                removeFromProcessingQueue(taskJson);
                            }
                        }, executorService);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                try {
                    Thread.sleep(planAsyncConfig.getWorkerInterval());
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void removeFromProcessingQueue(Object taskJson) {
        try {
            redisTemplate.opsForList().remove(
                    planAsyncConfig.getProcessingQueueName(),
                    1,
                    taskJson
            );
        } catch (Exception e) {
        }
    }

    private PlanGenerationTask deserializeTask(String taskJson) {
        try {
            return objectMapper.readValue(taskJson, PlanGenerationTask.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private String serializeTask(PlanGenerationTask task) {
        try {
            return objectMapper.writeValueAsString(task);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化任务失败", e);
        }
    }

    private void updateGenerationStatus(String generationId, String status, String planId, String errorMessage) {
        PlanGenerationStatus genStatus = new PlanGenerationStatus(generationId, status, planId, errorMessage);
        generationStatusMap.put(generationId, genStatus);
    }

    public PlanGenerationResult createPlanAsync(PlanRequest request) {
        Member member = memberRepository.findByMemberId(request.getMemberId())
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));

        Optional<Plan> existingPlan = planRepository.findByMemberId(request.getMemberId());
        if (existingPlan.isPresent() && "in_progress".equals(existingPlan.get().getPlanStatus())) {
            throw new IllegalStateException("该会员已有进行中的健身计划");
        }

        String generationId = IdGenerator.generateUUID();

        PlanGenerationResult result = new PlanGenerationResult();
        result.setGenerationId(generationId);
        result.setMemberId(request.getMemberId());
        result.setStatus("PROCESSING");
        result.setMessage("健身计划正在生成中，请稍后查询");
        result.setSubmittedAt(Instant.now());

        updateGenerationStatus(generationId, "PROCESSING", null, null);

        PlanGenerationTask task = new PlanGenerationTask();
        task.setGenerationId(generationId);
        task.setMemberId(request.getMemberId());
        task.setRequest(request);
        task.setSubmittedAt(Instant.now());

        String taskJson = serializeTask(task);
        redisTemplate.opsForList().rightPush(planAsyncConfig.getQueueName(), taskJson);

        return result;
    }

    private Plan generatePlanWithRetry(PlanRequest request, Member member) {
        int attempt = 0;
        Exception lastException = null;

        while (attempt < planAsyncConfig.getMaxRetry()) {
            planGenerationAttempts.incrementAndGet();
            attempt++;

            try {
                Plan plan = new Plan();
                plan.setPlanId(IdGenerator.generatePlanId());
                plan.setMemberId(request.getMemberId());
                plan.setPlanType(request.getPlanType() != null ? request.getPlanType() : "general");
                plan.setPlanDuration(request.getPlanDuration() != null ? request.getPlanDuration() : 30);
                plan.setPlanTarget(request.getPlanTarget() != null ? request.getPlanTarget() : "保持健康");
                plan.setPlanProgress(0);
                plan.setPlanStatus("in_progress");
                plan.setCreatedAt(Instant.now());
                plan.setPlanContent(generatePlanContent(plan.getPlanType(), plan.getPlanDuration()));

                Plan savedPlan = planRepository.save(plan);
                planGenerationSuccesses.incrementAndGet();
                return savedPlan;

            } catch (Exception e) {
                lastException = e;
                planGenerationRetries.incrementAndGet();

                if (attempt >= planAsyncConfig.getMaxRetry()) {
                    planGenerationFailures.incrementAndGet();
                    throw new RuntimeException("计划生成失败，已达到最大重试次数", e);
                }

                try {
                    Thread.sleep(planAsyncConfig.getRetryDelay() * (long) attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("计划生成被中断", ie);
                }
            }
        }

        throw new RuntimeException("计划生成失败", lastException);
    }

    private String generatePlanContent(String planType, int duration) {
        StringBuilder content = new StringBuilder();
        content.append("健身计划类型: ").append(planType).append("\n");
        content.append("计划周期: ").append(duration).append("天\n");
        content.append("训练频率: 每周5-6次\n");
        content.append("每次训练时长: 45-60分钟\n");
        content.append("包含内容: 有氧运动、力量训练、柔韧性练习\n");
        return content.toString();
    }

    public Plan getPlanByGenerationId(String generationId) {
        return generationResultMap.get(generationId);
    }

    public PlanGenerationStatus getGenerationStatus(String generationId) {
        PlanGenerationStatus status = generationStatusMap.get(generationId);
        if (status == null) {
            return new PlanGenerationStatus(generationId, "NOT_FOUND", null, null);
        }
        return status;
    }

    public long getQueueSize() {
        Long size = redisTemplate.opsForList().size(planAsyncConfig.getQueueName());
        return size != null ? size : 0;
    }

    public long getProcessingQueueSize() {
        Long size = redisTemplate.opsForList().size(planAsyncConfig.getProcessingQueueName());
        return size != null ? size : 0;
    }

    public int getPlanGenerationAttempts() {
        return planGenerationAttempts.get();
    }

    public int getPlanGenerationSuccesses() {
        return planGenerationSuccesses.get();
    }

    public int getPlanGenerationFailures() {
        return planGenerationFailures.get();
    }

    public int getPlanGenerationRetries() {
        return planGenerationRetries.get();
    }

    public void resetStats() {
        planGenerationAttempts.set(0);
        planGenerationSuccesses.set(0);
        planGenerationFailures.set(0);
        planGenerationRetries.set(0);
        generationStatusMap.clear();
        generationResultMap.clear();
    }

    public PlanAsyncConfig getPlanAsyncConfig() {
        return planAsyncConfig;
    }

    public static class PlanGenerationTask {
        private String generationId;
        private String memberId;
        private PlanRequest request;
        private Instant submittedAt;

        public String getGenerationId() {
            return generationId;
        }

        public void setGenerationId(String generationId) {
            this.generationId = generationId;
        }

        public String getMemberId() {
            return memberId;
        }

        public void setMemberId(String memberId) {
            this.memberId = memberId;
        }

        public PlanRequest getRequest() {
            return request;
        }

        public void setRequest(PlanRequest request) {
            this.request = request;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }
    }

    public static class PlanGenerationResult {
        private String generationId;
        private String memberId;
        private String status;
        private String message;
        private Instant submittedAt;

        public String getGenerationId() {
            return generationId;
        }

        public void setGenerationId(String generationId) {
            this.generationId = generationId;
        }

        public String getMemberId() {
            return memberId;
        }

        public void setMemberId(String memberId) {
            this.memberId = memberId;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public Instant getSubmittedAt() {
            return submittedAt;
        }

        public void setSubmittedAt(Instant submittedAt) {
            this.submittedAt = submittedAt;
        }
    }

    public static class PlanGenerationStatus {
        private final String generationId;
        private final String status;
        private final String planId;
        private final String errorMessage;

        public PlanGenerationStatus(String generationId, String status, String planId, String errorMessage) {
            this.generationId = generationId;
            this.status = status;
            this.planId = planId;
            this.errorMessage = errorMessage;
        }

        public String getGenerationId() {
            return generationId;
        }

        public String getStatus() {
            return status;
        }

        public String getPlanId() {
            return planId;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
