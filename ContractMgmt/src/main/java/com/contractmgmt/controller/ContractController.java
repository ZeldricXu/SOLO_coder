package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.dto.CreateContractRequest;
import com.contractmgmt.entity.Contract;
import com.contractmgmt.entity.ContractHistory;
import com.contractmgmt.service.ContractService;
import com.contractmgmt.service.HistoryService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractService contractService;
    private final HistoryService historyService;

    public ContractController(
            ContractService contractService,
            HistoryService historyService) {
        this.contractService = contractService;
        this.historyService = historyService;
    }

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createContract(
            @Valid @RequestBody CreateContractRequest request) {
        Map<String, Object> result = contractService.createContract(request);
        return ApiResponse.success(result);
    }

    @GetMapping("/{contractId}")
    public ApiResponse<Contract> getContract(@PathVariable String contractId) {
        Contract contract = contractService.getContract(contractId);
        return ApiResponse.success(contract);
    }

    @GetMapping
    public ApiResponse<List<Contract>> listContracts(
            @RequestParam(required = false) String status) {
        List<Contract> contracts = contractService.listContractsByStatus(status);
        return ApiResponse.success(contracts);
    }

    @GetMapping("/{contractId}/history")
    public ApiResponse<List<ContractHistory>> getContractHistory(@PathVariable String contractId) {
        List<ContractHistory> history = historyService.getContractHistory(contractId);
        return ApiResponse.success(history);
    }

    @GetMapping("/{contractId}/history/{type}")
    public ApiResponse<List<ContractHistory>> getHistoryByType(
            @PathVariable String contractId,
            @PathVariable String type) {
        List<ContractHistory> history = historyService.getHistoryByType(contractId, type);
        return ApiResponse.success(history);
    }
}
