package com.parking.platform.contract.controller;

import com.parking.platform.common.dto.ApiResponse;
import com.parking.platform.contract.service.ContractService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/mock/{contractId}/**")
public class MockServerController {

    private final ContractService contractService;

    public MockServerController(ContractService contractService) {
        this.contractService = contractService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> handleGet(
            @PathVariable String contractId,
            @RequestHeader(required = false) Map<String, String> headers,
            @RequestParam(required = false) Map<String, String> params) {
        Map<String, Object> response = contractService.generateMockResponse(contractId, getPath(), "GET");
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> handlePost(
            @PathVariable String contractId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> response = contractService.generateMockResponse(contractId, getPath(), "POST");
        return ResponseEntity.status(201).body(response);
    }

    @PutMapping
    public ResponseEntity<Map<String, Object>> handlePut(
            @PathVariable String contractId,
            @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> response = contractService.generateMockResponse(contractId, getPath(), "PUT");
        return ResponseEntity.ok(response);
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> handleDelete(@PathVariable String contractId) {
        Map<String, Object> response = contractService.generateMockResponse(contractId, getPath(), "DELETE");
        return ResponseEntity.ok(response);
    }

    private String getPath() {
        return "/mock-endpoint";
    }
}
