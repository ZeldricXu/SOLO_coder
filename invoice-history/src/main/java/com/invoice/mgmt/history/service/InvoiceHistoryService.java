package com.invoice.mgmt.history.service;

import com.invoice.mgmt.common.entity.InvoiceHistory;
import com.invoice.mgmt.common.enums.ActionTypeEnum;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.history.mapper.InvoiceHistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class InvoiceHistoryService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceHistoryService.class);

    @Autowired
    private InvoiceHistoryMapper historyMapper;

    @Transactional
    public InvoiceHistory record(String invoiceId, ActionTypeEnum actionType, String content, String operator) {
        InvoiceHistory history = InvoiceHistory.builder()
                .invoiceId(invoiceId)
                .actionType(actionType.getCode())
                .actionContent(content)
                .operator(operator != null ? operator : "system")
                .actionTime(DateTimeUtil.now())
                .createdAt(DateTimeUtil.now())
                .build();
        historyMapper.insert(history);
        logger.info("记录历史: invoiceId={}, action={}, operator={}", invoiceId, actionType.getCode(), operator);
        return history;
    }

    @Transactional
    public InvoiceHistory recordIssue(String invoiceId, String operator) {
        return record(invoiceId, ActionTypeEnum.ISSUE, "发票开具成功", operator);
    }

    @Transactional
    public InvoiceHistory recordVerify(String invoiceId, String result, String operator) {
        return record(invoiceId, ActionTypeEnum.VERIFY, "发票验证结果: " + result, operator);
    }

    @Transactional
    public InvoiceHistory recordReimburseApply(String invoiceId, String operator) {
        return record(invoiceId, ActionTypeEnum.REIMBURSE_APPLY, "提交报销申请", operator);
    }

    @Transactional
    public InvoiceHistory recordReimburseApprove(String invoiceId, String operator) {
        return record(invoiceId, ActionTypeEnum.REIMBURSE_APPROVE, "报销审核通过", operator);
    }

    @Transactional
    public InvoiceHistory recordReimburseReject(String invoiceId, String reason, String operator) {
        return record(invoiceId, ActionTypeEnum.REIMBURSE_REJECT, "报销审核拒绝: " + reason, operator);
    }

    @Transactional
    public InvoiceHistory recordArchive(String invoiceId, String archiveType, String operator) {
        return record(invoiceId, ActionTypeEnum.ARCHIVE, "发票已归档: " + archiveType, operator);
    }

    public List<InvoiceHistory> getByInvoice(String invoiceId) {
        return historyMapper.findByInvoiceId(invoiceId);
    }

    public List<InvoiceHistory> getByAction(ActionTypeEnum actionType) {
        return historyMapper.findByActionType(actionType.getCode());
    }

    public List<InvoiceHistory> getByOperator(String operator) {
        return historyMapper.findByOperator(operator);
    }

    public List<InvoiceHistory> getByTimeRange(Instant start, Instant end) {
        return historyMapper.findByTimeRange(start, end);
    }
}
