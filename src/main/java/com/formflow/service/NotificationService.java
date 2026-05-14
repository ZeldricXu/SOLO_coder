package com.formflow.service;

import com.formflow.entity.ApprovalTask;
import com.formflow.entity.ProcessInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    @Value("${formflow.notification.email-enabled:false}")
    private boolean emailEnabled;

    @Value("${formflow.notification.sms-enabled:false}")
    private boolean smsEnabled;

    public void sendApprovalNotification(ApprovalTask task, ProcessInstance instance) {
        logger.info("发送审批通知: taskId={}, approverId={}, formTitle={}",
                task.getTaskId(), task.getApproverId(), task.getFormTitle());

        if (emailEnabled) {
            sendApprovalEmail(task, instance);
        }

        if (smsEnabled) {
            sendApprovalSms(task, instance);
        }

        logNotification("APPROVAL", task.getTaskId(), task.getApproverId(),
                "您有待处理的审批任务: " + task.getFormTitle());
    }

    public void sendProcessCompleteNotification(ProcessInstance instance, boolean approved) {
        String result = approved ? "已通过" : "已拒绝";
        logger.info("发送流程完成通知: instanceId={}, submitterId={}, result={}",
                instance.getInstanceId(), instance.getSubmitterId(), result);

        if (emailEnabled) {
            sendProcessCompleteEmail(instance, approved);
        }

        if (smsEnabled) {
            sendProcessCompleteSms(instance, approved);
        }

        logNotification("PROCESS_COMPLETE", instance.getInstanceId(), instance.getSubmitterId(),
                "您的申请" + result);
    }

    public void sendReminderNotification(ApprovalTask task) {
        logger.info("发送催办通知: taskId={}, approverId={}", task.getTaskId(), task.getApproverId());

        if (emailEnabled) {
            sendReminderEmail(task);
        }

        if (smsEnabled) {
            sendReminderSms(task);
        }

        logNotification("REMINDER", task.getTaskId(), task.getApproverId(),
                "请及时处理审批任务: " + task.getFormTitle());
    }

    public void sendTransferNotification(ApprovalTask task, String newApproverName) {
        logger.info("发送转交通知: taskId={}, from={}, to={}",
                task.getTaskId(), task.getApproverName(), newApproverName);

        logNotification("TRANSFER", task.getTaskId(), task.getApproverId(),
                "审批任务已转交给: " + newApproverName);
    }

    public void sendRejectionNotification(ProcessInstance instance, String comment) {
        logger.info("发送拒绝通知: instanceId={}, submitterId={}",
                instance.getInstanceId(), instance.getSubmitterId());

        if (emailEnabled) {
            sendRejectionEmail(instance, comment);
        }

        logNotification("REJECTION", instance.getInstanceId(), instance.getSubmitterId(),
                "您的申请被拒绝，原因: " + (comment != null ? comment : "未填写"));
    }

    private void sendApprovalEmail(ApprovalTask task, ProcessInstance instance) {
        String subject = "待审批通知 - " + task.getFormTitle();
        String content = buildApprovalEmailContent(task, instance);
        logger.info("发送审批邮件(模拟): to={}, subject={}", task.getApproverId(), subject);
    }

    private void sendApprovalSms(ApprovalTask task, ProcessInstance instance) {
        String content = "您有待处理的审批任务: " + task.getFormTitle() +
                "，提交人: " + task.getSubmitterName();
        logger.info("发送审批短信(模拟): to={}, content={}", task.getApproverId(), content);
    }

    private void sendProcessCompleteEmail(ProcessInstance instance, boolean approved) {
        String result = approved ? "已通过" : "已拒绝";
        String subject = "申请" + result + "通知";
        String content = buildProcessCompleteEmailContent(instance, approved);
        logger.info("发送流程完成邮件(模拟): to={}, subject={}", instance.getSubmitterId(), subject);
    }

    private void sendProcessCompleteSms(ProcessInstance instance, boolean approved) {
        String result = approved ? "已通过" : "已拒绝";
        String content = "您的申请" + result + "，流程实例ID: " + instance.getInstanceId();
        logger.info("发送流程完成短信(模拟): to={}, content={}", instance.getSubmitterId(), content);
    }

    private void sendReminderEmail(ApprovalTask task) {
        String subject = "审批催办提醒 - " + task.getFormTitle();
        String content = buildReminderEmailContent(task);
        logger.info("发送催办邮件(模拟): to={}, subject={}", task.getApproverId(), subject);
    }

    private void sendReminderSms(ApprovalTask task) {
        String content = "请及时处理审批任务: " + task.getFormTitle() +
                "，截止时间: " + task.getDueTime();
        logger.info("发送催办短信(模拟): to={}, content={}", task.getApproverId(), content);
    }

    private void sendRejectionEmail(ProcessInstance instance, String comment) {
        String subject = "申请被拒绝通知";
        String content = buildRejectionEmailContent(instance, comment);
        logger.info("发送拒绝邮件(模拟): to={}, subject={}", instance.getSubmitterId(), subject);
    }

    private String buildApprovalEmailContent(ApprovalTask task, ProcessInstance instance) {
        return String.format(
                "您好，%s：\n\n" +
                        "您有待处理的审批任务，请及时处理。\n\n" +
                        "任务详情：\n" +
                        "- 表单标题: %s\n" +
                        "- 审批节点: %s\n" +
                        "- 提交人: %s\n" +
                        "- 任务ID: %s\n" +
                        "- 截止时间: %s\n\n" +
                        "请登录系统进行审批。\n\n" +
                        "此邮件由系统自动发送，请勿直接回复。",
                task.getApproverName() != null ? task.getApproverName() : task.getApproverId(),
                task.getFormTitle(),
                task.getNodeName(),
                task.getSubmitterName(),
                task.getTaskId(),
                task.getDueTime() != null ? task.getDueTime().toString() : "无"
        );
    }

    private String buildProcessCompleteEmailContent(ProcessInstance instance, boolean approved) {
        String result = approved ? "已通过" : "已拒绝";
        return String.format(
                "您好，%s：\n\n" +
                        "您的申请%s。\n\n" +
                        "流程详情：\n" +
                        "- 流程实例ID: %s\n" +
                        "- 表单ID: %s\n" +
                        "- 流程ID: %s\n" +
                        "- 结束时间: %s\n\n" +
                        "请登录系统查看详情。\n\n" +
                        "此邮件由系统自动发送，请勿直接回复。",
                instance.getSubmitterName() != null ? instance.getSubmitterName() : instance.getSubmitterId(),
                result,
                instance.getInstanceId(),
                instance.getFormId(),
                instance.getProcessId(),
                instance.getEndTime() != null ? instance.getEndTime().toString() : "无"
        );
    }

    private String buildReminderEmailContent(ApprovalTask task) {
        return String.format(
                "您好，%s：\n\n" +
                        "温馨提醒：您还有未处理的审批任务，请及时处理。\n\n" +
                        "任务详情：\n" +
                        "- 表单标题: %s\n" +
                        "- 审批节点: %s\n" +
                        "- 提交人: %s\n" +
                        "- 任务ID: %s\n" +
                        "- 截止时间: %s\n\n" +
                        "请尽快登录系统进行审批。\n\n" +
                        "此邮件由系统自动发送，请勿直接回复。",
                task.getApproverName() != null ? task.getApproverName() : task.getApproverId(),
                task.getFormTitle(),
                task.getNodeName(),
                task.getSubmitterName(),
                task.getTaskId(),
                task.getDueTime() != null ? task.getDueTime().toString() : "无"
        );
    }

    private String buildRejectionEmailContent(ProcessInstance instance, String comment) {
        return String.format(
                "您好，%s：\n\n" +
                        "您的申请被拒绝。\n\n" +
                        "详情：\n" +
                        "- 流程实例ID: %s\n" +
                        "- 表单ID: %s\n" +
                        "- 拒绝原因: %s\n\n" +
                        "请登录系统查看详情并重新提交。\n\n" +
                        "此邮件由系统自动发送，请勿直接回复。",
                instance.getSubmitterName() != null ? instance.getSubmitterName() : instance.getSubmitterId(),
                instance.getInstanceId(),
                instance.getFormId(),
                comment != null ? comment : "未填写"
        );
    }

    private void logNotification(String type, String targetId, String receiverId, String content) {
        logger.info("通知日志 - 类型: {}, 目标ID: {}, 接收人: {}, 内容: {}",
                type, targetId, receiverId, content);
    }
}
