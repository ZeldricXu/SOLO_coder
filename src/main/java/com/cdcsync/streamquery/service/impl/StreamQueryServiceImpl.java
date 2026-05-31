package com.cdcsync.streamquery.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.cdcsync.common.exception.BusinessException;
import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.common.util.ValidationUtils;
import com.cdcsync.streamquery.domain.StreamQuery;
import com.cdcsync.streamquery.mapper.StreamQueryMapper;
import com.cdcsync.streamquery.plan.LogicalPlan;
import com.cdcsync.streamquery.plan.PhysicalPlan;
import com.cdcsync.streamquery.plan.PhysicalPlanGenerator;
import com.cdcsync.streamquery.plan.PlanOptimizer;
import com.cdcsync.streamquery.plan.SqlParser;
import com.cdcsync.streamquery.service.StreamQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class StreamQueryServiceImpl extends AbstractBaseService<StreamQuery, String, StreamQueryMapper>
        implements StreamQueryService {

    private static final int MAX_RETRY_ATTEMPTS = 3;

    public StreamQueryServiceImpl(StreamQueryMapper mapper) {
        super(mapper);
    }

    @Override
    protected void setId(StreamQuery entity, String id) {
        entity.setId(id);
    }

    @Override
    protected String getId(StreamQuery entity) {
        return entity.getId();
    }

    @Override
    @Transactional
    public StreamQuery parseSql(String sql) {
        ValidationUtils.validSqlLength(sql, "sql");

        log.info("Parsing SQL: {}", sql);
        LogicalPlan logicalPlan = SqlParser.parse(sql);
        String planJson = SqlParser.toJson(logicalPlan);

        StreamQuery streamQuery = new StreamQuery();
        streamQuery.setName("Query_" + UUID.randomUUID().toString().substring(0, 8));
        streamQuery.setSqlText(sql);
        streamQuery.setParsedPlanJson(planJson);
        streamQuery.setStatus("PARSED");
        streamQuery.setExecutionCount(0);

        return create(streamQuery);
    }

    @Override
    @Transactional
    @Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS)
    public StreamQuery optimizePlan(String id) {
        ValidationUtils.notBlank(id, "id");
        log.info("Optimizing plan for query: {}", id);

        StreamQuery streamQuery = findById(id);
        if (streamQuery == null) {
            throw new BusinessException("Query not found: " + id);
        }
        if (streamQuery.getParsedPlanJson() == null) {
            throw new BusinessException("Query has not been parsed yet");
        }
        if (!streamQuery.canTransitionTo("OPTIMIZED")) {
            throw new BusinessException(
                "Invalid state transition: cannot optimize from status " + streamQuery.getStatus()
            );
        }

        LogicalPlan parsedPlan = SqlParser.fromJson(streamQuery.getParsedPlanJson());
        LogicalPlan optimizedPlan = PlanOptimizer.optimize(parsedPlan);
        String optimizedJson = SqlParser.toJson(optimizedPlan);

        streamQuery.setOptimizedPlanJson(optimizedJson);
        streamQuery.setStatus("OPTIMIZED");

        return update(streamQuery);
    }

    @Override
    @Transactional
    @Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS)
    public StreamQuery generatePhysicalPlan(String id) {
        ValidationUtils.notBlank(id, "id");
        log.info("Generating physical plan for query: {}", id);

        StreamQuery streamQuery = findById(id);
        if (streamQuery == null) {
            throw new BusinessException("Query not found: " + id);
        }
        if (!streamQuery.canTransitionTo("GENERATED")) {
            throw new BusinessException(
                "Invalid state transition: cannot generate plan from status " + streamQuery.getStatus()
            );
        }

        String planJson = streamQuery.getOptimizedPlanJson() != null
                ? streamQuery.getOptimizedPlanJson()
                : streamQuery.getParsedPlanJson();

        if (planJson == null) {
            throw new BusinessException("Query has not been parsed or optimized yet");
        }

        LogicalPlan logicalPlan = SqlParser.fromJson(planJson);
        List<PhysicalPlan> physicalPlans = generatePhysicalPlans(logicalPlan);
        String physicalJson = PhysicalPlanGenerator.toJson(physicalPlans.get(0));

        streamQuery.setPhysicalPlanJson(physicalJson);
        streamQuery.setStatus("GENERATED");

        return update(streamQuery);
    }

    private List<PhysicalPlan> generatePhysicalPlans(LogicalPlan logicalPlan) {
        List<PhysicalPlan> plans = new ArrayList<>();
        generatePhysicalPlansRecursive(logicalPlan, plans);
        return plans;
    }

    private void generatePhysicalPlansRecursive(LogicalPlan logicalPlan, List<PhysicalPlan> plans) {
        for (LogicalPlan child : logicalPlan.getChildren()) {
            generatePhysicalPlansRecursive(child, plans);
        }
        PhysicalPlan physicalPlan = PhysicalPlanGenerator.generate(logicalPlan);
        plans.add(physicalPlan);
    }

    @Override
    @Transactional
    @Retryable(retryFor = OptimisticLockingFailureException.class, maxAttempts = MAX_RETRY_ATTEMPTS)
    public Object executeQuery(String id, Map<String, Object> params) {
        ValidationUtils.notBlank(id, "id");
        log.info("Executing query: {} with params: {}", id, params);

        StreamQuery streamQuery = findById(id);
        if (streamQuery == null) {
            throw new BusinessException("Query not found: " + id);
        }
        if (!streamQuery.canTransitionTo("EXECUTING")) {
            throw new BusinessException(
                "Invalid state transition: cannot execute from status " + streamQuery.getStatus()
            );
        }

        incrementExecutionCountAtomic(id);

        UpdateWrapper<StreamQuery> startWrapper = new UpdateWrapper<>();
        startWrapper.eq("id", id)
                .eq("version", streamQuery.getVersion())
                .set("last_executed_at", LocalDateTime.now())
                .set("status", "EXECUTING");
        int updated = mapper.update(null, startWrapper);
        if (updated == 0) {
            throw new OptimisticLockingFailureException("Concurrent modification detected");
        }

        Map<String, Object> result;
        try {
            result = new HashMap<>();
            result.put("queryId", id);
            result.put("sql", streamQuery.getSqlText());
            result.put("params", params);
            result.put("executionTime", LocalDateTime.now().toString());
            result.put("rows", List.of(
                    Map.of("id", 1, "name", "Sample Data 1"),
                    Map.of("id", 2, "name", "Sample Data 2")
            ));
            result.put("totalRows", 2);

            UpdateWrapper<StreamQuery> successWrapper = new UpdateWrapper<>();
            successWrapper.eq("id", id).set("status", "EXECUTED");
            mapper.update(null, successWrapper);

            return result;
        } catch (Exception e) {
            UpdateWrapper<StreamQuery> failWrapper = new UpdateWrapper<>();
            failWrapper.eq("id", id).set("status", "FAILED");
            mapper.update(null, failWrapper);
            throw new BusinessException("Query execution failed: " + e.getMessage(), e);
        }
    }

    private void incrementExecutionCountAtomic(String id) {
        UpdateWrapper<StreamQuery> wrapper = new UpdateWrapper<>();
        wrapper.eq("id", id)
                .setSql("execution_count = COALESCE(execution_count, 0) + 1");
        mapper.update(null, wrapper);
    }
}
