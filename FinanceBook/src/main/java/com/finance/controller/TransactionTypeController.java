package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.TransactionType;
import com.finance.service.TransactionTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/transaction-types")
@RequiredArgsConstructor
public class TransactionTypeController {

    private final TransactionTypeService transactionTypeService;

    @GetMapping
    public ApiResponse<List<TransactionType>> getAllTransactionTypes() {
        List<TransactionType> types = transactionTypeService.getAllTransactionTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/active")
    public ApiResponse<List<TransactionType>> getActiveTransactionTypes() {
        List<TransactionType> types = transactionTypeService.getActiveTransactionTypes();
        return ApiResponse.success(types);
    }

    @GetMapping("/direction/{direction}")
    public ApiResponse<List<TransactionType>> getTransactionTypesByDirection(@PathVariable String direction) {
        List<TransactionType> types = transactionTypeService.getTransactionTypesByDirection(direction);
        return ApiResponse.success(types);
    }

    @GetMapping("/{typeCode}")
    public ApiResponse<TransactionType> getTransactionType(@PathVariable String typeCode) {
        TransactionType type = transactionTypeService.getTransactionTypeByCode(typeCode);
        return ApiResponse.success(type);
    }

    @PostMapping
    public ApiResponse<TransactionType> createTransactionType(@RequestBody Map<String, Object> request) {
        String typeCode = (String) request.get("type_code");
        String typeName = (String) request.get("type_name");
        String typeDirection = (String) request.getOrDefault("type_direction", "expense");
        Boolean affectsBalance = (Boolean) request.getOrDefault("affects_balance", true);
        Boolean requiresCategory = (Boolean) request.getOrDefault("requires_category", true);
        String description = (String) request.get("type_description");

        TransactionType type = transactionTypeService.createTransactionType(
                typeCode, typeName, typeDirection, affectsBalance, requiresCategory, description);
        return ApiResponse.success(type);
    }

    @PutMapping("/{typeCode}")
    public ApiResponse<TransactionType> updateTransactionType(@PathVariable String typeCode, @RequestBody Map<String, Object> request) {
        String typeName = (String) request.get("type_name");
        String description = (String) request.get("type_description");
        String status = (String) request.get("type_status");

        TransactionType type = transactionTypeService.updateTransactionType(typeCode, typeName, description, status);
        return ApiResponse.success(type);
    }

    @PutMapping("/{typeCode}/activate")
    public ApiResponse<TransactionType> activateTransactionType(@PathVariable String typeCode) {
        TransactionType type = transactionTypeService.activateTransactionType(typeCode);
        return ApiResponse.success(type);
    }

    @PutMapping("/{typeCode}/deactivate")
    public ApiResponse<TransactionType> deactivateTransactionType(@PathVariable String typeCode) {
        TransactionType type = transactionTypeService.deactivateTransactionType(typeCode);
        return ApiResponse.success(type);
    }
}
