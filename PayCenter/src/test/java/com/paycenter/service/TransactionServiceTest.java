package com.paycenter.service;

import com.paycenter.dto.PaymentRequest;
import com.paycenter.dto.PaymentResponse;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.entity.Transaction;
import com.paycenter.enums.ChannelType;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.impl.TransactionServiceImpl;
import com.paycenter.testdata.TestDataBuilder;
import com.paycenter.util.IdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("交易处理模块测试")
class TransactionServiceTest {

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private PaymentChannelService paymentChannelService;

    @Mock
    private TransactionStatusService transactionStatusService;

    @InjectMocks
    private TransactionServiceImpl transactionService;

    private PaymentRequest validPaymentRequest;
    private PaymentChannel alipayChannel;
    private Transaction pendingTransaction;

    @BeforeEach
    void setUp() {
        validPaymentRequest = new PaymentRequest();
        validPaymentRequest.setMerchantId(TestDataBuilder.TEST_MERCHANT_ID);
        validPaymentRequest.setOrderNo(TestDataBuilder.TEST_ORDER_NO);
        validPaymentRequest.setAmount(TestDataBuilder.TEST_AMOUNT);
        validPaymentRequest.setChannel("ALIPAY");

        alipayChannel = TestDataBuilder.buildAlipayChannel();
        pendingTransaction = TestDataBuilder.buildPendingTransaction();
    }

    @Nested
    @DisplayName("支付请求创建测试")
    class PaymentCreationTests {

        @Test
        @DisplayName("成功创建支付订单")
        void createPayment_Success() {
            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY)).thenReturn(Optional.of(alipayChannel));
            when(transactionRepository.save(any(Transaction.class))).thenReturn(pendingTransaction);

            PaymentResponse response = transactionService.createPayment(validPaymentRequest);

            assertNotNull(response);
            assertEquals("pending", response.getStatus());
            assertNotNull(response.getTransactionId());
            
            verify(transactionRepository, times(1)).findByOrderNo(TestDataBuilder.TEST_ORDER_NO);
            verify(paymentChannelService, times(1)).getChannelByType(ChannelType.ALIPAY);
            verify(transactionRepository, times(1)).save(any(Transaction.class));
            verify(transactionStatusService, times(1)).logStatusChange(
                    anyString(), isNull(), eq(TransactionStatus.PENDING), anyString());
        }

        @Test
        @DisplayName("订单号已存在时抛出异常")
        void createPayment_DuplicateOrderNo() {
            when(transactionRepository.findByOrderNo(TestDataBuilder.TEST_ORDER_NO))
                    .thenReturn(Optional.of(pendingTransaction));

            BusinessException exception = assertThrows(BusinessException.class, 
                    () -> transactionService.createPayment(validPaymentRequest));

            assertEquals("订单号已存在", exception.getMessage());
            verify(transactionRepository, never()).save(any(Transaction.class));
            verify(transactionStatusService, never()).logStatusChange(anyString(), any(), any(), anyString());
        }

        @Test
        @DisplayName("无效渠道类型时抛出异常")
        void createPayment_InvalidChannelType() {
            validPaymentRequest.setChannel("INVALID_CHANNEL");

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> transactionService.createPayment(validPaymentRequest));

            assertEquals("无效的支付渠道类型", exception.getMessage());
            verify(transactionRepository, never()).save(any(Transaction.class));
        }

        @Test
        @DisplayName("渠道未配置时抛出异常")
        void createPayment_ChannelNotConfigured() {
            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelService.getChannelByType(ChannelType.ALIPAY)).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> transactionService.createPayment(validPaymentRequest));

            assertEquals("支付渠道未配置", exception.getMessage());
            verify(transactionRepository, never()).save(any(Transaction.class));
        }
    }

    @Nested
    @DisplayName("支付确认测试")
    class PaymentConfirmationTests {

        @Test
        @DisplayName("支付成功确认")
        void confirmPayment_Success() {
            Transaction successTransaction = TestDataBuilder.buildSuccessTransaction();
            String notifyData = "{\"trade_status\":\"TRADE_SUCCESS\",\"trade_no\":\"20260510123456\"}";

            when(transactionRepository.findById(anyString())).thenReturn(Optional.of(pendingTransaction));
            when(transactionRepository.save(any(Transaction.class))).thenReturn(successTransaction);

            Transaction result = transactionService.confirmPayment(
                    pendingTransaction.getTransactionId(), true, notifyData);

            assertEquals(TransactionStatus.SUCCESS, result.getStatus());
            assertTrue(result.getNotifyReceived());
            assertEquals(notifyData, result.getNotifyData());
            assertNotNull(result.getPaidAt());
            
            verify(transactionStatusService, times(1)).logStatusChange(
                    anyString(), eq(TransactionStatus.PENDING), eq(TransactionStatus.SUCCESS), eq("支付成功"));
        }

        @Test
        @DisplayName("支付失败确认")
        void confirmPayment_Failed() {
            Transaction failedTransaction = TestDataBuilder.buildFailedTransaction();
            String notifyData = "{\"trade_status\":\"TRADE_CLOSED\",\"reason\":\"用户取消支付\"}";

            when(transactionRepository.findById(anyString())).thenReturn(Optional.of(pendingTransaction));
            when(transactionRepository.save(any(Transaction.class))).thenReturn(failedTransaction);

            Transaction result = transactionService.confirmPayment(
                    pendingTransaction.getTransactionId(), false, notifyData);

            assertEquals(TransactionStatus.FAILED, result.getStatus());
            assertTrue(result.getNotifyReceived());
            assertEquals(notifyData, result.getNotifyData());
            assertNull(result.getPaidAt());
            
            verify(transactionStatusService, times(1)).logStatusChange(
                    anyString(), eq(TransactionStatus.PENDING), eq(TransactionStatus.FAILED), eq("支付失败"));
        }

        @Test
        @DisplayName("交易不存在时抛出异常")
        void confirmPayment_TransactionNotFound() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> transactionService.confirmPayment("invalid_trans_id", true, "{}"));

            assertEquals("交易不存在", exception.getMessage());
        }

        @Test
        @DisplayName("非待支付状态不可确认")
        void confirmPayment_InvalidStatus() {
            Transaction successTransaction = TestDataBuilder.buildSuccessTransaction();
            when(transactionRepository.findById(anyString())).thenReturn(Optional.of(successTransaction));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> transactionService.confirmPayment(successTransaction.getTransactionId(), true, "{}"));

            assertEquals("交易状态不允许确认", exception.getMessage());
            verify(transactionStatusService, never()).logStatusChange(anyString(), any(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("交易查询测试")
    class TransactionQueryTests {

        @Test
        @DisplayName("根据ID查询交易成功")
        void getTransactionById_Success() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.of(pendingTransaction));

            Optional<Transaction> result = transactionService.getTransactionById(pendingTransaction.getTransactionId());

            assertTrue(result.isPresent());
            assertEquals(pendingTransaction.getTransactionId(), result.get().getTransactionId());
            assertEquals(TestDataBuilder.TEST_MERCHANT_ID, result.get().getMerchantId());
        }

        @Test
        @DisplayName("根据ID查询交易不存在")
        void getTransactionById_NotFound() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());

            Optional<Transaction> result = transactionService.getTransactionById("invalid_id");

            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("根据订单号查询交易成功")
        void getTransactionByOrderNo_Success() {
            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.of(pendingTransaction));

            Optional<Transaction> result = transactionService.getTransactionByOrderNo(TestDataBuilder.TEST_ORDER_NO);

            assertTrue(result.isPresent());
            assertEquals(pendingTransaction.getOrderNo(), result.get().getOrderNo());
        }
    }

    @Nested
    @DisplayName("交易状态更新测试")
    class TransactionStatusUpdateTests {

        @Test
        @DisplayName("成功更新交易状态")
        void updateTransactionStatus_Success() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.of(pendingTransaction));
            when(transactionRepository.save(any(Transaction.class))).thenReturn(pendingTransaction);

            Transaction result = transactionService.updateTransactionStatus(
                    pendingTransaction.getTransactionId(), TransactionStatus.SUCCESS);

            assertEquals(TransactionStatus.SUCCESS, result.getStatus());
            verify(transactionStatusService, times(1)).logStatusChange(
                    anyString(), eq(TransactionStatus.PENDING), eq(TransactionStatus.SUCCESS), anyString());
        }

        @Test
        @DisplayName("交易不存在时更新失败")
        void updateTransactionStatus_TransactionNotFound() {
            when(transactionRepository.findById(anyString())).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> transactionService.updateTransactionStatus("invalid_id", TransactionStatus.SUCCESS));

            assertEquals("交易不存在", exception.getMessage());
            verify(transactionStatusService, never()).logStatusChange(anyString(), any(), any(), anyString());
        }
    }
}
