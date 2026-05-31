package com.chaoslab.modules.dns.callback;

import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class WebhookCallbackHandler {

    private final WebClient webClient = WebClient.builder().build();

    public void handleWebhook(DnsAsyncTask task, DnsResolveResponse response, Throwable error) {
        if (task.getCallbackUrl() == null || task.getCallbackUrl().isEmpty()) {
            return;
        }

        try {
            Map<String, Object> payload = buildCallbackPayload(task, response, error);

            webClient.post()
                    .uri(task.getCallbackUrl())
                    .headers(headers -> {
                        if (task.getCallbackHeaders() != null) {
                            task.getCallbackHeaders().forEach((k, v) -> headers.set(k, String.valueOf(v)));
                        }
                    })
                    .bodyValue(payload)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnSuccess(v -> log.info("Webhook callback sent successfully for task: {}", task.getTaskId()))
                    .doOnError(e -> log.warn("Webhook callback failed for task: {}, error: {}", task.getTaskId(), e.getMessage()))
                    .subscribe();

        } catch (Exception e) {
            log.error("Failed to build webhook callback for task: {}", task.getTaskId(), e);
        }
    }

    private Map<String, Object> buildCallbackPayload(DnsAsyncTask task, DnsResolveResponse response, Throwable error) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", task.getTaskId());
        payload.put("requestId", task.getRequestId());
        payload.put("domain", task.getDomain());
        payload.put("queryType", task.getQueryType());
        payload.put("status", task.getStatus());
        payload.put("timestamp", System.currentTimeMillis());

        if (error != null) {
            payload.put("success", false);
            payload.put("error", error.getMessage());
            payload.put("errorType", error.getClass().getSimpleName());
        } else {
            payload.put("success", true);
            Map<String, Object> result = new HashMap<>();
            result.put("answers", response.getAnswers());
            result.put("ttl", response.getTtl());
            result.put("upstreamId", response.getUpstreamId());
            result.put("resolvedAt", response.getResolvedAt());
            result.put("fromCache", response.isFromCache());
            payload.put("result", result);
        }

        if (task.getContext() != null && !task.getContext().isEmpty()) {
            payload.put("context", task.getContext());
        }

        return payload;
    }
}
