package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.dto.ExecutionRequest;
import com.contractmgmt.entity.ExecutionRecord;
import com.contractmgmt.service.ExecutionService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contracts")
public class ExecutionController {

    private final ExecutionService executionService;

    public ExecutionController(ExecutionService executionService) {
        this.executionService = executionService;
    }

    @PostMapping("/execute")
    public ApiResponse<Map<String, Object>> recordExecution(
            @Valid @RequestBody ExecutionRequest request) {
        Map<String, Object> result = executionService.recordExecution(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{contractId}/executions")
    public ApiResponse<List<ExecutionRecord>> getExecutionHistory(@PathVariable String contractId) {
        List<ExecutionRecord> records = executionService.getExecutionHistory(contractId);
        return ApiResponse.success(records);
    }

    @GetMapping("/{contractId}/progress")
    public ApiResponse<Map<String, Object>> getCurrentProgress(@PathVariable String contractId) {
        Integer progress = executionService.getCurrentProgress(contractId);
        Map<String, Object> result = new HashMap<>();
        result.put("contract_id", contractId);
        result.put("progress", progress);
        return ApiResponse.success(result);
    }
}
