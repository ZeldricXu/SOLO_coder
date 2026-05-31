package com.parking.platform.contract.service;

import com.parking.platform.contract.entity.Contract;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class ContractService {

    private static final Logger log = LoggerFactory.getLogger(ContractService.class);

    private final Map<String, Contract> contractStore = new ConcurrentHashMap<>();

    public Contract create(Contract contract) {
        contract.setStatus("DRAFT");
        contractStore.put(contract.getId(), contract);
        log.info("Contract created: {} type: {}", contract.getName(), contract.getType());
        return contract;
    }

    public Contract get(String id) {
        return contractStore.get(id);
    }

    public List<Contract> list(String type, String status, Integer page, Integer size) {
        List<Contract> contracts = contractStore.values().stream()
                .filter(c -> type == null || type.equals(c.getType()))
                .filter(c -> status == null || status.equals(c.getStatus()))
                .sorted((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()))
                .collect(Collectors.toList());

        int pageNum = page != null ? page : 1;
        int sizeNum = size != null ? size : 20;
        int start = (pageNum - 1) * sizeNum;
        int end = Math.min(start + sizeNum, contracts.size());

        return start >= contracts.size() ? new ArrayList<>() : contracts.subList(start, end);
    }

    public Contract validate(String id) {
        Contract contract = get(id);
        if (contract == null) return null;

        Contract.ValidationResult result = new Contract.ValidationResult();
        result.setValidator("schema-validator");
        result.setValidatedAt(Instant.now());
        result.setErrors(new ArrayList<>());

        try {
            if ("openapi".equalsIgnoreCase(contract.getSchemaType())) {
                validateOpenApi(contract, result);
            } else if ("graphql".equalsIgnoreCase(contract.getSchemaType())) {
                validateGraphql(contract, result);
            } else {
                result.setErrors(List.of("Unknown schema type: " + contract.getSchemaType()));
                result.setSuccess(false);
            }
        } catch (Exception e) {
            result.setSuccess(false);
            result.setMessage(e.getMessage());
            result.getErrors().add(e.getMessage());
        }

        contract.getValidationResults().add(result);
        contract.setLastValidatedAt(Instant.now());
        contract.setStatus(result.isSuccess() ? "VALID" : "INVALID");

        log.info("Contract validated: {} success: {}", contract.getId(), result.isSuccess());
        return contract;
    }

    private void validateOpenApi(Contract contract, Contract.ValidationResult result) {
        String schema = contract.getSchema();
        if (schema == null || schema.isEmpty()) {
            result.setSuccess(false);
            result.getErrors().add("Schema is empty");
            return;
        }

        if (!schema.contains("openapi") && !schema.contains("swagger")) {
            result.setSuccess(false);
            result.getErrors().add("Invalid OpenAPI/Swagger format");
            return;
        }

        result.setSuccess(true);
        result.setMessage("OpenAPI schema is valid");
    }

    private void validateGraphql(Contract contract, Contract.ValidationResult result) {
        String schema = contract.getSchema();
        if (schema == null || schema.isEmpty()) {
            result.setSuccess(false);
            result.getErrors().add("Schema is empty");
            return;
        }

        if (!schema.contains("type Query")) {
            result.setSuccess(false);
            result.getErrors().add("GraphQL schema must contain Query type");
            return;
        }

        result.setSuccess(true);
        result.setMessage("GraphQL schema is valid");
    }

    public Contract enableMock(String id, Map<String, Object> config) {
        Contract contract = get(id);
        if (contract == null) return null;

        contract.setMockEnabled(true);
        if (config != null) {
            contract.getMockConfig().putAll(config);
        }
        contract.setMockEndpoint("/api/mock/" + contract.getId());

        log.info("Mock enabled for contract: {}", contract.getId());
        return contract;
    }

    public Contract disableMock(String id) {
        Contract contract = get(id);
        if (contract != null) {
            contract.setMockEnabled(false);
            contract.setMockEndpoint(null);
            log.info("Mock disabled for contract: {}", contract.getId());
        }
        return contract;
    }

    public Map<String, Object> generateMockResponse(String id, String path, String method) {
        Contract contract = get(id);
        Map<String, Object> response = new HashMap<>();
        response.put("message", "Mock response for " + method + " " + path);
        response.put("contractId", id);
        response.put("timestamp", Instant.now().toString());

        if (contract != null && contract.getMockConfig() != null) {
            response.putAll(contract.getMockConfig());
        }

        return response;
    }

    public boolean delete(String id) {
        Contract removed = contractStore.remove(id);
        if (removed != null) {
            log.info("Contract deleted: {}", id);
            return true;
        }
        return false;
    }

    public Map<String, Long> getStatistics() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total", (long) contractStore.size());
        stats.put("valid", contractStore.values().stream().filter(c -> "VALID".equals(c.getStatus())).count());
        stats.put("invalid", contractStore.values().stream().filter(c -> "INVALID".equals(c.getStatus())).count());
        stats.put("draft", contractStore.values().stream().filter(c -> "DRAFT".equals(c.getStatus())).count());
        stats.put("mockEnabled", contractStore.values().stream().filter(Contract::isMockEnabled).count());
        return stats;
    }
}
