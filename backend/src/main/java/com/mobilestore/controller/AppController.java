package com.mobilestore.controller;

import com.mobilestore.common.ApiResponse;
import com.mobilestore.dto.AppCreateRequest;
import com.mobilestore.dto.AppUpdateRequest;
import com.mobilestore.entity.App;
import com.mobilestore.service.AppService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/v1/apps")
@CrossOrigin(origins = "*")
public class AppController {

    @Autowired
    private AppService appService;

    @PostMapping("/create")
    public ApiResponse<Map<String, Object>> createApp(@Valid @RequestBody AppCreateRequest request) {
        Map<String, Object> result = appService.createApp(request);
        return ApiResponse.success("应用创建成功", result);
    }

    @GetMapping
    public ApiResponse<List<App>> getApps(
            @RequestParam(required = false) String developerId,
            @RequestParam(required = false) String status) {
        List<App> apps = appService.getApps(developerId, status);
        return ApiResponse.success(apps);
    }

    @GetMapping("/{appId}")
    public ApiResponse<App> getApp(@PathVariable String appId) {
        App app = appService.getApp(appId);
        return ApiResponse.success(app);
    }

    @PutMapping("/{appId}")
    public ApiResponse<App> updateApp(
            @PathVariable String appId,
            @RequestBody AppUpdateRequest request) {
        App app = appService.updateApp(appId, request);
        return ApiResponse.success("应用更新成功", app);
    }

    @DeleteMapping("/{appId}")
    public ApiResponse<Void> deleteApp(@PathVariable String appId) {
        appService.deleteApp(appId);
        return ApiResponse.success("应用删除成功", null);
    }
}
