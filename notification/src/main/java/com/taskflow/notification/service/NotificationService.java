package com.taskflow.notification.service;

import com.taskflow.common.exception.BusinessException;
import com.taskflow.common.utils.IdGenerator;
import com.taskflow.notification.model.NotificationRequest;
import com.taskflow.notification.model.NotificationResult;
import com.taskflow.notification.template.TemplateEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final List<NotificationChannel> channels;
    private final TemplateEngine templateEngine;
    private final Map<String, com.taskflow.notification.model.NotificationTemplate> templateCache = new HashMap<>();

    public Mono<NotificationResult> send(NotificationRequest request) {
        return Mono.fromCallable(() -> {
            long startTime = System.currentTimeMillis();
            String recordId = IdGenerator.generateId("notif");

            try {
                NotificationChannel channel = getChannel(request.getChannel());
                if (!channel.validate(request)) {
                    throw new BusinessException(400, "Invalid notification request");
                }

                String content = request.getContent();
                String subject = request.getSubject();

                if (request.getTemplateId() != null) {
                    com.taskflow.notification.model.NotificationTemplate template = getTemplate(request.getTemplateId());
                    if (template != null) {
                        Map<String, Object> variables = request.getVariables() != null ? request.getVariables() : new HashMap<>();
                        if (template.getDefaultValues() != null) {
                            template.getDefaultValues().forEach(variables::putIfAbsent);
                        }
                        content = templateEngine.render(template.getContent(), variables);
                        subject = templateEngine.renderSubject(template.getSubject(), variables);
                    }
                }

                boolean success = channel.send(request, content, subject);

                return NotificationResult.builder()
                        .recordId(recordId)
                        .templateId(request.getTemplateId())
                        .type(request.getType())
                        .channel(request.getChannel())
                        .receivers(request.getReceivers())
                        .status(success ? "sent" : "failed")
                        .sentAt(LocalDateTime.now())
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();

            } catch (Exception e) {
                log.error("Notification send failed", e);
                return NotificationResult.builder()
                        .recordId(recordId)
                        .templateId(request.getTemplateId())
                        .type(request.getType())
                        .channel(request.getChannel())
                        .receivers(request.getReceivers())
                        .status("failed")
                        .errorMessage(e.getMessage())
                        .sentAt(LocalDateTime.now())
                        .durationMs(System.currentTimeMillis() - startTime)
                        .build();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Async("notificationExecutor")
    public void sendAsync(NotificationRequest request) {
        send(request).subscribe(
                result -> log.info("Notification sent: {}", result.getRecordId()),
                error -> log.error("Notification send failed", error)
        );
    }

    private NotificationChannel getChannel(String channelName) {
        return channels.stream()
                .filter(c -> c.getChannelName().equalsIgnoreCase(channelName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(400, "Unknown notification channel: " + channelName));
    }

    private com.taskflow.notification.model.NotificationTemplate getTemplate(String templateId) {
        return templateCache.get(templateId);
    }

    public void registerTemplate(com.taskflow.notification.model.NotificationTemplate template) {
        templateCache.put(template.getTemplateId(), template);
        log.info("Notification template registered: {}", template.getTemplateId());
    }

    public List<String> getAvailableChannels() {
        return channels.stream()
                .map(NotificationChannel::getChannelName)
                .toList();
    }
}
