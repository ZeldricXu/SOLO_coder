package com.cicd.server.notification;

import com.cicd.common.enums.NotificationChannel;
import com.cicd.common.enums.PipelineStatus;
import com.cicd.server.entity.*;
import com.cicd.server.notification.channel.NotificationSender;
import com.cicd.server.repository.NotificationHistoryRepository;
import com.cicd.server.repository.NotificationTemplateRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationService {

    private final List<NotificationSender> senders;
    private final NotificationTemplateRepository templateRepository;
    private final NotificationHistoryRepository historyRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void sendPipelineNotification(PipelineExecution execution, PipelineStatus status) {
        try {
            Pipeline pipeline = execution.getPipeline();
            Project project = pipeline.getProject();

            List<NotificationTemplate> templates = templateRepository.findByProjectIdAndEventType(
                    project.getId(),
                    "PIPELINE_" + status.name()
            );

            if (templates.isEmpty()) {
                templates = templateRepository.findByProjectIdIsNullAndEventType(
                        "PIPELINE_" + status.name()
                );
            }

            for (NotificationTemplate template : templates) {
                if (!template.getEnabled()) continue;

                String title = buildTitle(template.getTitleTemplate(), execution, status);
                String content = buildContent(template.getContentTemplate(), execution, status);

                Map<String, Object> extra = new HashMap<>();
                if (template.getExtraConfig() != null && !template.getExtraConfig().isEmpty()) {
                    extra = objectMapper.readValue(template.getExtraConfig(),
                            new TypeReference<Map<String, Object>>() {});
                }

                List<String> channels = parseChannels(template.getChannels());
                List<String> targets = parseTargets(template.getTargets(), execution);

                for (String channelStr : channels) {
                    try {
                        NotificationChannel channel = NotificationChannel.valueOf(channelStr.toUpperCase());
                        NotificationSender sender = findSender(channel);
                        if (sender == null) continue;

                        for (String target : targets) {
                            boolean success = sender.send(target, title, content, extra);
                            saveHistory(template, channel, target, title, content, success, execution);
                        }
                    } catch (Exception e) {
                        log.error("Failed to send notification via {}", channelStr, e);
                        saveHistory(template, NotificationChannel.valueOf(channelStr.toUpperCase()),
                                null, title, content, false, execution);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to send pipeline notification", e);
        }
    }

    @Async
    public void sendApprovalNotification(Approval approval) {
        try {
            String title = "【审批待处理】" + approval.getTitle();
            StringBuilder content = new StringBuilder();
            content.append("**项目**: ").append(approval.getPipelineExecution().getPipeline().getProject().getName()).append("\n\n");
            content.append("**流水线**: ").append(approval.getPipelineExecution().getPipeline().getName()).append("\n\n");
            content.append("**执行编号**: #").append(approval.getPipelineExecution().getExecutionNumber()).append("\n\n");
            content.append("**环境**: ").append(approval.getEnvironment() != null ? approval.getEnvironment().getName() : "N/A").append("\n\n");
            content.append("**描述**: ").append(approval.getDescription()).append("\n\n");
            content.append("**审批模式**: ").append(approval.getApprovalMode().getDescription()).append("\n\n");
            content.append("**过期时间**: ").append(approval.getExpiresAt()).append("\n\n");

            List<String> approvers = parseApprovers(approval.getApproversJson());

            Map<String, Object> extra = new HashMap<>();
            List<Map<String, Object>> actions = new ArrayList<>();

            String baseUrl = "http://localhost:8080/api";
            actions.add(Map.of("title", "通过", "url", baseUrl + "/approvals/" + approval.getId() + "/approve", "type", "primary"));
            actions.add(Map.of("title", "拒绝", "url", baseUrl + "/approvals/" + approval.getId() + "/reject", "type", "danger"));
            extra.put("actions", actions);

            for (String approver : approvers) {
                for (NotificationSender sender : senders) {
                    try {
                        String target = resolveTarget(approver, sender.getChannelType());
                        if (target != null) {
                            boolean success = sender.send(target, title, content.toString(), extra);
                            log.info("Approval notification sent to {} via {}", approver, sender.getChannelType());
                        }
                    } catch (Exception e) {
                        log.error("Failed to send approval notification to {}", approver, e);
                    }
                }
            }

            approval.setNotificationSent(true);
        } catch (Exception e) {
            log.error("Failed to send approval notification", e);
        }
    }

    public void sendApprovalResultNotification(Approval approval, boolean approved, String approver, String comment) {
        try {
            String title = approved ? "【审批已通过】" : "【审批已拒绝】" + approval.getTitle();
            StringBuilder content = new StringBuilder();
            content.append("**审批人**: ").append(approver).append("\n\n");
            content.append("**意见**: ").append(comment != null ? comment : "无").append("\n\n");
            content.append("**审批时间**: ").append(LocalDateTime.now()).append("\n\n");

            List<String> approvers = parseApprovers(approval.getApproversJson());
            Map<String, Object> extra = new HashMap<>();

            for (String approverUser : approvers) {
                for (NotificationSender sender : senders) {
                    try {
                        String target = resolveTarget(approverUser, sender.getChannelType());
                        if (target != null) {
                            sender.send(target, title, content.toString(), extra);
                        }
                    } catch (Exception e) {
                        log.error("Failed to send approval result notification", e);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to send approval result notification", e);
        }
    }

    private String buildTitle(String template, PipelineExecution execution, PipelineStatus status) {
        String result = template;
        result = result.replace("${projectName}", execution.getPipeline().getProject().getName());
        result = result.replace("${pipelineName}", execution.getPipeline().getName());
        result = result.replace("${executionNumber}", String.valueOf(execution.getExecutionNumber()));
        result = result.replace("${status}", status.getDescription());
        result = result.replace("${branch}", execution.getBranchName() != null ? execution.getBranchName() : "N/A");
        return result;
    }

    private String buildContent(String template, PipelineExecution execution, PipelineStatus status) {
        StringBuilder content = new StringBuilder();

        content.append("**项目**: ").append(execution.getPipeline().getProject().getName()).append("\n\n");
        content.append("**流水线**: ").append(execution.getPipeline().getName()).append("\n\n");
        content.append("**执行编号**: #").append(execution.getExecutionNumber()).append("\n\n");
        content.append("**状态**: ").append(status.getDescription()).append("\n\n");
        content.append("**分支**: ").append(execution.getBranchName() != null ? execution.getBranchName() : "N/A").append("\n\n");
        content.append("**Commit**: ").append(execution.getCommitSha() != null ? execution.getCommitSha().substring(0, 7) : "N/A").append("\n\n");
        content.append("**触发人**: ").append(execution.getTriggeredBy() != null ? execution.getTriggeredBy() : "system").append("\n\n");
        content.append("**耗时**: ").append(execution.getDurationSeconds() != null ? execution.getDurationSeconds() + "s" : "N/A").append("\n\n");

        if (execution.getCommitMessage() != null) {
            content.append("**提交信息**: ").append(execution.getCommitMessage()).append("\n\n");
        }

        content.append("[查看详情](http://localhost:8080/#/pipelines/").append(execution.getPipeline().getId())
                .append("/executions/").append(execution.getId()).append(")");

        return content.toString();
    }

    private List<String> parseChannels(String channelsStr) {
        try {
            return objectMapper.readValue(channelsStr, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseTargets(String targetsStr, PipelineExecution execution) {
        try {
            List<String> targets = objectMapper.readValue(targetsStr, new TypeReference<List<String>>() {});
            List<String> resolved = new ArrayList<>();
            for (String target : targets) {
                if ("${submitter}".equals(target) && execution.getTriggeredBy() != null) {
                    resolved.add(execution.getTriggeredBy());
                } else {
                    resolved.add(target);
                }
            }
            return resolved;
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseApprovers(String approversJson) {
        try {
            return objectMapper.readValue(approversJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String resolveTarget(String user, NotificationChannel channel) {
        return user;
    }

    private NotificationSender findSender(NotificationChannel channel) {
        return senders.stream()
                .filter(s -> s.getChannelType() == channel)
                .findFirst()
                .orElse(null);
    }

    private void saveHistory(NotificationTemplate template, NotificationChannel channel, String target,
                             String title, String content, boolean success, PipelineExecution execution) {
        try {
            NotificationHistory history = new NotificationHistory();
            history.setTemplateId(template.getId());
            history.setChannel(channel);
            history.setTarget(target);
            history.setTitle(title);
            history.setContent(content);
            history.setSuccess(success);
            history.setPipelineExecutionId(execution != null ? execution.getId() : null);
            history.setSentAt(LocalDateTime.now());
            historyRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to save notification history", e);
        }
    }
}
