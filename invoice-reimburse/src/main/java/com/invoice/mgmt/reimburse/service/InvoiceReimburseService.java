package com.invoice.mgmt.reimburse.service;

import com.invoice.mgmt.common.dto.InvoiceReimburseRequest;
import com.invoice.mgmt.common.dto.InvoiceReimburseResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceReimburse;
import com.invoice.mgmt.common.enums.ReimburseStatusEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.reimburse.mapper.InvoiceReimburseMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class InvoiceReimburseService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceReimburseService.class);

    @Autowired
    private InvoiceReimburseMapper reimburseMapper;

    @Autowired
    private InvoiceIssueService invoiceIssueService;

    @Autowired
    private InvoiceStatusService invoiceStatusService;

    @Autowired
    private InvoiceStatisticsService invoiceStatisticsService;

    @Autowired
    private InvoiceHistoryService invoiceHistoryService;

    @Transactional
    public InvoiceReimburseResponse apply(InvoiceReimburseRequest request) {
        Invoice invoice = invoiceIssueService.getById(request.getInvoiceId());
        String currentStatus = invoice.getInvoiceStatus();

        if (invoiceStatusService.isAlreadyReimbursed(currentStatus)) {
            logger.warn("发票已在报销流程中: invoiceId={}, status={}", request.getInvoiceId(), currentStatus);
            throw InvoiceException.alreadyReimbursed();
        }
        if (!invoiceStatusService.canReimburse(currentStatus)) {
            logger.warn("发票状态不可报销: invoiceId={}, status={}", request.getInvoiceId(), currentStatus);
            throw InvoiceException.invalidStatus();
        }

        BigDecimal reimburseAmount = request.getReimburseAmount() != null
                ? request.getReimburseAmount()
                : invoice.getTotalAmount();

        if (reimburseAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvoiceException(400, "报销金额必须大于0");
        }
        if (reimburseAmount.compareTo(invoice.getTotalAmount()) > 0) {
            throw new InvoiceException(400, "报销金额不能大于发票金额");
        }

        Instant now = DateTimeUtil.now();
        InvoiceReimburse reimburse = InvoiceReimburse.builder()
                .reimburseId(IdGenerator.generateReimburseId())
                .invoiceId(request.getInvoiceId())
                .reimburseUser(request.getReimburseUser())
                .reimburseDepartment(request.getReimburseDepartment())
                .reimburseAmount(reimburseAmount)
                .reimburseReason(request.getReimburseReason())
                .reimburseStatus(ReimburseStatusEnum.PENDING.getCode())
                .applyTime(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        reimburseMapper.insert(reimburse);

        invoiceStatusService.reimbursePending(request.getInvoiceId(), request.getOperator());
        invoiceStatisticsService.recordReimburse(false);
        invoiceHistoryService.recordReimburseApply(request.getInvoiceId(), request.getOperator());

        logger.info("报销申请提交: reimburseId={}, invoiceId={}, user={}",
                reimburse.getReimburseId(), request.getInvoiceId(), request.getReimburseUser());

        return InvoiceReimburseResponse.builder()
                .reimburseId(reimburse.getReimburseId())
                .invoiceId(reimburse.getInvoiceId())
                .status(ReimburseStatusEnum.PENDING.getCode())
                .reimburseUser(reimburse.getReimburseUser())
                .reimburseAmount(reimburse.getReimburseAmount())
                .applyTime(DateTimeUtil.formatFull(reimburse.getApplyTime()))
                .build();
    }

    @Transactional
    public InvoiceReimburse approve(String reimburseId, String approver, String remark) {
        InvoiceReimburse reimburse = getById(reimburseId);
        if (!ReimburseStatusEnum.PENDING.getCode().equals(reimburse.getReimburseStatus())) {
            throw new InvoiceException(400, "报销申请已处理，无法重复审核");
        }
        reimburseMapper.updateStatus(reimburseId, ReimburseStatusEnum.APPROVED.getCode(), approver, remark);
        reimburse.setReimburseStatus(ReimburseStatusEnum.APPROVED.getCode());
        reimburse.setApprover(approver);
        reimburse.setApproveRemark(remark);
        reimburse.setApproveTime(DateTimeUtil.now());

        invoiceStatusService.reimbursed(reimburse.getInvoiceId(), approver);
        invoiceStatisticsService.recordReimburse(true);
        invoiceHistoryService.recordReimburseApprove(reimburse.getInvoiceId(), approver);

        logger.info("报销审核通过: reimburseId={}, approver={}", reimburseId, approver);
        return reimburse;
    }

    @Transactional
    public InvoiceReimburse reject(String reimburseId, String approver, String reason) {
        InvoiceReimburse reimburse = getById(reimburseId);
        if (!ReimburseStatusEnum.PENDING.getCode().equals(reimburse.getReimburseStatus())) {
            throw new InvoiceException(400, "报销申请已处理，无法重复审核");
        }
        reimburseMapper.updateStatus(reimburseId, ReimburseStatusEnum.REJECTED.getCode(), approver, reason);
        reimburse.setReimburseStatus(ReimburseStatusEnum.REJECTED.getCode());
        reimburse.setApprover(approver);
        reimburse.setApproveRemark(reason);
        reimburse.setApproveTime(DateTimeUtil.now());

        invoiceHistoryService.recordReimburseReject(reimburse.getInvoiceId(), reason, approver);

        logger.info("报销审核拒绝: reimburseId={}, reason={}", reimburseId, reason);
        return reimburse;
    }

    public InvoiceReimburse getById(String reimburseId) {
        InvoiceReimburse reimburse = reimburseMapper.findById(reimburseId);
        if (reimburse == null) {
            throw new InvoiceException(404, "报销记录不存在");
        }
        return reimburse;
    }

    public List<InvoiceReimburse> getByInvoice(String invoiceId) {
        return reimburseMapper.findByInvoiceId(invoiceId);
    }

    public List<InvoiceReimburse> getByUser(String reimburseUser) {
        return reimburseMapper.findByUser(reimburseUser);
    }

    public List<InvoiceReimburse> getPending() {
        return reimburseMapper.findByStatus(ReimburseStatusEnum.PENDING.getCode());
    }
}
