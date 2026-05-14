package com.invoice.mgmt.issue.service;

import com.invoice.mgmt.archive.service.InvoiceArchiveService;
import com.invoice.mgmt.common.dto.InvoiceIssueRequest;
import com.invoice.mgmt.common.dto.InvoiceIssueResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.mapper.InvoiceMapper;
import com.invoice.mgmt.common.util.DateTimeUtil;
import com.invoice.mgmt.common.util.IdGenerator;
import com.invoice.mgmt.common.util.InvoiceAmountUtil;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.number.service.InvoiceNumberService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.type.service.InvoiceTypeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

@Service
public class InvoiceIssueService {
    private static final Logger logger = LoggerFactory.getLogger(InvoiceIssueService.class);

    @Value("${invoice.default.seller.name:销售公司}")
    private String defaultSellerName;

    @Value("${invoice.default.seller.taxno:911100000000000000}")
    private String defaultSellerTaxNo;

    @Autowired
    private InvoiceMapper invoiceMapper;

    @Autowired
    private InvoiceTypeService invoiceTypeService;

    @Autowired
    private InvoiceNumberService invoiceNumberService;

    @Autowired
    private InvoiceStatusService invoiceStatusService;

    @Autowired
    private InvoiceArchiveService invoiceArchiveService;

    @Autowired
    private InvoiceStatisticsService invoiceStatisticsService;

    @Autowired
    private InvoiceHistoryService invoiceHistoryService;

    @Transactional
    public InvoiceIssueResponse issue(InvoiceIssueRequest request) {
        validateRequest(request);
        String typeCode = request.getInvoiceType();
        if (!invoiceTypeService.isValidType(typeCode)) {
            throw InvoiceException.invalidType();
        }
        if (request.getBuyerName() == null || request.getBuyerName().trim().isEmpty()) {
            throw InvoiceException.missingBuyerInfo();
        }
        if (!InvoiceAmountUtil.isValidAmount(request.getInvoiceAmount())) {
            throw InvoiceException.invalidAmount();
        }

        BigDecimal taxRate = invoiceTypeService.getTaxRate(typeCode);
        BigDecimal taxAmount = InvoiceAmountUtil.calculateTax(request.getInvoiceAmount(), taxRate);
        BigDecimal totalAmount = InvoiceAmountUtil.calculateTotal(request.getInvoiceAmount(), taxAmount);

        String invoiceCode = invoiceNumberService.getInvoiceCode(typeCode);
        String invoiceNo = invoiceNumberService.allocate(typeCode);

        Instant now = DateTimeUtil.now();
        Invoice invoice = Invoice.builder()
                .invoiceId(IdGenerator.generateInvoiceId())
                .invoiceType(typeCode)
                .invoiceNo(invoiceNo)
                .invoiceCode(invoiceCode)
                .buyerName(request.getBuyerName().trim())
                .buyerTaxNo(request.getBuyerTaxNo())
                .sellerName(request.getSellerName() != null ? request.getSellerName() : defaultSellerName)
                .sellerTaxNo(request.getSellerTaxNo() != null ? request.getSellerTaxNo() : defaultSellerTaxNo)
                .invoiceAmount(request.getInvoiceAmount())
                .taxAmount(taxAmount)
                .totalAmount(totalAmount)
                .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                .issueTime(now)
                .createdAt(now)
                .updatedAt(now)
                .build();
        invoiceMapper.insert(invoice);

        invoiceStatusService.issue(invoice.getInvoiceId(), request.getOperator());
        invoiceArchiveService.archiveElectronic(invoice.getInvoiceId(), request.getOperator());
        invoiceStatisticsService.recordIssue(request.getInvoiceAmount(), taxAmount);
        invoiceStatisticsService.recordIssueByType(typeCode, request.getInvoiceAmount(), taxAmount);
        invoiceHistoryService.recordIssue(invoice.getInvoiceId(), request.getOperator());

        logger.info("发票开具成功: invoiceId={}, invoiceNo={}", invoice.getInvoiceId(), invoiceNo);

        return InvoiceIssueResponse.builder()
                .invoiceId(invoice.getInvoiceId())
                .invoiceNo(invoice.getInvoiceNo())
                .invoiceCode(invoice.getInvoiceCode())
                .invoiceStatus(invoice.getInvoiceStatus())
                .issueTime(DateTimeUtil.formatFull(invoice.getIssueTime()))
                .build();
    }

    public Invoice getById(String invoiceId) {
        Invoice invoice = invoiceMapper.findById(invoiceId);
        if (invoice == null) {
            throw InvoiceException.notFound();
        }
        return invoice;
    }

    public Invoice getByNoAndCode(String invoiceNo, String invoiceCode) {
        Invoice invoice = invoiceMapper.findByNoAndCode(invoiceNo, invoiceCode);
        if (invoice == null) {
            throw InvoiceException.notFound();
        }
        return invoice;
    }

    private void validateRequest(InvoiceIssueRequest request) {
        if (request == null) {
            throw new InvoiceException(400, "请求不能为空");
        }
        if (request.getInvoiceType() == null || request.getInvoiceType().trim().isEmpty()) {
            throw InvoiceException.invalidType();
        }
        if (request.getInvoiceAmount() == null) {
            throw InvoiceException.invalidAmount();
        }
    }
}
