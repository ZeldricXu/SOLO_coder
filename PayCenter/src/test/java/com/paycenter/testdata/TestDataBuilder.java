package com.paycenter.testdata;

import com.alibaba.fastjson.JSON;
import com.paycenter.entity.*;
import com.paycenter.enums.*;
import com.paycenter.util.IdGenerator;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class TestDataBuilder {

    public static final String TEST_MERCHANT_ID = "merchant_001";
    public static final String TEST_MERCHANT_ID_2 = "merchant_002";
    public static final String TEST_ORDER_NO = "20260510123456001";
    public static final BigDecimal TEST_AMOUNT = new BigDecimal("100.00");
    public static final BigDecimal TEST_FEE_RATE = new BigDecimal("0.006");

    public static PaymentChannel buildAlipayChannel() {
        Map<String, Object> config = new HashMap<>();
        config.put("app_id", "2021000000000001");
        config.put("private_key", "test_private_key");
        config.put("notify_url", "https://example.com/notify/alipay");
        
        return PaymentChannel.builder()
                .channelId("channel_alipay_001")
                .channelName("支付宝主渠道")
                .channelType(ChannelType.ALIPAY)
                .channelConfig(JSON.toJSONString(config))
                .feeRate(TEST_FEE_RATE)
                .status(true)
                .build();
    }

    public static PaymentChannel buildAlipayBackupChannel() {
        Map<String, Object> config = new HashMap<>();
        config.put("app_id", "2021000000000002");
        config.put("private_key", "test_backup_private_key");
        config.put("notify_url", "https://example.com/notify/alipay_backup");
        
        return PaymentChannel.builder()
                .channelId("channel_alipay_002")
                .channelName("支付宝备用渠道")
                .channelType(ChannelType.ALIPAY)
                .channelConfig(JSON.toJSONString(config))
                .feeRate(new BigDecimal("0.007"))
                .status(true)
                .build();
    }

    public static PaymentChannel buildWechatChannel() {
        Map<String, Object> config = new HashMap<>();
        config.put("mch_id", "1234567890");
        config.put("api_key", "test_wechat_api_key");
        config.put("notify_url", "https://example.com/notify/wechat");
        
        return PaymentChannel.builder()
                .channelId("channel_wechat_001")
                .channelName("微信支付渠道")
                .channelType(ChannelType.WECHAT)
                .channelConfig(JSON.toJSONString(config))
                .feeRate(new BigDecimal("0.005"))
                .status(true)
                .build();
    }

    public static PaymentChannel buildInactiveChannel() {
        PaymentChannel channel = buildAlipayChannel();
        channel.setChannelId("channel_alipay_inactive");
        channel.setChannelName("已停用支付宝渠道");
        channel.setStatus(false);
        return channel;
    }

    public static Transaction buildPendingTransaction() {
        return Transaction.builder()
                .transactionId(IdGenerator.generateTransactionId())
                .merchantId(TEST_MERCHANT_ID)
                .orderNo(IdGenerator.generateOrderNo())
                .amount(TEST_AMOUNT)
                .channelId("channel_alipay_001")
                .transactionType(TransactionType.PAYMENT)
                .status(TransactionStatus.PENDING)
                .refundedAmount(BigDecimal.ZERO)
                .notifyReceived(false)
                .build();
    }

    public static Transaction buildSuccessTransaction() {
        Transaction transaction = buildPendingTransaction();
        transaction.setStatus(TransactionStatus.SUCCESS);
        transaction.setPaidAt(LocalDateTime.now());
        transaction.setNotifyReceived(true);
        return transaction;
    }

    public static Transaction buildFailedTransaction() {
        Transaction transaction = buildPendingTransaction();
        transaction.setStatus(TransactionStatus.FAILED);
        transaction.setNotifyReceived(true);
        transaction.setFailureReason("支付失败：用户取消支付");
        return transaction;
    }

    public static Transaction buildTransactionWithAmount(BigDecimal amount) {
        Transaction transaction = buildSuccessTransaction();
        transaction.setAmount(amount);
        transaction.setTransactionId(IdGenerator.generateTransactionId());
        transaction.setOrderNo(IdGenerator.generateOrderNo());
        return transaction;
    }

    public static Transaction buildPartialRefundTransaction() {
        Transaction transaction = buildSuccessTransaction();
        transaction.setStatus(TransactionStatus.PARTIAL_REFUND);
        transaction.setRefundedAmount(new BigDecimal("50.00"));
        return transaction;
    }

    public static Transaction buildFullRefundTransaction() {
        Transaction transaction = buildSuccessTransaction();
        transaction.setStatus(TransactionStatus.FULL_REFUND);
        transaction.setRefundedAmount(TEST_AMOUNT);
        return transaction;
    }

    public static Settlement buildPendingSettlement() {
        LocalDate settlementDate = LocalDate.now().minusDays(1);
        return Settlement.builder()
                .settlementId(IdGenerator.generateSettlementId())
                .merchantId(TEST_MERCHANT_ID)
                .settlementPeriod(settlementDate)
                .transactionCount(10)
                .totalAmount(new BigDecimal("1000.00"))
                .settlementAmount(new BigDecimal("994.00"))
                .feeAmount(new BigDecimal("6.00"))
                .settlementStatus(SettlementStatus.PENDING)
                .build();
    }

    public static Settlement buildCompletedSettlement() {
        Settlement settlement = buildPendingSettlement();
        settlement.setSettlementStatus(SettlementStatus.COMPLETED);
        settlement.setSettledAt(LocalDateTime.now());
        return settlement;
    }

    public static Settlement buildFailedSettlement() {
        Settlement settlement = buildPendingSettlement();
        settlement.setSettlementStatus(SettlementStatus.FAILED);
        settlement.setFailureReason("银行接口调用失败");
        return settlement;
    }

    public static Refund buildPendingRefund() {
        return Refund.builder()
                .refundId(IdGenerator.generateRefundId())
                .transactionId("trans_test_001")
                .refundAmount(new BigDecimal("50.00"))
                .refundReason("用户申请部分退款")
                .refundStatus(RefundStatus.PENDING)
                .build();
    }

    public static Refund buildSuccessRefund() {
        Refund refund = buildPendingRefund();
        refund.setRefundStatus(RefundStatus.SUCCESS);
        refund.setRefundedAt(LocalDateTime.now());
        return refund;
    }

    public static Refund buildFailedRefund() {
        Refund refund = buildPendingRefund();
        refund.setRefundStatus(RefundStatus.FAILED);
        refund.setFailureReason("退款金额超过可退金额");
        return refund;
    }

    public static Account buildEmptyAccount() {
        return Account.builder()
                .accountId(IdGenerator.generateAccountId(TEST_MERCHANT_ID))
                .merchantId(TEST_MERCHANT_ID)
                .balance(BigDecimal.ZERO)
                .frozenAmount(BigDecimal.ZERO)
                .availableBalance(BigDecimal.ZERO)
                .build();
    }

    public static Account buildAccountWithBalance(BigDecimal balance) {
        Account account = buildEmptyAccount();
        account.setBalance(balance);
        account.setAvailableBalance(balance);
        return account;
    }

    public static Account buildAccountWithFrozenAmount(BigDecimal balance, BigDecimal frozenAmount) {
        Account account = buildEmptyAccount();
        account.setBalance(balance);
        account.setFrozenAmount(frozenAmount);
        account.setAvailableBalance(balance.subtract(frozenAmount));
        return account;
    }

    public static SettlementPeriod buildDailyPeriod() {
        Map<String, Object> config = new HashMap<>();
        config.put("execute_time", "00:00");
        
        return SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .periodType(PeriodType.DAILY)
                .periodConfig(JSON.toJSONString(config))
                .minSettlementAmount(new BigDecimal("100.00"))
                .enabled(true)
                .build();
    }

    public static SettlementPeriod buildWeeklyPeriod() {
        Map<String, Object> config = new HashMap<>();
        config.put("execute_time", "02:00");
        config.put("settle_day", 1);
        
        return SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .periodType(PeriodType.WEEKLY)
                .periodConfig(JSON.toJSONString(config))
                .minSettlementAmount(new BigDecimal("500.00"))
                .enabled(true)
                .build();
    }

    public static SettlementPeriod buildMonthlyPeriod() {
        Map<String, Object> config = new HashMap<>();
        config.put("execute_time", "03:00");
        config.put("settle_day", 1);
        
        return SettlementPeriod.builder()
                .periodId(IdGenerator.generatePeriodId())
                .periodType(PeriodType.MONTHLY)
                .periodConfig(JSON.toJSONString(config))
                .minSettlementAmount(new BigDecimal("1000.00"))
                .enabled(true)
                .build();
    }

    public static TransactionStat buildDailyStat(LocalDate date) {
        return TransactionStat.builder()
                .statId(IdGenerator.generateStatId())
                .merchantId(TEST_MERCHANT_ID)
                .statDate(date)
                .transactionCount(100)
                .totalAmount(new BigDecimal("10000.00"))
                .successCount(95)
                .failCount(5)
                .refundCount(3)
                .build();
    }

    public static TransactionStatusLog buildStatusLog(String transactionId, 
                                                      TransactionStatus from, 
                                                      TransactionStatus to, 
                                                      String remark) {
        return TransactionStatusLog.builder()
                .transactionId(transactionId)
                .fromStatus(from)
                .toStatus(to)
                .remark(remark)
                .build();
    }
}
