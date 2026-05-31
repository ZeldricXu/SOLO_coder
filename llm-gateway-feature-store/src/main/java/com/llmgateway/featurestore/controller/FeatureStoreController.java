package com.llmgateway.featurestore.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.featurestore.dto.BackfillJobCreateDTO;
import com.llmgateway.featurestore.dto.FeatureIngestDTO;
import com.llmgateway.featurestore.dto.FeatureRegisterDTO;
import com.llmgateway.featurestore.entity.Feature;
import com.llmgateway.featurestore.entity.FeatureBackfillJob;
import com.llmgateway.featurestore.entity.FeatureValue;
import com.llmgateway.featurestore.service.FeatureBackfillJobService;
import com.llmgateway.featurestore.service.FeatureService;
import com.llmgateway.featurestore.service.FeatureValueService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/feature-store")
@RequiredArgsConstructor
public class FeatureStoreController {

    private final FeatureService featureService;
    private final FeatureValueService featureValueService;
    private final FeatureBackfillJobService backfillJobService;

    @PostMapping("/features")
    public R<Feature> registerFeature(@Valid @RequestBody FeatureRegisterDTO dto) {
        return R.created(featureService.register(dto));
    }

    @GetMapping("/features/{featureId}")
    public R<Feature> getFeature(@PathVariable String featureId) {
        return R.success(featureService.getById(featureId));
    }

    @GetMapping("/features")
    public R<PageResult<Feature>> listFeatures(
            @RequestParam(required = false) String entity,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(featureService.list(entity, status, pageNum, pageSize));
    }

    @PutMapping("/features/{featureId}/status")
    public R<Feature> updateFeatureStatus(@PathVariable String featureId, @RequestParam String status) {
        return R.success(featureService.updateStatus(featureId, status));
    }

    @DeleteMapping("/features/{featureId}")
    public R<Void> deleteFeature(@PathVariable String featureId) {
        featureService.delete(featureId);
        return R.success();
    }

    @PostMapping("/values/ingest")
    public R<FeatureValue> ingestValue(@Valid @RequestBody FeatureIngestDTO dto) {
        return R.success(featureValueService.ingest(dto));
    }

    @PostMapping("/values/batch-ingest")
    public R<Void> batchIngest(@RequestBody List<FeatureIngestDTO> batch) {
        featureValueService.batchIngest(batch);
        return R.success();
    }

    @GetMapping("/values/latest")
    public R<FeatureValue> getLatestValue(
            @RequestParam String featureId,
            @RequestParam String entityKey) {
        return R.success(featureValueService.getLatest(featureId, entityKey));
    }

    @GetMapping("/values/range")
    public R<List<FeatureValue>> getValueRange(
            @RequestParam String featureId,
            @RequestParam String entityKey,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return R.success(featureValueService.getRange(featureId, entityKey, startTime, endTime));
    }

    @PostMapping("/backfill-jobs")
    public R<FeatureBackfillJob> createBackfillJob(@Valid @RequestBody BackfillJobCreateDTO dto) {
        FeatureBackfillJob job = backfillJobService.createJob(dto);
        backfillJobService.executeBackfill(job.getJobId());
        return R.created(job);
    }

    @GetMapping("/backfill-jobs/{jobId}")
    public R<FeatureBackfillJob> getBackfillJob(@PathVariable String jobId) {
        return R.success(backfillJobService.getJob(jobId));
    }

    @GetMapping("/backfill-jobs")
    public R<PageResult<FeatureBackfillJob>> listBackfillJobs(
            @RequestParam(required = false) String featureId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(backfillJobService.listJobs(featureId, status, pageNum, pageSize));
    }
}
