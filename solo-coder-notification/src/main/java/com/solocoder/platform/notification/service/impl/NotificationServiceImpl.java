package com.solocoder.platform.notification.service.impl;

import com.solocoder.platform.common.exception.BusinessException;
import com.solocoder.platform.notification.channel.ChannelProvider;
import com.solocoder.platform.notification.model.NotificationRequest;
import com.solocoder.platform.notification.model.NotificationResult;
import com.solocoder.platform.notification.model.NotificationTemplate;
import com.solocoder.platform.notification.monitor.NotificationMonitor;
import com.solocoder.platform.notification.service.NotificationService;
import com.solocoder.platform.notification.template.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class NotificationServiceImpl implements NotificationService {

    private final List<ChannelProvider> channelProviders;
    private final TemplateRenderer templateRenderer;
    private final NotificationMonitor monitor;
    private final com.solocoder.platform.notification.config.NotificationConfigManager configManager;

    public NotificationServiceImpl(List<ChannelProvider> channelProviders,
                                   TemplateRenderer templateRenderer,
                                   NotificationMonitor monitor,
                                   com.solocoder.platform.notification.config.NotificationConfigManager configManager) {
        this.channelProviders = channelProviders;
        this.templateRenderer = templateRenderer;
        this.monitor = monitor;
        this.configManager = configManager;
    }

    @Override
    public NotificationResult send(NotificationRequest request) {
        if (!configManager.isChannelEnabled(request.getChannel())) {
            log.warn("Channel is disabled: {}", request.getChannel());
            return NotificationResult.builder()
                    .channel(request.getChannel())
                    .recipient(request.getRecipient())
                    .status(NotificationResult.NotificationStatus.FAILED)
                    .errorMessage("Channel is disabled: " + request.getChannel())
                    .build();
        }

        long startTime = System.currentTimeMillis();
        ChannelProvider provider = findProvider(request.getChannel());

        String renderedContent;
        if (request.getTemplateId() != null && !request.getTemplateId().isBlank()) {
            long renderStart = System.currentTimeMillis();
            renderedContent = templateRenderer.render(request.getTemplateId(), request.getTemplateParams());
            monitor.recordTemplateRender(request.getTemplateId(), System.currentTimeMillis() - renderStart);
        } else if (request.getPlainContent() != null) {
            renderedContent = templateRenderer.renderContent(request.getPlainContent(), request.getTemplateParams());
        } else {
            throw new BusinessException("Either templateId or plainContent must be provided");
        }

        monitor.recordActiveChannel(request.getChannel());
        try {
            NotificationResult result = provider.send(request, renderedContent);
            long totalDuration = System.currentTimeMillis() - startTime;
            monitor.recordSend(request.getChannel(), totalDuration, result.getStatus() == NotificationResult.NotificationStatus.SENT);
            return result;
        } finally {
            monitor.deactivateChannel(request.getChannel());
        }
    }

    @Override
    public List<NotificationResult> sendBatch(List<NotificationRequest> requests) {
        List<NotificationResult> results = new ArrayList<>();
        for (NotificationRequest request : requests) {
            try {
                results.add(send(request));
            } catch (Exception e) {
                log.error("Batch send failed for recipient: {}", request.getRecipient(), e);
                results.add(NotificationResult.builder()
                        .channel(request.getChannel())
                        .recipient(request.getRecipient())
                        .status(NotificationResult.NotificationStatus.FAILED)
                        .errorMessage(e.getMessage())
                        .build());
            }
        }
        return results;
    }

    @Override
    public void registerTemplate(NotificationTemplate template) {
        templateRenderer.registerTemplate(template);
    }

    @Override
    public NotificationTemplate getTemplate(String templateId) {
        return templateRenderer.getTemplate(templateId);
    }

    @Override
    public java.util.Collection<NotificationTemplate> getAllTemplates() {
        return templateRenderer.getAllTemplates();
    }

    private ChannelProvider findProvider(String channelType) {
        return channelProviders.stream()
                .filter(p -> p.supports(channelType))
                .findFirst()
                .orElseThrow(() -> new BusinessException("No channel provider found for: " + channelType));
    }
}
