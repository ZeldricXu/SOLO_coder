package com.invoice.mgmt.common.testdata;

import java.math.BigDecimal;

public class MockConstants {

    public static final String TEST_INVOICE_TYPE_VAT_SPECIAL = "vat_special";
    public static final String TEST_INVOICE_TYPE_VAT_COMMON = "vat_common";
    public static final String TEST_INVOICE_TYPE_VAT_ELECTRONIC = "vat_electronic";
    public static final String TEST_INVOICE_TYPE_6PCT = "vat_6pct";
    public static final String TEST_INVOICE_TYPE_9PCT = "vat_9pct";

    public static final String TEST_INVOICE_CODE = "1100";
    public static final String TEST_INVOICE_NO_START = "00000001";
    public static final String TEST_INVOICE_NO_MIDDLE = "00005000";
    public static final String TEST_INVOICE_NO_END = "00009999";

    public static final BigDecimal TEST_AMOUNT_SMALL = new BigDecimal("100.00");
    public static final BigDecimal TEST_AMOUNT_STANDARD = new BigDecimal("10000.00");
    public static final BigDecimal TEST_AMOUNT_LARGE = new BigDecimal("999999.99");

    public static final BigDecimal TAX_RATE_13PCT = new BigDecimal("0.13");
    public static final BigDecimal TAX_RATE_9PCT = new BigDecimal("0.09");
    public static final BigDecimal TAX_RATE_6PCT = new BigDecimal("0.06");
    public static final BigDecimal TAX_RATE_3PCT = new BigDecimal("0.03");

    public static final BigDecimal TAX_13PCT_10000 = new BigDecimal("1300.00");
    public static final BigDecimal TOTAL_13PCT_10000 = new BigDecimal("11300.00");

    public static final String TEST_BUYER_NAME = "采购公司";
    public static final String TEST_BUYER_TAX_NO = "911100001234567890";
    public static final String TEST_SELLER_NAME = "销售公司";
    public static final String TEST_SELLER_TAX_NO = "911100000000000000";

    public static final String TEST_USER_NORMAL = "user_001";
    public static final String TEST_USER_URGENT = "user_urgent";
    public static final String TEST_APPROVER = "approver_001";
    public static final String TEST_OPERATOR = "admin";

    public static final int NUMBER_POOL_SIZE = 10000;
    public static final int NUMBER_WARNING_THRESHOLD = 10;
    public static final int NUMBER_WARNING_THRESHOLD_HIGH_FREQ = 100;
    public static final int NUMBER_WARNING_THRESHOLD_LOW_FREQ = 5;

    public static final int VERIFY_TIMEOUT_MS = 5000;
    public static final int VERIFY_MAX_RETRY = 3;
    public static final int VERIFY_RETRY_DELAY_MS = 1000;

    public static final int REIMBURSE_PRIORITY_URGENT = 3;
    public static final int REIMBURSE_PRIORITY_HIGH = 2;
    public static final int REIMBURSE_PRIORITY_NORMAL = 1;

    public static final String VERIFY_TYPE_ONLINE = "online";
    public static final String VERIFY_TYPE_LOCAL = "local";

    public static final String VERIFY_RESULT_VALID = "valid";
    public static final String VERIFY_RESULT_INVALID = "invalid";
    public static final String VERIFY_RESULT_TIMEOUT = "timeout";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_ISSUED = "issued";
    public static final String STATUS_VERIFIED = "verified";
    public static final String STATUS_REIMBURSE_PENDING = "reimburse_pending";
    public static final String STATUS_REIMBURSED = "reimbursed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final long ASYNC_RESPONSE_TIMEOUT_MS = 500;
    public static final long ASYNC_PROCESSING_DELAY_MS = 100;
}
