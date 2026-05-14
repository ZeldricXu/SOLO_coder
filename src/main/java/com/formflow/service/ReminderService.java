package com.formflow.service;

import com.formflow.entity.ApprovalTask;
import com.formflow.entity.ProcessDefinition;
import com.formflow.entity.ProcessInstance;
import com.formflow.entity.ProcessNode;
import com.formflow.enums.TaskStatus;
import com.formflow.event.ApprovalEvents;
import com.formflow.repository.ApprovalTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ReminderService.class);

    @Autowired
    private ApprovalTaskRepository approvalTaskRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ProcessDefinitionService processDefinitionService;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${formflow.reminder.enabled:true}")
    private boolean reminderEnabled;

    @Value("${formflow.reminder.interval-hours:24}")
    private int defaultIntervalHours;

    @Value("${formflow.reminder.max-reminders:3}")
    private int defaultMaxReminders;

    @Value("${formflow.reminder.escalation.enabled:true}")
    private boolean defaultEscalationEnabled;

    @Value("${formflow.reminder.escalation.after-hours:48}")
    private int defaultEscalationAfterHours;

    private final Map<String, List<LocalDateTime>> reminderHistory = new ConcurrentHashMap<>();

    private final Map<String, Integer> reminderCountMap = new ConcurrentHashMap<>();

    private final Map<String, List<String>> escalationApprovers = new ConcurrentHashMap<>();

    @Scheduled(fixedRateString = "${formflow.reminder.check-interval-ms:3600000}")
    public void checkAndSendReminders() {
        if (!reminderEnabled) {
            logger.debug("催办功能已禁用");
            return;
        }

        logger.info("开始执行催办检查任务");

        List<ApprovalTask> pendingTasks = approvalTaskRepository.findAll().stream()
                .filter(task -> task.getTaskStatus() == TaskStatus.PENDING)
                .filter(task -> task.getDueTime() != null)
                .toList();

        int reminderSent = 0;
        int escalationsPerformed = 0;

        for (ApprovalTask task : pendingTasks) {
            try {
                ProcessNode nodeConfig = getNodeConfig(task);

                if (!isNodeReminderEnabled(nodeConfig)) {
                    continue;
                }

                if (shouldSendReminder(task, nodeConfig)) {
                    sendReminder(task, nodeConfig);
                    reminderSent++;
                }

                if (shouldEscalate(task, nodeConfig)) {
                    performEscalation(task, nodeConfig);
                    escalationsPerformed++;
                }
            } catch (Exception e) {
                logger.error("处理催办任务失败: taskId={}, error={}", task.getTaskId(), e.getMessage());
            }
        }

        logger.info("催办检查完成: 发送催办通知 {} 条, 执行升级 {} 次", reminderSent, escalationsPerformed);
    }

    private ProcessNode getNodeConfig(ApprovalTask task) {
        try {
            ProcessInstance instance = processInstanceRepository.findByInstanceId(task.getInstanceId())
                    .orElse(null);
            if (instance == null) {
                return null;
            }
            ProcessDefinition definition = processDefinitionService
                    .getProcessDefinition(instance.getProcessId());
            return processDefinitionService.getNodeById(definition, task.getNodeId());
        } catch (Exception e) {
            logger.debug("获取节点配置失败: taskId={}, error={}", task.getTaskId(), e.getMessage());
            return null;
        }
    }

    private boolean isNodeReminderEnabled(ProcessNode nodeConfig) {
        if (nodeConfig == null) {
            return true;
        }
        return nodeConfig.isReminderEnabled();
    }

    public boolean shouldSendReminder(ApprovalTask task, ProcessNode nodeConfig) {
        return shouldSendReminderInternal(task, nodeConfig);
    }

    private boolean shouldSendReminderInternal(ApprovalTask task, ProcessNode nodeConfig) {
        if (task.getTaskStatus() != TaskStatus.PENDING) {
            return false;
        }

        if (task.getDueTime() == null) {
            return false;
        }

        int intervalHours = getIntervalHours(nodeConfig);

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime dueTime = task.getDueTime();

        if (now.isBefore(dueTime)) {
            int triggerHours = getTriggerHoursBeforeDue(nodeConfig);
            Duration timeUntilDue = Duration.between(now, dueTime);
            if (timeUntilDue.toHours() > triggerHours) {
                return false;
            }
        }

        List<LocalDateTime> history = reminderHistory.getOrDefault(task.getTaskId(), new ArrayList<>());

        int currentCount = reminderCountMap.getOrDefault(task.getTaskId(), 0);
        if (currentCount >= getMaxReminders(nodeConfig)) {
            logger.debug("任务已达到最大催办次数: taskId={}, count={}", task.getTaskId(), currentCount);
            return false;
        }

        if (!history.isEmpty()) {
            LocalDateTime lastReminder = history.get(history.size() - 1);
            Duration sinceLastReminder = Duration.between(lastReminder, now);
            if (sinceLastReminder.toHours() < intervalHours) {
                logger.debug("距离上次催办不足间隔时间: taskId={}, hours={}",
                        task.getTaskId(), sinceLastReminder.toHours());
                return false;
            }
        }

        return true;
    }

    private int getTriggerHoursBeforeDue(ProcessNode nodeConfig) {
        if (nodeConfig != null && nodeConfig.getReminderConfig() != null
                && nodeConfig.getReminderConfig().getTriggerHoursBeforeDue() != null) {
            return nodeConfig.getReminderConfig().getTriggerHoursBeforeDue();
        }
        return defaultIntervalHours;
    }

    private int getIntervalHours(ProcessNode nodeConfig) {
        if (nodeConfig != null) {
            return nodeConfig.getReminderIntervalHours(defaultIntervalHours);
        }
        return defaultIntervalHours;
    }

    private int getMaxReminders(ProcessNode nodeConfig) {
        if (nodeConfig != null) {
            return nodeConfig.getMaxReminders(defaultMaxReminders);
        }
        return defaultMaxReminders;
    }

    private boolean isEscalationEnabled(ProcessNode nodeConfig) {
        if (nodeConfig != null) {
            return nodeConfig.isEscalationEnabled();
        }
        return defaultEscalationEnabled;
    }

    private int getEscalationAfterHours(ProcessNode nodeConfig) {
        if (nodeConfig != null) {
            return nodeConfig.getEscalationAfterHours(defaultEscalationAfterHours);
        }
        return defaultEscalationAfterHours;
    }

    public void sendReminder(ApprovalTask task) {
        ProcessNode nodeConfig = getNodeConfig(task);
        sendReminder(task, nodeConfig);
    }

    public void sendReminder(ApprovalTask task, ProcessNode nodeConfig) {
        logger.info("发送催办通知: taskId={}, approver={}, formTitle={}",
                task.getTaskId(), task.getApproverId(), task.getFormTitle());

        notificationService.sendReminderNotification(task);

        reminderHistory.computeIfAbsent(task.getTaskId(), k -> new ArrayList<>()).add(LocalDateTime.now());

        int currentCount = reminderCountMap.getOrDefault(task.getTaskId(), 0);
        reminderCountMap.put(task.getTaskId(), currentCount + 1);

        logger.info("催办通知已记录: taskId={}, count={}",
                task.getTaskId(), reminderCountMap.get(task.getTaskId()));

        ApprovalEvents.ReminderTriggeredEvent event = ApprovalEvents.ReminderTriggeredEvent.builder()
                .taskId(task.getTaskId())
                .instanceId(task.getInstanceId())
                .approverId(task.getApproverId())
                .formTitle(task.getFormTitle())
                .reminderCount(reminderCountMap.get(task.getTaskId()))
                .dueTime(task.getDueTime())
                .reminderTime(LocalDateTime.now())
                .message(buildReminderMessage(task, nodeConfig))
                .build();
        eventPublisher.publishEvent(event);
    }

    private String buildReminderMessage(ApprovalTask task, ProcessNode nodeConfig) {
        if (nodeConfig != null && nodeConfig.getReminderConfig() != null
                && nodeConfig.getReminderConfig().getReminderTemplate() != null) {
            String template = nodeConfig.getReminderConfig().getReminderTemplate();
            return template
                    .replace("{approver}", task.getApproverId())
                    .replace("{formTitle}", task.getFormTitle())
                    .replace("{dueTime}", task.getDueTime() != null ? task.getDueTime().toString() : "");
        }
        return String.format("请及时审批表单：%s", task.getFormTitle());
    }

    public boolean shouldEscalate(ApprovalTask task) {
        ProcessNode nodeConfig = getNodeConfig(task);
        return shouldEscalate(task, nodeConfig);
    }

    private boolean shouldEscalate(ApprovalTask task, ProcessNode nodeConfig) {
        if (!isEscalationEnabled(nodeConfig)) {
            return false;
        }

        if (task.getTaskStatus() != TaskStatus.PENDING) {
            return false;
        }

        if (task.getAssignedTime() == null) {
            return false;
        }

        LocalDateTime now = LocalDateTime.now();
        Duration sinceAssigned = Duration.between(task.getAssignedTime(), now);

        if (sinceAssigned.toHours() >= getEscalationAfterHours(nodeConfig)) {
            int reminderCount = reminderCountMap.getOrDefault(task.getTaskId(), 0);
            if (reminderCount >= getMaxReminders(nodeConfig)) {
                return true;
            }
        }

        return false;
    }

    public void performEscalation(ApprovalTask task) {
        ProcessNode nodeConfig = getNodeConfig(task);
        performEscalation(task, nodeConfig);
    }

    private void performEscalation(ApprovalTask task, ProcessNode nodeConfig) {
        logger.info("执行审批升级: taskId={}, approver={}", task.getTaskId(), task.getApproverId());

        List<String> escalateTo = getEscalationApprovers(task, nodeConfig);

        for (String approver : escalateTo) {
            ApprovalTask escalationTask = createEscalationTask(task, approver, nodeConfig);
            logger.info("创建升级审批任务: from={}, to={}", task.getApproverId(), approver);

            notificationService.sendReminderNotification(escalationTask);

            ApprovalEvents.ApprovalEscalatedEvent event = ApprovalEvents.ApprovalEscalatedEvent.builder()
                    .originalTaskId(task.getTaskId())
                    .escalationTaskId(escalationTask.getTaskId())
                    .instanceId(task.getInstanceId())
                    .originalApproverId(task.getApproverId())
                    .escalationApproverId(approver)
                    .escalationReason("超时自动升级")
                    .originalReminderCount(reminderCountMap.getOrDefault(task.getTaskId(), 0))
                    .escalationTime(LocalDateTime.now())
                    .build();
            eventPublisher.publishEvent(event);
        }

        logger.info("审批升级完成: taskId={}", task.getTaskId());
    }

    private List<String> getEscalationApprovers(ApprovalTask task, ProcessNode nodeConfig) {
        if (nodeConfig != null && nodeConfig.getReminderConfig() != null) {
            if (nodeConfig.getReminderConfig().getEscalationUserIds() != null
                    && !nodeConfig.getReminderConfig().getEscalationUserIds().isEmpty()) {
                return Arrays.asList(nodeConfig.getReminderConfig().getEscalationUserIds().split(","));
            }
            if (nodeConfig.getReminderConfig().getEscalationRole() != null
                    && !nodeConfig.getReminderConfig().getEscalationRole().isEmpty()) {
                return getApproversByRole(nodeConfig.getReminderConfig().getEscalationRole());
            }
        }

        return escalationApprovers.getOrDefault(task.getInstanceId(),
                Collections.singletonList("user_director_01"));
    }

    private List<String> getApproversByRole(String role) {
        List<String> approvers = new ArrayList<>();
        if (role == null || role.isEmpty()) {
            return approvers;
        }

        Map<String, List<String>> roleApprovers = new HashMap<>();
        roleApprovers.put("manager", Arrays.asList("user_manager_01", "user_manager_02"));
        roleApprovers.put("hr", Arrays.asList("user_hr_01", "user_hr_02"));
        roleApprovers.put("director", Arrays.asList("user_director_01"));
        roleApprovers.put("admin", Arrays.asList("user_admin_01"));

        List<String> roleUsers = roleApprovers.get(role.toLowerCase());
        if (roleUsers != null) {
            approvers.addAll(roleUsers);
        }

        return approvers;
    }

    private ApprovalTask createEscalationTask(ApprovalTask originalTask, String approverId, ProcessNode nodeConfig) {
        int intervalHours = getIntervalHours(nodeConfig);

        ApprovalTask task = new ApprovalTask();
        task.setTaskId("task_escalation_" + System.currentTimeMillis());
        task.setInstanceId(originalTask.getInstanceId());
        task.setNodeId(originalTask.getNodeId());
        task.setNodeName(originalTask.getNodeName() + " (升级)");
        task.setFormId(originalTask.getFormId());
        task.setTemplateId(originalTask.getTemplateId());
        task.setApproverId(approverId);
        task.setApproverName("升级审批人");
        task.setSubmitterId(originalTask.getSubmitterId());
        task.setSubmitterName(originalTask.getSubmitterName());
        task.setFormTitle(originalTask.getFormTitle() + " (升级审批)");
        task.setTaskStatus(TaskStatus.PENDING);
        task.setPriority(originalTask.getPriority() + 1);
        task.setAssignedTime(LocalDateTime.now());
        task.setDueTime(LocalDateTime.now().plusHours(intervalHours));
        return task;
    }

    public int getReminderCount(String taskId) {
        return reminderCountMap.getOrDefault(taskId, 0);
    }

    public List<LocalDateTime> getReminderHistory(String taskId) {
        return reminderHistory.getOrDefault(taskId, Collections.emptyList());
    }

    public void clearReminderHistory(String taskId) {
        reminderHistory.remove(taskId);
        reminderCountMap.remove(taskId);
    }

    public void clearAllHistory() {
        reminderHistory.clear();
        reminderCountMap.clear();
    }

    public void setEscalationApprovers(String instanceId, List<String> approvers) {
        escalationApprovers.put(instanceId, approvers);
    }

    public void setReminderEnabled(boolean enabled) {
        this.reminderEnabled = enabled;
    }

    public void setReminderIntervalHours(int hours) {
        this.defaultIntervalHours = hours;
    }

    public void setMaxReminders(int max) {
        this.defaultMaxReminders = max;
    }

    public void setEscalationEnabled(boolean enabled) {
        this.defaultEscalationEnabled = enabled;
    }

    public void setEscalationAfterHours(int hours) {
        this.defaultEscalationAfterHours = hours;
    }
}
