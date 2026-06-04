package com.cicd.server.webhook;

import com.cicd.server.entity.WebhookEvent;
import com.cicd.server.repository.WebhookEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookHandler webhookHandler;
    private final WebhookEventRepository eventRepository;

    @PostMapping("/gitlab/{projectId}")
    public ResponseEntity<String> handleGitLabWebhook(
            @PathVariable Long projectId,
            @RequestHeader("X-Gitlab-Event") String eventType,
            @RequestHeader(value = "X-Gitlab-Token", required = false) String token,
            @RequestBody String payload,
            HttpServletRequest request) {

        log.info("Received GitLab webhook: {} for project {}", eventType, projectId);

        WebhookEvent event = saveEvent(projectId, "GITLAB", eventType, payload, request);

        try {
            webhookHandler.processGitLabWebhook(projectId, eventType, token, payload, event);
            return ResponseEntity.ok("Webhook processed");
        } catch (Exception e) {
            log.error("Failed to process GitLab webhook", e);
            event.setErrorMessage(e.getMessage());
            eventRepository.save(event);
            return ResponseEntity.internalServerError().body("Failed to process webhook: " + e.getMessage());
        }
    }

    @PostMapping("/github/{projectId}")
    public ResponseEntity<String> handleGitHubWebhook(
            @PathVariable Long projectId,
            @RequestHeader("X-GitHub-Event") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody String payload,
            HttpServletRequest request) {

        log.info("Received GitHub webhook: {} for project {}", eventType, projectId);

        WebhookEvent event = saveEvent(projectId, "GITHUB", eventType, payload, request);

        try {
            webhookHandler.processGitHubWebhook(projectId, eventType, signature, payload, event);
            return ResponseEntity.ok("Webhook processed");
        } catch (Exception e) {
            log.error("Failed to process GitHub webhook", e);
            event.setErrorMessage(e.getMessage());
            eventRepository.save(event);
            return ResponseEntity.internalServerError().body("Failed to process webhook: " + e.getMessage());
        }
    }

    private WebhookEvent saveEvent(Long projectId, String provider, String eventType, String payload, HttpServletRequest request) {
        WebhookEvent event = new WebhookEvent();
        event.setGitProvider(provider);
        event.setEventType(eventType);
        event.setProjectId(projectId);
        event.setPayload(payload);
        event.setHeadersJson(serializeHeaders(request));
        event.setProcessed(false);
        return eventRepository.save(event);
    }

    private String serializeHeaders(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            headers.put(name, request.getHeader(name));
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(headers);
        } catch (Exception e) {
            return "{}";
        }
    }
}
