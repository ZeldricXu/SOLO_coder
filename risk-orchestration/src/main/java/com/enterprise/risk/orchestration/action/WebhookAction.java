package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.config.WebhookProperties;
import com.enterprise.risk.orchestration.core.ActionContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/**
 * Webhook通知动作
 * 通过WebClient POST到配置的URL，支持3次指数退避重试和HMAC签名验证
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookAction implements Action {

    private static final String ACTION_ID = "webhook";
    private static final String ACTION_NAME = "Webhook通知动作";

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNATURE_HEADER = "X-Risk-Signature";
    private static final String TIMESTAMP_HEADER = "X-Risk-Timestamp";
    private static final String EVENT_ID_HEADER = "X-Risk-Event-Id";

    private final WebClient.Builder webClientBuilder;
    private final WebhookProperties webhookProperties;
    private final ObjectMapper objectMapper;

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
        String webhookUrl = context.getParameter("webhook_url");
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("[WebhookAction] Webhook URL未配置，跳过通知，alertId={}", alertEvent.getAlertId());
            return false;
        }

        String secretKey = context.getParameterOrDefault("secret_key", webhookProperties.getSecretKey());
        int maxRetries = context.getParameterOrDefault("max_retries", webhookProperties.getMaxRetries());
        long timeoutSeconds = context.getParameterOrDefault("timeout_seconds", webhookProperties.getTimeoutSeconds());

        try {
            Map<String, Object> payload = buildPayload(alertEvent, context);
            String payloadJson = objectMapper.writeValueAsString(payload);
            String timestamp = String.valueOf(System.currentTimeMillis());
            String signature = generateSignature(payloadJson, timestamp, secretKey);

            Boolean result = sendWebhook(webhookUrl, payloadJson, signature, timestamp,
                    alertEvent.getAlertId(), maxRetries, timeoutSeconds);

            if (Boolean.TRUE.equals(result)) {
                log.info("[WebhookAction] Webhook通知成功, url={}, alertId={}", webhookUrl, alertEvent.getAlertId());
                context.saveResult(ACTION_ID, true);
                return true;
            } else {
                log.warn("[WebhookAction] Webhook通知失败，重试已耗尽, url={}, alertId={}", webhookUrl, alertEvent.getAlertId());
                context.saveResult(ACTION_ID, false);
                return false;
            }
        } catch (Exception e) {
            log.error("[WebhookAction] Webhook通知异常, url={}, alertId={}", webhookUrl, alertEvent.getAlertId(), e);
            context.saveResult(ACTION_ID, false);
            return false;
        }
    }

    /**
     * 构建Webhook请求体
     */
    private Map<String, Object> buildPayload(AlertEvent alertEvent, ActionContext context) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "RISK_ALERT");
        payload.put("event_id", alertEvent.getAlertId());
        payload.put("data", alertEvent);
        payload.put("action_context", Map.of(
                "execution_id", context.getExecutionId(),
                "action_index", context.getActionIndex(),
                "total_actions", context.getTotalActions()
        ));
        payload.put("sent_at", System.currentTimeMillis());
        return payload;
    }

    /**
     * 生成HMAC签名
     */
    private String generateSignature(String payload, String timestamp, String secretKey) throws Exception {
        if (secretKey == null || secretKey.isEmpty()) {
            return "";
        }
        String message = timestamp + "." + payload;
        Mac mac = Mac.getInstance(HMAC_ALGORITHM);
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
        mac.init(secretKeySpec);
        byte[] hmacBytes = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return "sha256=" + Base64.getEncoder().encodeToString(hmacBytes);
    }

    /**
     * 发送Webhook请求（带指数退避重试）
     */
    private Boolean sendWebhook(String url, String payloadJson, String signature, String timestamp,
                                 String eventId, int maxRetries, long timeoutSeconds) {
        try {
            Map<String, Object> response = webClientBuilder.build()
                    .post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(EVENT_ID_HEADER, eventId)
                    .header(TIMESTAMP_HEADER, timestamp)
                    .header(SIGNATURE_HEADER, signature)
                    .header(HttpHeaders.USER_AGENT, "Risk-Orchestration/1.0")
                    .bodyValue(payloadJson)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .retryWhen(Retry.backoff(maxRetries, Duration.ofMillis(webhookProperties.getBackoffInitialMillis()))
                            .maxBackoff(Duration.ofMillis(webhookProperties.getBackoffMaxMillis()))
                            .filter(throwable -> isRetryable(throwable))
                            .doBeforeRetry(signal -> log.warn("[WebhookAction] Webhook重试中, 第{}次, url={}, eventId={}",
                                    signal.totalRetries() + 1, url, eventId, signal.failure())))
                    .block();

            return response != null && isSuccessResponse(response);
        } catch (WebClientResponseException e) {
            log.error("[WebhookAction] Webhook返回错误状态码, status={}, body={}, url={}, eventId={}",
                    e.getStatusCode(), e.getResponseBodyAsString(), url, eventId);
            return false;
        } catch (Exception e) {
            log.error("[WebhookAction] Webhook发送异常, url={}, eventId={}", url, eventId, e);
            return false;
        }
    }

    /**
     * 判断是否可重试的异常
     */
    private boolean isRetryable(Throwable throwable) {
        if (throwable instanceof WebClientResponseException wcre) {
            int statusCode = wcre.getStatusCode().value();
            return statusCode >= 500 || statusCode == 429;
        }
        return true;
    }

    /**
     * 判断响应是否成功
     */
    @SuppressWarnings("unchecked")
    private boolean isSuccessResponse(Map<String, Object> response) {
        Object code = response.get("code");
        Object success = response.get("success");

        if (success != null) {
            return Boolean.TRUE.equals(success);
        }
        if (code != null) {
            return "0".equals(String.valueOf(code)) || "200".equals(String.valueOf(code));
        }
        return true;
    }
}
