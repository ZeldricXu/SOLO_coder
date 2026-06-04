package com.cicd.server.pipeline;

import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.PipelineExecution;
import com.cicd.server.notification.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineNotificationService {

    private final NotificationService notificationService;

    public void onPipelineCompleted(PipelineExecution execution, boolean success) {
        try {
            PipelineStatus status = success ? PipelineStatus.SUCCESS : PipelineStatus.FAILED;
            notificationService.sendPipelineNotification(execution, status);
        } catch (Exception e) {
            log.error("Failed to send pipeline completion notification", e);
        }
    }
}
