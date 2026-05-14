package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.Operator;
import com.deviceops.service.operator.OperatorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/operators")
public class OperatorController {

    @Autowired
    private OperatorService operatorService;

    @PostMapping
    public ApiResponse<Operator> createOperator(@RequestParam String name,
                                                @RequestParam String type) {
        Operator operator = operatorService.createOperator(name, type);
        return ApiResponse.success(operator);
    }

    @GetMapping("/{operatorId}")
    public ApiResponse<Operator> getOperator(@PathVariable String operatorId) {
        Operator operator = operatorService.getOperator(operatorId);
        return ApiResponse.success(operator);
    }

    @GetMapping
    public ApiResponse<List<Operator>> getAllOperators() {
        return ApiResponse.success(operatorService.getAllOperators());
    }

    @GetMapping("/available")
    public ApiResponse<List<Operator>> getAvailableOperators() {
        return ApiResponse.success(operatorService.getAvailableOperators());
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Operator>> getOperatorsByType(@PathVariable String type) {
        return ApiResponse.success(operatorService.getOperatorsByType(type));
    }

    @PutMapping("/{operatorId}")
    public ApiResponse<Operator> updateOperator(@PathVariable String operatorId,
                                                @RequestParam(required = false) String name,
                                                @RequestParam(required = false) String type,
                                                @RequestParam(required = false) String status) {
        Operator operator = operatorService.updateOperator(operatorId, name, type, status);
        return ApiResponse.success(operator);
    }

    @PutMapping("/{operatorId}/release")
    public ApiResponse<Operator> releaseOperator(@PathVariable String operatorId) {
        Operator operator = operatorService.releaseOperator(operatorId);
        return ApiResponse.success(operator);
    }

    @DeleteMapping("/{operatorId}")
    public ApiResponse<Void> deleteOperator(@PathVariable String operatorId) {
        operatorService.deleteOperator(operatorId);
        return ApiResponse.success(null);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Long>> getOperatorCount() {
        Map<String, Long> count = new HashMap<>();
        count.put("total", operatorService.count());
        count.put("available", operatorService.countAvailable());
        return ApiResponse.success(count);
    }
}
