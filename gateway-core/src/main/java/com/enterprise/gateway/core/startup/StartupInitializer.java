package com.enterprise.gateway.core.startup;

import com.enterprise.gateway.admin.service.PluginService;
import com.enterprise.gateway.admin.service.RateLimitService;
import com.enterprise.gateway.auth.rbac.RbacPermissionService;
import com.enterprise.gateway.routing.DynamicRouteService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class StartupInitializer implements ApplicationRunner {

    private final DynamicRouteService dynamicRouteService;
    private final RbacPermissionService rbacPermissionService;
    private final RateLimitService rateLimitService;
    private final PluginService pluginService;

    @Override
    public void run(ApplicationArguments args) {
        log.info("Starting gateway initial data loading...");

        try {
            dynamicRouteService.refreshAll();
            log.info("Routes loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load routes", e);
        }

        try {
            rbacPermissionService.refreshAll();
            log.info("RBAC permissions loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load RBAC permissions", e);
        }

        try {
            rateLimitService.refreshAll();
            log.info("Rate limit rules loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load rate limit rules", e);
        }

        try {
            pluginService.refreshAll();
            log.info("Plugins loaded successfully");
        } catch (Exception e) {
            log.error("Failed to load plugins", e);
        }

        log.info("Gateway initial data loading completed");
    }
}
