package com.supplychain.contract.controller;

import com.supplychain.common.dto.ResponseResult;
import com.supplychain.common.entity.Contract;
import com.supplychain.common.enums.ContractStatus;
import com.supplychain.contract.service.ContractService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@Tag(name = "合同管理", description = "采购合同关联管理接口")
@RestController
@RequestMapping("/api/contracts")
@RequiredArgsConstructor
public class ContractController {

    private final ContractService contractService;

    @Operation(summary = "创建合同")
    @PostMapping
    public ResponseResult<Contract> createContract(@RequestBody Contract contract) {
        return ResponseResult.success(contractService.createContract(contract));
    }

    @Operation(summary = "获取合同详情")
    @GetMapping("/{contractId}")
    public ResponseResult<Contract> getContract(@PathVariable String contractId) {
        return ResponseResult.success(contractService.getContract(contractId));
    }

    @Operation(summary = "获取订单关联合同")
    @GetMapping("/order/{orderId}")
    public ResponseResult<Contract> getContractByOrder(@PathVariable String orderId) {
        return ResponseResult.success(contractService.getContractByOrder(orderId));
    }

    @Operation(summary = "获取合同列表")
    @GetMapping
    public ResponseResult<List<Contract>> listContracts(
            @RequestParam(required = false) String supplierId,
            @RequestParam(required = false) String status) {
        return ResponseResult.success(contractService.listContracts(supplierId, status));
    }

    @Operation(summary = "更新合同状态")
    @PostMapping("/{contractId}/status")
    public ResponseResult<Contract> updateStatus(
            @PathVariable String contractId,
            @RequestBody Map<String, String> request) {
        String statusCode = request.get("status");
        ContractStatus status = ContractStatus.valueOf(statusCode.toUpperCase());
        return ResponseResult.success(contractService.updateStatus(contractId, status));
    }

    @Operation(summary = "签署合同")
    @PostMapping("/{contractId}/sign")
    public ResponseResult<Contract> signContract(@PathVariable String contractId) {
        return ResponseResult.success(contractService.signContract(contractId));
    }

    @Operation(summary = "根据订单生成合同")
    @PostMapping("/generate")
    public ResponseResult<Contract> generateFromOrder(@RequestBody Map<String, Object> request) {
        String orderId = (String) request.get("orderId");
        String supplierId = (String) request.get("supplierId");
        BigDecimal amount = request.containsKey("amount") 
            ? new BigDecimal(request.get("amount").toString()) 
            : BigDecimal.ZERO;
        return ResponseResult.success(contractService.generateContractFromOrder(orderId, supplierId, amount));
    }
}
