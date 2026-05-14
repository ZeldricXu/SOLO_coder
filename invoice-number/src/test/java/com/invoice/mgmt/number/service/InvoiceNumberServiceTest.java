package com.invoice.mgmt.number.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.invoice.mgmt.common.entity.InvoiceNumber;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.testdata.AsyncTestUtil;
import com.invoice.mgmt.common.testdata.MockConstants;
import com.invoice.mgmt.common.testdata.TestDataBuilder;
import com.invoice.mgmt.number.mapper.InvoiceNumberMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("发票号码服务单元测试")
class InvoiceNumberServiceTest {

    @Mock
    private InvoiceNumberMapper invoiceNumberMapper;

    @InjectMocks
    private InvoiceNumberService invoiceNumberService;

    private TestAppender testAppender;

    @BeforeEach
    void setUp() {
        testAppender = new TestAppender();
        Logger logger = (Logger) LoggerFactory.getLogger(InvoiceNumberService.class);
        logger.addAppender(testAppender);
        logger.setLevel(Level.WARN);
        testAppender.start();
    }

    @AfterEach
    void tearDown() {
        if (testAppender != null) {
            Logger logger = (Logger) LoggerFactory.getLogger(InvoiceNumberService.class);
            logger.detachAppender(testAppender);
        }
    }

    @Nested
    @DisplayName("号码预警机制测试")
    class NumberWarningMechanismTests {

        @Test
        @Order(1)
        @DisplayName("测试号码使用达到预警阈值时触发预警通知")
        void testWarningTriggeredWhenReachThreshold() {
            int threshold = 10;
            List<InvoiceNumber> nearExhausted = TestDataBuilder.ListBuilder.buildNearExhaustedNumberPool(
                    MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL, threshold);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(nearExhausted.get(0));
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenReturn(1);

            testAppender.clearEvents();
            String allocatedNo = invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);

            assertNotNull(allocatedNo);
            assertTrue(testAppender.hasWarningMessage("发票号码预警"),
                    "应该触发号码预警日志");
            verify(invoiceNumberMapper, times(1)).updateUsedCount(anyLong(), anyInt(), anyInt(), anyString());
        }

        @Test
        @Order(2)
        @DisplayName("测试号码剩余充足时不触发预警")
        void testNoWarningWhenNumbersSufficient() {
            List<InvoiceNumber> sufficientPool = TestDataBuilder.ListBuilder.buildNumberPoolList(
                    MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL, 3);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(sufficientPool.get(0));
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenReturn(1);

            testAppender.clearEvents();
            String allocatedNo = invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);

            assertNotNull(allocatedNo);
            assertEquals("00000001", allocatedNo);
            assertFalse(testAppender.hasWarningMessage("发票号码预警"),
                    "号码充足时不应该触发预警");
        }

        @Test
        @Order(3)
        @DisplayName("测试边界值：剩余11条时不触发预警")
        void testNoWarningAtBoundaryAboveThreshold() {
            int aboveThreshold = 11;
            List<InvoiceNumber> pool = TestDataBuilder.ListBuilder.buildNearExhaustedNumberPool(
                    MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL, aboveThreshold);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(pool.get(0));
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenReturn(1);

            testAppender.clearEvents();
            invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);

            assertFalse(testAppender.hasWarningMessage("发票号码预警"),
                    "剩余11条时不应触发预警");
        }

        @Test
        @Order(4)
        @DisplayName("测试边界值：剩余10条时触发预警")
        void testWarningAtBoundaryThreshold() {
            int atThreshold = 10;
            List<InvoiceNumber> pool = TestDataBuilder.ListBuilder.buildNearExhaustedNumberPool(
                    MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL, atThreshold);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(pool.get(0));
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenReturn(1);

            testAppender.clearEvents();
            invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);

            assertTrue(testAppender.hasWarningMessage("发票号码预警"),
                    "剩余10条时应触发预警");
        }
    }

    @Nested
    @DisplayName("号码不足时告警时效测试")
    class InsufficientNumberAlertTests {

        @Test
        @Order(5)
        @DisplayName("测试号码耗尽时立即抛出异常")
        void testImmediateExceptionWhenExhausted() {
            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(null);

            long startTime = System.currentTimeMillis();
            InvoiceException exception = assertThrows(InvoiceException.class, () -> {
                invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);
            });
            long elapsed = System.currentTimeMillis() - startTime;

            assertTrue(elapsed < 100, "应该立即抛出异常，耗时: " + elapsed + "ms");
            assertTrue(exception.getMessage().contains("发票号码不足"));
        }

        @Test
        @Order(6)
        @DisplayName("测试剩余数量为0时抛出异常")
        void testExceptionWhenRemainingIsZero() {
            InvoiceNumber exhaustedNumber = TestDataBuilder.invoiceNumber()
                    .invoiceType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL)
                    .invoiceCode(MockConstants.TEST_INVOICE_CODE)
                    .startNo("00000001")
                    .endNo("00000100")
                    .currentNo("00000100")
                    .totalCount(100)
                    .usedCount(100)
                    .remainingCount(0)
                    .status("active")
                    .build();

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(exhaustedNumber);

            assertThrows(InvoiceException.class, () -> {
                invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);
            });
        }
    }

    @Nested
    @DisplayName("不同开票频率下的预警阈值差异测试")
    class FrequencyBasedWarningTests {

        @Test
        @Order(7)
        @DisplayName("测试高频开票场景：快速连续分配号码")
        void testHighFrequencyAllocation() throws Exception {
            List<InvoiceNumber> pools = TestDataBuilder.ListBuilder.buildNumberPoolList(
                    MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL, 2);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(pools.get(0));
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenReturn(1);

            int allocationCount = 20;
            CountDownLatch latch = new CountDownLatch(allocationCount);
            AtomicInteger warningCount = new AtomicInteger(0);

            testAppender.clearEvents();

            ExecutorService executor = Executors.newFixedThreadPool(4);
            List<Future<String>> futures = new ArrayList<>();

            for (int i = 0; i < allocationCount; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    try {
                        String no = invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);
                        latch.countDown();
                        return no;
                    } catch (Exception e) {
                        latch.countDown();
                        throw e;
                    }
                }));
            }

            boolean completed = AsyncTestUtil.awaitLatch(latch, 5000);
            assertTrue(completed, "所有分配任务应在5秒内完成");

            executor.shutdown();
        }

        @Test
        @Order(8)
        @DisplayName("测试多类型发票号码独立预警")
        void testIndependentWarningPerType() {
            List<InvoiceNumber> vatSpecialPool = TestDataBuilder.ListBuilder.buildNearExhaustedNumberPool(
                    MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL, 8);
            List<InvoiceNumber> vatCommonPool = TestDataBuilder.ListBuilder.buildNumberPoolList(
                    MockConstants.TEST_INVOICE_TYPE_VAT_COMMON, 2);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(vatSpecialPool.get(0));
            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_COMMON))
                    .thenReturn(vatCommonPool.get(0));
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenReturn(1);

            testAppender.clearEvents();

            invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);
            int specialWarnings = testAppender.countWarningMessages("发票号码预警");

            testAppender.clearEvents();

            invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_COMMON);
            int commonWarnings = testAppender.countWarningMessages("发票号码预警");

            assertEquals(1, specialWarnings, "专用发票应触发预警");
            assertEquals(0, commonWarnings, "普通发票不应触发预警");
        }
    }

    @Nested
    @DisplayName("预警阈值与开票频率关联计算测试")
    class ThresholdCalculationTests {

        @Test
        @Order(9)
        @DisplayName("测试号码顺序分配正确性")
        void testSequentialNumberAllocation() {
            InvoiceNumber pool = TestDataBuilder.invoiceNumber()
                    .invoiceType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL)
                    .invoiceCode(MockConstants.TEST_INVOICE_CODE)
                    .startNo("00000001")
                    .endNo("00000010")
                    .currentNo("00000001")
                    .totalCount(10)
                    .usedCount(0)
                    .remainingCount(10)
                    .status("active")
                    .build();

            List<String> allocatedNumbers = new ArrayList<>();
            AtomicInteger remaining = new AtomicInteger(10);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(pool);
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        int newRemaining = invocation.getArgument(2);
                        remaining.set(newRemaining);
                        if (newRemaining > 0) {
                            pool.setCurrentNo(String.format("%08d", 11 - newRemaining));
                            pool.setUsedCount(10 - newRemaining);
                            pool.setRemainingCount(newRemaining);
                        }
                        return 1;
                    });

            for (int i = 1; i <= 10; i++) {
                String no = invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);
                allocatedNumbers.add(no);
            }

            assertEquals(10, allocatedNumbers.size());
            assertEquals("00000001", allocatedNumbers.get(0));
            assertEquals("00000005", allocatedNumbers.get(4));
            assertEquals("00000010", allocatedNumbers.get(9));
        }

        @Test
        @Order(10)
        @DisplayName("测试创建号码池时计数计算正确性")
        void testPoolCountCalculationOnCreate() {
            String type = MockConstants.TEST_INVOICE_TYPE_VAT_ELECTRONIC;
            String code = "1102";
            String startNo = "00010001";
            String endNo = "00011000";

            when(invoiceNumberMapper.insert(any(InvoiceNumber.class))).thenReturn(1);

            InvoiceNumber created = invoiceNumberService.create(type, code, startNo, endNo);

            assertEquals(1000, created.getTotalCount());
            assertEquals(0, created.getUsedCount());
            assertEquals(1000, created.getRemainingCount());
            assertEquals(startNo, created.getCurrentNo());
        }

        @Test
        @Order(11)
        @DisplayName("测试无效号码范围抛出异常")
        void testInvalidRangeThrowsException() {
            String invalidStart = "00010000";
            String invalidEnd = "00009999";

            assertThrows(InvoiceException.class, () -> {
                invoiceNumberService.create(
                        MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL,
                        MockConstants.TEST_INVOICE_CODE,
                        invalidStart,
                        invalidEnd
                );
            }, "起始号大于结束号应抛出异常");
        }

        @Test
        @Order(12)
        @DisplayName("测试获取剩余数量汇总")
        void testRemainingCountSum() {
            List<InvoiceNumber> multiPools = new ArrayList<>();
            multiPools.add(TestDataBuilder.invoiceNumber()
                    .invoiceType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL)
                    .invoiceCode("1100")
                    .remainingCount(50)
                    .build());
            multiPools.add(TestDataBuilder.invoiceNumber()
                    .invoiceType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL)
                    .invoiceCode("1101")
                    .remainingCount(30)
                    .build());
            multiPools.add(TestDataBuilder.invoiceNumber()
                    .invoiceType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL)
                    .invoiceCode("1102")
                    .remainingCount(20)
                    .build());

            when(invoiceNumberMapper.findAvailableByType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(multiPools);

            int totalRemaining = invoiceNumberService.getRemainingCount(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);

            assertEquals(100, totalRemaining, "剩余数量应汇总所有号码池");
        }
    }

    @Nested
    @DisplayName("并发场景测试")
    class ConcurrentTests {

        @Test
        @Order(13)
        @DisplayName("测试并发号码分配线程安全")
        void testConcurrentAllocationThreadSafety() throws Exception {
            int threadCount = 10;
            int allocationsPerThread = 5;
            int totalAllocations = threadCount * allocationsPerThread;

            InvoiceNumber pool = TestDataBuilder.invoiceNumber()
                    .invoiceType(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL)
                    .invoiceCode(MockConstants.TEST_INVOICE_CODE)
                    .startNo("00000001")
                    .endNo("00000100")
                    .currentNo("00000001")
                    .totalCount(100)
                    .usedCount(0)
                    .remainingCount(100)
                    .status("active")
                    .build();

            Set<String> allocatedSet = ConcurrentHashMap.newKeySet();
            AtomicInteger currentNo = new AtomicInteger(1);

            when(invoiceNumberMapper.findFirstAvailable(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL))
                    .thenReturn(pool);
            when(invoiceNumberMapper.updateUsedCount(anyLong(), anyInt(), anyInt(), anyString()))
                    .thenAnswer(invocation -> {
                        int newRemaining = invocation.getArgument(2);
                        pool.setUsedCount(100 - newRemaining);
                        pool.setRemainingCount(newRemaining);
                        pool.setCurrentNo(String.format("%08d", currentNo.incrementAndGet()));
                        return 1;
                    });

            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            List<Callable<String>> tasks = new ArrayList<>();

            for (int t = 0; t < threadCount; t++) {
                for (int i = 0; i < allocationsPerThread; i++) {
                    tasks.add(() -> {
                        try {
                            String no = invoiceNumberService.allocate(MockConstants.TEST_INVOICE_TYPE_VAT_SPECIAL);
                            allocatedSet.add(no);
                            return no;
                        } catch (Exception e) {
                            throw e;
                        }
                    });
                }
            }

            List<Future<String>> futures = executor.invokeAll(tasks);
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.SECONDS);

            assertEquals(totalAllocations, allocatedSet.size(),
                    "并发分配不应产生重复号码，期望" + totalAllocations + "个唯一号码，实际" + allocatedSet.size() + "个");
        }
    }

    static class TestAppender implements Appender<ILoggingEvent> {
        private final List<ILoggingEvent> events = new ArrayList<>();
        private String name = "TestAppender";
        private boolean started = false;

        @Override
        public String getName() { return name; }
        @Override
        public void setName(String name) { this.name = name; }
        @Override
        public void start() { started = true; }
        @Override
        public void stop() { started = false; }
        @Override
        public boolean isStarted() { return started; }
        @Override
        public void doAppend(ILoggingEvent event) { events.add(event); }

        public List<ILoggingEvent> getEvents() { return new ArrayList<>(events); }
        public void clearEvents() { events.clear(); }

        public boolean hasWarningMessage(String substring) {
            return events.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .anyMatch(e -> e.getMessage().contains(substring));
        }

        public int countWarningMessages(String substring) {
            return (int) events.stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .filter(e -> e.getMessage().contains(substring))
                    .count();
        }

        @Override public void setContext(ch.qos.logback.core.Context context) {}
        @Override public ch.qos.logback.core.Context getContext() { return null; }
        @Override public void addStatus(ch.qos.logback.core.status.Status status) {}
        @Override public void addInfo(String msg) {}
        @Override public void addInfo(String msg, Throwable ex) {}
        @Override public void addWarn(String msg) {}
        @Override public void addWarn(String msg, Throwable ex) {}
        @Override public void addError(String msg) {}
        @Override public void addError(String msg, Throwable ex) {}
    }
}
