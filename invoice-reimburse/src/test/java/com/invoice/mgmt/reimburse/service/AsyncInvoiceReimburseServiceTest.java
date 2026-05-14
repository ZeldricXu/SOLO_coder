package com.invoice.mgmt.reimburse.service;

import com.invoice.mgmt.common.dto.InvoiceReimburseRequest;
import com.invoice.mgmt.common.dto.InvoiceReimburseResponse;
import com.invoice.mgmt.common.entity.Invoice;
import com.invoice.mgmt.common.entity.InvoiceReimburse;
import com.invoice.mgmt.common.enums.InvoiceStatusEnum;
import com.invoice.mgmt.common.enums.ReimburseStatusEnum;
import com.invoice.mgmt.common.exception.InvoiceException;
import com.invoice.mgmt.common.testdata.AsyncTestUtil;
import com.invoice.mgmt.common.testdata.MockConstants;
import com.invoice.mgmt.common.testdata.TestDataBuilder;
import com.invoice.mgmt.history.service.InvoiceHistoryService;
import com.invoice.mgmt.issue.service.InvoiceIssueService;
import com.invoice.mgmt.statistics.service.InvoiceStatisticsService;
import com.invoice.mgmt.status.service.InvoiceStatusService;
import com.invoice.mgmt.reimburse.mapper.InvoiceReimburseMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("异步发票报销服务单元测试")
class AsyncInvoiceReimburseServiceTest {

    @Mock
    private InvoiceReimburseMapper reimburseMapper;

    @Mock
    private InvoiceIssueService invoiceIssueService;

    @Mock
    private InvoiceStatusService invoiceStatusService;

    @Mock
    private InvoiceStatisticsService invoiceStatisticsService;

    @Mock
    private InvoiceHistoryService invoiceHistoryService;

    @InjectMocks
    private AsyncInvoiceReimburseService asyncReimburseService;

    private Invoice issuedInvoice;
    private Invoice verifiedInvoice;
    private Invoice reimbursedInvoice;
    private BigDecimal standardAmount;

    @BeforeEach
    void setUp() {
        standardAmount = new BigDecimal("11300.00");

        issuedInvoice = TestDataBuilder.invoice()
                .invoiceId("invoice_issued")
                .invoiceNo("00000001")
                .invoiceCode("1100")
                .invoiceStatus(InvoiceStatusEnum.ISSUED.getCode())
                .invoiceAmount(new BigDecimal("10000.00"))
                .taxAmount(new BigDecimal("1300.00"))
                .totalAmount(standardAmount)
                .issueTime(Instant.now().minusSeconds(3600))
                .build();

        verifiedInvoice = TestDataBuilder.invoice()
                .invoiceId("invoice_verified")
                .invoiceNo("00000002")
                .invoiceCode("1100")
                .invoiceStatus(InvoiceStatusEnum.VERIFIED.getCode())
                .invoiceAmount(new BigDecimal("10000.00"))
                .taxAmount(new BigDecimal("1300.00"))
                .totalAmount(standardAmount)
                .issueTime(Instant.now().minusSeconds(3600))
                .build();

        reimbursedInvoice = TestDataBuilder.invoice()
                .invoiceId("invoice_reimbursed")
                .invoiceNo("00000003")
                .invoiceCode("1100")
                .invoiceStatus(InvoiceStatusEnum.REIMBURSED.getCode())
                .invoiceAmount(new BigDecimal("10000.00"))
                .taxAmount(new BigDecimal("1300.00"))
                .totalAmount(standardAmount)
                .issueTime(Instant.now().minusSeconds(3600))
                .build();
    }

    @Nested
    @DisplayName("报销异步化测试")
    class AsyncReimburseTests {

        @Test
        @Order(1)
        @DisplayName("测试报销申请提交后立即返回响应不阻塞")
        void testSubmitReimburseReturnsImmediately() {
            when(invoiceIssueService.getById("invoice_issued")).thenReturn(issuedInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(false);
            when(invoiceStatusService.canReimburse(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(true);

            InvoiceReimburseRequest request = TestDataBuilder.RequestBuilder.buildReimburseRequest(
                    "invoice_issued", "user_001", standardAmount);

            long startTime = System.currentTimeMillis();
            String taskId = asyncReimburseService.submitReimburseAsync(request);
            long elapsed = System.currentTimeMillis() - startTime;

            assertNotNull(taskId);
            assertTrue(elapsed < 100, "提交应立即返回，耗时: " + elapsed + "ms");
            assertTrue(taskId.startsWith("reimburse_"), "taskId格式正确");
        }

        @Test
        @Order(2)
        @DisplayName("测试后台Worker执行审核处理")
        void testWorkerProcessesTask() throws Exception {
            when(invoiceIssueService.getById("invoice_verified")).thenReturn(verifiedInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.VERIFIED.getCode())).thenReturn(false);
            when(invoiceStatusService.canReimburse(InvoiceStatusEnum.VERIFIED.getCode())).thenReturn(true);
            when(reimburseMapper.insert(any(InvoiceReimburse.class))).thenReturn(1);

            InvoiceReimburseRequest request = TestDataBuilder.RequestBuilder.buildReimburseRequest(
                    "invoice_verified", "user_002", standardAmount);
            request.setReimburseReason("差旅费报销");

            String taskId = asyncReimburseService.submitReimburseAsync(request);

            AsyncInvoiceReimburseService.ReimburseTask task = asyncReimburseService.getReimburseTaskQueue().poll(500, TimeUnit.MILLISECONDS);
            assertNotNull(task, "任务应已加入队列");

            AsyncInvoiceReimburseService.ReimburseResultHolder holderBefore = asyncReimburseService.getResultCache().get("invoice_verified");
            assertNotNull(holderBefore);
            assertFalse(holderBefore.isCompleted(), "处理前结果未完成");

            InvoiceReimburseResponse response = asyncReimburseService.processApply(task);

            AsyncInvoiceReimburseService.ReimburseResultHolder holderAfter = asyncReimburseService.getResultCache().get("invoice_verified");
            assertNotNull(response);
            assertEquals(taskId, response.getReimburseId());
            assertEquals(ReimburseStatusEnum.PENDING.getCode(), response.getStatus());

            verify(reimburseMapper, times(1)).insert(any(InvoiceReimburse.class));
            verify(invoiceStatusService, times(1)).reimbursePending("invoice_verified", "user_002");
            verify(invoiceStatisticsService, times(1)).recordReimburse(false);
            verify(invoiceHistoryService, times(1)).recordReimburseApply("invoice_verified", "user_002");
        }

        @Test
        @Order(3)
        @DisplayName("测试多个报销申请并行提交")
        void testMultipleReimburseRequests() throws Exception {
            int requestCount = 4;
            CountDownLatch submitLatch = new CountDownLatch(requestCount);
            ExecutorService executor = Executors.newFixedThreadPool(2);

            String[] invoiceIds = {"inv_1", "inv_2", "inv_3", "inv_4"};
            String[] statuses = {
                    InvoiceStatusEnum.ISSUED.getCode(),
                    InvoiceStatusEnum.VERIFIED.getCode(),
                    InvoiceStatusEnum.ISSUED.getCode(),
                    InvoiceStatusEnum.VERIFIED.getCode()
            };

            for (int i = 0; i < requestCount; i++) {
                Invoice invoice = TestDataBuilder.invoice()
                        .invoiceId(invoiceIds[i])
                        .invoiceStatus(statuses[i])
                        .totalAmount(standardAmount)
                        .build();
                when(invoiceIssueService.getById(invoiceIds[i])).thenReturn(invoice);
                when(invoiceStatusService.isAlreadyReimbursed(statuses[i])).thenReturn(false);
                when(invoiceStatusService.canReimburse(statuses[i])).thenReturn(true);
            }
            when(reimburseMapper.insert(any(InvoiceReimburse.class))).thenReturn(1);

            long submitStart = System.currentTimeMillis();
            for (int i = 0; i < requestCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    InvoiceReimburseRequest req = TestDataBuilder.RequestBuilder.buildReimburseRequest(
                            invoiceIds[idx], "user_" + idx, standardAmount);
                    String tid = asyncReimburseService.submitReimburseAsync(req);
                    submitLatch.countDown();
                    return tid;
                });
            }

            boolean submitted = AsyncTestUtil.awaitLatch(submitLatch, 2000);
            long submitElapsed = System.currentTimeMillis() - submitStart;

            assertTrue(submitted, "所有申请应提交成功");
            assertTrue(submitElapsed < 500, "4个申请提交应在500ms内完成，实际: " + submitElapsed + "ms");
            assertEquals(requestCount, asyncReimburseService.getResultCache().size());

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("紧急报销优先级排序测试")
    class PrioritySortingTests {

        @Test
        @Order(4)
        @DisplayName("测试普通报销优先级判断")
        void testNormalPriority() {
            InvoiceReimburseRequest normalRequest = InvoiceReimburseRequest.builder()
                    .invoiceId("invoice_normal")
                    .reimburseUser("user_normal")
                    .reimburseReason("日常办公费用报销")
                    .reimburseAmount(new BigDecimal("500.00"))
                    .build();

            AsyncInvoiceReimburseService.ReimburseTask normalTask =
                    new AsyncInvoiceReimburseService.ReimburseTask(normalRequest);

            assertEquals(AsyncInvoiceReimburseService.PRIORITY_NORMAL, normalTask.getPriority());
        }

        @Test
        @Order(5)
        @DisplayName("测试高优先级报销判断")
        void testHighPriority() {
            InvoiceReimburseRequest importantRequest = InvoiceReimburseRequest.builder()
                    .invoiceId("invoice_important")
                    .reimburseUser("user_important")
                    .reimburseReason("重要客户拜访差旅费")
                    .reimburseAmount(new BigDecimal("3000.00"))
                    .build();

            AsyncInvoiceReimburseService.ReimburseTask highTask =
                    new AsyncInvoiceReimburseService.ReimburseTask(importantRequest);

            assertEquals(AsyncInvoiceReimburseService.PRIORITY_HIGH, highTask.getPriority());
        }

        @Test
        @Order(6)
        @DisplayName("测试紧急报销优先级判断")
        void testUrgentPriority() {
            InvoiceReimburseRequest urgentRequest = InvoiceReimburseRequest.builder()
                    .invoiceId("invoice_urgent")
                    .reimburseUser("user_urgent")
                    .reimburseReason("紧急医疗费用报销")
                    .reimburseAmount(new BigDecimal("5000.00"))
                    .build();

            AsyncInvoiceReimburseService.ReimburseTask urgentTask =
                    new AsyncInvoiceReimburseService.ReimburseTask(urgentRequest);

            assertEquals(AsyncInvoiceReimburseService.PRIORITY_URGENT, urgentTask.getPriority());
        }

        @Test
        @Order(7)
        @DisplayName("测试优先级队列按优先级排序")
        void testPriorityQueueSortsByPriority() {
            PriorityBlockingQueue<AsyncInvoiceReimburseService.ReimburseTask> queue =
                    new PriorityBlockingQueue<>();

            InvoiceReimburseRequest normalReq = InvoiceReimburseRequest.builder()
                    .invoiceId("n1").reimburseReason("办公费").build();
            InvoiceReimburseRequest highReq = InvoiceReimburseRequest.builder()
                    .invoiceId("h1").reimburseReason("重要出差").build();
            InvoiceReimburseRequest urgentReq = InvoiceReimburseRequest.builder()
                    .invoiceId("u1").reimburseReason("紧急医疗").build();
            InvoiceReimburseRequest normalReq2 = InvoiceReimburseRequest.builder()
                    .invoiceId("n2").reimburseReason("办公用品").build();

            queue.offer(new AsyncInvoiceReimburseService.ReimburseTask(normalReq));
            queue.offer(new AsyncInvoiceReimburseService.ReimburseTask(highReq));
            queue.offer(new AsyncInvoiceReimburseService.ReimburseTask(urgentReq));
            queue.offer(new AsyncInvoiceReimburseService.ReimburseTask(normalReq2));

            AsyncInvoiceReimburseService.ReimburseTask first = queue.poll();
            AsyncInvoiceReimburseService.ReimburseTask second = queue.poll();
            AsyncInvoiceReimburseService.ReimburseTask third = queue.poll();
            AsyncInvoiceReimburseService.ReimburseTask fourth = queue.poll();

            assertNotNull(first);
            assertNotNull(second);
            assertNotNull(third);
            assertNotNull(fourth);

            assertEquals("u1", first.getRequest().getInvoiceId(), "紧急应排在第一位");
            assertEquals("h1", second.getRequest().getInvoiceId(), "高优先级应排在第二位");
            assertEquals(AsyncInvoiceReimburseService.PRIORITY_NORMAL, third.getPriority());
            assertEquals(AsyncInvoiceReimburseService.PRIORITY_NORMAL, fourth.getPriority());
        }

        @Test
        @Order(8)
        @DisplayName("测试同优先级按时间排序")
        void testSamePrioritySortedByTime() throws InterruptedException {
            PriorityBlockingQueue<AsyncInvoiceReimburseService.ReimburseTask> queue =
                    new PriorityBlockingQueue<>();

            InvoiceReimburseRequest req1 = InvoiceReimburseRequest.builder()
                    .invoiceId("first").reimburseReason("办公费1").build();
            AsyncInvoiceReimburseService.ReimburseTask task1 =
                    new AsyncInvoiceReimburseService.ReimburseTask(req1);

            Thread.sleep(10);

            InvoiceReimburseRequest req2 = InvoiceReimburseRequest.builder()
                    .invoiceId("second").reimburseReason("办公费2").build();
            AsyncInvoiceReimburseService.ReimburseTask task2 =
                    new AsyncInvoiceReimburseService.ReimburseTask(req2);

            queue.offer(task2);
            queue.offer(task1);

            AsyncInvoiceReimburseService.ReimburseTask first = queue.poll();
            AsyncInvoiceReimburseService.ReimburseTask second = queue.poll();

            assertNotNull(first);
            assertNotNull(second);
            assertEquals(task1.getPriority(), task2.getPriority());
            assertTrue(first.getSubmitTime() < second.getSubmitTime());
        }
    }

    @Nested
    @DisplayName("审核结果更新测试")
    class ApprovalResultTests {

        @Test
        @Order(9)
        @DisplayName("测试审核通过后状态更新正确")
        void testApprovalUpdatesStatus() {
            InvoiceReimburse pendingReimburse = TestDataBuilder.invoiceReimburse()
                    .invoiceId("invoice_approved")
                    .reimburseUser("user_approve")
                    .reimburseAmount(standardAmount)
                    .reimburseStatus(ReimburseStatusEnum.PENDING.getCode())
                    .build();

            when(reimburseMapper.findById(pendingReimburse.getReimburseId())).thenReturn(pendingReimburse);
            when(reimburseMapper.updateStatus(
                    eq(pendingReimburse.getReimburseId()),
                    eq(ReimburseStatusEnum.APPROVED.getCode()),
                    anyString(),
                    anyString())).thenReturn(1);

            InvoiceReimburse approved = asyncReimburseService.approve(
                    pendingReimburse.getReimburseId(), "approver_001", "审核通过，材料齐全");

            assertEquals(ReimburseStatusEnum.APPROVED.getCode(), approved.getReimburseStatus());
            assertEquals("approver_001", approved.getApprover());
            assertEquals("审核通过，材料齐全", approved.getApproveRemark());
            assertNotNull(approved.getApproveTime());

            verify(invoiceStatusService, times(1)).reimbursed("invoice_approved", "approver_001");
            verify(invoiceStatisticsService, times(1)).recordReimburse(true);
            verify(invoiceHistoryService, times(1)).recordReimburseApprove("invoice_approved", "approver_001");
        }

        @Test
        @Order(10)
        @DisplayName("测试审核拒绝后状态更新正确")
        void testRejectionUpdatesStatus() {
            InvoiceReimburse pendingReimburse = TestDataBuilder.invoiceReimburse()
                    .invoiceId("invoice_rejected")
                    .reimburseUser("user_reject")
                    .reimburseAmount(standardAmount)
                    .reimburseStatus(ReimburseStatusEnum.PENDING.getCode())
                    .build();

            when(reimburseMapper.findById(pendingReimburse.getReimburseId())).thenReturn(pendingReimburse);
            when(reimburseMapper.updateStatus(
                    eq(pendingReimburse.getReimburseId()),
                    eq(ReimburseStatusEnum.REJECTED.getCode()),
                    anyString(),
                    anyString())).thenReturn(1);

            InvoiceReimburse rejected = asyncReimburseService.reject(
                    pendingReimburse.getReimburseId(), "approver_002", "发票信息不完整，缺少附件");

            assertEquals(ReimburseStatusEnum.REJECTED.getCode(), rejected.getReimburseStatus());
            assertEquals("approver_002", rejected.getApprover());
            assertEquals("发票信息不完整，缺少附件", rejected.getApproveRemark());

            verify(invoiceHistoryService, times(1)).recordReimburseReject(
                    "invoice_rejected", "发票信息不完整，缺少附件", "approver_002");
        }

        @Test
        @Order(11)
        @DisplayName("测试已处理的报销无法重复审核")
        void testCannotReapproveAlreadyProcessed() {
            InvoiceReimburse alreadyApproved = TestDataBuilder.invoiceReimburse()
                    .invoiceId("invoice_done")
                    .reimburseStatus(ReimburseStatusEnum.APPROVED.getCode())
                    .build();

            when(reimburseMapper.findById(alreadyApproved.getReimburseId())).thenReturn(alreadyApproved);

            assertThrows(InvoiceException.class, () -> {
                asyncReimburseService.approve(alreadyApproved.getReimburseId(), "approver", "再次审核");
            }, "已通过的报销应无法再次审核");
        }
    }

    @Nested
    @DisplayName("报销业务规则测试")
    class BusinessRuleTests {

        @Test
        @Order(12)
        @DisplayName("测试已报销发票无法再次报销")
        void testCannotReimburseAlreadyReimbursed() {
            when(invoiceIssueService.getById("invoice_reimbursed")).thenReturn(reimbursedInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.REIMBURSED.getCode())).thenReturn(true);

            InvoiceReimburseRequest request = TestDataBuilder.RequestBuilder.buildReimburseRequest(
                    "invoice_reimbursed", "user_dup", standardAmount);

            AsyncInvoiceReimburseService.ReimburseTask task =
                    new AsyncInvoiceReimburseService.ReimburseTask(request);

            assertThrows(InvoiceException.class, () -> {
                asyncReimburseService.processApply(task);
            }, "已报销的发票应无法再次报销");
        }

        @Test
        @Order(13)
        @DisplayName("测试报销金额不能超过发票金额")
        void testReimburseAmountCannotExceedInvoice() {
            when(invoiceIssueService.getById("invoice_issued")).thenReturn(issuedInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(false);
            when(invoiceStatusService.canReimburse(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(true);

            InvoiceReimburseRequest overAmountRequest = InvoiceReimburseRequest.builder()
                    .invoiceId("invoice_issued")
                    .reimburseUser("user_over")
                    .reimburseAmount(new BigDecimal("20000.00"))
                    .build();

            AsyncInvoiceReimburseService.ReimburseTask task =
                    new AsyncInvoiceReimburseService.ReimburseTask(overAmountRequest);

            assertThrows(InvoiceException.class, () -> {
                asyncReimburseService.processApply(task);
            }, "报销金额超过发票金额应失败");
        }

        @Test
        @Order(14)
        @DisplayName("测试无效发票状态无法报销")
        void testCannotReimburseInvalidStatus() {
            Invoice cancelledInvoice = TestDataBuilder.invoice()
                    .invoiceId("invoice_cancelled")
                    .invoiceStatus(InvoiceStatusEnum.CANCELLED.getCode())
                    .totalAmount(standardAmount)
                    .build();

            when(invoiceIssueService.getById("invoice_cancelled")).thenReturn(cancelledInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.CANCELLED.getCode())).thenReturn(false);
            when(invoiceStatusService.canReimburse(InvoiceStatusEnum.CANCELLED.getCode())).thenReturn(false);

            InvoiceReimburseRequest request = TestDataBuilder.RequestBuilder.buildReimburseRequest(
                    "invoice_cancelled", "user_invalid", standardAmount);

            AsyncInvoiceReimburseService.ReimburseTask task =
                    new AsyncInvoiceReimburseService.ReimburseTask(request);

            assertThrows(InvoiceException.class, () -> {
                asyncReimburseService.processApply(task);
            }, "已作废的发票应无法报销");
        }

        @Test
        @Order(15)
        @DisplayName("测试未指定报销金额时使用发票全额")
        void testUseInvoiceTotalWhenAmountNotSpecified() {
            when(invoiceIssueService.getById("invoice_verified")).thenReturn(verifiedInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.VERIFIED.getCode())).thenReturn(false);
            when(invoiceStatusService.canReimburse(InvoiceStatusEnum.VERIFIED.getCode())).thenReturn(true);
            when(reimburseMapper.insert(any(InvoiceReimburse.class))).thenReturn(1);

            InvoiceReimburseRequest noAmountRequest = InvoiceReimburseRequest.builder()
                    .invoiceId("invoice_verified")
                    .reimburseUser("user_no_amount")
                    .reimburseReason("全额报销")
                    .build();

            AsyncInvoiceReimburseService.ReimburseTask task =
                    new AsyncInvoiceReimburseService.ReimburseTask(noAmountRequest);

            ArgumentCaptor<InvoiceReimburse> reimburseCaptor = ArgumentCaptor.forClass(InvoiceReimburse.class);

            InvoiceReimburseResponse response = asyncReimburseService.processApply(task);

            verify(reimburseMapper).insert(reimburseCaptor.capture());
            InvoiceReimburse captured = reimburseCaptor.getValue();

            assertEquals(verifiedInvoice.getTotalAmount(), captured.getReimburseAmount());
            assertEquals(standardAmount, response.getReimburseAmount());
        }

        @Test
        @Order(16)
        @DisplayName("测试待审核列表按优先级排序")
        void testPendingListSortedByPriority() {
            List<InvoiceReimburse> pendingList = new ArrayList<>();

            InvoiceReimburse normal1 = TestDataBuilder.invoiceReimburse()
                    .reimburseReason("办公费用")
                    .applyTime(Instant.now().minusSeconds(100))
                    .build();
            InvoiceReimburse urgent = TestDataBuilder.invoiceReimburse()
                    .reimburseReason("紧急医疗费用")
                    .applyTime(Instant.now().minusSeconds(50))
                    .build();
            InvoiceReimburse normal2 = TestDataBuilder.invoiceReimburse()
                    .reimburseReason("交通费")
                    .applyTime(Instant.now().minusSeconds(80))
                    .build();

            pendingList.add(normal1);
            pendingList.add(urgent);
            pendingList.add(normal2);

            when(reimburseMapper.findByStatus(ReimburseStatusEnum.PENDING.getCode())).thenReturn(pendingList);

            List<InvoiceReimburse> sorted = asyncReimburseService.getPendingSortedByPriority();

            assertEquals(3, sorted.size());
            assertEquals("紧急医疗费用", sorted.get(0).getReimburseReason(), "紧急的应排在第一位");
            assertEquals("办公费用", sorted.get(1).getReimburseReason(), "同优先级按时间排序");
            assertEquals("交通费", sorted.get(2).getReimburseReason());
        }
    }

    @Nested
    @DisplayName("结果查询测试")
    class ResultQueryTests {

        @Test
        @Order(17)
        @DisplayName("测试结果缓存查询正确")
        void testResultCacheQuery() throws Exception {
            when(invoiceIssueService.getById("invoice_issued")).thenReturn(issuedInvoice);
            when(invoiceStatusService.isAlreadyReimbursed(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(false);
            when(invoiceStatusService.canReimburse(InvoiceStatusEnum.ISSUED.getCode())).thenReturn(true);
            when(reimburseMapper.insert(any(InvoiceReimburse.class))).thenReturn(1);

            InvoiceReimburseRequest request = TestDataBuilder.RequestBuilder.buildReimburseRequest(
                    "invoice_issued", "user_test", standardAmount);

            String taskId = asyncReimburseService.submitReimburseAsync(request);

            assertEquals(1, asyncReimburseService.getResultCache().size());

            AsyncInvoiceReimburseService.ReimburseResultHolder holder =
                    asyncReimburseService.getResultCache().get("invoice_issued");
            assertNotNull(holder);
            assertFalse(holder.isCompleted());

            AsyncInvoiceReimburseService.ReimburseTask task =
                    asyncReimburseService.getReimburseTaskQueue().poll(500, TimeUnit.MILLISECONDS);
            asyncReimburseService.processApply(task);

            assertTrue(holder.isCompleted());
            assertNotNull(holder.result);
            assertEquals(taskId, holder.result.getReimburseId());
        }

        @Test
        @Order(18)
        @DisplayName("测试查询不存在的任务抛出异常")
        void testQueryNonExistentTaskThrowsException() {
            assertThrows(InvoiceException.class, () -> {
                asyncReimburseService.getReimburseResult("non_existent", 1000);
            }, "查询不存在的任务应抛出异常");
        }
    }
}
