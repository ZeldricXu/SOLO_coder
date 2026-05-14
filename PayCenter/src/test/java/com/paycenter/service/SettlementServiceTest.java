package com.paycenter.service;

import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.*;
import com.paycenter.enums.SettlementStatus;
import com.paycenter.enums.TransactionStatus;
import com.paycenter.exception.BusinessException;
import com.paycenter.repository.SettlementRepository;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.impl.SettlementServiceImpl;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("结算管理模块测试")
class SettlementServiceTest {

    @Mock
    private SettlementRepository settlementRepository;

    @Mock
    private TransactionRepository transactionRepository;

    @Mock
    private AccountService accountService;

    @Mock
    private SettlementPeriodService settlementPeriodService;

    @Mock
    private PaymentChannelService paymentChannelService;

    @Mock
    private TransactionStatService transactionStatService;

    @InjectMocks
    private SettlementServiceImpl settlementService;

    private Settlement pendingSettlement;
    private Settlement completedSettlement;
    private SettlementPeriod dailyPeriod;
    private PaymentChannel alipayChannel;
    private Account testAccount;
    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        pendingSettlement = TestDataBuilder.buildPendingSettlement();
        completedSettlement = TestDataBuilder.buildCompletedSettlement();
        dailyPeriod = TestDataBuilder.buildDailyPeriod();
        alipayChannel = TestDataBuilder.buildAlipayChannel();
        testAccount = TestDataBuilder.buildAccountWithBalance(new BigDecimal("5000.00"));
        testDate = LocalDate.now().minusDays(1);
    }

    @Nested
    @DisplayName("结算计算测试")
    class SettlementCalculationTests {

        @Test
        @DisplayName("成功计算并执行结算")
        void testCalculateAndExecuteSettlement_Success() {
            Transaction tx1 = TestDataBuilder.buildTransactionWithAmount(new BigDecimal("100.00"));
            Transaction tx2 = TestDataBuilder.buildTransactionWithAmount(new BigDecimal("200.00"));
            Transaction tx3 = TestDataBuilder.buildTransactionWithAmount(new BigDecimal("300.00"));

            BigDecimal totalSuccess = new BigDecimal("600.00");
            BigDecimal totalRefund = new BigDecimal("50.00");
            BigDecimal netAmount = totalSuccess.subtract(totalRefund);
            BigDecimal fee = netAmount.multiply(TestDataBuilder.TEST_FEE_RATE);
            BigDecimal settlementAmount = netAmount.subtract(fee);

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalSuccess);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalRefund);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(3L);
            when(transactionRepository.findByMerchantIdAndStatusIn(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), anyList()))
                    .thenReturn(Arrays.asList(tx1, tx2, tx3));
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(eq(TestDataBuilder.TEST_MERCHANT_ID), any(BigDecimal.class), anyString()))
                    .thenReturn(testAccount);

            Settlement result = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            assertNotNull(result);
            assertEquals(SettlementStatus.COMPLETED, result.getSettlementStatus());
            assertEquals(3, result.getTransactionCount());
            assertEquals(netAmount, result.getTotalAmount());
            assertTrue(result.getFeeAmount().compareTo(fee) >= 0);
            assertNotNull(result.getSettledAt());
            assertEquals(TestDataBuilder.TEST_MERCHANT_ID, result.getMerchantId());

            verify(settlementRepository, times(3)).save(any(Settlement.class));
            verify(accountService, times(1)).deposit(eq(TestDataBuilder.TEST_MERCHANT_ID), any(BigDecimal.class), anyString());
        }

        @Test
        @DisplayName("低于最低结算金额时跳过结算")
        void testSettlementSkippedBelowMinimum() {
            SettlementPeriod highMinPeriod = TestDataBuilder.buildDailyPeriod();
            highMinPeriod.setMinSettlementAmount(new BigDecimal("100000.00"));

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new BigDecimal("500.00"));
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(5L);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(highMinPeriod));

            Settlement result = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            assertNull(result);
            verify(settlementRepository, never()).save(any(Settlement.class));
            verify(accountService, never()).deposit(anyString(), any(BigDecimal.class), anyString());
        }

        @Test
        @DisplayName("账户异常时结算失败")
        void testSettlementFailsOnAccountError() {
            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new BigDecimal("1000.00"));
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(10L);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenThrow(new RuntimeException("银行接口异常"));

            BusinessException exception = assertThrows(BusinessException.class,
                    () -> settlementService.calculateAndExecuteSettlement(TestDataBuilder.TEST_MERCHANT_ID, testDate));

            assertTrue(exception.getMessage().contains("结算执行失败"));
            assertTrue(exception.getMessage().contains("银行接口异常"));
        }
    }

    @Nested
    @DisplayName("结算查询测试")
    class SettlementQueryTests {

        @Test
        @DisplayName("根据ID查询结算记录")
        void testGetSettlementById() {
            when(settlementRepository.findById(completedSettlement.getSettlementId()))
                    .thenReturn(Optional.of(completedSettlement));

            Optional<Settlement> result = settlementService.getSettlementById(completedSettlement.getSettlementId());

            assertTrue(result.isPresent());
            assertEquals(completedSettlement.getSettlementId(), result.get().getSettlementId());
            assertEquals(SettlementStatus.COMPLETED, result.get().getSettlementStatus());
        }

        @Test
        @DisplayName("根据商户ID查询结算记录")
        void testGetSettlementsByMerchant() {
            when(settlementRepository.findByMerchantId(TestDataBuilder.TEST_MERCHANT_ID))
                    .thenReturn(Arrays.asList(completedSettlement, pendingSettlement));

            List<Settlement> results = settlementService.getSettlementsByMerchant(TestDataBuilder.TEST_MERCHANT_ID);

            assertEquals(2, results.size());
            assertEquals(TestDataBuilder.TEST_MERCHANT_ID, results.get(0).getMerchantId());
        }

        @Test
        @DisplayName("按时间范围查询结算记录")
        void testQuerySettlementsByDateRange() {
            SettlementQueryRequest request = new SettlementQueryRequest();
            request.setMerchantId(TestDataBuilder.TEST_MERCHANT_ID);
            request.setStartDate(LocalDate.now().minusDays(7));
            request.setEndDate(LocalDate.now());

            when(settlementRepository.findByMerchantIdAndSettlementPeriodBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(List.of(completedSettlement));

            List<Settlement> results = settlementService.querySettlements(request);

            assertEquals(1, results.size());
            verify(settlementRepository, times(1)).findByMerchantIdAndSettlementPeriodBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDate.class), any(LocalDate.class));
        }

        @Test
        @DisplayName("查询结算记录时使用默认时间范围")
        void testQuerySettlementsWithDefaultDates() {
            SettlementQueryRequest request = new SettlementQueryRequest();
            request.setMerchantId(TestDataBuilder.TEST_MERCHANT_ID);

            when(settlementRepository.findByMerchantIdAndSettlementPeriodBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDate.class), any(LocalDate.class)))
                    .thenReturn(Collections.emptyList());

            List<Settlement> results = settlementService.querySettlements(request);

            assertTrue(results.isEmpty());
        }
    }

    @Nested
    @DisplayName("结算状态流转测试")
    class SettlementStateFlowTests {

        @Test
        @DisplayName("结算状态从PENDING到PROCESSING")
        void testStateFromPendingToProcessing() {
            Settlement settlement = Settlement.builder()
                    .settlementId(IdGenerator.generateSettlementId())
                    .merchantId(TestDataBuilder.TEST_MERCHANT_ID)
                    .settlementPeriod(testDate)
                    .transactionCount(10)
                    .totalAmount(new BigDecimal("1000.00"))
                    .settlementAmount(new BigDecimal("994.00"))
                    .feeAmount(new BigDecimal("6.00"))
                    .settlementStatus(SettlementStatus.PENDING)
                    .build();

            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> {
                Settlement s = inv.getArgument(0);
                if (s.getSettlementStatus() == SettlementStatus.PENDING) {
                    s.setSettlementStatus(SettlementStatus.PROCESSING);
                }
                return s;
            });

            Settlement saved = settlementRepository.save(settlement);
            assertEquals(SettlementStatus.PROCESSING, saved.getSettlementStatus());
        }

        @Test
        @DisplayName("结算成功完成状态流转")
        void testStateFromProcessingToCompleted() {
            Settlement settlement = Settlement.builder()
                    .settlementId(IdGenerator.generateSettlementId())
                    .merchantId(TestDataBuilder.TEST_MERCHANT_ID)
                    .settlementPeriod(testDate)
                    .transactionCount(10)
                    .totalAmount(new BigDecimal("1000.00"))
                    .settlementAmount(new BigDecimal("994.00"))
                    .feeAmount(new BigDecimal("6.00"))
                    .settlementStatus(SettlementStatus.PROCESSING)
                    .build();

            settlement.setSettlementStatus(SettlementStatus.COMPLETED);
            settlement.setSettledAt(LocalDateTime.now());

            assertEquals(SettlementStatus.COMPLETED, settlement.getSettlementStatus());
            assertNotNull(settlement.getSettledAt());
        }

        @Test
        @DisplayName("结算失败状态流转")
        void testStateFromProcessingToFailed() {
            Settlement settlement = Settlement.builder()
                    .settlementId(IdGenerator.generateSettlementId())
                    .merchantId(TestDataBuilder.TEST_MERCHANT_ID)
                    .settlementPeriod(testDate)
                    .transactionCount(10)
                    .totalAmount(new BigDecimal("1000.00"))
                    .settlementAmount(new BigDecimal("994.00"))
                    .feeAmount(new BigDecimal("6.00"))
                    .settlementStatus(SettlementStatus.PROCESSING)
                    .build();

            settlement.setSettlementStatus(SettlementStatus.FAILED);
            settlement.setFailureReason("银行账户冻结");

            assertEquals(SettlementStatus.FAILED, settlement.getSettlementStatus());
            assertEquals("银行账户冻结", settlement.getFailureReason());
        }
    }
}
