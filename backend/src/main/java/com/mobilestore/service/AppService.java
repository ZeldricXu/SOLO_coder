package com.mobilestore.service;

import com.mobilestore.dto.AppCreateRequest;
import com.mobilestore.dto.AppUpdateRequest;
import com.mobilestore.entity.App;
import com.mobilestore.repository.AppRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AppService {

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private NotificationService notificationService;

    @Transactional
    public Map<String, Object> createApp(AppCreateRequest request) {
        if (appRepository.existsByNameAndPlatform(request.getName(), request.getPlatform())) {
            throw new IllegalArgumentException("该平台下已存在同名应用");
        }

        App app = new App();
        app.setAppId("app_" + UUID.randomUUID().toString().substring(0, 8));
        app.setName(request.getName());
        app.setIcon(request.getIcon());
        app.setDescription(request.getDescription());
        app.setCategory(request.getCategory());
        app.setPlatform(request.getPlatform());
        app.setDeveloperId(request.getDeveloperId() != null ? request.getDeveloperId() : "dev_001");
        app.setStatus("draft");

        app = appRepository.save(app);

        notificationService.sendNotification(
                app.getDeveloperId(),
                "app_created",
                "应用创建成功",
                "您的应用 [" + app.getName() + "] 已成功创建，请提交版本进行发布",
                "app",
                app.getAppId()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("app_id", app.getAppId());
        result.put("name", app.getName());
        result.put("status", app.getStatus());
        return result;
    }

    public List<App> getApps(String developerId, String status) {
        if (developerId != null && status != null) {
            return appRepository.findByDeveloperIdAndStatus(developerId, status);
        } else if (developerId != null) {
            return appRepository.findByDeveloperId(developerId);
        } else if (status != null) {
            return appRepository.findByStatus(status);
        }
        return appRepository.findAll();
    }

    public App getApp(String appId) {
        return appRepository.findByAppId(appId)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));
    }

    @Transactional
    public App updateApp(String appId, AppUpdateRequest request) {
        App app = appRepository.findByAppId(appId)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));

        if (request.getName() != null) {
            if (!request.getName().equals(app.getName()) &&
                    appRepository.existsByNameAndPlatform(request.getName(), app.getPlatform())) {
                throw new IllegalArgumentException("该平台下已存在同名应用");
            }
            app.setName(request.getName());
        }
        if (request.getIcon() != null) app.setIcon(request.getIcon());
        if (request.getDescription() != null) app.setDescription(request.getDescription());
        if (request.getCategory() != null) app.setCategory(request.getCategory());
        if (request.getPlatform() != null) app.setPlatform(request.getPlatform());
        if (request.getStatus() != null) app.setStatus(request.getStatus());

        return appRepository.save(app);
    }

    @Transactional
    public void deleteApp(String appId) {
        App app = appRepository.findByAppId(appId)
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));
        appRepository.delete(app);
    }
}
