package com.solocoder.platform.core.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.core.model.DataRecord;
import com.solocoder.platform.core.model.StandardizationRule;
import com.solocoder.platform.core.model.TransformRule;
import com.solocoder.platform.core.pipeline.ProcessingPipeline;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/core")
@RequiredArgsConstructor
public class CoreProcessingController {

    private final ProcessingPipeline processingPipeline;

    @PostMapping("/process")
    public ApiResponse<List<DataRecord>> process(@RequestBody List<DataRecord> records,
                                                 @RequestParam(required = false) List<TransformRule> transformRules,
                                                 @RequestParam(required = false) List<StandardizationRule> standardizationRules) {
        List<TransformRule> rules = transformRules != null ? transformRules : List.of();
        List<StandardizationRule> sRules = standardizationRules != null ? standardizationRules : List.of();
        return ApiResponse.success(processingPipeline.process(records, rules, sRules));
    }

    @PostMapping("/transform")
    public ApiResponse<List<DataRecord>> transform(@RequestBody List<DataRecord> records,
                                                   @RequestBody List<TransformRule> rules) {
        return ApiResponse.success(new com.solocoder.platform.core.transformer.impl.DataTransformerImpl().transformBatch(records, rules));
    }

    @PostMapping("/standardize")
    public ApiResponse<List<DataRecord>> standardize(@RequestBody List<DataRecord> records,
                                                      @RequestBody List<StandardizationRule> rules) {
        return ApiResponse.success(new com.solocoder.platform.core.standardizer.impl.DataStandardizerImpl().standardizeBatch(records, rules));
    }
}
