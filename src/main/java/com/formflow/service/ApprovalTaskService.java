package com.formflow.service;

import com.formflow.entity.ApprovalTask;
import com.formflow.enums.TaskStatus;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ApprovalTaskRepository;
import com.formflow.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ApprovalTaskService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalTaskService.class);

    @Autowired
    private ApprovalTaskRepository approvalTaskRepository;

    public ApprovalTask getTaskByTaskId(String taskId) {
        return approvalTaskRepository.findByTaskId(taskId)
                .orElseThrow(() -> new BusinessException(404, "审批任务不存在: " + taskId));
    }

    public List<ApprovalTask> getTasksByApproverId(String approverId) {
        return approvalTaskRepository.findByApproverIdOrderByAssignedTimeDesc(approverId);
    }

    public List<ApprovalTask> getPendingTasksByApproverId(String approverId) {
        return approvalTaskRepository.findByApproverIdAndTaskStatusOrderByAssignedTimeDesc(
                approverId, TaskStatus.PENDING);
    }

    public List<ApprovalTask> getTasksByInstanceId(String instanceId) {
        return approvalTaskRepository.findByInstanceId(instanceId);
    }

    public List<ApprovalTask> getTasksByFormId(String formId) {
        return approvalTaskRepository.findByFormId(formId);
    }

    public List<ApprovalTask> getPendingTasksByInstanceId(String instanceId) {
        return approvalTaskRepository.findByInstanceIdAndTaskStatus(instanceId, TaskStatus.PENDING);
    }

    @Transactional
    public ApprovalTask createApprovalTask(String instanceId, String nodeId, String nodeName,
                                           String formId, String templateId,
                                           String approverId, String approverName,
                                           String submitterId, String submitterName,
                                           String formTitle, LocalDateTime dueTime) {
        ApprovalTask task = new ApprovalTask();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setInstanceId(instanceId);
        task.setNodeId(nodeId);
        task.setNodeName(nodeName);
        task.setFormId(formId);
        task.setTemplateId(templateId);
        task.setApproverId(approverId);
        task.setApproverName(approverName);
        task.setSubmitterId(submitterId);
        task.setSubmitterName(submitterName);
        task.setFormTitle(formTitle);
        task.setTaskStatus(TaskStatus.PENDING);
        task.setDueTime(dueTime);
        task.setPriority(0);

        ApprovalTask saved = approvalTaskRepository.save(task);
        logger.info("创建审批任务成功: taskId={}, approverId={}", saved.getTaskId(), approverId);
        return saved;
    }

    @Transactional
    public ApprovalTask completeTask(String taskId, String approvalResult,
                                     String approvalComment,
                                     String approverId, String approverName) {
        ApprovalTask task = getTaskByTaskId(taskId);

        if (task.getTaskStatus() != TaskStatus.PENDING) {
            throw new BusinessException("任务已处理，状态: " + task.getTaskStatus());
        }

        task.setTaskStatus(TaskStatus.COMPLETED);
        task.setApprovalResult(approvalResult);
        task.setApprovalComment(approvalComment);
        task.setCompletedTime(LocalDateTime.now());

        if (approverId != null) {
            task.setApproverId(approverId);
        }
        if (approverName != null) {
            task.setApproverName(approverName);
        }

        ApprovalTask saved = approvalTaskRepository.save(task);
        logger.info("审批任务完成: taskId={}, result={}", taskId, approvalResult);
        return saved;
    }

    @Transactional
    public void cancelTask(String taskId) {
        ApprovalTask task = getTaskByTaskId(taskId);
        if (task.getTaskStatus() == TaskStatus.PENDING) {
            task.setTaskStatus(TaskStatus.CANCELED);
            approvalTaskRepository.save(task);
            logger.info("取消审批任务: taskId={}", taskId);
        }
    }

    @Transactional
    public void cancelPendingTasks(String instanceId) {
        List<ApprovalTask> tasks = getPendingTasksByInstanceId(instanceId);
        for (ApprovalTask task : tasks) {
            task.setTaskStatus(TaskStatus.CANCELED);
        }
        approvalTaskRepository.saveAll(tasks);
        logger.info("取消流程所有待处理任务: instanceId={}, count={}", instanceId, tasks.size());
    }

    @Transactional
    public void transferTask(String taskId, String newApproverId, String newApproverName,
                             String transferComment) {
        ApprovalTask task = getTaskByTaskId(taskId);

        if (task.getTaskStatus() != TaskStatus.PENDING) {
            throw new BusinessException("只能转交待处理的任务");
        }

        String oldApprover = task.getApproverId();
        task.setApproverId(newApproverId);
        task.setApproverName(newApproverName);

        if (task.getApprovalComment() != null && !task.getApprovalComment().isEmpty()) {
            task.setApprovalComment(task.getApprovalComment() + "; 转交原因: " + transferComment);
        } else {
            task.setApprovalComment("转交原因: " + transferComment);
        }

        approvalTaskRepository.save(task);
        logger.info("转交审批任务: taskId={}, from={}, to={}", taskId, oldApprover, newApproverId);
    }

    @Transactional
    public void delegateTask(String taskId, String delegateApproverId, String delegateApproverName) {
        ApprovalTask task = getTaskByTaskId(taskId);

        if (task.getTaskStatus() != TaskStatus.PENDING) {
            throw new BusinessException("只能委托待处理的任务");
        }

        ApprovalTask delegateTask = new ApprovalTask();
        delegateTask.setTaskId(IdGenerator.generateTaskId());
        delegateTask.setInstanceId(task.getInstanceId());
        delegateTask.setNodeId(task.getNodeId());
        delegateTask.setNodeName(task.getNodeName());
        delegateTask.setFormId(task.getFormId());
        delegateTask.setTemplateId(task.getTemplateId());
        delegateTask.setApproverId(delegateApproverId);
        delegateTask.setApproverName(delegateApproverName);
        delegateTask.setSubmitterId(task.getSubmitterId());
        delegateTask.setSubmitterName(task.getSubmitterName());
        delegateTask.setFormTitle(task.getFormTitle());
        delegateTask.setTaskStatus(TaskStatus.PENDING);
        delegateTask.setDueTime(task.getDueTime());
        delegateTask.setPriority(task.getPriority());

        approvalTaskRepository.save(delegateTask);
        logger.info("委托审批任务: taskId={}, delegateTaskId={}, delegator={}, delegate={}",
                taskId, delegateTask.getTaskId(), task.getApproverId(), delegateApproverId);
    }

    public Long countPendingTasksByApproverId(String approverId) {
        return approvalTaskRepository.countByApproverIdAndTaskStatus(approverId, TaskStatus.PENDING);
    }

    public Long countTotalTasksByApproverId(String approverId) {
        return approvalTaskRepository.countByApproverId(approverId);
    }
}
