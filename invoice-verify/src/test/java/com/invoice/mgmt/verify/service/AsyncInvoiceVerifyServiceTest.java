package com.invoice.mgmt.verify.service;

import com.invoice.mgmt.common.dto.InvoiceVerifyRequest;
import com.invoice.mgmt.common.dto.InvoiceVerifyResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.enums.VerifyResultEnum;
import com.invoice.mgmt.common.enums.VerifyTypeEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.testdata.AsyncTestUtil;
import com.invoice.mgmt.common.testdata.MockConstants;
import com.invoice.mgmt.common.testdata.TestDataBuilder;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.verify.mapper.InvoiceVerifyMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("异步发票验证服务单元测试")
class AsyncInvoiceVerifyServiceTest {

    @Mock
    private InvoiceVerifyMapper verifyMapper;

    @Mock
    private InvoiceIssueService invoiceIssueService;

    @Mock
    private InvoiceStatusService invoiceStatusService;

    @Mock
    private InvoiceStatisticsService invoiceStatisticsService;

    @Mock
    private InvoiceHistoryService invoiceHistoryService;

    @InjectMocks
    private AsyncInvoiceVerifyService asyncVerifyService;

    @BeforeEach
    void setUp() throws Exception {
        setPrivateField(asyncVerifyService, "onlineTimeoutMs", 5000);
        setPrivateField(asyncVerifyService, "mockOnline", true);
        setPrivateField(asyncVerifyService, "maxRetry", 3);
        setPrivateField(asyncVerifyService, "retryDelayMs", 100);
        asyncVerifyService.resetRetryCounter();
    }

    private void setPrivateField(Object obj, String fieldName, Object value) throws Exception {
        Field field = obj.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(obj, value);
    }

    @Nested
    @DisplayName("验证异步化测试")
    class AsyncVerifyTests {

        @Test
        @Order(1)
        @DisplayName("测试验证请求提交后立即返回taskId不阻塞")
        void testSubmitVerifyReturnsImmediately() {
            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000001", "1100", VerifyTypeEnum.ONLINE.getCode());

            long startTime = System.currentTimeMillis();
            String taskId = asyncVerifyService.submitVerifyAsync(request);
            long elapsed = System.currentTimeMillis() - startTime;

            assertNotNull(taskId);
            assertTrue(elapsed < 100, "提交应立即返回，耗时: " + elapsed + "ms");
            assertTrue(taskId.startsWith("verify_"), "taskId格式正确");
        }

        @Test
        @Order(2)
        @DisplayName("测试后台Worker执行验证计算与结果更新")
        void testWorkerProcessesTaskAndUpdatesResult() throws Exception {
            Invoice validInvoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_test_001")
                    .invoiceNo("00000001")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000001", "1100", VerifyTypeEnum.ONLINE.getCode());

            when(invoiceIssueService.getByNoAndCode("00000001", "1100")).thenReturn(validInvoice);
            when(invoiceStatusService.canVerify(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(true);
            when(verifyMapper.insert(any())).thenReturn(1);

            String taskId = asyncVerifyService.submitVerifyAsync(request);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            CountDownLatch latch = new CountDownLatch(1);
            AtomicInteger resultCount = new AtomicInteger(0);

            executor.submit(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        String quickKey = "quick_00000001_1100";
                        AsyncInvoiceVerifyService.VerifyResultHolder holder = asyncVerifyService.getResultCache().get(quickKey);
                        if (holder != null && holder.isCompleted()) {
                            resultCount.incrementAndGet();
                            latch.countDown();
                            break;
                        }
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            });

            AsyncInvoiceVerifyService.VerifyTask task = asyncVerifyService.getVerifyTaskQueue().poll(500, TimeUnit.MILLISECONDS);
            assertNotNull(task, "任务应已加入队列");

            AsyncInvoiceVerifyService.VerifyResultHolder holderBefore = asyncVerifyService.getResultCache().get("quick_00000001_1100");
            assertNotNull(holderBefore);
            assertFalse(holderBefore.isCompleted(), "处理前结果未完成");

            asyncVerifyService.executeVerifyWithRetry(task);

            AsyncInvoiceVerifyService.VerifyResultHolder holderAfter = asyncVerifyService.getResultCache().get("quick_00000001_1100");
            assertTrue(holderAfter.isCompleted(), "处理后结果已完成");
            assertNotNull(holderAfter.result);
            assertEquals(VerifyResultEnum.VALID.getCode(), holderAfter.result.getVerifyResult());

            executor.shutdown();
        }

        @Test
        @Order(3)
        @DisplayName("测试多次验证请求并行处理")
        void testMultipleVerifyRequestsProcessed() throws Exception {
            int requestCount = 5;
            CountDownLatch latch = new CountDownLatch(requestCount);
            ExecutorService executor = Executors.newFixedThreadPool(3);

            for (int i = 1; i <= requestCount; i++) {
                final String invoiceNo = String.format("%08d", i);
                Invoice invoice = TestDataBuilder.invoice()
                        .invoiceId("invoice_" + i)
                        .invoiceNo(invoiceNo)
                        .invoiceCode("1100")
                        .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                        .invoiceAmount(new BigDecimal("1000.00"))
                        .issueTime(Instant.now().minusSeconds(3600))
                        .build();

                when(invoiceIssueService.getByNoAndCode(invoiceNo, "1100")).thenReturn(invoice);
                when(invoiceStatusService.canVerify(anyString())).thenReturn(true);
            }

            when(verifyMapper.insert(any())).thenReturn(1);

            long submitStart = System.currentTimeMillis();
            for (int i = 1; i <= requestCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    String no = String.format("%08d", idx);
                    InvoiceVerifyRequest req = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                            no, "1100", VerifyTypeEnum.ONLINE.getCode());
                    String taskId = asyncVerifyService.submitVerifyAsync(req);
                    latch.countDown();
                    return taskId;
                });
            }

            boolean submitted = AsyncTestUtil.awaitLatch(latch, 2000);
            long submitElapsed = System.currentTimeMillis() - submitStart;

            assertTrue(submitted, "所有请求应提交成功");
            assertTrue(submitElapsed < 500, "5个请求提交应在500ms内完成，实际: " + submitElapsed + "ms");

            assertEquals(requestCount, asyncVerifyService.getResultCache().size());
            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("验证方式正确性测试")
    class VerifyMethodTests {

        @Test
        @Order(4)
        @DisplayName("测试在线验证方式正确性")
        void testOnlineVerifyCorrectness() {
            Invoice validInvoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_test_001")
                    .invoiceNo("00000001")
                    .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            VerifyResultEnum result = asyncVerifyService.doOnlineVerify(validInvoice);
            assertEquals(VerifyResultEnum.VALID, result, "mock模式在线验证应通过");
        }

        @Test
        @Order(5)
        @DisplayName("测试本地验证方式正确性")
        void testLocalVerifyCorrectness() {
            Invoice validInvoice = TestDataBuilder.invoice()
                    .invoiceNo("00000001")
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            VerifyResultEnum result1 = asyncVerifyService.doLocalVerify(validInvoice);
            assertEquals(VerifyResultEnum.VALID, result1, "有效发票本地验证应通过");

            Invoice invalidNoInvoice = TestDataBuilder.invoice()
                    .invoiceNo("123")
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            VerifyResultEnum result2 = asyncVerifyService.doLocalVerify(invalidNoInvoice);
            assertEquals(VerifyResultEnum.INVALID, result2, "无效号码格式应验证失败");

            Invoice invalidAmountInvoice = TestDataBuilder.invoice()
                    .invoiceNo("00000001")
                    .invoiceAmount(BigDecimal.ZERO)
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            VerifyResultEnum result3 = asyncVerifyService.doLocalVerify(invalidAmountInvoice);
            assertEquals(VerifyResultEnum.INVALID, result3, "无效金额应验证失败");

            Invoice futureInvoice = TestDataBuilder.invoice()
                    .invoiceNo("00000001")
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().plusSeconds(3600))
                    .build();

            VerifyResultEnum result4 = asyncVerifyService.doLocalVerify(futureInvoice);
            assertEquals(VerifyResultEnum.INVALID, result4, "未来时间发票应验证失败");
        }

        @Test
        @Order(6)
        @DisplayName("测试已作废发票验证失败")
        void testCancelledInvoiceVerifyFails() {
            Invoice cancelledInvoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_cancelled")
                    .invoiceNo("00000100")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.CANCELLED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            when(invoiceIssueService.getByNoAndCode("00000100", "1100")).thenReturn(cancelledInvoice);

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000100", "1100", VerifyTypeEnum.LOCAL.getCode());

            AsyncInvoiceVerifyService.VerifyTask task = new AsyncInvoiceVerifyService.VerifyTask(request);

            assertThrows(InvoiceException.class, () -> {
                asyncVerifyService.executeVerifyWithRetry(task);
            }, "已作废发票应验证失败");
        }

        @Test
        @Order(7)
        @DisplayName("测试发票不存在时验证失败")
        void testNonExistentInvoiceVerifyFails() {
            when(invoiceIssueService.getByNoAndCode("99999999", "9999"))
                    .thenThrow(new InvoiceException(404, "发票不存在"));

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "99999999", "9999", VerifyTypeEnum.LOCAL.getCode());

            AsyncInvoiceVerifyService.VerifyTask task = new AsyncInvoiceVerifyService.VerifyTask(request);

            assertThrows(InvoiceException.class, () -> {
                asyncVerifyService.executeVerifyWithRetry(task);
            }, "不存在的发票应验证失败");
        }
    }

    @Nested
    @DisplayName("验证失败重试机制测试")
    class RetryMechanismTests {

        @Test
        @Order(8)
        @DisplayName("测试验证失败时触发重试机制")
        void testRetryOnVerifyFailure() throws Exception {
            Invoice invoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_retry")
                    .invoiceNo("00000099")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            when(invoiceIssueService.getByNoAndCode("00000099", "1100"))
                    .thenThrow(new InvoiceException(500, "临时网络错误"))
                    .thenThrow(new InvoiceException(500, "临时网络错误"))
                    .thenReturn(invoice);
            when(invoiceStatusService.canVerify(anyString())).thenReturn(true);
            when(verifyMapper.insert(any())).thenReturn(1);

            asyncVerifyService.resetRetryCounter();
            assertEquals(0, asyncVerifyService.getTotalRetryCount());

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000099", "1100", VerifyTypeEnum.ONLINE.getCode());

            String taskId = asyncVerifyService.submitVerifyAsync(request);

            AsyncInvoiceVerifyService.VerifyTask task = asyncVerifyService.getVerifyTaskQueue().poll(500, TimeUnit.MILLISECONDS);
            assertNotNull(task);
            assertEquals(0, task.getRetryCount());

            assertThrows(InvoiceException.class, () -> {
                asyncVerifyService.executeVerifyWithRetry(task);
            });

            assertEquals(1, task.getRetryCount());
            task.incrementRetry();

            assertThrows(InvoiceException.class, () -> {
                asyncVerifyService.executeVerifyWithRetry(task);
            });

            assertEquals(2, task.getRetryCount());
            task.incrementRetry();

            InvoiceVerifyResponse response = asyncVerifyService.executeVerifyWithRetry(task);

            assertEquals(2, task.getRetryCount());
            assertNotNull(response);
            assertEquals(VerifyResultEnum.VALID.getCode(), response.getVerifyResult());
        }

        @Test
        @Order(9)
        @DisplayName("测试超过最大重试次数后不再重试")
        void testNoRetryAfterMaxAttempts() throws Exception {
            setPrivateField(asyncVerifyService, "maxRetry", 2);

            Invoice invoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_max_retry")
                    .invoiceNo("00000088")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            when(invoiceIssueService.getByNoAndCode("00000088", "1100"))
                    .thenThrow(new InvoiceException(500, "持续网络错误"));

            asyncVerifyService.resetRetryCounter();

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000088", "1100", VerifyTypeEnum.ONLINE.getCode());

            String taskId = asyncVerifyService.submitVerifyAsync(request);

            AsyncInvoiceVerifyService.VerifyTask task = asyncVerifyService.getVerifyTaskQueue().poll(500, TimeUnit.MILLISECONDS);
            assertNotNull(task);

            for (int i = 0; i < 3; i++) {
                try {
                    asyncVerifyService.executeVerifyWithRetry(task);
                    fail("应抛出异常");
                } catch (InvoiceException e) {
                    if (i < 2) {
                        task.incrementRetry();
                    }
                }
            }

            assertEquals(2, task.getRetryCount(), "最多重试2次");
        }
    }

    @Nested
    @DisplayName("验证结果状态更新测试")
    class ResultStatusUpdateTests {

        @Test
        @Order(10)
        @DisplayName("测试验证通过时发票状态更新为已验证")
        void testStatusUpdatedToVerifiedOnSuccess() {
            Invoice invoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_status_test")
                    .invoiceNo("00000077")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            when(invoiceIssueService.getByNoAndCode("00000077", "1100")).thenReturn(invoice);
            when(invoiceStatusService.canVerify(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(true);
            when(invoiceStatusService.verify("invoice_status_test", "admin")).thenReturn(null);
            when(verifyMapper.insert(any())).thenReturn(1);

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000077", "1100", VerifyTypeEnum.LOCAL.getCode());
            request.setOperator("admin");

            AsyncInvoiceVerifyService.VerifyTask task = new AsyncInvoiceVerifyService.VerifyTask(request);

            InvoiceVerifyResponse response = asyncVerifyService.executeVerifyWithRetry(task);

            assertEquals(VerifyResultEnum.VALID.getCode(), response.getVerifyResult());
            verify(invoiceStatusService, times(1)).verify("invoice_status_test", "admin");
            verify(invoiceStatisticsService, times(1)).recordVerify(true);
            verify(invoiceHistoryService, times(1)).recordVerify("invoice_status_test", "valid", "admin");
        }

        @Test
        @Order(11)
        @DisplayName("测试验证失败时统计更新正确")
        void testStatisticsUpdatedOnFailure() {
            Invoice invoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_fail_test")
                    .invoiceNo("00000066")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.CANCELLED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            when(invoiceIssueService.getByNoAndCode("00000066", "1100")).thenReturn(invoice);

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000066", "1100", VerifyTypeEnum.LOCAL.getCode());

            AsyncInvoiceVerifyService.VerifyTask task = new AsyncInvoiceVerifyService.VerifyTask(request);

            assertThrows(InvoiceException.class, () -> {
                asyncVerifyService.executeVerifyWithRetry(task);
            });

            verify(invoiceStatisticsService, times(1)).recordVerify(false);
        }
    }

    @Nested
    @DisplayName("验证结果查询测试")
    class ResultQueryTests {

        @Test
        @Order(12)
        @DisplayName("测试结果缓存查询正确")
        void testResultCacheQuery() throws Exception {
            Invoice invoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_cache")
                    .invoiceNo("00000055")
                    .invoiceCode("1100")
                    .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                    .invoiceAmount(new BigDecimal("10000.00"))
                    .issueTime(Instant.now().minusSeconds(3600))
                    .build();

            when(invoiceIssueService.getByNoAndCode("00000055", "1100")).thenReturn(invoice);
            when(invoiceStatusService.canVerify(anyString())).thenReturn(true);
            when(verifyMapper.insert(any())).thenReturn(1);

            InvoiceVerifyRequest request = TestDataBuilder.RequestBuilder.buildVerifyRequest(
                    "00000055", "1100", VerifyTypeEnum.LOCAL.getCode());

            String taskId = asyncVerifyService.submitVerifyAsync(request);

            assertEquals(1, asyncVerifyService.getResultCache().size());

            String quickKey = "quick_00000055_1100";
            AsyncInvoiceVerifyService.VerifyResultHolder holder = asyncVerifyService.getResultCache().get(quickKey);

            assertNotNull(holder);
            assertFalse(holder.isCompleted());

            AsyncInvoiceVerifyService.VerifyTask task = asyncVerifyService.getVerifyTaskQueue().poll(500, TimeUnit.MILLISECONDS);
            asyncVerifyService.executeVerifyWithRetry(task);

            assertTrue(holder.isCompleted());
            assertNotNull(holder.result);
            assertEquals(VerifyResultEnum.VALID.getCode(), holder.result.getVerifyResult());
        }

        @Test
        @Order(13)
        @DisplayName("测试查询不存在的任务抛出异常")
        void testQueryNonExistentTaskThrowsException() {
            assertThrows(InvoiceException.class, () -> {
                asyncVerifyService.getVerifyResult("non_existent_task", 1000);
            }, "查询不存在的任务应抛出异常");
        }
    }
}
