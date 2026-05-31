package com.taskplatform.controller;

import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.dataaccess.DataMigrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/data")
@RequiredArgsConstructor
public class DataMigrationController {

    private final DataMigrationService dataMigrationService;

    @GetMapping("/migrations")
    public ApiResponse<Map<String, Object>> getMigrationStatus() {
        return ApiResponse.success(dataMigrationService.getMigrationStatus());
    }

    @PostMapping("/migrations/run")
    public ApiResponse<Map<String, Object>> runMigrations() {
        return ApiResponse.success(dataMigrationService.runMigrations());
    }

    @PostMapping("/migrations/baseline")
    public ApiResponse<Map<String, Object>> baseline(@RequestBody Map<String, String> request) {
        String version = request.getOrDefault("version", "1.0.0");
        return ApiResponse.success(dataMigrationService.baseline(version));
    }

    @PostMapping("/migrations/repair")
    public ApiResponse<Map<String, Object>> repair() {
        return ApiResponse.success(dataMigrationService.repair());
    }

    @GetMapping("/tables")
    public ApiResponse<List<String>> listTables() {
        return ApiResponse.success(dataMigrationService.listTables());
    }

    @GetMapping("/tables/{tableName}")
    public ApiResponse<Map<String, Object>> getTableInfo(@PathVariable String tableName) {
        return ApiResponse.success(dataMigrationService.getTableInfo(tableName));
    }

    @GetMapping("/export/{tableName}")
    public ApiResponse<Map<String, Object>> exportData(@PathVariable String tableName) {
        return ApiResponse.success(dataMigrationService.exportData(tableName));
    }

    @PostMapping("/import/{tableName}")
    public ApiResponse<Map<String, Object>> importData(
            @PathVariable String tableName,
            @RequestBody Map<String, Object> request) {
        List<Map<String, Object>> data = (List<Map<String, Object>>) request.get("data");
        return ApiResponse.success(dataMigrationService.importData(tableName, data));
    }
}
