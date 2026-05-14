package com.houserental.controller;

import com.houserental.dto.ApiResponse;
import com.houserental.dto.ContractRenewDTO;
import com.houserental.entity.Contract;
import com.houserental.service.ContractService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    @Autowired
    private ContractService contractService;

    @GetMapping("/{contractId}")
    public ApiResponse<Contract> getContractById(@PathVariable String contractId) {
        Contract contract = contractService.getContractById(contractId);
        return ApiResponse.success(contract);
    }

    @PostMapping("/renew")
    public ApiResponse<Contract> renewContract(@Valid @RequestBody ContractRenewDTO dto) {
        Contract contract = contractService.renewContract(dto);
        return ApiResponse.success(contract);
    }

    @PostMapping("/{contractId}/terminate")
    public ApiResponse<Contract> terminateContract(
            @PathVariable String contractId,
            @RequestParam(required = false) String reason) {
        Contract contract = contractService.terminateContract(contractId, reason);
        return ApiResponse.success(contract);
    }

    @PostMapping("/{contractId}/expire")
    public ApiResponse<Contract> expireContract(@PathVariable String contractId) {
        Contract contract = contractService.expireContract(contractId);
        return ApiResponse.success(contract);
    }

    @GetMapping("/list")
    public ApiResponse<List<Contract>> getAllContracts() {
        List<Contract> contracts = contractService.getAllContracts();
        return ApiResponse.success(contracts);
    }

    @GetMapping("/active")
    public ApiResponse<List<Contract>> getActiveContracts() {
        List<Contract> contracts = contractService.getActiveContracts();
        return ApiResponse.success(contracts);
    }

    @GetMapping("/expired")
    public ApiResponse<List<Contract>> getExpiredContracts() {
        List<Contract> contracts = contractService.getExpiredContracts();
        return ApiResponse.success(contracts);
    }

    @GetMapping("/house/{houseId}")
    public ApiResponse<List<Contract>> getContractsByHouse(@PathVariable String houseId) {
        List<Contract> contracts = contractService.getContractsByHouse(houseId);
        return ApiResponse.success(contracts);
    }

    @GetMapping("/tenant/{tenantId}")
    public ApiResponse<List<Contract>> getContractsByTenant(@PathVariable String tenantId) {
        List<Contract> contracts = contractService.getContractsByTenant(tenantId);
        return ApiResponse.success(contracts);
    }

    @GetMapping("/landlord/{landlordId}")
    public ApiResponse<List<Contract>> getContractsByLandlord(@PathVariable String landlordId) {
        List<Contract> contracts = contractService.getContractsByLandlord(landlordId);
        return ApiResponse.success(contracts);
    }

    @GetMapping("/expiring")
    public ApiResponse<List<Contract>> getExpiringContracts(
            @RequestParam(defaultValue = "30") int daysBefore) {
        List<Contract> contracts = contractService.getExpiringContracts(daysBefore);
        return ApiResponse.success(contracts);
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getContractStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", contractService.countTotalContracts());
        stats.put("active", contractService.countActiveContracts());
        stats.put("expired", contractService.countExpiredContracts());
        stats.put("renewed", contractService.countRenewedContracts());
        return ApiResponse.success(stats);
    }
}
