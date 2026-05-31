package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.CoreProcessingService;
import com.tracetopology.common.exception.BaseException;
import com.tracetopology.common.exception.ValidationException;
import com.tracetopology.common.result.Result;
import com.tracetopology.core.config.ProcessingConfig;
import com.tracetopology.core.context.ProcessingContext;
import com.tracetopology.core.resource.ResourceManager;
import com.tracetopology.core.validation.ParamValidator;
import com.tracetopology.domain.entity.Entity;
import com.tracetopology.domain.entity.RunInstance;
import com.tracetopology.spi.event.EventPublisher;
import com.tracetopology.spi.metrics.MetricsCollector;
import com.tracetopology.spi.repository.EntityRepository;
import com.tracetopology.spi.transaction.TransactionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@RequiredArgsConstructor
public class CoreProcessingServiceImpl implements CoreProcessingService {

    private final EntityRepository entityRepository;
    private final EventPublisher eventPublisher;
    private final MetricsCollector metricsCollector;
    private final TransactionManager transactionManager;

    @Value("${app.processing.batch-size:1000}")
    private int defaultBatchSize = 1000;

    @Value("${app.processing.parallelism:4}")
    private int defaultParallelism = 4;

    @Value("${app.processing.timeout-seconds:300}")
    private int defaultTimeoutSeconds = 300;

    private final ExecutorService processingExecutor = new ThreadPoolExecutor(
            Runtime.getRuntime().availableProcessors(),
            Runtime.getRuntime().availableProcessors() * 2,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(10000),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private final Map<String, LongAdder> hotRuleCounter = new ConcurrentHashMap<>();
    private final Map<String, Function<Map<String, Object>, Object>> ruleOptimizer = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> process(String traceId, String namespace, Map<String, Object> payload,
                                       Map<String, Object> params) {
        ProcessingContext ctx = ProcessingContext.init(traceId);
        try {
            ctx.setPhase("validating");
            validateParams(params);

            ctx.setPhase("loadingConfig");
            ProcessingConfig config = loadConfig(namespace);

            ctx.setPhase("acquiringResource");
            ResourceManager resourceManager = new ResourceManager(config.getPoolSize());
            ResourceManager.Resource resource = acquireResource(resourceManager, config.getTimeout());

            try {
                ctx.setPhase("processingCore");
                Map<String, Object> result = processCoreOptimized(payload, config.getRules());

                ctx.setPhase("persistingResult");
                persistResult(result);

                ctx.setPhase("emittingEvent");
                emitEvent("task.completed", buildEvent(result));

                ctx.markCompleted();
                return Result.success(result).getData();
            } finally {
                releaseResource(resourceManager, resource);
            }
        } catch (ValidationException e) {
            log.warn("参数校验失败: traceId={}, error={}", traceId, e.getMessage());
            return Result.error(e.getCode(), e.getMessage()).getData();
        } catch (TimeoutException e) {
            log.error("处理超时: traceId={}", traceId, e);
            return Result.error(504, "上游服务响应超时").getData();
        } catch (Exception e) {
            log.error("处理异常: traceId={}", traceId, e);
            rollbackTransaction(ctx);
            return Result.error(500, "内部处理错误").getData();
        } finally {
            recordMetrics(ctx);
            ctx.cleanup();
        }
    }

    @Override
    public List<Map<String, Object>> processBatch(String traceId, String namespace,
                                                   List<Map<String, Object>> payloads,
                                                   Map<String, Object> params) {
        ProcessingContext ctx = ProcessingContext.init(traceId);
        long startTime = System.currentTimeMillis();

        try {
            ctx.setPhase("validating");
            ParamValidator.validateNotNull(payloads, "payloads");
            if (payloads.isEmpty()) {
                return Collections.emptyList();
            }

            ctx.setPhase("loadingConfig");
            ProcessingConfig config = loadConfig(namespace);

            ctx.setPhase("batching");
            int batchSize = params != null && params.containsKey("batchSize")
                    ? ((Number) params.get("batchSize")).intValue()
                    : defaultBatchSize;

            List<List<Map<String, Object>>> batches = partitionList(payloads, batchSize);
            log.info("[{}] 批处理开始: total={}, batches={}, batchSize={}",
                    traceId, payloads.size(), batches.size(), batchSize);

            int parallelism = params != null && params.containsKey("parallelism")
                    ? ((Number) params.get("parallelism")).intValue()
                    : Math.min(defaultParallelism, batches.size());

            ctx.setPhase("processingBatches");
            List<CompletableFuture<List<Map<String, Object>>>> futures = new ArrayList<>();
            Semaphore concurrencyLimiter = new Semaphore(parallelism);

            for (int i = 0; i < batches.size(); i++) {
                final int batchIndex = i;
                final List<Map<String, Object>> batch = batches.get(i);

                concurrencyLimiter.acquire();
                CompletableFuture<List<Map<String, Object>>> future = CompletableFuture
                        .supplyAsync(() -> processSingleBatch(batchIndex, batch, config), processingExecutor)
                        .whenComplete((result, ex) -> concurrencyLimiter.release());

                futures.add(future);
            }

            CompletableFuture<Void> allDone = CompletableFuture.allOf(
                    futures.toArray(new CompletableFuture[0]));

            try {
                allDone.get(defaultTimeoutSeconds, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                futures.forEach(f -> f.cancel(true));
                throw new TimeoutException("批处理超时");
            }

            ctx.setPhase("aggregatingResults");
            List<Map<String, Object>> allResults = new ArrayList<>();
            for (CompletableFuture<List<Map<String, Object>>> future : futures) {
                try {
                    allResults.addAll(future.get());
                } catch (Exception e) {
                    log.error("[{}] 批处理任务失败: error={}", traceId, e.getMessage(), e);
                }
            }

            ctx.markCompleted();
            long totalTime = System.currentTimeMillis() - startTime;
            log.info("[{}] 批处理完成: total={}, success={}, time={}ms, throughput={}/s",
                    traceId, payloads.size(), allResults.size(), totalTime,
                    payloads.size() * 1000 / Math.max(1, totalTime));

            return allResults;

        } catch (Exception e) {
            log.error("[{}] 批处理异常: error={}", traceId, e.getMessage(), e);
            rollbackTransaction(ctx);
            throw new BaseException("BATCH_PROCESS_FAILED", "批处理失败: " + e.getMessage());
        } finally {
            recordMetrics(ctx);
            ctx.cleanup();
        }
    }

    private List<Map<String, Object>> processSingleBatch(int batchIndex,
                                                          List<Map<String, Object>> batch,
                                                          ProcessingConfig config) {
        List<Map<String, Object>> results = new ArrayList<>(batch.size());
        Map<String, Object> rules = config.getRules();

        for (Map<String, Object> payload : batch) {
            try {
                Map<String, Object> result = processCoreOptimized(payload, rules);
                results.add(result);
            } catch (Exception e) {
                log.warn("批处理项失败: batchIndex={}, error={}", batchIndex, e.getMessage());
                Map<String, Object> failedResult = new HashMap<>();
                failedResult.put("failed", true);
                failedResult.put("error", e.getMessage());
                failedResult.put("originalPayload", payload);
                results.add(failedResult);
            }
        }

        if (!results.isEmpty()) {
            persistBatchResults(results);
        }

        return results;
    }

    private Map<String, Object> processCoreOptimized(Map<String, Object> payload, Map<String, Object> rules) {
        Map<String, Object> result = new HashMap<>();
        result.put("processed", true);
        result.put("originalPayload", payload);
        result.put("processedAt", System.currentTimeMillis());

        if (rules == null || rules.isEmpty()) {
            result.put("appliedRules", 0);
            return result;
        }

        int appliedCount = 0;
        for (Map.Entry<String, Object> rule : rules.entrySet()) {
            String ruleName = rule.getKey();
            Object ruleValue = rule.getValue();

            LongAdder counter = hotRuleCounter.computeIfAbsent(ruleName, k -> new LongAdder());
            counter.increment();

            Function<Map<String, Object>, Object> optimizer = ruleOptimizer.get(ruleName);
            if (optimizer != null) {
                Object optimizedResult = optimizer.apply(payload);
                result.put("rule_" + ruleName + "_result", optimizedResult);
            } else {
                applyRule(result, payload, ruleName, ruleValue);
            }
            appliedCount++;
        }
        result.put("appliedRules", appliedCount);

        return result;
    }

    private void persistBatchResults(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return;
        }

        transactionManager.executeInTransaction(() -> {
            List<Entity> entities = results.stream()
                    .filter(r -> !Boolean.TRUE.equals(r.get("failed")))
                    .map(r -> {
                        String entityType = (String) r.getOrDefault("type", "result");
                        @SuppressWarnings("unchecked")
                        Map<String, Object> attributes = (Map<String, Object>) r.getOrDefault("attributes", new HashMap<>());
                        Entity entity = Entity.create(entityType, attributes);
                        r.put("entityId", entity.getId());
                        return entity;
                    })
                    .collect(Collectors.toList());

            if (!entities.isEmpty()) {
                entityRepository.saveBatch(entities);
            }
            return null;
        });
    }

    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> batches = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            batches.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return batches;
    }

    public void registerRuleOptimizer(String ruleName, Function<Map<String, Object>, Object> optimizer) {
        ruleOptimizer.put(ruleName, optimizer);
        log.info("规则优化器已注册: ruleName={}", ruleName);
    }

    public Map<String, Long> getHotRules() {
        return hotRuleCounter.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().sum()
                ));
    }

    private void validateParams(Map<String, Object> params) {
        ParamValidator.validateParams(params, "requestId", "timestamp");
    }

    private ProcessingConfig loadConfig(String namespace) {
        Map<String, Object> configParams = entityRepository.findConfigParameters(namespace);
        return ProcessingConfig.fromMap(configParams);
    }

    private ResourceManager.Resource acquireResource(ResourceManager resourceManager, Duration timeout) throws TimeoutException {
        try {
            return resourceManager.acquire(timeout.toMillis());
        } catch (BaseException e) {
            if ("RESOURCE_TIMEOUT".equals(e.getCode())) {
                throw new TimeoutException(e.getMessage());
            }
            throw e;
        }
    }

    private void applyRule(Map<String, Object> result, Map<String, Object> payload, String ruleName, Object ruleValue) {
        result.put("rule_" + ruleName + "_applied", true);
    }

    private void persistResult(Map<String, Object> result) {
        transactionManager.executeInTransaction(() -> {
            String entityType = (String) result.getOrDefault("type", "result");
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) result.getOrDefault("attributes", new HashMap<>());
            Entity entity = Entity.create(entityType, attributes);
            entityRepository.save(entity);
            result.put("entityId", entity.getId());
            return null;
        });
    }

    private Map<String, Object> buildEvent(Map<String, Object> result) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "task.completed");
        event.put("entityId", result.get("entityId"));
        event.put("timestamp", System.currentTimeMillis());
        event.put("result", result);
        return event;
    }

    private void emitEvent(String eventType, Map<String, Object> eventData) {
        eventPublisher.publish(eventType, eventData);
    }

    private void releaseResource(ResourceManager resourceManager, ResourceManager.Resource resource) {
        resourceManager.release(resource);
    }

    private void rollbackTransaction(ProcessingContext ctx) {
        try {
            transactionManager.rollback();
            ctx.markRolledBack();
            log.warn("事务已回滚: traceId={}", ctx.getTraceId());
        } catch (Exception e) {
            log.error("回滚失败: traceId={}", ctx.getTraceId(), e);
        }
    }

    private void recordMetrics(ProcessingContext ctx) {
        Map<String, String> tags = new HashMap<>();
        tags.put("traceId", ctx.getTraceId());
        tags.put("phase", ctx.getPhase());
        tags.put("completed", String.valueOf(ctx.isCompleted()));
        tags.put("rolledBack", String.valueOf(ctx.isRolledBack()));

        metricsCollector.recordTimer("processing.duration", ctx.getElapsedTime(), tags);
        metricsCollector.incrementCounter("processing.count", tags);
        if (!ctx.isCompleted()) {
            metricsCollector.incrementCounter("processing.failed", tags);
        }
    }

    @Override
    public RunInstance startProcessing(String entityId, Map<String, Object> config) {
        ParamValidator.validateNotBlank(entityId, "entityId");
        RunInstance runInstance = RunInstance.create(entityId);
        runInstance.updateProgress("starting", 0.1);
        return entityRepository.saveRunInstance(runInstance);
    }

    @Override
    public RunInstance getProcessingStatus(String runId) {
        ParamValidator.validateNotBlank(runId, "runId");
        return entityRepository.findRunInstanceById(runId)
                .orElseThrow(() -> new BaseException("RUN_NOT_FOUND", "运行实例不存在: " + runId));
    }

    @Override
    public Entity createEntity(String type, Map<String, Object> attributes) {
        ParamValidator.validateNotBlank(type, "type");
        Entity entity = Entity.create(type, attributes);
        return transactionManager.executeInTransaction(() -> entityRepository.save(entity));
    }

    @Override
    public Entity getEntity(String entityId) {
        ParamValidator.validateNotBlank(entityId, "entityId");
        return entityRepository.findById(entityId)
                .orElseThrow(() -> new BaseException("ENTITY_NOT_FOUND", "实体不存在: " + entityId));
    }

    @Override
    public Entity updateEntity(String entityId, Map<String, Object> updates) {
        ParamValidator.validateNotBlank(entityId, "entityId");
        Entity entity = getEntity(entityId);
        if (updates.containsKey("status")) {
            entity.updateStatus((String) updates.get("status"));
        }
        if (updates.containsKey("attributes")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> attributes = (Map<String, Object>) updates.get("attributes");
            entity.updateAttributes(attributes);
        }
        return transactionManager.executeInTransaction(() -> entityRepository.save(entity));
    }

    @Override
    public void deleteEntity(String entityId) {
        ParamValidator.validateNotBlank(entityId, "entityId");
        transactionManager.executeInTransaction(() -> {
            entityRepository.deleteById(entityId);
            return null;
        });
    }

    @Override
    public void cancelProcessing(String runId) {
        ParamValidator.validateNotBlank(runId, "runId");
        RunInstance runInstance = getProcessingStatus(runId);
        runInstance.fail("用户取消");
        entityRepository.saveRunInstance(runInstance);
    }

    public void shutdown() {
        log.info("关闭核心处理线程池...");
        processingExecutor.shutdown();
        try {
            if (!processingExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                processingExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            processingExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("核心处理线程池已关闭");
    }
}
