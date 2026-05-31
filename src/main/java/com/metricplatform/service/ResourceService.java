package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.metricplatform.entity.SysEntity;
import com.metricplatform.entity.SysRunInstance;
import com.metricplatform.mapper.SysEntityMapper;
import com.metricplatform.mapper.SysRunInstanceMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceService extends ServiceImpl<SysEntityMapper, SysEntity> {

    private final SysRunInstanceMapper runInstanceMapper;

    private final Map<String, String> processingResources = new ConcurrentHashMap<>();

    @Data
    @lombok.AllArgsConstructor
    public static class BatchResult {
        private String id;
        private String action;
        private String status;
        private String message;
    }

    @Transactional(rollbackFor = Exception.class)
    public SysEntity createResource(String type, Map<String, Object> config,
                                    Map<String, Object> labels, Map<String, Object> attributes) {
        SysEntity entity = new SysEntity();
        entity.setId("rsc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        entity.setType(type);
        entity.setStatus("provisioning");
        entity.setConfig(config);
        entity.setLabels(labels != null ? labels : new HashMap<>());
        entity.setAttributes(attributes != null ? attributes : new HashMap<>());
        entity.setCreatedAt(LocalDateTime.now());
        entity.setUpdatedAt(LocalDateTime.now());

        this.save(entity);

        createRunInstance(entity.getId(), "initializing", 0.0);

        processResourceAsync(entity.getId());

        log.info("已创建资源: {} (类型: {})", entity.getId(), type);
        return entity;
    }

    @Async("resourceExecutor")
    public void processResourceAsync(String entityId) {
        try {
            Thread.sleep(100);
            updateResourceStatus(entityId, "running");

            SysRunInstance instance = createRunInstance(entityId, "processing", 0.25);

            for (int i = 0; i < 4; i++) {
                Thread.sleep(200);
                double progress = 0.25 + (i + 1) * 0.1875;
                updateRunProgress(instance.getRunId(), progress,
                        i == 3 ? "completed" : "processing");
            }

            updateResourceStatus(entityId, "ready");
            log.info("资源处理完成: {}", entityId);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            updateResourceStatus(entityId, "failed");
            log.warn("资源处理被中断: {}", entityId);
        } catch (Exception e) {
            updateResourceStatus(entityId, "failed");
            log.error("资源处理失败: {}", entityId, e);
        } finally {
            processingResources.remove(entityId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SysRunInstance createRunInstance(String entityId, String phase, double progress) {
        SysRunInstance instance = new SysRunInstance();
        instance.setRunId("run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        instance.setEntityId(entityId);
        instance.setPhase(phase);
        instance.setProgress(progress);
        instance.setStartedAt(LocalDateTime.now());
        instance.setMetrics(new HashMap<>());
        instance.setContext(new HashMap<>());

        runInstanceMapper.insert(instance);
        return instance;
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateRunProgress(String runId, double progress, String phase) {
        SysRunInstance instance = runInstanceMapper.selectById(runId);
        if (instance != null) {
            instance.setProgress(progress);
            instance.setPhase(phase);
            if ("completed".equals(phase) || "failed".equals(phase)) {
                instance.setCompletedAt(LocalDateTime.now());
            }

            Map<String, Object> metrics = instance.getMetrics();
            if (metrics == null) {
                metrics = new HashMap<>();
            }
            metrics.put("lastUpdate", System.currentTimeMillis());
            metrics.put("progress", progress);
            instance.setMetrics(metrics);

            runInstanceMapper.updateById(instance);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void updateResourceStatus(String entityId, String status) {
        this.lambdaUpdate()
                .eq(SysEntity::getId, entityId)
                .set(SysEntity::getStatus, status)
                .set(SysEntity::getUpdatedAt, LocalDateTime.now())
                .update();
    }

    public Map<String, Object> getResourceStatus(String entityId) {
        SysEntity entity = this.getById(entityId);
        Map<String, Object> status = new HashMap<>();

        if (entity != null) {
            status.put("id", entity.getId());
            status.put("type", entity.getType());
            status.put("status", entity.getStatus());
            status.put("labels", entity.getLabels());

            List<SysRunInstance> runs = runInstanceMapper.selectList(
                    new LambdaQueryWrapper<SysRunInstance>()
                            .eq(SysRunInstance::getEntityId, entityId)
                            .orderByDesc(SysRunInstance::getStartedAt)
                            .last("LIMIT 5"));

            if (!runs.isEmpty()) {
                SysRunInstance latest = runs.get(0);
                status.put("phase", latest.getPhase());
                status.put("progress", latest.getProgress());
                status.put("startedAt", latest.getStartedAt());
                status.put("completedAt", latest.getCompletedAt());
                status.put("errorDetail", latest.getErrorDetail());
            }
        }

        return status;
    }

    @Transactional(rollbackFor = Exception.class)
    public List<BatchResult> batchOperation(List<Map<String, Object>> operations) {
        List<BatchResult> results = new ArrayList<>();

        for (Map<String, Object> op : operations) {
            String id = (String) op.get("id");
            String action = (String) op.get("action");

            try {
                switch (action.toLowerCase()) {
                    case "start" -> {
                        startResource(id);
                        results.add(new BatchResult(id, action, "success", "资源已启动"));
                    }
                    case "stop" -> {
                        stopResource(id);
                        results.add(new BatchResult(id, action, "success", "资源已停止"));
                    }
                    case "restart" -> {
                        restartResource(id);
                        results.add(new BatchResult(id, action, "success", "资源已重启"));
                    }
                    case "delete" -> {
                        deleteResource(id);
                        results.add(new BatchResult(id, action, "success", "资源已删除"));
                    }
                    default -> results.add(new BatchResult(id, action, "failed", "不支持的操作: " + action));
                }
            } catch (Exception e) {
                results.add(new BatchResult(id, action, "failed", e.getMessage()));
                log.error("批量操作失败: id={}, action={}", id, action, e);
            }
        }

        return results;
    }

    @Transactional(rollbackFor = Exception.class)
    public void startResource(String entityId) {
        SysEntity entity = this.getById(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("资源不存在: " + entityId);
        }
        if ("stopped".equals(entity.getStatus()) || "failed".equals(entity.getStatus())) {
            updateResourceStatus(entityId, "provisioning");
            processResourceAsync(entityId);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void stopResource(String entityId) {
        SysEntity entity = this.getById(entityId);
        if (entity == null) {
            throw new IllegalArgumentException("资源不存在: " + entityId);
        }
        updateResourceStatus(entityId, "stopped");

        List<SysRunInstance> runs = runInstanceMapper.selectList(
                new LambdaQueryWrapper<SysRunInstance>()
                        .eq(SysRunInstance::getEntityId, entityId)
                        .eq(SysRunInstance::getPhase, "processing")
                        .orderByDesc(SysRunInstance::getStartedAt)
                        .last("LIMIT 1"));

        for (SysRunInstance run : runs) {
            run.setPhase("cancelled");
            run.setCompletedAt(LocalDateTime.now());
            run.setErrorDetail("用户主动停止");
            runInstanceMapper.updateById(run);
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void restartResource(String entityId) {
        stopResource(entityId);
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        startResource(entityId);
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteResource(String entityId) {
        stopResource(entityId);
        runInstanceMapper.delete(new LambdaQueryWrapper<SysRunInstance>()
                .eq(SysRunInstance::getEntityId, entityId));
        return this.removeById(entityId);
    }

    public Page<SysEntity> listResources(String type, String status, Map<String, Object> labels,
                                         int page, int size) {
        LambdaQueryWrapper<SysEntity> wrapper = new LambdaQueryWrapper<>();

        if (type != null && !type.isEmpty()) {
            wrapper.eq(SysEntity::getType, type);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysEntity::getStatus, status);
        }
        if (labels != null && !labels.isEmpty()) {
            wrapper.apply("JSON_CONTAINS(labels, '" + mapToJson(labels) + "')");
        }

        wrapper.orderByDesc(SysEntity::getCreatedAt);
        return this.page(new Page<>(page, size), wrapper);
    }

    private String mapToJson(Map<String, Object> map) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            return "{}";
        }
    }

    public List<SysRunInstance> getRunHistory(String entityId, int limit) {
        return runInstanceMapper.selectList(
                new LambdaQueryWrapper<SysRunInstance>()
                        .eq(SysRunInstance::getEntityId, entityId)
                        .orderByDesc(SysRunInstance::getStartedAt)
                        .last("LIMIT " + Math.min(limit, 100)));
    }

    @Transactional(rollbackFor = Exception.class)
    public SysRunInstance failRun(String runId, String errorDetail) {
        SysRunInstance instance = runInstanceMapper.selectById(runId);
        if (instance != null) {
            instance.setPhase("failed");
            instance.setCompletedAt(LocalDateTime.now());
            instance.setErrorDetail(errorDetail);
            runInstanceMapper.updateById(instance);

            updateResourceStatus(instance.getEntityId(), "failed");
        }
        return instance;
    }

    public long countResources(String type, String status) {
        LambdaQueryWrapper<SysEntity> wrapper = new LambdaQueryWrapper<>();
        if (type != null && !type.isEmpty()) {
            wrapper.eq(SysEntity::getType, type);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(SysEntity::getStatus, status);
        }
        return this.count(wrapper);
    }
}
