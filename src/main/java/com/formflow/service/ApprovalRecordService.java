package com.formflow.service;

import com.formflow.entity.ApprovalRecord;
import com.formflow.entity.ApprovalTask;
import com.formflow.enums.ApprovalResult;
import com.formflow.exception.BusinessException;
import com.formflow.repository.ApprovalRecordRepository;
import com.formflow.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ApprovalRecordService {

    private static final Logger logger = LoggerFactory.getLogger(ApprovalRecordService.class);

    @Autowired
    private ApprovalRecordRepository approvalRecordRepository;

    public ApprovalRecord getRecordByApprovalId(String approvalId) {
        return approvalRecordRepository.findByApprovalId(approvalId)
                .orElseThrow(() -> new BusinessException(404, "审批记录不存在: " + approvalId));
    }

    public List<ApprovalRecord> getRecordsByInstanceId(String instanceId) {
        return approvalRecordRepository.findByInstanceIdOrderBySortOrderAsc(instanceId);
    }

    public List<ApprovalRecord> getRecordsByFormId(String formId) {
        return approvalRecordRepository.findByFormIdOrderBySortOrderAsc(formId);
    }

    public List<ApprovalRecord> getRecordsByApproverId(String approverId) {
        return approvalRecordRepository.findByApproverId(approverId);
    }

    public List<ApprovalRecord> getRecordsByInstanceIdAndNodeId(String instanceId, String nodeId) {
        return approvalRecordRepository.findByInstanceIdAndNodeId(instanceId, nodeId);
    }

    @Transactional
    public ApprovalRecord createApprovalRecord(ApprovalTask task, ApprovalResult result,
                                               String comment) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(IdGenerator.generateApprovalId());
        record.setInstanceId(task.getInstanceId());
        record.setNodeId(task.getNodeId());
        record.setNodeName(task.getNodeName());
        record.setFormId(task.getFormId());
        record.setTaskId(task.getTaskId());
        record.setApproverId(task.getApproverId());
        record.setApproverName(task.getApproverName());
        record.setApprovalResult(result);
        record.setApprovalComment(comment);
        record.setSubmitterId(task.getSubmitterId());
        record.setSubmitterName(task.getSubmitterName());
        record.setActionType(result.name());

        Integer maxSortOrder = approvalRecordRepository.findMaxSortOrderByInstanceId(task.getInstanceId());
        record.setSortOrder(maxSortOrder != null ? maxSortOrder + 1 : 1);

        ApprovalRecord saved = approvalRecordRepository.save(record);
        logger.info("创建审批记录成功: approvalId={}, instanceId={}, result={}",
                saved.getApprovalId(), task.getInstanceId(), result);
        return saved;
    }

    @Transactional
    public ApprovalRecord createTransferRecord(ApprovalTask task, String newApproverId,
                                               String newApproverName, String comment) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(IdGenerator.generateApprovalId());
        record.setInstanceId(task.getInstanceId());
        record.setNodeId(task.getNodeId());
        record.setNodeName(task.getNodeName());
        record.setFormId(task.getFormId());
        record.setTaskId(task.getTaskId());
        record.setApproverId(task.getApproverId());
        record.setApproverName(task.getApproverName());
        record.setApprovalResult(ApprovalResult.TRANSFER);
        record.setApprovalComment("转交给: " + newApproverName + "(" + newApproverId + "). 原因: " + comment);
        record.setSubmitterId(task.getSubmitterId());
        record.setSubmitterName(task.getSubmitterName());
        record.setActionType("TRANSFER");

        Integer maxSortOrder = approvalRecordRepository.findMaxSortOrderByInstanceId(task.getInstanceId());
        record.setSortOrder(maxSortOrder != null ? maxSortOrder + 1 : 1);

        ApprovalRecord saved = approvalRecordRepository.save(record);
        logger.info("创建转交记录成功: approvalId={}, from={}, to={}",
                saved.getApprovalId(), task.getApproverId(), newApproverId);
        return saved;
    }

    @Transactional
    public ApprovalRecord createDelegateRecord(ApprovalTask task, String delegateApproverId,
                                               String delegateApproverName) {
        ApprovalRecord record = new ApprovalRecord();
        record.setApprovalId(IdGenerator.generateApprovalId());
        record.setInstanceId(task.getInstanceId());
        record.setNodeId(task.getNodeId());
        record.setNodeName(task.getNodeName());
        record.setFormId(task.getFormId());
        record.setTaskId(task.getTaskId());
        record.setApproverId(task.getApproverId());
        record.setApproverName(task.getApproverName());
        record.setApprovalResult(ApprovalResult.DELEGATE);
        record.setApprovalComment("委托给: " + delegateApproverName + "(" + delegateApproverId + ")");
        record.setSubmitterId(task.getSubmitterId());
        record.setSubmitterName(task.getSubmitterName());
        record.setActionType("DELEGATE");

        Integer maxSortOrder = approvalRecordRepository.findMaxSortOrderByInstanceId(task.getInstanceId());
        record.setSortOrder(maxSortOrder != null ? maxSortOrder + 1 : 1);

        ApprovalRecord saved = approvalRecordRepository.save(record);
        logger.info("创建委托记录成功: approvalId={}, delegator={}, delegate={}",
                saved.getApprovalId(), task.getApproverId(), delegateApproverId);
        return saved;
    }

    public String getLastApprovalId(String instanceId) {
        List<ApprovalRecord> records = getRecordsByInstanceId(instanceId);
        if (records.isEmpty()) {
            return null;
        }
        return records.get(records.size() - 1).getApprovalId();
    }

    public Long countRecordsByInstanceId(String instanceId) {
        return approvalRecordRepository.countByInstanceId(instanceId);
    }
}
