package com.invoice.mgmt.common.testdata;

import com.invoice.mgmt.common.dto.*;
import com.invoice.mgmt.common.entity.*;
import com.invoice.mgmt.common.enums.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static class InvoiceBuilder {
        private String invoiceId;
        private String invoiceType = "vat_special";
        private String invoiceNo = "00000001";
        private String invoiceCode = "1100";
        private String buyerName = "采购公司";
        private String buyerTaxNo = "911100001234567890";
        private String sellerName = "销售公司";
        private String sellerTaxNo = "911100000000000000";
        private BigDecimal invoiceAmount = new BigDecimal("10000.00");
        private BigDecimal taxAmount = new BigDecimal("1300.00");
        private BigDecimal totalAmount = new BigDecimal("11300.00");
        private String invoiceStatus = InvoiceStatusEnum.ISSUED.getCode();
        private Instant issueTime = Instant.now();

        public InvoiceBuilder invoiceId(String invoiceId) { this.invoiceId = invoiceId; return this; }
        public InvoiceBuilder invoiceType(String invoiceType) { this.invoiceType = invoiceType; return this; }
        public InvoiceBuilder invoiceNo(String invoiceNo) { this.invoiceNo = invoiceNo; return this; }
        public InvoiceBuilder invoiceCode(String invoiceCode) { this.invoiceCode = invoiceCode; return this; }
        public InvoiceBuilder buyerName(String buyerName) { this.buyerName = buyerName; return this; }
        public InvoiceBuilder buyerTaxNo(String buyerTaxNo) { this.buyerTaxNo = buyerTaxNo; return this; }
        public InvoiceBuilder sellerName(String sellerName) { this.sellerName = sellerName; return this; }
        public InvoiceBuilder sellerTaxNo(String sellerTaxNo) { this.sellerTaxNo = sellerTaxNo; return this; }
        public InvoiceBuilder invoiceAmount(BigDecimal invoiceAmount) { this.invoiceAmount = invoiceAmount; return this; }
        public InvoiceBuilder taxAmount(BigDecimal taxAmount) { this.taxAmount = taxAmount; return this; }
        public InvoiceBuilder totalAmount(BigDecimal totalAmount) { this.totalAmount = totalAmount; return this; }
        public InvoiceBuilder invoiceStatus(String invoiceStatus) { this.invoiceStatus = invoiceStatus; return this; }
        public InvoiceBuilder issueTime(Instant issueTime) { this.issueTime = issueTime; return this; }

        public Invoice build() {
            return Invoice.builder()
                    .invoiceId(invoiceId != null ? invoiceId : "invoice_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                    .invoiceType(invoiceType)
                    .invoiceNo(invoiceNo)
                    .invoiceCode(invoiceCode)
                    .buyerName(buyerName)
                    .buyerTaxNo(buyerTaxNo)
                    .sellerName(sellerName)
                    .sellerTaxNo(sellerTaxNo)
                    .invoiceAmount(invoiceAmount)
                    .taxAmount(taxAmount)
                    .totalAmount(totalAmount)
                    .invoiceStatus(invoiceStatus)
                    .issueTime(issueTime)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }

    public static class InvoiceTypeBuilder {
        private String typeCode = "vat_special";
        private String typeName = "增值税专用发票";
        private BigDecimal taxRate = new BigDecimal("0.13");
        private Boolean enabled = true;
        private String description = "一般纳税人适用，可抵扣进项税";

        public InvoiceTypeBuilder typeCode(String typeCode) { this.typeCode = typeCode; return this; }
        public InvoiceTypeBuilder typeName(String typeName) { this.typeName = typeName; return this; }
        public InvoiceTypeBuilder taxRate(BigDecimal taxRate) { this.taxRate = taxRate; return this; }
        public InvoiceTypeBuilder enabled(Boolean enabled) { this.enabled = enabled; return this; }
        public InvoiceTypeBuilder description(String description) { this.description = description; return this; }

        public InvoiceType build() {
            return InvoiceType.builder()
                    .typeId("type_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                    .typeCode(typeCode)
                    .typeName(typeName)
                    .taxRate(taxRate)
                    .enabled(enabled)
                    .description(description)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }

    public static class InvoiceNumberBuilder {
        private String invoiceType = "vat_special";
        private String invoiceCode = "1100";
        private String startNo = "00000001";
        private String endNo = "00009999";
        private String currentNo = "00000001";
        private Integer totalCount = 9999;
        private Integer usedCount = 0;
        private Integer remainingCount = 9999;
        private String status = "active";

        public InvoiceNumberBuilder invoiceType(String invoiceType) { this.invoiceType = invoiceType; return this; }
        public InvoiceNumberBuilder invoiceCode(String invoiceCode) { this.invoiceCode = invoiceCode; return this; }
        public InvoiceNumberBuilder startNo(String startNo) { this.startNo = startNo; return this; }
        public InvoiceNumberBuilder endNo(String endNo) { this.endNo = endNo; return this; }
        public InvoiceNumberBuilder currentNo(String currentNo) { this.currentNo = currentNo; return this; }
        public InvoiceNumberBuilder totalCount(Integer totalCount) { this.totalCount = totalCount; return this; }
        public InvoiceNumberBuilder usedCount(Integer usedCount) { this.usedCount = usedCount; return this; }
        public InvoiceNumberBuilder remainingCount(Integer remainingCount) { this.remainingCount = remainingCount; return this; }
        public InvoiceNumberBuilder status(String status) { this.status = status; return this; }

        public InvoiceNumber build() {
            return InvoiceNumber.builder()
                    .invoiceType(invoiceType)
                    .invoiceCode(invoiceCode)
                    .startNo(startNo)
                    .endNo(endNo)
                    .currentNo(currentNo)
                    .totalCount(totalCount)
                    .usedCount(usedCount)
                    .remainingCount(remainingCount)
                    .status(status)
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }

    public static class InvoiceReimburseBuilder {
        private String invoiceId;
        private String reimburseUser = "user_001";
        private String reimburseDepartment = "财务部";
        private BigDecimal reimburseAmount = new BigDecimal("11300.00");
        private String reimburseReason = "差旅费用报销";
        private String reimburseStatus = ReimburseStatusEnum.PENDING.getCode();
        private Integer priority = 1;

        public InvoiceReimburseBuilder invoiceId(String invoiceId) { this.invoiceId = invoiceId; return this; }
        public InvoiceReimburseBuilder reimburseUser(String reimburseUser) { this.reimburseUser = reimburseUser; return this; }
        public InvoiceReimburseBuilder reimburseDepartment(String reimburseDepartment) { this.reimburseDepartment = reimburseDepartment; return this; }
        public InvoiceReimburseBuilder reimburseAmount(BigDecimal reimburseAmount) { this.reimburseAmount = reimburseAmount; return this; }
        public InvoiceReimburseBuilder reimburseReason(String reimburseReason) { this.reimburseReason = reimburseReason; return this; }
        public InvoiceReimburseBuilder reimburseStatus(String reimburseStatus) { this.reimburseStatus = reimburseStatus; return this; }
        public InvoiceReimburseBuilder priority(Integer priority) { this.priority = priority; return this; }

        public InvoiceReimburse build() {
            return InvoiceReimburse.builder()
                    .reimburseId("reimburse_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12))
                    .invoiceId(invoiceId != null ? invoiceId : "invoice_test_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                    .reimburseUser(reimburseUser)
                    .reimburseDepartment(reimburseDepartment)
                    .reimburseAmount(reimburseAmount)
                    .reimburseReason(reimburseReason)
                    .reimburseStatus(reimburseStatus)
                    .applyTime(Instant.now())
                    .createdAt(Instant.now())
                    .updatedAt(Instant.now())
                    .build();
        }
    }

    public static class RequestBuilder {

        public static InvoiceIssueRequest buildIssueRequest() {
            return InvoiceIssueRequest.builder()
                    .invoiceType("vat_special")
                    .buyerName("采购公司")
                    .buyerTaxNo("911100001234567890")
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .sellerName("销售公司")
                    .sellerTaxNo("911100000000000000")
                    .operator("admin")
                    .build();
        }

        public static InvoiceIssueRequest buildIssueRequest(String type, BigDecimal amount, String buyer) {
            return InvoiceIssueRequest.builder()
                    .invoiceType(type)
                    .buyerName(buyer)
                    .buyerTaxNo("911100001234567890")
                    .invoiceAmount(amount)
                    .sellerName("销售公司")
                    .sellerTaxNo("911100000000000000")
                    .operator("admin")
                    .build();
        }

        public static InvoiceVerifyRequest buildVerifyRequest() {
            return InvoiceVerifyRequest.builder()
                    .invoiceNo("00000001")
                    .invoiceCode("1100")
                    .verifyType(VerifyTypeEnum.ONLINE.getCode())
                    .operator("admin")
                    .build();
        }

        public static InvoiceVerifyRequest buildVerifyRequest(String invoiceNo, String invoiceCode, String verifyType) {
            return InvoiceVerifyRequest.builder()
                    .invoiceNo(invoiceNo)
                    .invoiceCode(invoiceCode)
                    .verifyType(verifyType)
                    .operator("admin")
                    .build();
        }

        public static InvoiceReimburseRequest buildReimburseRequest() {
            return InvoiceReimburseRequest.builder()
                    .invoiceId("invoice_test_001")
                    .reimburseUser("user_001")
                    .reimburseDepartment("财务部")
                    .reimburseReason("差旅费用报销")
                    .operator("user_001")
                    .build();
        }

        public static InvoiceReimburseRequest buildReimburseRequest(String invoiceId, String user, BigDecimal amount) {
            return InvoiceReimburseRequest.builder()
                    .invoiceId(invoiceId)
                    .reimburseUser(user)
                    .reimburseDepartment("财务部")
                    .reimburseAmount(amount)
                    .reimburseReason("费用报销")
                    .operator(user)
                    .build();
        }
    }

    public static class ListBuilder {

        public static List<InvoiceNumber> buildNumberPoolList(String type, int poolCount) {
            List<InvoiceNumber> list = new ArrayList<>();
            for (int i = 0; i < poolCount; i++) {
                int start = i * 100 + 1;
                int end = (i + 1) * 100;
                list.add(new InvoiceNumberBuilder()
                        .invoiceType(type)
                        .invoiceCode("110" + i)
                        .startNo(String.format("%08d", start))
                        .endNo(String.format("%08d", end))
                        .currentNo(String.format("%08d", start))
                        .totalCount(100)
                        .usedCount(0)
                        .remainingCount(100)
                        .build());
            }
            return list;
        }

        public static List<InvoiceNumber> buildNearExhaustedNumberPool(String type, int remaining) {
            List<InvoiceNumber> list = new ArrayList<>();
            list.add(new InvoiceNumberBuilder()
                    .invoiceType(type)
                    .invoiceCode("1100")
                    .startNo("00000001")
                    .endNo("00000100")
                    .currentNo(String.format("%08d", 101 - remaining))
                    .totalCount(100)
                    .usedCount(100 - remaining)
                    .remainingCount(remaining)
                    .build());
            return list;
        }

        public static List<InvoiceType> buildDefaultInvoiceTypes() {
            List<InvoiceType> list = new ArrayList<>();
            list.add(new InvoiceTypeBuilder().typeCode("vat_special").typeName("增值税专用发票").taxRate(new BigDecimal("0.13")).build());
            list.add(new InvoiceTypeBuilder().typeCode("vat_common").typeName("增值税普通发票").taxRate(new BigDecimal("0.13")).build());
            list.add(new InvoiceTypeBuilder().typeCode("vat_electronic").typeName("电子增值税发票").taxRate(new BigDecimal("0.13")).build());
            list.add(new InvoiceTypeBuilder().typeCode("vat_6pct").typeName("服务业增值税发票").taxRate(new BigDecimal("0.06")).build());
            list.add(new InvoiceTypeBuilder().typeCode("vat_9pct").typeName("建筑业增值税发票").taxRate(new BigDecimal("0.09")).build());
            return list;
        }

        public static List<InvoiceReimburse> buildPriorityReimburseList() {
            List<InvoiceReimburse> list = new ArrayList<>();
            list.add(new InvoiceReimburseBuilder()
                    .invoiceId("invoice_001")
                    .reimburseUser("user_normal")
                    .reimburseReason("日常办公费用")
                    .reimburseAmount(new BigDecimal("1000.00"))
                    .build());
            list.add(new InvoiceReimburseBuilder()
                    .invoiceId("invoice_002")
                    .reimburseUser("user_urgent")
                    .reimburseReason("紧急医疗费用")
                    .reimburseAmount(new BigDecimal("5000.00"))
                    .build());
            list.add(new InvoiceReimburseBuilder()
                    .invoiceId("invoice_003")
                    .reimburseUser("user_normal2")
                    .reimburseReason("差旅费")
                    .reimburseAmount(new BigDecimal("3000.00"))
                    .build());
            return list;
        }
    }

    public static InvoiceBuilder invoice() { return new InvoiceBuilder(); }
    public static InvoiceTypeBuilder invoiceType() { return new InvoiceTypeBuilder(); }
    public static InvoiceNumberBuilder invoiceNumber() { return new InvoiceNumberBuilder(); }
    public static InvoiceReimburseBuilder invoiceReimburse() { return new InvoiceReimburseBuilder(); }
}
