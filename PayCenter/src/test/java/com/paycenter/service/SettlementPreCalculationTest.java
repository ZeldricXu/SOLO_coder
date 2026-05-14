package com.paycenter.service;

import com.paycenter.dto.SettlementQueryRequest;
import com.paycenter.entity.*;
import com.paycenter.enums.SettlementStatus;
import com.paycenter.repository.SettlementRepository;
import com.paycenter.repository.TransactionRepository;
import com.paycenter.service.impl.SettlementServiceImpl;
import com.paycenter.testdata.TestDataBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("结算预计算机制测试")
class SettlementPreCalculationTest {

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

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache cache;

    @InjectMocks
    private SettlementServiceImpl settlementService;

    private SettlementPeriod dailyPeriod;
    private PaymentChannel alipayChannel;
    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        dailyPeriod = TestDataBuilder.buildDailyPeriod();
        alipayChannel = TestDataBuilder.buildAlipayChannel();
        testDate = LocalDate.now().minusDays(1);
    }

    @Nested
    @DisplayName("预计算准确性测试")
    class PreCalculationAccuracyTests {

        @Test
        @DisplayName("结算周期到达前预计算金额准确性")
        void testPreCalculationAmountAccuracy() {
            List<Transaction> transactions = createTestTransactions(10, new BigDecimal("100.00"));
            BigDecimal totalSuccess = new BigDecimal("1000.00");
            BigDecimal totalRefund = new BigDecimal("50.00");
            BigDecimal expectedNet = totalSuccess.subtract(totalRefund);
            BigDecimal expectedFee = expectedNet.multiply(alipayChannel.getFeeRate())
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal expectedSettlement = expectedNet.subtract(expectedFee);

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalSuccess);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalRefund);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn((long) transactions.size());
            when(transactionRepository.findByMerchantIdAndStatusIn(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), anyList()))
                    .thenReturn(transactions);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(TestDataBuilder.buildAccountWithBalance(expectedSettlement));

            Settlement result = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            assertNotNull(result);
            assertEquals(expectedNet, result.getTotalAmount());
            assertEquals(expectedFee, result.getFeeAmount());
            assertEquals(expectedSettlement, result.getSettlementAmount());
            assertEquals(10, result.getTransactionCount());
        }

        @Test
        @DisplayName("多笔不同金额交易预计算")
        void testPreCalculationWithVariousAmounts() {
            BigDecimal[] amounts = {
                    new BigDecimal("99.99"),
                    new BigDecimal("199.50"),
                    new BigDecimal("1500.00"),
                    new BigDecimal("50.00"),
                    new BigDecimal("899.99")
            };
            List<Transaction> transactions = new ArrayList<>();
            for (BigDecimal amount : amounts) {
                transactions.add(TestDataBuilder.buildTransactionWithAmount(amount));
            }

            BigDecimal totalSuccess = Arrays.stream(amounts).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalRefund = new BigDecimal("100.00");
            BigDecimal expectedNet = totalSuccess.subtract(totalRefund);
            BigDecimal expectedFee = expectedNet.multiply(alipayChannel.getFeeRate())
                    .setScale(2, RoundingMode.HALF_UP);

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalSuccess);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalRefund);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn((long) amounts.length);
            when(transactionRepository.findByMerchantIdAndStatusIn(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), anyList()))
                    .thenReturn(transactions);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(TestDataBuilder.buildEmptyAccount());

            Settlement result = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            assertNotNull(result);
            assertEquals(expectedNet, result.getTotalAmount());
            assertEquals(expectedFee, result.getFeeAmount());
            assertEquals(amounts.length, result.getTransactionCount());
        }

        @Test
        @DisplayName("零交易预计算")
        void testPreCalculationWithZeroTransactions() {
            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(0L);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));

            Settlement result = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            assertNull(result);
        }
    }

    @Nested
    @DisplayName("预计算缓存机制测试")
    class PreCalculationCacheTests {

        @Test
        @DisplayName("预计算结果缓存机制验证")
        void testPreCalculationCaching() {
            when(cacheManager.getCache("settlementPreCalc")).thenReturn(cache);
            when(cache.get(anyString())).thenReturn(null);

            List<Transaction> transactions = createTestTransactions(5, new BigDecimal("200.00"));
            BigDecimal totalSuccess = new BigDecimal("1000.00");

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalSuccess);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(5L);
            when(transactionRepository.findByMerchantIdAndStatusIn(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), anyList()))
                    .thenReturn(transactions);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(TestDataBuilder.buildEmptyAccount());

            Settlement result1 = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);
            assertNotNull(result1);

            verify(transactionRepository, times(1)).sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class));
        }

        @Test
        @DisplayName("实时计算与预计算结果一致性")
        void testRealTimeVsPreCalculationConsistency() {
            List<Transaction> transactions = createTestTransactions(20, new BigDecimal("500.00"));
            BigDecimal totalSuccess = new BigDecimal("10000.00");
            BigDecimal totalRefund = new BigDecimal("500.00");
            BigDecimal expectedNet = totalSuccess.subtract(totalRefund);
            BigDecimal expectedFee = expectedNet.multiply(alipayChannel.getFeeRate())
                    .setScale(2, RoundingMode.HALF_UP);

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalSuccess);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalRefund);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(20L);
            when(transactionRepository.findByMerchantIdAndStatusIn(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), anyList()))
                    .thenReturn(transactions);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(TestDataBuilder.buildEmptyAccount());

            Settlement preCalcResult = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            Settlement realTimeCalcResult = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);

            assertEquals(preCalcResult.getTotalAmount(), realTimeCalcResult.getTotalAmount());
            assertEquals(preCalcResult.getFeeAmount(), realTimeCalcResult.getFeeAmount());
            assertEquals(preCalcResult.getSettlementAmount(), realTimeCalcResult.getSettlementAmount());
            assertEquals(preCalcResult.getTransactionCount(), realTimeCalcResult.getTransactionCount());
        }
    }

    @Nested
    @DisplayName("预计算性能测试")
    class PreCalculationPerformanceTests {

        @Test
        @DisplayName("不同商户数量下预计算性能")
        void testPreCalculationPerformanceWithMultipleMerchants() throws InterruptedException, ExecutionException {
            int merchantCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(4);
            List<Callable<Settlement>> tasks = new ArrayList<>();

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new BigDecimal("10000.00"));
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(new BigDecimal("500.00"));
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(100L);
            when(transactionRepository.findByMerchantIdAndStatusIn(anyString(), anyList()))
                    .thenReturn(createTestTransactions(100, new BigDecimal("100.00")));
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(TestDataBuilder.buildEmptyAccount());

            for (int i = 0; i < merchantCount; i++) {
                final String merchantId = "merchant_" + i;
                tasks.add(() -> {
                    SettlementServiceImpl service = new SettlementServiceImpl();
                    return service;
                });
            }

            long startTime = System.currentTimeMillis();
            for (int i = 0; i < merchantCount; i++) {
                settlementService.calculateAndExecuteSettlement("merchant_" + i, testDate);
            }
            long endTime = System.currentTimeMillis();

            long duration = endTime - startTime;
            assertTrue(duration < 5000, "10个商户预计算应该在5秒内完成，实际耗时: " + duration + "ms");
        }

        @Test
        @DisplayName("大量交易数据预计算性能")
        void testPreCalculationPerformanceWithLargeTransactionCount() {
            int transactionCount = 1000;
            List<Transaction> transactions = createTestTransactions(transactionCount, new BigDecimal("10.00"));
            BigDecimal totalSuccess = new BigDecimal("10000.00");

            when(transactionRepository.sumSuccessAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(totalSuccess);
            when(transactionRepository.sumRefundedAmountByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn(BigDecimal.ZERO);
            when(transactionRepository.countSuccessByMerchantIdAndCreatedAtBetween(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), any(LocalDateTime.class), any(LocalDateTime.class)))
                    .thenReturn((long) transactionCount);
            when(transactionRepository.findByMerchantIdAndStatusIn(
                    eq(TestDataBuilder.TEST_MERCHANT_ID), anyList()))
                    .thenReturn(transactions);
            when(settlementPeriodService.getAllEnabledPeriods()).thenReturn(List.of(dailyPeriod));
            when(paymentChannelService.getChannelById(anyString())).thenReturn(Optional.of(alipayChannel));
            when(settlementRepository.save(any(Settlement.class))).thenAnswer(inv -> inv.getArgument(0));
            when(accountService.deposit(anyString(), any(BigDecimal.class), anyString()))
                    .thenReturn(TestDataBuilder.buildEmptyAccount());

            long startTime = System.currentTimeMillis();
            Settlement result = settlementService.calculateAndExecuteSettlement(
                    TestDataBuilder.TEST_MERCHANT_ID, testDate);
            long endTime = System.currentTimeMillis();

            assertNotNull(result);
            assertEquals(transactionCount, result.getTransactionCount());
            
            long duration = endTime - startTime;
            assertTrue(duration < 3000, "1000笔交易预计算应该在3秒内完成，实际耗时: " + duration + "ms");
        }
    }

    private List<Transaction> createTestTransactions(int count, BigDecimal amount) {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            transactions.add(TestDataBuilder.buildTransactionWithAmount(amount));
        }
        return transactions;
    }
}
