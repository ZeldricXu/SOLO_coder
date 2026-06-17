package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.core.ActionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * 冻结账户动作
 * 调用账户服务Webhook接口执行账户冻结操作
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FreezeAccountAction implements Action {

    private static final String ACTION_ID = "freeze_account";
    private static final String ACTION_NAME = "冻结账户动作";

    /**
     * 默认账户服务冻结接口地址
     */
    private static final String DEFAULT_WEBHOOK_URL = "http://account-service/api/accounts/freeze";

    /**
     * 默认连接超时时间（秒）
     */
    private static final long DEFAULT_TIMEOUT_SECONDS = 10;

    /**
     * 默认冻结类型
     */
    private static final String DEFAULT_FREEZE_TYPE = "RISK_FREEZE";

    private final WebClient.Builder webClientBuilder;

    @Override
    public String getActionId() {
        return ACTION_ID;
    }

    @Override
    public String getActionName() {
        return ACTION_NAME;
    }

    @Override
    public boolean execute(AlertEvent alertEvent, ActionContext context) {
        String entityId = alertEvent.getEntityId();
        String entityType = alertEvent.getEntityType();

        if (!"USER".equalsIgnoreCase(entityType) && !"ACCOUNT".equalsIgnoreCase(entityType)) {
            log.warn("[FreezeAccountAction] 实体类型不是账户/用户，跳过冻结，entityType={}, alertId={}",
                    entityType, alertEvent.getAlertId());
            return false;
        }

        String webhookUrl = context.getParameterOrDefault("webhook_url", DEFAULT_WEBHOOK_URL);
        Long timeoutSeconds = context.getParameterOrDefault("timeout_seconds", DEFAULT_TIMEOUT_SECONDS);
        String freezeType = context.getParameterOrDefault("freeze_type", DEFAULT_FREEZE_TYPE);
        String authToken = context.getParameter("auth_token");

        try {
            Map<String, Object> requestBody = buildRequestBody(entityId, freezeType, alertEvent);
            Boolean result = callAccountService(webhookUrl, authToken, requestBody, timeoutSeconds);
            if (Boolean.TRUE.equals(result)) {
                log.info("[FreezeAccountAction] 账户冻结成功, entityId={}, alertId={}", entityId, alertEvent.getAlertId());
                context.saveResult(ACTION_ID, true);
                return true;
            } else {
                log.warn("[FreezeAccountAction] 账户冻结返回失败, entityId={}, alertId={}", entityId, alertEvent.getAlertId());
                context.saveResult(ACTION_ID, false);
                return false;
            }
        } catch (Exception e) {
            log.error("[FreezeAccountAction] 账户冻结异常, entityId={}, alertId={}", entityId, alertEvent.getAlertId(), e);
            context.saveResult(ACTION_ID, false);
            return false;
        }
    }

    /**
     * 构建请求体
     */
    private Map<String, Object> buildRequestBody(String entityId, String freezeType, AlertEvent alertEvent) {
        Map<String, Object> body = new HashMap<>();
        body.put("account_id", entityId);
        body.put("freeze_type", freezeType);
        body.put("reason", alertEvent.getDescription());
        body.put("alert_id", alertEvent.getAlertId());
        body.put("rule_id", alertEvent.getRuleId());
        body.put("severity", alertEvent.getSeverity() != null ? alertEvent.getSeverity().name() : null);
        body.put("risk_score", alertEvent.getRiskScore());
        body.put("operator", "RISK_SYSTEM");
        body.put("freeze_at", System.currentTimeMillis());
        return body;
    }

    /**
     * 调用账户服务接口
     */
    private Boolean callAccountService(String url, String authToken, Map<String, Object> body, long timeoutSeconds) {
        WebClient.RequestHeadersSpec<?> requestSpec = webClientBuilder.build()
                .post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body);

        if (authToken != null && !authToken.isEmpty()) {
            requestSpec.header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
        }

        Map<String, Object> response = requestSpec.retrieve()
                .bodyToMono(Map.class)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .block();

        return response != null && Boolean.TRUE.equals(response.get("success"));
    }
}
