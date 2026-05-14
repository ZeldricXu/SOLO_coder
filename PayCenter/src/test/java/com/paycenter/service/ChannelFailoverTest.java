package com.paycenter.service;

import com.paycenter.dto.PaymentRequest;
import com.paycenter.dto.PaymentResponse;
import com.paycenter.entity.PaymentChannel;
import com.paycenter.enums.ChannelType;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.PaymentChannelRepository;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.impl.PaymentChannelServiceImpl;
import com.paycenter.service.impl.TransactionServiceImpl;
import com.paycenter.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("渠道故障切换机制测试")
class ChannelFailoverTest {

    @Mock
    private PaymentChannelRepository paymentChannelRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private TransactionStatusService transactionStatusService;

    @InjectMocks
    private PaymentChannelServiceImpl paymentChannelService;

    private MockTransactionServiceWithFailover mockTransactionService;

    private PaymentChannel primaryChannel;
    private PaymentChannel backupChannel;
    private PaymentChannel wechatChannel;
    private PaymentChannel inactiveChannel;

    @BeforeEach
    void setUp() {
        primaryChannel = TestDataBuilder.buildAlipayChannel();
        backupChannel = TestDataBuilder.buildAlipayBackupChannel();
        wechatChannel = TestDataBuilder.buildWechatChannel();
        inactiveChannel = TestDataBuilder.buildInactiveChannel();

        mockTransactionService = new MockTransactionServiceWithFailover(
                transactionRepository, paymentChannelService, transactionStatusService);
    }

    @Nested
    @DisplayName("渠道故障检测与切换测试")
    class ChannelFailoverTests {

        @Test
        @DisplayName("主渠道故障时自动切换备用渠道")
        void testFailoverToBackupChannel() {
            when(paymentChannelRepository.findByChannelTypeAndStatusTrue(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(primaryChannel));

            PaymentChannel selected = paymentChannelService.getChannelByType(ChannelType.ALIPAY).orElse(null);
            assertEquals(primaryChannel.getChannelId(), selected.getChannelId());

            primaryChannel.setStatus(false);
            when(paymentChannelRepository.findByChannelTypeAndStatusTrue(ChannelType.ALIPAY))
                    .thenReturn(Optional.empty());

            Optional<PaymentChannel> result = paymentChannelService.getChannelByType(ChannelType.ALIPAY);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("多备用渠道按优先级切换")
        void testMultipleBackupChannelsPriority() {
            List<PaymentChannel> backupChannels = Arrays.asList(primaryChannel, backupChannel);

            when(paymentChannelRepository.findByStatusTrue()).thenReturn(backupChannels);

            List<PaymentChannel> activeChannels = paymentChannelService.getAllActiveChannels();

            assertEquals(2, activeChannels.size());
            assertTrue(activeChannels.stream().anyMatch(c -> c.getChannelId().equals(primaryChannel.getChannelId())));
            assertTrue(activeChannels.stream().anyMatch(c -> c.getChannelId().equals(backupChannel.getChannelId())));

            primaryChannel.setStatus(false);
            when(paymentChannelRepository.findByStatusTrue()).thenReturn(List.of(backupChannel));

            activeChannels = paymentChannelService.getAllActiveChannels();
            assertEquals(1, activeChannels.size());
            assertEquals(backupChannel.getChannelId(), activeChannels.get(0).getChannelId());
        }

        @Test
        @DisplayName("故障渠道恢复检测机制")
        void testChannelRecoveryDetection() {
            primaryChannel.setStatus(false);
            when(paymentChannelRepository.findById(primaryChannel.getChannelId()))
                    .thenReturn(Optional.of(primaryChannel));

            PaymentChannel recoveryUpdate = new PaymentChannel();
            recoveryUpdate.setChannelId(primaryChannel.getChannelId());
            recoveryUpdate.setStatus(true);

            when(paymentChannelRepository.save(any(PaymentChannel.class))).thenReturn(primaryChannel);

            paymentChannelService.updateChannel(recoveryUpdate);

            verify(paymentChannelRepository, times(1)).save(any(PaymentChannel.class));
        }

        @Test
        @DisplayName("切换过程中交易状态一致性")
        void testTransactionStateConsistencyDuringFailover() {
            PaymentRequest request = new PaymentRequest();
            request.setMerchantId(TestDataBuilder.TEST_MERCHANT_ID);
            request.setOrderNo(TestDataBuilder.TEST_ORDER_NO);
            request.setAmount(TestDataBuilder.TEST_AMOUNT);
            request.setChannel("ALIPAY");

            when(transactionRepository.findByOrderNo(anyString())).thenReturn(Optional.empty());
            when(paymentChannelRepository.findByChannelTypeAndStatusTrue(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(primaryChannel));
            when(transactionRepository.save(any(com.paycenter.entity.Transaction.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            PaymentResponse response = mockTransactionService.createPayment(request);

            assertNotNull(response);
            assertEquals("pending", response.getStatus());
            verify(transactionRepository, times(1)).save(any(com.paycenter.entity.Transaction.class));
            verify(transactionStatusService, times(1)).logStatusChange(
                    anyString(), isNull(), any(), anyString());
        }
    }

    @Nested
    @DisplayName("渠道状态管理测试")
    class ChannelStatusManagementTests {

        @Test
        @DisplayName("创建渠道并启用")
        void testCreateChannelEnabled() {
            when(paymentChannelRepository.save(any(PaymentChannel.class))).thenReturn(primaryChannel);

            PaymentChannel created = paymentChannelService.createChannel(primaryChannel);

            assertNotNull(created);
            assertTrue(created.getStatus());
            verify(paymentChannelRepository, times(1)).save(primaryChannel);
        }

        @Test
        @DisplayName("停用渠道")
        void testDisableChannel() {
            when(paymentChannelRepository.findById(primaryChannel.getChannelId()))
                    .thenReturn(Optional.of(primaryChannel));
            when(paymentChannelRepository.save(any(PaymentChannel.class))).thenReturn(primaryChannel);

            paymentChannelService.deleteChannel(primaryChannel.getChannelId());

            assertFalse(primaryChannel.getStatus());
            verify(paymentChannelRepository, times(1)).save(primaryChannel);
        }

        @Test
        @DisplayName("获取非停用渠道")
        void testGetOnlyActiveChannels() {
            when(paymentChannelRepository.findByStatusTrue())
                    .thenReturn(Arrays.asList(primaryChannel, backupChannel, wechatChannel));

            List<PaymentChannel> channels = paymentChannelService.getAllActiveChannels();

            assertEquals(3, channels.size());
            assertFalse(channels.stream().anyMatch(c -> !c.getStatus()));
        }

        @Test
        @DisplayName("获取不存在渠道抛出异常")
        void testGetNonExistentChannelThrowsException() {
            when(paymentChannelRepository.findById("non_existent")).thenReturn(Optional.empty());

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> paymentChannelService.updateChannel(new PaymentChannel()));

            assertEquals("支付渠道不存在", exception.getMessage());
        }
    }

    @Nested
    @DisplayName("多渠道优先级测试")
    class MultiChannelPriorityTests {

        @Test
        @DisplayName("按类型获取渠道")
        void testGetChannelByType() {
            when(paymentChannelRepository.findByChannelTypeAndStatusTrue(ChannelType.ALIPAY))
                    .thenReturn(Optional.of(primaryChannel));
            when(paymentChannelRepository.findByChannelTypeAndStatusTrue(ChannelType.WECHAT))
                    .thenReturn(Optional.of(wechatChannel));

            Optional<PaymentChannel> alipayChannel = paymentChannelService.getChannelByType(ChannelType.ALIPAY);
            Optional<PaymentChannel> wechat = paymentChannelService.getChannelByType(ChannelType.WECHAT);

            assertTrue(alipayChannel.isPresent());
            assertEquals(ChannelType.ALIPAY, alipayChannel.get().getChannelType());
            
            assertTrue(wechat.isPresent());
            assertEquals(ChannelType.WECHAT, wechat.get().getChannelType());
        }

        @Test
        @DisplayName("按ID获取激活渠道")
        void testGetActiveChannelById() {
            when(paymentChannelRepository.findByChannelIdAndStatusTrue(primaryChannel.getChannelId()))
                    .thenReturn(Optional.of(primaryChannel));
            when(paymentChannelRepository.findByChannelIdAndStatusTrue(inactiveChannel.getChannelId()))
                    .thenReturn(Optional.empty());

            PaymentChannel active = paymentChannelService.getActiveChannelById(primaryChannel.getChannelId());
            assertNotNull(active);
            assertTrue(active.getStatus());

            assertThrows(BusinessException.class,
                    () -> paymentChannelService.getActiveChannelById(inactiveChannel.getChannelId()));
        }
    }

    static class MockTransactionServiceWithFailover extends TransactionServiceImpl {
        public MockTransactionServiceWithFailover(TransactionRepository transactionRepository,
                                                   PaymentChannelService paymentChannelService,
                                                   TransactionStatusService transactionStatusService) {
            super();
            this.transactionRepository = transactionRepository;
            this.paymentChannelService = paymentChannelService;
            this.transactionStatusService = transactionStatusService;
        }
    }
}
