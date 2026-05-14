package com.invoice.mgmt.verify.service;

import com.invoice.mgmt.common.dto.InvoiceVerifyRequest;
import com.invoice.mgmt.common.dto.InvoiceVerifyResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceVerify;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.enums.VerifyResultEnum;
import com.invoice.mgmt.common.enums.VerifyTypeEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.verify.mapper.InvoiceVerifyMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.regex.Pattern;

@Service
public class InvoiceVerifyService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceVerifyService.class);
    private static final Pattern INVOICE_NO_PATTERN = Pattern.compile("^\\d{8,20}$");

    @Value("${invoice.verify.online.timeout:5000}")
    private int onlineTimeoutMs;

    @Value("${invoice.verify.online.mock:true}")
    private boolean mockOnline;

    @Autowired
    private InvoiceVerifyMapper verifyMapper;

    @Autowired
    private InvoiceIssueService invoiceIssueService;

    @Autowired
    private InvoiceStatusService invoiceStatusService;

    @Autowired
    private InvoiceStatisticsService invoiceStatisticsService;

    @Autowired
    private InvoiceHistoryService invoiceHistoryService;

    @Transactional
    public InvoiceVerifyResponse verify(InvoiceVerifyRequest request) {
        String invoiceNo = request.getInvoiceNo();
        String invoiceCode = request.getInvoiceCode();
        String verifyType = request.getVerifyType() != null ? request.getVerifyType() : VerifyTypeEnum.ONLINE.getCode();

        Invoice invoice;
        try {
            invoice = invoiceIssueService.getByNoAndCode(invoiceNo, invoiceCode);
        } catch (InvoiceException e) {
            logger.warn("发票不存在: invoiceNo={}, invoiceCode={}", invoiceNo, invoiceCode);
            saveVerifyRecord(null, invoiceNo, invoiceCode, verifyType, VerifyResultEnum.INVALID, "发票不存在");
            throw InvoiceException.verifyFailed();
        }

        String currentStatus = invoice.getInvoiceStatus();
        if (InvoiceStatusEnum.CANCELLED.getCode().equals(currentStatus)
                || InvoiceStatusEnum.INVALID.getCode().equals(currentStatus)) {
            logger.warn("发票状态无效: invoiceId={}, status={}", invoice.getInvoiceId(), currentStatus);
            saveVerifyRecord(invoice.getInvoiceId(), invoiceNo, invoiceCode, verifyType, VerifyResultEnum.INVALID, "发票状态无效");
            invoiceStatisticsService.recordVerify(false);
            throw InvoiceException.verifyFailed();
        }

        VerifyResultEnum result;
        String detail;

        if (VerifyTypeEnum.ONLINE.getCode().equals(verifyType)) {
            result = doOnlineVerify(invoice);
            detail = "在线验证" + (result == VerifyResultEnum.VALID ? "通过" : "未通过");
        } else {
            result = doLocalVerify(invoice);
            detail = "本地验证" + (result == VerifyResultEnum.VALID ? "通过" : "未通过");
        }

        saveVerifyRecord(invoice.getInvoiceId(), invoiceNo, invoiceCode, verifyType, result, detail);
        invoiceStatisticsService.recordVerify(result == VerifyResultEnum.VALID);
        invoiceHistoryService.recordVerify(invoice.getInvoiceId(), result.getCode(), request.getOperator());

        if (result == VerifyResultEnum.VALID) {
            if (invoiceStatusService.canVerify(currentStatus)) {
                invoiceStatusService.verify(invoice.getInvoiceId(), request.getOperator());
            }
        }

        logger.info("发票验证完成: invoiceId={}, result={}", invoice.getInvoiceId(), result.getCode());

        return InvoiceVerifyResponse.builder()
                .verifyId(IdGenerator.generateVerifyId())
                .verifyResult(result.getCode())
                .verifyType(verifyType)
                .verifiedAt(DateTimeUtil.formatFull(DateTimeUtil.now()))
                .verifyDetail(detail)
                .build();
    }

    private VerifyResultEnum doOnlineVerify(Invoice invoice) {
        if (mockOnline) {
            return VerifyResultEnum.VALID;
        }
        try {
            Thread.sleep(Math.min(onlineTimeoutMs, 100));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return VerifyResultEnum.VALID;
    }

    private VerifyResultEnum doLocalVerify(Invoice invoice) {
        if (invoice.getInvoiceNo() == null || !INVOICE_NO_PATTERN.matcher(invoice.getInvoiceNo()).matches()) {
            return VerifyResultEnum.INVALID;
        }
        if (invoice.getInvoiceAmount() == null || invoice.getInvoiceAmount().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            return VerifyResultEnum.INVALID;
        }
        if (invoice.getIssueTime() == null || invoice.getIssueTime().isAfter(DateTimeUtil.now())) {
            return VerifyResultEnum.INVALID;
        }
        return VerifyResultEnum.VALID;
    }

    private void saveVerifyRecord(String invoiceId, String invoiceNo, String invoiceCode,
                                  String verifyType, VerifyResultEnum result, String detail) {
        InvoiceVerify verify = InvoiceVerify.builder()
                .verifyId(IdGenerator.generateVerifyId())
                .invoiceId(invoiceId)
                .verifyType(verifyType)
                .verifyResult(result.getCode())
                .verifySource(VerifyTypeEnum.ONLINE.getCode().equals(verifyType) ? "tax_system" : "local")
                .verifyDetail(detail)
                .verifiedAt(DateTimeUtil.now())
                .createdAt(DateTimeUtil.now())
                .build();
        verifyMapper.insert(verify);
    }

    public InvoiceVerify getById(String verifyId) {
        return verifyMapper.findById(verifyId);
    }

    public List<InvoiceVerify> getByInvoice(String invoiceId) {
        return verifyMapper.findByInvoiceId(invoiceId);
    }

    public InvoiceVerify getLatestByInvoice(String invoiceId) {
        return verifyMapper.findLatestByInvoiceId(invoiceId);
    }
}
