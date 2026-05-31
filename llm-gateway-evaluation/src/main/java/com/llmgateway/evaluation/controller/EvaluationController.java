package com.llmgateway.evaluation.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.evaluation.entity.EvaluationRun;
import com.llmgateway.evaluation.entity.ModelDrift;
import com.llmgateway.evaluation.service.EvaluationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
public class EvaluationController {

    private final EvaluationService evaluationService;

    @PostMapping("/runs")
    public R<EvaluationRun> createEvaluation(@Valid @RequestBody EvaluationRun run) {
        EvaluationRun created = evaluationService.createEvaluation(run);
        evaluationService.executeEvaluation(created.getRunId());
        return R.created(created);
    }

    @GetMapping("/runs/{runId}")
    public R<EvaluationRun> getEvaluation(@PathVariable String runId) {
        return R.success(evaluationService.getEvaluation(runId));
    }

    @GetMapping("/runs")
    public R<PageResult<EvaluationRun>> listEvaluations(
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(evaluationService.listEvaluations(modelId, status, pageNum, pageSize));
    }

    @PostMapping("/runs/compare")
    public R<List<EvaluationRun>> compareEvaluations(@RequestBody Map<String, List<String>> request) {
        List<String> runIds = request.get("runIds");
        return R.success(evaluationService.compareEvaluations(runIds));
    }

    @GetMapping("/drift")
    public R<List<ModelDrift>> getModelDrift(
            @RequestParam String modelId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return R.success(evaluationService.getModelDrift(modelId, startTime, endTime));
    }

    @GetMapping("/drift/alerts")
    public R<List<ModelDrift>> getRecentAlerts(@RequestParam(required = false, defaultValue = "20") Integer limit) {
        return R.success(evaluationService.getRecentAlerts(limit));
    }

    @GetMapping("/dashboard/summary")
    public R<Map<String, Object>> getDashboardSummary() {
        return R.success(evaluationService.getDashboardSummary());
    }
}
