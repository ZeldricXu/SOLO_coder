package com.paycenter.service;

import com.paycenter.dto.RefundRequest;
import com.paycenter.dto.RefundResponse;
import com.paycenter.entity.*;
import com.paycenter.enums.*;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.RefundRepository;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.impl.RefundServiceImpl;
import com.paycenter.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("退款处理模块测试")
class RefundServiceTest {

    @Mock
    private RefundRepository refundRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private TransactionStatusService transactionStatusService;

    @Mock
    private TransactionStatService transactionStatService;

    @InjectMocks
    private RefundServiceImpl refundService;

    private RefundRequest validRefundRequest;
    private Transaction successTransaction;
    private Transaction partialRefundTransaction;
    private Transaction fullRefundTransaction;
    private Refund pendingRefund;
    private Account testAccount;

    @BeforeEach
    void setUp() {
        validRefundRequest = new RefundRequest();
        validRefundRequest.setTransactionId("trans_test_001");
        validRefundRequest.setRefundAmount(new BigDecimal("50.00"));
        validRefundRequest.setRefundReason("用户申请部分退款");

        successTransaction = TestDataBuilder.buildSuccessTransaction();
        successTransaction.setTransactionId("trans_test_001");
        successTransaction.setAmount(new BigDecimal("100.00"));
        successTransaction.setRefundedAmount(BigDecimal.ZERO);

        partialRefundTransaction = TestDataBuilder.buildPartialRefundTransaction();
        partialRefundTransaction.setTransactionId("trans_test_001");
        partialRefundTransaction.setAmount(new BigDecimal("100.00"));
        partialRefundTransaction.setRefundedAmount(new BigDecimal("50.00"));

        fullRefundTransaction = TestDataBuilder.buildFullRefundTransaction();
        fullRefundTransaction.setTransactionId("trans_test_001");
        fullRefundTransaction.setAmount(new BigDecimal("100.00"));
        fullRefundTransaction.setRefundedAmount(new BigDecimal("100.00"));

        pendingRefund = TestDataBuilder.buildPendingRefund();
        pendingRefund.setTransactionId("trans_test_001");

        testAccount = TestDataBuilder.buildAccountWithBalance(new BigDecimal("1000.00"));
    }

    @Nested
    @DisplayName("退款申请创建测试")
    class RefundCreationTests {

        @Test
        @DisplayName("成功创建退款申请")
        void createRefund_Success() {
            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(successTransaction));
            when(refundRepository.save(any(Refund.class))).thenReturn(pendingRefund);

            RefundResponse response = refundService.createRefund(validRefundRequest);

            assertNotNull(response);
            assertNotNull(response.getRefundId());
            assertEquals("processing", response.getStatus());

            verify(transactionRepository, times(1)).findById("trans_test_001");
            verify(refundRepository, times(1)).save(any(Refund.class));
        }

        @Test
        @DisplayName("交易不存在时抛出异常")
        void createRefund_TransactionNotFound() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.createRefund(validRefundRequest));

            assertEquals("交易不存在", exception.getMessage());
            verify(refundRepository, never()).save(any(Refund.class));
        }

        @Test
        @DisplayName("交易状态不支持退款时抛出异常")
        void createRefund_InvalidTransactionStatus() {
            Transaction pendingTransaction = TestDataBuilder.buildPendingTransaction();
            pendingTransaction.setTransactionId("trans_test_001");

            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(pendingTransaction));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.createRefund(validRefundRequest));

            assertEquals("交易状态不支持退款", exception.getMessage());
            verify(refundRepository, never()).save(any(Refund.class));
        }

        @Test
        @DisplayName("退款金额超过可退金额时抛出异常")
        void createRefund_AmountExceedsRefundable() {
            validRefundRequest.setRefundAmount(new BigDecimal("150.00"));

            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(successTransaction));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.createRefund(validRefundRequest));

            assertTrue(exception.getMessage().contains("退款金额超过可退款金额"));
            verify(refundRepository, never()).save(any(Refund.class));
        }

        @Test
        @DisplayName("部分退款后剩余可退金额计算正确")
        void createRefund_PartialRefundRemainingAmount() {
            validRefundRequest.setRefundAmount(new BigDecimal("30.00"));

            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(partialRefundTransaction));

            RefundResponse response = refundService.createRefund(validRefundRequest);

            assertNotNull(response);
            assertEquals("processing", response.getStatus());
            verify(refundRepository, times(1)).save(any(Refund.class));
        }

        @Test
        @DisplayName("全额退款后无法再退款")
        void createRefund_FullRefundCannotRefundAgain() {
            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(fullRefundTransaction));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.createRefund(validRefundRequest));

            assertEquals("交易状态不支持退款", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("退款执行测试")
    class RefundExecutionTests {

        @Test
        @DisplayName("退款执行成功，部分退款")
        void executeRefund_Success_Partial() {
            pendingRefund.setRefundAmount(new BigDecimal("50.00"));
            Refund successRefund = TestDataBuilder.buildSuccessRefund();

            when(refundRepository.findById(pendingRefund.getRefundId()))
                    .thenReturn(Optional.of(pendingRefund));
            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(successTransaction));
            when(refundRepository.save(any(Refund.class))).thenReturn(successRefund);
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(accountService.withdraw(eq(TestDataBuilder.TEST_MERCHANT_ID), any(BigDecimal.class), anyString()))
                    .thenReturn(testAccount);

            Refund result = refundService.executeRefund(pendingRefund.getRefundId(), true);

            assertNotNull(result);
            assertEquals(RefundStatus.SUCCESS, result.getRefundStatus());
            assertNotNull(result.getRefundedAt());

            verify(accountService, times(1)).withdraw(
                    eq(TestDataBuilder.TEST_MERCHANT_ID),
                    eq(new BigDecimal("50.00")),
                    anyString());
            verify(transactionStatusService, times(1)).logStatusChange(
                    eq("trans_test_001"),
                    eq(TransactionStatus.SUCCESS),
                    eq(TransactionStatus.PARTIAL_REFUND),
                    anyString());
            verify(transactionStatService, times(1)).updateRefundStats(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any());
        }

        @Test
        @DisplayName("退款执行成功，全额退款")
        void executeRefund_Success_Full() {
            pendingRefund.setRefundAmount(new BigDecimal("100.00"));
            Refund successRefund = TestDataBuilder.buildSuccessRefund();

            when(refundRepository.findById(pendingRefund.getRefundId()))
                    .thenReturn(Optional.of(pendingRefund));
            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(successTransaction));
            when(refundRepository.save(any(Refund.class))).thenReturn(successRefund);
            when(transactionRepository.save(any(Transaction.class)))
                    .thenAnswer(inv -> inv.getArgument(0));
            when(accountService.withdraw(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(testAccount);

            Refund result = refundService.executeRefund(pendingRefund.getRefundId(), true);

            assertNotNull(result);
            assertEquals(RefundStatus.SUCCESS, result.getRefundStatus());

            verify(transactionStatusService, times(1)).logStatusChange(
                    eq("trans_test_001"),
                    eq(TransactionStatus.SUCCESS),
                    eq(TransactionStatus.FULL_REFUND),
                    anyString());
        }

        @Test
        @DisplayName("退款执行失败")
        void executeRefund_Failed() {
            Refund failedRefund = TestDataBuilder.buildFailedRefund();

            when(refundRepository.findById(pendingRefund.getRefundId()))
                    .thenReturn(Optional.of(pendingRefund));
            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(successTransaction));
            when(refundRepository.save(any(Refund.class))).thenReturn(failedRefund);

            Refund result = refundService.executeRefund(pendingRefund.getRefundId(), false);

            assertNotNull(result);
            assertEquals(RefundStatus.FAILED, result.getRefundStatus());
            assertNotNull(result.getFailureReason());

            verify(accountService, never()).withdraw(anyString(), any(BigDecimal.class), anyString());
            verify(transactionStatusService, never()).logStatusChange(anyString(), any(), any(), anyString());
            verify(transactionStatService, never()).updateRefundStats(anyString(), any());
        }

        @Test
        @DisplayName("退款记录不存在时抛出异常")
        void executeRefund_RefundNotFound() {
            when(refundRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.executeRefund("invalid_refund_id", true));

            assertEquals("退款记录不存在", exception.getMessage());
        }

        @Test
        @DisplayName("已处理的退款不可重复执行")
        void executeRefund_AlreadyProcessed() {
            Refund processedRefund = TestDataBuilder.buildSuccessRefund();

            when(refundRepository.findById(processedRefund.getRefundId()))
                    .thenReturn(Optional.of(processedRefund));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.executeRefund(processedRefund.getRefundId(), true));

            assertEquals("退款已处理，不允许重复执行", exception.getMessage());
        }

        @Test
        @DisplayName("账户异常时退款失败")
        void executeRefund_AccountError() {
            when(refundRepository.findById(pendingRefund.getRefundId()))
                    .thenReturn(Optional.of(pendingRefund));
            when(transactionRepository.findById("trans_test_001"))
                    .thenReturn(Optional.of(successTransaction));
            when(accountService.withdraw(anyString(), any(BigDecimal.class), anyString()))
                    .thenThrow(new BusinessException("可用余额不足"));
            when(refundRepository.save(any(Refund.class))).thenReturn(pendingRefund);

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> refundService.executeRefund(pendingRefund.getRefundId(), true));

            assertTrue(exception.getMessage().contains("退款执行失败"));
            assertTrue(exception.getMessage().contains("可用余额不足"));
        }
    }

    @Nested
    @DisplayName("退款查询测试")
    class RefundQueryTests {

        @Test
        @DisplayName("根据ID查询退款记录")
        void getRefundById_Success() {
            Refund successRefund = TestDataBuilder.buildSuccessRefund();
            when(refundRepository.findById(successRefund.getRefundId()))
                    .thenReturn(Optional.of(successRefund));

            Optional<Refund> result = refundService.getRefundById(successRefund.getRefundId());

            assertTrue(result.isPresent());
            assertEquals(successRefund.getRefundId(), result.get().getRefundId());
        }

        @Test
        @DisplayName("查询不存在的退款记录")
        void getRefundById_NotFound() {
            when(refundRepository.findById(anyString())).thenReturn(Optional.empty());

            Optional<Refund> result = refundService.getRefundById("invalid_id");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("根据交易ID查询退款记录")
        void getRefundsByTransaction_Success() {
            Refund refund1 = TestDataBuilder.buildSuccessRefund();
            Refund refund2 = TestDataBuilder.buildPendingRefund();
            List<Refund> refunds = Arrays.asList(refund1, refund2);

            when(refundRepository.findByTransactionId("trans_test_001"))
                    .thenReturn(refunds);

            List<Refund> result = refundService.getRefundsByTransaction("trans_test_001");

            assertEquals(2, result.size());
        }
    }

    @Nested
    @DisplayName("退款状态流转测试")
    class RefundStateFlowTests {

        @Test
        @DisplayName("完整的退款状态流转链路")
        void testCompleteRefundStateFlow() {
            Refund refund = Refund.builder()
                    .refundId("refund_flow_test")
                    .transactionId("trans_test_001")
                    .refundAmount(new BigDecimal("100.00"))
                    .refundStatus(RefundStatus.PENDING)
                    .build();

            assertEquals(RefundStatus.PENDING, refund.getRefundStatus());

            refund.setRefundStatus(RefundStatus.PROCESSING);
            assertEquals(RefundStatus.PROCESSING, refund.getRefundStatus());

            refund.setRefundStatus(RefundStatus.SUCCESS);
            refund.setRefundedAt(java.time.LocalDateTime.now());
            assertEquals(RefundStatus.SUCCESS, refund.getRefundStatus());
            assertNotNull(refund.getRefundedAt());
        }

        @Test
        @DisplayName("退款失败状态流转")
        void testFailedRefundStateFlow() {
            Refund refund = Refund.builder()
                    .refundId("refund_flow_test_2")
                    .transactionId("trans_test_001")
                    .refundAmount(new BigDecimal("100.00"))
                    .refundStatus(RefundStatus.PENDING)
                    .build();

            refund.setRefundStatus(RefundStatus.PROCESSING);
            refund.setRefundStatus(RefundStatus.FAILED);
            refund.setFailureReason("渠道退款接口超时");

            assertEquals(RefundStatus.FAILED, refund.getRefundStatus());
            assertEquals("渠道退款接口超时", refund.getFailureReason());
        }
    }
}
