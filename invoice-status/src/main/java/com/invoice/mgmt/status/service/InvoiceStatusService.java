package com.invoice.mgmt.status.service;

import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceStatusLog;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.mapper.InvoiceMapper;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.status.mapper.InvoiceStatusLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class InvoiceStatusService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceStatusService.class);

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private InvoiceStatusLogMapper statusLogMapper;

    @Transactional
    public InvoiceStatusLog updateStatus(String invoiceId, String newStatus, String operator, String remark) {
        Invoice invoice = invoiceMapper.findById(invoiceId);
        if (invoice == null) {
            throw InvoiceException.notFound();
        }
        if (InvoiceStatusEnum.of(newStatus) == null) {
            throw InvoiceException.invalidStatus();
        }
        String oldStatus = invoice.getInvoiceStatus();
        if (oldStatus != null && oldStatus.equals(newStatus)) {
            logger.info("状态未变化, invoiceId: {}, status: {}", invoiceId, newStatus);
            return null;
        }
        invoiceMapper.updateStatus(invoiceId, newStatus);
        InvoiceStatusLog log = InvoiceStatusLog.builder()
                .invoiceId(invoiceId)
                .previousStatus(oldStatus)
                .currentStatus(newStatus)
                .operator(operator != null ? operator : "system")
                .remark(remark)
                .changeTime(DateTimeUtil.now())
                .createdAt(DateTimeUtil.now())
                .build();
        statusLogMapper.insert(log);
        logger.info("发票状态变更: invoiceId={}, {} -> {}", invoiceId, oldStatus, newStatus);
        return log;
    }

    @Transactional
    public InvoiceStatusLog issue(String invoiceId, String operator) {
        return updateStatus(invoiceId, InvoiceStatusEnum.ISSUED.getCode(), operator, "发票已开具");
    }

    @Transactional
    public InvoiceStatusLog verify(String invoiceId, String operator) {
        return updateStatus(invoiceId, InvoiceStatusEnum.VERIFIED.getCode(), operator, "发票已验证");
    }

    @Transactional
    public InvoiceStatusLog reimbursePending(String invoiceId, String operator) {
        return updateStatus(invoiceId, InvoiceStatusEnum.REIMBURSE_PENDING.getCode(), operator, "报销审核中");
    }

    @Transactional
    public InvoiceStatusLog reimbursed(String invoiceId, String operator) {
        return updateStatus(invoiceId, InvoiceStatusEnum.REIMBURSED.getCode(), operator, "发票已报销");
    }

    @Transactional
    public InvoiceStatusLog cancel(String invoiceId, String operator) {
        return updateStatus(invoiceId, InvoiceStatusEnum.CANCELLED.getCode(), operator, "发票已作废");
    }

    public String getCurrentStatus(String invoiceId) {
        Invoice invoice = invoiceMapper.findById(invoiceId);
        if (invoice == null) {
            throw InvoiceException.notFound();
        }
        return invoice.getInvoiceStatus();
    }

    public List<InvoiceStatusLog> getStatusHistory(String invoiceId) {
        return statusLogMapper.findByInvoiceId(invoiceId);
    }

    public InvoiceStatusLog getLatestLog(String invoiceId) {
        return statusLogMapper.findLatestByInvoiceId(invoiceId);
    }

    public boolean canIssue(String currentStatus) {
        return currentStatus == null || InvoiceStatusEnum.PENDING.getCode().equals(currentStatus);
    }

    public boolean canVerify(String currentStatus) {
        return InvoiceStatusEnum.ISSUED.getCode().equals(currentStatus);
    }

    public boolean canReimburse(String currentStatus) {
        return InvoiceStatusEnum.ISSUED.getCode().equals(currentStatus)
                || InvoiceStatusEnum.VERIFIED.getCode().equals(currentStatus);
    }

    public boolean isAlreadyReimbursed(String currentStatus) {
        return InvoiceStatusEnum.REIMBURSED.getCode().equals(currentStatus)
                || InvoiceStatusEnum.REIMBURSE_PENDING.getCode().equals(currentStatus);
    }
}
