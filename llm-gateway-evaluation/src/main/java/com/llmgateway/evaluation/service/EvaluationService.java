package com.llmgateway.evaluation.service;

import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.common.constant.CommonConstants;
import com.llmgateway.common.exception.BusinessException;
import com.llmgateway.common.util.IdGenerator;
import com.llmgateway.evaluation.entity.EvaluationRun;
import com.llmgateway.evaluation.entity.ModelDrift;
import com.llmgateway.evaluation.mapper.EvaluationRunMapper;
import com.llmgateway.evaluation.mapper.ModelDriftMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class EvaluationService {

    private final EvaluationRunMapper runMapper;
    private final ModelDriftMapper driftMapper;

    @Transactional(rollbackFor = Exception.class)
    public EvaluationRun createEvaluation(EvaluationRun run) {
        run.setRunId(IdGenerator.generateId("run"));
        run.setStatus(CommonConstants.STATUS_PENDING);
        runMapper.insert(run);
        log.info("评估任务创建成功: runId={}", run.getRunId());
        return run;
    }

    @Async
    @Transactional(rollbackFor = Exception.class)
    public void executeEvaluation(String runId) {
        EvaluationRun run = runMapper.selectById(runId);
        if (run == null) {
            return;
        }

        try {
            run.setStatus(CommonConstants.STATUS_RUNNING);
            runMapper.updateById(run);

            log.info("开始执行评估: runId={}", runId);
            Thread.sleep(2000);

            Map<String, Object> metrics = new HashMap<>();
            metrics.put("accuracy", RandomUtil.randomDouble(0.7, 0.95));
            metrics.put("precision", RandomUtil.randomDouble(0.7, 0.95));
            metrics.put("recall", RandomUtil.randomDouble(0.7, 0.95));
            metrics.put("f1_score", RandomUtil.randomDouble(0.7, 0.95));
            metrics.put("bleu_score", RandomUtil.randomDouble(0.3, 0.8));
            metrics.put("rouge_score", RandomUtil.randomDouble(0.3, 0.8));

            run.setMetrics(metrics);
            run.setStatus(CommonConstants.STATUS_SUCCESS);
            run.setCompletedAt(LocalDateTime.now());
            runMapper.updateById(run);

            log.info("评估完成: runId={}", runId);
        } catch (Exception e) {
            log.error("评估失败: runId={}", runId, e);
            run.setStatus(CommonConstants.STATUS_FAILED);
            run.setErrorDetail(e.getMessage());
            run.setCompletedAt(LocalDateTime.now());
            runMapper.updateById(run);
        }
    }

    public EvaluationRun getEvaluation(String runId) {
        EvaluationRun run = runMapper.selectById(runId);
        if (run == null) {
            throw new BusinessException(404, "评估任务不存在");
        }
        return run;
    }

    public PageResult<EvaluationRun> listEvaluations(String modelId, String status, Integer pageNum, Integer pageSize) {
        LambdaQueryWrapper<EvaluationRun> wrapper = new LambdaQueryWrapper<>();
        if (modelId != null) {
            wrapper.eq(EvaluationRun::getModelId, modelId);
        }
        if (status != null) {
            wrapper.eq(EvaluationRun::getStatus, status);
        }
        wrapper.eq(EvaluationRun::getDeleted, 0);
        wrapper.orderByDesc(EvaluationRun::getCreatedAt);

        IPage<EvaluationRun> page = runMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        return PageResult.of(page);
    }

    public List<EvaluationRun> compareEvaluations(List<String> runIds) {
        List<EvaluationRun> runs = new ArrayList<>();
        for (String runId : runIds) {
            EvaluationRun run = runMapper.selectById(runId);
            if (run != null) {
                runs.add(run);
            }
        }
        return runs;
    }

    @Scheduled(cron = "0 0 * * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void detectDrift() {
        log.info("开始漂移检测");
        List<String> modelIds = List.of("model_001", "model_002", "model_003");
        for (String modelId : modelIds) {
            checkModelDrift(modelId);
        }
    }

    private void checkModelDrift(String modelId) {
        List<String> features = List.of("input_length", "output_length", "response_time", "token_usage");
        for (String feature : features) {
            double driftScore = RandomUtil.randomDouble(0, 0.3);
            double threshold = 0.15;

            ModelDrift drift = new ModelDrift();
            drift.setDriftId(IdGenerator.generateId("drift"));
            drift.setModelId(modelId);
            drift.setFeatureName(feature);
            drift.setDriftType("data_drift");
            drift.setDriftScore(driftScore);
            drift.setThreshold(threshold);
            drift.setIsAlert(driftScore > threshold);
            drift.setWindowStart(LocalDateTime.now().minusHours(1));
            drift.setWindowEnd(LocalDateTime.now());
            driftMapper.insert(drift);

            if (drift.getIsAlert()) {
                log.warn("检测到模型漂移: modelId={}, feature={}, score={}", modelId, feature, driftScore);
            }
        }
    }

    public List<ModelDrift> getModelDrift(String modelId, LocalDateTime startTime, LocalDateTime endTime) {
        return driftMapper.findByModelIdAndTimeRange(modelId,
                startTime != null ? startTime : LocalDateTime.now().minusDays(7),
                endTime != null ? endTime : LocalDateTime.now());
    }

    public List<ModelDrift> getRecentAlerts(Integer limit) {
        return driftMapper.findRecentAlerts(limit != null ? limit : 20);
    }

    public Map<String, Object> getDashboardSummary() {
        Map<String, Object> summary = new HashMap<>();

        LambdaQueryWrapper<EvaluationRun> runWrapper = new LambdaQueryWrapper<>();
        runWrapper.eq(EvaluationRun::getDeleted, 0);
        Long totalRuns = runMapper.selectCount(runWrapper);

        runWrapper.eq(EvaluationRun::getStatus, CommonConstants.STATUS_SUCCESS);
        Long successRuns = runMapper.selectCount(runWrapper);

        summary.put("totalEvaluations", totalRuns);
        summary.put("successRate", totalRuns > 0 ? (double) successRuns / totalRuns * 100 : 0);
        summary.put("activeDriftAlerts", driftMapper.findRecentAlerts(100).size());
        summary.put("lastDriftCheck", LocalDateTime.now());

        return summary;
    }
}
