package com.parking.platform.contract.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.contract.entity.Contract;
import com.parking.platform.contract.service.ContractService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/contracts")
public class ContractController {

    private final ContractService contractService;

    public ContractController(ContractService contractService) {
        this.contractService = contractService;
    }

    @PostMapping
    public ApiResponse<Contract> create(@RequestBody Contract contract) {
        return ApiResponse.created(contractService.create(contract));
    }

    @GetMapping("/{id}")
    public ApiResponse<Contract> get(@PathVariable String id) {
        Contract contract = contractService.get(id);
        return contract != null ? ApiResponse.success(contract) : ApiResponse.notFound("Contract not found");
    }

    @GetMapping
    public ApiResponse<List<Contract>> list(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer size) {
        return ApiResponse.success(contractService.list(type, status, page, size));
    }

    @PostMapping("/{id}/validate")
    public ApiResponse<Contract> validate(@PathVariable String id) {
        Contract contract = contractService.validate(id);
        return contract != null ? ApiResponse.success(contract) : ApiResponse.notFound("Contract not found");
    }

    @PostMapping("/{id}/mock/enable")
    public ApiResponse<Contract> enableMock(@PathVariable String id, @RequestBody(required = false) Map<String, Object> config) {
        Contract contract = contractService.enableMock(id, config);
        return contract != null ? ApiResponse.success(contract) : ApiResponse.notFound("Contract not found");
    }

    @PostMapping("/{id}/mock/disable")
    public ApiResponse<Contract> disableMock(@PathVariable String id) {
        Contract contract = contractService.disableMock(id);
        return contract != null ? ApiResponse.success(contract) : ApiResponse.notFound("Contract not found");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        boolean deleted = contractService.delete(id);
        return deleted ? ApiResponse.noContent() : ApiResponse.notFound("Contract not found");
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Long>> getStats() {
        return ApiResponse.success(contractService.getStatistics());
    }
}
