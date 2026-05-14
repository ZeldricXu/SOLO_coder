package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.config.CustomerTypeProperties;
import com.crm.service.CustomerTypeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/customer-types")
public class CustomerTypeController {

    @Autowired
    private CustomerTypeService customerTypeService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<CustomerTypeProperties.CustomerType>>> getAllTypes(
            @RequestParam(required = false, defaultValue = "true") boolean enabledOnly) {
        List<CustomerTypeProperties.CustomerType> types;
        if (enabledOnly) {
            types = customerTypeService.getEnabledTypes();
        } else {
            types = customerTypeService.getAllTypes();
        }
        return ResponseEntity.ok(ApiResponse.success(types));
    }

    @GetMapping("/{code}")
    public ResponseEntity<ApiResponse<CustomerTypeProperties.CustomerType>> getTypeByCode(@PathVariable String code) {
        Optional<CustomerTypeProperties.CustomerType> typeOpt = customerTypeService.getTypeByCode(code);
        if (typeOpt.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(typeOpt.get()));
        } else {
            return ResponseEntity.notFound().build();
        }
    }

    @GetMapping("/validate/{code}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> validateType(@PathVariable String code) {
        Map<String, Object> result = new HashMap<>();
        boolean isValid = customerTypeService.isValidType(code);
        result.put("code", code);
        result.put("valid", isValid);
        if (isValid) {
            result.put("name", customerTypeService.getTypeName(code));
        }
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/default")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getDefaultType() {
        Map<String, Object> result = new HashMap<>();
        String defaultCode = customerTypeService.getDefaultTypeCode();
        result.put("code", defaultCode);
        result.put("name", customerTypeService.getTypeName(defaultCode));
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/codes")
    public ResponseEntity<ApiResponse<List<String>>> getTypeCodes(
            @RequestParam(required = false, defaultValue = "true") boolean enabledOnly) {
        List<String> codes;
        if (enabledOnly) {
            codes = customerTypeService.getEnabledTypeCodes();
        } else {
            codes = customerTypeService.getAllTypeCodes();
        }
        return ResponseEntity.ok(ApiResponse.success(codes));
    }
}
