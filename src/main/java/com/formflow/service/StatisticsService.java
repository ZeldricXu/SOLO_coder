package com.formflow.service;

import com.formflow.dto.*;
import com.formflow.entity.ApprovalRecord;
import com.formflow.entity.ProcessInstance;
import com.formflow.enums.FormStatus;
import com.formflow.enums.ProcessInstanceStatus;
import com.formflow.enums.TaskStatus;
import com.formflow.repository.ApprovalRecordRepository;
import com.formflow.repository.ApprovalTaskRepository;
import com.formflow.repository.FormDataRepository;
import com.formflow.repository.ProcessInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class StatisticsService {

    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);

    @Autowired
    private FormDataRepository formDataRepository;

    @Autowired
    private ProcessInstanceRepository processInstanceRepository;

    @Autowired
    private ApprovalTaskRepository approvalTaskRepository;

    @Autowired
    private ApprovalRecordRepository approvalRecordRepository;

    public Map<String, Object> getOverallStatistics() {
        Map<String, Object> stats = new LinkedHashMap<>();

        Long totalForms = formDataRepository.count();
        stats.put("totalForms", totalForms);

        Long pendingForms = formDataRepository.countByStatus(FormStatus.PENDING_APPROVAL);
        stats.put("pendingForms", pendingForms);

        Long approvedForms = formDataRepository.countByStatus(FormStatus.APPROVED);
        stats.put("approvedForms", approvedForms);

        Long rejectedForms = formDataRepository.countByStatus(FormStatus.REJECTED);
        stats.put("rejectedForms", rejectedForms);

        Long totalProcesses = processInstanceRepository.count();
        stats.put("totalProcesses", totalProcesses);

        Long runningProcesses = processInstanceRepository.countByInstanceStatus(ProcessInstanceStatus.RUNNING);
        stats.put("runningProcesses", runningProcesses);

        Long completedProcesses = processInstanceRepository.countByInstanceStatus(ProcessInstanceStatus.COMPLETED);
        stats.put("completedProcesses", completedProcesses);

        Long pendingTasks = approvalTaskRepository.count();
        stats.put("totalTasks", pendingTasks);

        logger.info("获取总体统计数据完成");
        return stats;
    }

    public Map<String, Object> getFormStatistics(String templateId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        if (templateId != null && !templateId.isEmpty()) {
            Long totalForms = formDataRepository.countByTemplateId(templateId);
            stats.put("totalForms", totalForms);

            Long pendingForms = formDataRepository.countByTemplateIdAndStatus(templateId, FormStatus.PENDING_APPROVAL);
            stats.put("pendingForms", pendingForms);

            Long approvedForms = formDataRepository.countByTemplateIdAndStatus(templateId, FormStatus.APPROVED);
            stats.put("approvedForms", approvedForms);

            Long rejectedForms = formDataRepository.countByTemplateIdAndStatus(templateId, FormStatus.REJECTED);
            stats.put("rejectedForms", rejectedForms);

            if (totalForms > 0) {
                double approvalRate = (double) approvedForms / totalForms * 100;
                stats.put("approvalRate", String.format("%.2f%%", approvalRate));
            } else {
                stats.put("approvalRate", "0%");
            }
        } else {
            stats.putAll(getOverallStatistics());
        }

        logger.info("获取表单统计数据完成: templateId={}", templateId);
        return stats;
    }

    public Map<String, Object> getProcessStatistics(String processId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        if (processId != null && !processId.isEmpty()) {
            Long totalProcesses = processInstanceRepository.countByProcessId(processId);
            stats.put("totalProcesses", totalProcesses);

            Long runningProcesses = processInstanceRepository.countByProcessIdAndInstanceStatus(
                    processId, ProcessInstanceStatus.RUNNING);
            stats.put("runningProcesses", runningProcesses);

            Long completedProcesses = processInstanceRepository.countByProcessIdAndInstanceStatus(
                    processId, ProcessInstanceStatus.COMPLETED);
            stats.put("completedProcesses", completedProcesses);

            Long rejectedProcesses = processInstanceRepository.countByProcessIdAndInstanceStatus(
                    processId, ProcessInstanceStatus.REJECTED);
            stats.put("rejectedProcesses", rejectedProcesses);

            if (totalProcesses > 0) {
                double completionRate = (double) completedProcesses / totalProcesses * 100;
                stats.put("completionRate", String.format("%.2f%%", completionRate));
            } else {
                stats.put("completionRate", "0%");
            }
        } else {
            Long totalProcesses = processInstanceRepository.count();
            stats.put("totalProcesses", totalProcesses);

            Long runningProcesses = processInstanceRepository.countByInstanceStatus(ProcessInstanceStatus.RUNNING);
            stats.put("runningProcesses", runningProcesses);

            Long completedProcesses = processInstanceRepository.countByInstanceStatus(ProcessInstanceStatus.COMPLETED);
            stats.put("completedProcesses", completedProcesses);

            Long rejectedProcesses = processInstanceRepository.countByInstanceStatus(ProcessInstanceStatus.REJECTED);
            stats.put("rejectedProcesses", rejectedProcesses);
        }

        logger.info("获取流程统计数据完成: processId={}", processId);
        return stats;
    }

    public Map<String, Object> getApprovalTaskStatistics(String approverId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        if (approverId != null && !approverId.isEmpty()) {
            Long totalTasks = approvalTaskRepository.countByApproverId(approverId);
            stats.put("totalTasks", totalTasks);

            Long pendingTasks = approvalTaskRepository.countByApproverIdAndTaskStatus(
                    approverId, TaskStatus.PENDING);
            stats.put("pendingTasks", pendingTasks);

            Long completedTasks = approvalTaskRepository.countByApproverIdAndTaskStatus(
                    approverId, TaskStatus.COMPLETED);
            stats.put("completedTasks", completedTasks);

            Long canceledTasks = approvalTaskRepository.countByApproverIdAndTaskStatus(
                    approverId, TaskStatus.CANCELED);
            stats.put("canceledTasks", canceledTasks);
        } else {
            Long totalTasks = approvalTaskRepository.count();
            stats.put("totalTasks", totalTasks);

            Long pendingTasks = approvalTaskRepository.count();
            stats.put("pendingTasks", pendingTasks);
        }

        logger.info("获取审批任务统计数据完成: approverId={}", approverId);
        return stats;
    }

    public Map<String, Object> getDailyStatistics(LocalDate date) {
        Map<String, Object> stats = new LinkedHashMap<>();

        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.atTime(LocalTime.MAX);

        Long submittedForms = formDataRepository.countBySubmitTimeBetween(startOfDay, endOfDay);
        stats.put("submittedForms", submittedForms);

        Long startedProcesses = processInstanceRepository.countByStartTimeBetween(startOfDay, endOfDay);
        stats.put("startedProcesses", startedProcesses);

        logger.info("获取每日统计数据完成: date={}", date);
        return stats;
    }

    public Map<String, Long> getFormStatusDistribution(String templateId) {
        Map<String, Long> distribution = new LinkedHashMap<>();

        for (FormStatus status : FormStatus.values()) {
            Long count;
            if (templateId != null && !templateId.isEmpty()) {
                count = formDataRepository.countByTemplateIdAndStatus(templateId, status);
            } else {
                count = formDataRepository.countByStatus(status);
            }
            distribution.put(status.name(), count);
        }

        logger.info("获取表单状态分布完成: templateId={}", templateId);
        return distribution;
    }

    public Map<String, Long> getProcessStatusDistribution(String processId) {
        Map<String, Long> distribution = new LinkedHashMap<>();

        for (ProcessInstanceStatus status : ProcessInstanceStatus.values()) {
            Long count;
            if (processId != null && !processId.isEmpty()) {
                count = processInstanceRepository.countByProcessIdAndInstanceStatus(processId, status);
            } else {
                count = processInstanceRepository.countByInstanceStatus(status);
            }
            distribution.put(status.name(), count);
        }

        logger.info("获取流程状态分布完成: processId={}", processId);
        return distribution;
    }

    public List<Map<String, Object>> getRecentApprovals(String approverId, int limit) {
        List<Map<String, Object>> result = new ArrayList<>();

        List<ApprovalRecord> records = approvalRecordRepository.findByApproverId(approverId);

        int count = 0;
        for (ApprovalRecord record : records) {
            if (count >= limit) {
                break;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("approvalId", record.getApprovalId());
            item.put("instanceId", record.getInstanceId());
            item.put("formId", record.getFormId());
            item.put("nodeName", record.getNodeName());
            item.put("approvalResult", record.getApprovalResult().name());
            item.put("approvalComment", record.getApprovalComment());
            item.put("approvalTime", record.getApprovalTime());
            result.add(item);

            count++;
        }

        logger.info("获取最近审批记录完成: approverId={}, limit={}", approverId, limit);
        return result;
    }

    public Map<String, Object> getProcessStatusDetails(String instanceId) {
        Map<String, Object> details = new LinkedHashMap<>();

        ProcessInstance instance = processInstanceRepository.findByInstanceId(instanceId)
                .orElse(null);

        if (instance == null) {
            return details;
        }

        details.put("instanceId", instance.getInstanceId());
        details.put("processId", instance.getProcessId());
        details.put("formId", instance.getFormId());
        details.put("currentNodeId", instance.getCurrentNodeId());
        details.put("status", instance.getInstanceStatus().name());
        details.put("startTime", instance.getStartTime());
        details.put("endTime", instance.getEndTime());
        details.put("submitterId", instance.getSubmitterId());
        details.put("submitterName", instance.getSubmitterName());

        List<ApprovalRecord> history = approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc(instanceId);
        details.put("approvalHistory", history);

        long approvalCount = approvalRecordRepository.countByInstanceId(instanceId);
        details.put("approvalCount", approvalCount);

        logger.info("获取流程状态详情完成: instanceId={}", instanceId);
        return details;
    }
}
