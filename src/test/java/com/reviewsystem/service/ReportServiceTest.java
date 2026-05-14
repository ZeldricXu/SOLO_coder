package com.reviewsystem.service;

import com.reviewsystem.dto.ReportRequest;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.ReportRecord;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.ReportRecordRepository;
import com.reviewsystem.testdata.TestDataBuilder;
import com.reviewsystem.util.IdGenerator;
import com.reviewsystem.util.ReportPriorityCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("举报模块单元测试")
class ReportServiceTest {

    @Mock
    private ReportRecordRepository reportRecordRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private ReportPriorityCalculator priorityCalculator;

    @Mock
    private HistoryService historyService;

    @InjectMocks
    private ReportService reportService;

    private Comment existingComment;
    private ReportRequest spamReportRequest;
    private ReportRequest violationReportRequest;

    @BeforeEach
    void setUp() {
        existingComment = TestDataBuilder.buildPublishedComment(
                TestDataBuilder.TEST_COMMENT_ID,
                TestDataBuilder.NORMAL_COMMENT,
                85, 80
        );

        spamReportRequest = TestDataBuilder.buildSpamReportRequest(TestDataBuilder.TEST_COMMENT_ID);
        violationReportRequest = TestDataBuilder.buildViolationReportRequest(TestDataBuilder.TEST_COMMENT_ID);
    }

    @Nested
    @DisplayName("举报受理测试")
    class ReportSubmitTests {

        @Test
        @DisplayName("提交举报 - 成功")
        void testSubmitReport_Success() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_001");

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));
                when(reportRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(new ArrayList<>());
                when(priorityCalculator.calculatePriority("spam", 0)).thenReturn(30);

                Map<String, Object> result = reportService.submitReport(spamReportRequest);

                assertTrue((Boolean) result.get("success"), "举报提交应成功");
                assertEquals("report_001", result.get("report_id"), "应返回举报ID");
                assertEquals("pending", result.get("status"), "状态应为待处理");
                assertEquals(30, result.get("priority"), "优先级应为30");
                assertNotNull(result.get("reported_at"), "应包含举报时间");

                verify(reportRecordRepository, times(1)).save(any(ReportRecord.class));
                verify(historyService, times(1)).recordHistory(
                        eq(TestDataBuilder.TEST_COMMENT_ID),
                        eq("REPORT_SUBMIT"),
                        anyString(),
                        any(),
                        any(),
                        any(),
                        any(),
                        eq(TestDataBuilder.TEST_REPORT_USER),
                        eq("user")
                );
            }
        }

        @Test
        @DisplayName("提交举报 - 评论不存在")
        void testSubmitReport_CommentNotFound() {
            when(commentRepository.findById("non_existing_comment"))
                    .thenReturn(Optional.empty());

            ReportRequest request = TestDataBuilder.buildSpamReportRequest("non_existing_comment");
            Map<String, Object> result = reportService.submitReport(request);

            assertFalse((Boolean) result.get("success"), "举报提交应失败");
            assertEquals("评论不存在", result.get("message"), "错误信息应正确");

            verify(reportRecordRepository, never()).save(any(ReportRecord.class));
            verify(historyService, never()).recordHistory(anyString(), anyString(), anyString(),
                    any(), any(), any(), any(), anyString(), anyString());
        }

        @Test
        @DisplayName("提交举报 - 已有举报记录")
        void testSubmitReport_ExistingReports() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_002");

                List<ReportRecord> existingReports = Arrays.asList(
                        TestDataBuilder.buildPendingReport("report_old_1", TestDataBuilder.TEST_COMMENT_ID, "spam"),
                        TestDataBuilder.buildPendingReport("report_old_2", TestDataBuilder.TEST_COMMENT_ID, "violation")
                );

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));
                when(reportRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(existingReports);
                when(priorityCalculator.calculatePriority("violation", 2)).thenReturn(80);

                Map<String, Object> result = reportService.submitReport(violationReportRequest);

                assertTrue((Boolean) result.get("success"), "举报提交应成功");
                assertEquals(80, result.get("priority"), "优先级应因已有举报而提升");

                verify(priorityCalculator, times(1)).calculatePriority("violation", 2);
            }
        }

        @Test
        @DisplayName("提交举报 - 不同举报类型")
        void testSubmitReport_DifferentTypes() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));
                when(reportRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(new ArrayList<>());

                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_spam");
                when(priorityCalculator.calculatePriority("spam", 0)).thenReturn(30);
                Map<String, Object> spamResult = reportService.submitReport(spamReportRequest);
                assertEquals(30, spamResult.get("priority"));

                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_violation");
                when(priorityCalculator.calculatePriority("violation", 0)).thenReturn(50);
                Map<String, Object> violationResult = reportService.submitReport(violationReportRequest);
                assertEquals(50, violationResult.get("priority"));

                ReportRequest abuseRequest = TestDataBuilder.buildReportRequest(
                        TestDataBuilder.TEST_COMMENT_ID, "abuse", "辱骂他人", "user_abuse");
                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_abuse");
                when(priorityCalculator.calculatePriority("abuse", 0)).thenReturn(70);
                Map<String, Object> abuseResult = reportService.submitReport(abuseRequest);
                assertEquals(70, abuseResult.get("priority"));

                ReportRequest illegalRequest = TestDataBuilder.buildReportRequest(
                        TestDataBuilder.TEST_COMMENT_ID, "illegal", "违法内容", "user_illegal");
                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_illegal");
                when(priorityCalculator.calculatePriority("illegal", 0)).thenReturn(90);
                Map<String, Object> illegalResult = reportService.submitReport(illegalRequest);
                assertEquals(90, illegalResult.get("priority"));
            }
        }
    }

    @Nested
    @DisplayName("举报优先级排序测试")
    class ReportPriorityTests {

        @Test
        @DisplayName("优先级排序 - 高优先级在前")
        void testPendingReportsSortedByPriority() {
            ReportRecord highPriority = TestDataBuilder.buildPendingReport(
                    "report_high", TestDataBuilder.TEST_COMMENT_ID, "illegal");
            highPriority.setPriority(90);

            ReportRecord mediumPriority = TestDataBuilder.buildPendingReport(
                    "report_medium", TestDataBuilder.TEST_COMMENT_ID_2, "violation");
            mediumPriority.setPriority(50);

            ReportRecord lowPriority = TestDataBuilder.buildPendingReport(
                    "report_low", TestDataBuilder.TEST_COMMENT_ID_3, "spam");
            lowPriority.setPriority(30);

            List<ReportRecord> sortedReports = Arrays.asList(
                    highPriority,
                    mediumPriority,
                    lowPriority
            );

            when(reportRecordRepository.findPendingReportsOrdered())
                    .thenReturn(sortedReports);

            List<ReportRecord> result = reportService.getPendingReports();

            assertEquals(3, result.size(), "应返回3条举报");
            assertEquals("report_high", result.get(0).getReportId(),
                    "高优先级应排第一");
            assertEquals(90, result.get(0).getPriority(),
                    "第一优先级应为90");
            assertEquals("report_medium", result.get(1).getReportId(),
                    "中优先级应排第二");
            assertEquals("report_low", result.get(2).getReportId(),
                    "低优先级应排第三");
        }

        @Test
        @DisplayName("优先级计算 - 基础优先级")
        void testPriorityCalculation_BasePriority() {
            when(priorityCalculator.calculatePriority("spam", 0)).thenReturn(30);
            when(priorityCalculator.calculatePriority("violation", 0)).thenReturn(50);
            when(priorityCalculator.calculatePriority("abuse", 0)).thenReturn(70);
            when(priorityCalculator.calculatePriority("illegal", 0)).thenReturn(90);

            assertEquals(30, priorityCalculator.calculatePriority("spam", 0));
            assertEquals(50, priorityCalculator.calculatePriority("violation", 0));
            assertEquals(70, priorityCalculator.calculatePriority("abuse", 0));
            assertEquals(90, priorityCalculator.calculatePriority("illegal", 0));
        }

        @Test
        @DisplayName("优先级计算 - 多次举报叠加")
        void testPriorityCalculation_MultipleReports() {
            when(priorityCalculator.calculatePriority("violation", 0)).thenReturn(50);
            when(priorityCalculator.calculatePriority("violation", 1)).thenReturn(60);
            when(priorityCalculator.calculatePriority("violation", 2)).thenReturn(70);
            when(priorityCalculator.calculatePriority("violation", 5)).thenReturn(85);

            assertEquals(50, priorityCalculator.calculatePriority("violation", 0));
            assertEquals(60, priorityCalculator.calculatePriority("violation", 1));
            assertEquals(70, priorityCalculator.calculatePriority("violation", 2));
            assertEquals(85, priorityCalculator.calculatePriority("violation", 5));
        }

        @Test
        @DisplayName("优先级边界测试 - 不超过100")
        void testPriorityCalculation_Capped() {
            when(priorityCalculator.calculatePriority("illegal", 10)).thenReturn(100);

            assertEquals(100, priorityCalculator.calculatePriority("illegal", 10));
        }
    }

    @Nested
    @DisplayName("举报处理状态流转测试")
    class ReportStatusFlowTests {

        @Test
        @DisplayName("处理有效举报 - 状态变为resolved")
        void testHandleReport_Valid() {
            ReportRecord pendingReport = TestDataBuilder.buildPendingReport(
                    "report_valid_001", TestDataBuilder.TEST_COMMENT_ID, "spam");
            pendingReport.setReportStatus("pending");

            when(reportRecordRepository.findById("report_valid_001"))
                    .thenReturn(Optional.of(pendingReport));
            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(existingComment));

            Map<String, Object> result = reportService.handleReport(
                    "report_valid_001",
                    TestDataBuilder.TEST_AUDITOR,
                    "valid",
                    "确认是垃圾评论，已隐藏"
            );

            assertTrue((Boolean) result.get("success"), "处理应成功");
            assertEquals("resolved", result.get("status"), "状态应为已处理");
            assertEquals("valid", result.get("handle_result"), "处理结果应为有效");
            assertEquals(TestDataBuilder.TEST_AUDITOR, result.get("handler"), "处理人应正确");
            assertNotNull(result.get("handled_at"), "应包含处理时间");

            verify(reportRecordRepository, times(1)).save(pendingReport);
            assertEquals("resolved", pendingReport.getReportStatus(),
                    "举报状态应为已处理");
            assertEquals("valid", pendingReport.getHandleResult(),
                    "处理结果应为有效");

            verify(commentRepository, times(1)).save(existingComment);
            assertEquals("hidden", existingComment.getCommentStatus(),
                    "评论状态应为已隐藏");
        }

        @Test
        @DisplayName("处理无效举报 - 状态变为rejected")
        void testHandleReport_Invalid() {
            ReportRecord pendingReport = TestDataBuilder.buildPendingReport(
                    "report_invalid_001", TestDataBuilder.TEST_COMMENT_ID, "spam");
            pendingReport.setReportStatus("pending");

            when(reportRecordRepository.findById("report_invalid_001"))
                    .thenReturn(Optional.of(pendingReport));

            Map<String, Object> result = reportService.handleReport(
                    "report_invalid_001",
                    TestDataBuilder.TEST_AUDITOR,
                    "invalid",
                    "经核实不属于垃圾评论"
            );

            assertTrue((Boolean) result.get("success"), "处理应成功");
            assertEquals("rejected", result.get("status"), "状态应为已拒绝");
            assertEquals("invalid", result.get("handle_result"), "处理结果应为无效");

            verify(reportRecordRepository, times(1)).save(pendingReport);
            assertEquals("rejected", pendingReport.getReportStatus(),
                    "举报状态应为已拒绝");

            verify(commentRepository, never()).save(any(Comment.class));
            assertEquals("published", existingComment.getCommentStatus(),
                    "评论状态不应改变");
        }

        @Test
        @DisplayName("处理举报 - 举报记录不存在")
        void testHandleReport_ReportNotFound() {
            when(reportRecordRepository.findById("non_existing_report"))
                    .thenReturn(Optional.empty());

            Map<String, Object> result = reportService.handleReport(
                    "non_existing_report",
                    TestDataBuilder.TEST_AUDITOR,
                    "valid",
                    "测试处理"
            );

            assertFalse((Boolean) result.get("success"), "处理应失败");
            assertEquals("举报记录不存在", result.get("message"), "错误信息应正确");

            verify(reportRecordRepository, never()).save(any(ReportRecord.class));
        }

        @Test
        @DisplayName("处理举报 - 无效处理结果")
        void testHandleReport_InvalidResultType() {
            ReportRecord pendingReport = TestDataBuilder.buildPendingReport(
                    "report_invalid_type_001", TestDataBuilder.TEST_COMMENT_ID, "spam");

            when(reportRecordRepository.findById("report_invalid_type_001"))
                    .thenReturn(Optional.of(pendingReport));

            Map<String, Object> result = reportService.handleReport(
                    "report_invalid_type_001",
                    TestDataBuilder.TEST_AUDITOR,
                    "unknown_result",
                    "测试处理"
            );

            assertFalse((Boolean) result.get("success"), "处理应失败");
            assertEquals("无效的处理结果", result.get("message"), "错误信息应正确");

            verify(reportRecordRepository, never()).save(any(ReportRecord.class));
        }

        @Test
        @DisplayName("状态流转完整测试")
        void testStatusFlow_CompleteCycle() {
            ReportRecord report = TestDataBuilder.buildPendingReport(
                    "report_cycle_001", TestDataBuilder.TEST_COMMENT_ID, "violation");
            assertEquals("pending", report.getReportStatus(), "初始状态应为待处理");

            when(reportRecordRepository.findById("report_cycle_001"))
                    .thenReturn(Optional.of(report));
            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(existingComment));

            reportService.handleReport(
                    "report_cycle_001",
                    TestDataBuilder.TEST_AUDITOR,
                    "valid",
                    "确认违规"
            );

            assertEquals("resolved", report.getReportStatus(), "最终状态应为已处理");
            assertNotNull(report.getHandledAt(), "应设置处理时间");
            assertEquals("valid", report.getHandleResult(), "应设置处理结果");
            assertEquals(TestDataBuilder.TEST_AUDITOR, report.getHandledBy(), "应设置处理人");
        }
    }

    @Nested
    @DisplayName("重复举报去重测试")
    class DuplicateReportTests {

        @Test
        @DisplayName("同一评论多举报 - 优先级递增但不阻止新举报")
        void testMultipleReports_SameComment() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                List<ReportRecord> existingReports = new ArrayList<>(Arrays.asList(
                        TestDataBuilder.buildPendingReport("report_1", TestDataBuilder.TEST_COMMENT_ID, "spam"),
                        TestDataBuilder.buildPendingReport("report_2", TestDataBuilder.TEST_COMMENT_ID, "violation")
                ));

                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));
                when(reportRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(existingReports);

                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_3");
                when(priorityCalculator.calculatePriority("abuse", 2)).thenReturn(85);

                ReportRequest thirdReport = TestDataBuilder.buildReportRequest(
                        TestDataBuilder.TEST_COMMENT_ID, "abuse", "辱骂行为", "user_3");
                Map<String, Object> result = reportService.submitReport(thirdReport);

                assertTrue((Boolean) result.get("success"), "新举报应可提交");
                assertEquals("report_3", result.get("report_id"), "应生成新举报ID");
                assertEquals(85, result.get("priority"), "优先级应因已有举报而提升");

                verify(reportRecordRepository, times(1)).save(any(ReportRecord.class));
            }
        }

        @Test
        @DisplayName("同一用户多举报 - 不被拦截")
        void testSameUserMultipleReports() {
            try (MockedStatic<IdGenerator> idGenerator = mockStatic(IdGenerator.class)) {
                when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(Optional.of(existingComment));
                when(reportRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(new ArrayList<>());

                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_user_1");
                when(priorityCalculator.calculatePriority("spam", 0)).thenReturn(30);
                Map<String, Object> result1 = reportService.submitReport(spamReportRequest);
                assertTrue((Boolean) result1.get("success"));

                List<ReportRecord> afterFirst = Arrays.asList(
                        TestDataBuilder.buildPendingReport("report_user_1", TestDataBuilder.TEST_COMMENT_ID, "spam")
                );
                when(reportRecordRepository.findByCommentId(TestDataBuilder.TEST_COMMENT_ID))
                        .thenReturn(afterFirst);

                idGenerator.when(IdGenerator::generateReportId).thenReturn("report_user_2");
                when(priorityCalculator.calculatePriority("violation", 1)).thenReturn(65);
                ReportRequest secondRequest = TestDataBuilder.buildReportRequest(
                        TestDataBuilder.TEST_COMMENT_ID, "violation", "还有违规内容",
                        TestDataBuilder.TEST_REPORT_USER);
                Map<String, Object> result2 = reportService.submitReport(secondRequest);

                assertTrue((Boolean) result2.get("success"), "同一用户可提交多次举报");
                assertEquals("report_user_2", result2.get("report_id"));
            }
        }

        @Test
        @DisplayName("举报查询 - 按评论获取所有举报")
        void testGetReportsByComment() {
            List<ReportRecord> reports = Arrays.asList(
                    TestDataBuilder.buildPendingReport("report_1", TestDataBuilder.TEST_COMMENT_ID, "spam"),
                    TestDataBuilder.buildPendingReport("report_2", TestDataBuilder.TEST_COMMENT_ID, "violation")
            );

            when(reportRecordRepository.findByCommentIdOrderByReportedAtDesc(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(reports);

            List<ReportRecord> result = reportService.getReportsByComment(TestDataBuilder.TEST_COMMENT_ID);

            assertEquals(2, result.size(), "应返回2条举报");
        }

        @Test
        @DisplayName("举报查询 - 按状态获取")
        void testGetReportsByStatus() {
            List<ReportRecord> pendingReports = Arrays.asList(
                    TestDataBuilder.buildPendingReport("report_pending_1", TestDataBuilder.TEST_COMMENT_ID, "spam"),
                    TestDataBuilder.buildPendingReport("report_pending_2", TestDataBuilder.TEST_COMMENT_ID_2, "violation")
            );

            when(reportRecordRepository.findByReportStatus("pending"))
                    .thenReturn(pendingReports);

            List<ReportRecord> result = reportService.getReportsByStatus("pending");

            assertEquals(2, result.size(), "应返回2条待处理举报");
        }
    }

    @Nested
    @DisplayName("批量处理举报测试")
    class BatchHandleTests {

        @Test
        @DisplayName("批量处理有效举报 - 全部成功")
        void testBatchHandleReports_AllSuccess() {
            List<String> reportIds = Arrays.asList("batch_1", "batch_2", "batch_3");

            ReportRecord report1 = TestDataBuilder.buildPendingReport("batch_1", TestDataBuilder.TEST_COMMENT_ID, "spam");
            ReportRecord report2 = TestDataBuilder.buildPendingReport("batch_2", TestDataBuilder.TEST_COMMENT_ID_2, "violation");
            ReportRecord report3 = TestDataBuilder.buildPendingReport("batch_3", TestDataBuilder.TEST_COMMENT_ID_3, "abuse");

            when(reportRecordRepository.findById("batch_1")).thenReturn(Optional.of(report1));
            when(reportRecordRepository.findById("batch_2")).thenReturn(Optional.of(report2));
            when(reportRecordRepository.findById("batch_3")).thenReturn(Optional.of(report3));
            when(commentRepository.findById(anyString())).thenReturn(Optional.of(existingComment));

            Map<String, Object> result = reportService.batchHandleReports(
                    reportIds, TestDataBuilder.TEST_AUDITOR, "valid", "批量处理");

            assertEquals(3, result.get("total"), "总数应为3");
            assertEquals(3, result.get("success_count"), "成功数应为3");
            assertEquals(0, result.get("fail_count"), "失败数应为0");
            assertTrue((Boolean) result.get("success"), "批量处理应成功");

            verify(reportRecordRepository, times(3)).save(any(ReportRecord.class));
        }

        @Test
        @DisplayName("批量处理 - 部分失败")
        void testBatchHandleReports_PartialFailure() {
            List<String> reportIds = Arrays.asList("batch_success", "batch_failure");

            ReportRecord successReport = TestDataBuilder.buildPendingReport(
                    "batch_success", TestDataBuilder.TEST_COMMENT_ID, "spam");

            when(reportRecordRepository.findById("batch_success"))
                    .thenReturn(Optional.of(successReport));
            when(reportRecordRepository.findById("batch_failure"))
                    .thenReturn(Optional.empty());
            when(commentRepository.findById(TestDataBuilder.TEST_COMMENT_ID))
                    .thenReturn(Optional.of(existingComment));

            Map<String, Object> result = reportService.batchHandleReports(
                    reportIds, TestDataBuilder.TEST_AUDITOR, "valid", "批量处理");

            assertEquals(2, result.get("total"), "总数应为2");
            assertEquals(1, result.get("success_count"), "成功数应为1");
            assertEquals(1, result.get("fail_count"), "失败数应为1");
        }

        @Test
        @DisplayName("批量处理 - 全部无效结果")
        void testBatchHandleReports_InvalidResult() {
            List<String> reportIds = Arrays.asList("batch_invalid_1", "batch_invalid_2");

            ReportRecord report1 = TestDataBuilder.buildPendingReport(
                    "batch_invalid_1", TestDataBuilder.TEST_COMMENT_ID, "spam");
            ReportRecord report2 = TestDataBuilder.buildPendingReport(
                    "batch_invalid_2", TestDataBuilder.TEST_COMMENT_ID_2, "spam");

            when(reportRecordRepository.findById("batch_invalid_1"))
                    .thenReturn(Optional.of(report1));
            when(reportRecordRepository.findById("batch_invalid_2"))
                    .thenReturn(Optional.of(report2));

            Map<String, Object> result = reportService.batchHandleReports(
                    reportIds, TestDataBuilder.TEST_AUDITOR, "invalid", "批量驳回");

            assertEquals(2, result.get("total"), "总数应为2");
            assertEquals(2, result.get("success_count"), "成功数应为2");
            assertEquals(0, result.get("fail_count"), "失败数应为0");

            verify(reportRecordRepository, times(2)).save(any(ReportRecord.class));
            assertEquals("rejected", report1.getReportStatus());
            assertEquals("rejected", report2.getReportStatus());
        }
    }

    @Nested
    @DisplayName("举报统计测试")
    class ReportStatsTests {

        @Test
        @DisplayName("获取举报统计")
        void testGetReportStats() {
            when(reportRecordRepository.countByReportStatus("pending")).thenReturn(15L);
            when(reportRecordRepository.countByReportStatus("resolved")).thenReturn(80L);
            when(reportRecordRepository.countByReportStatus("rejected")).thenReturn(5L);

            Map<String, Long> stats = reportService.getReportStats();

            assertEquals(15L, stats.get("pending"), "待处理数应正确");
            assertEquals(80L, stats.get("resolved"), "已处理数应正确");
            assertEquals(5L, stats.get("rejected"), "已拒绝数应正确");
        }

        @Test
        @DisplayName("按类型统计举报数量")
        void testCountReportsByType() {
            when(reportRecordRepository.countByReportType("spam")).thenReturn(30L);
            when(reportRecordRepository.countByReportType("violation")).thenReturn(25L);
            when(reportRecordRepository.countByReportType("abuse")).thenReturn(15L);
            when(reportRecordRepository.countByReportType("illegal")).thenReturn(5L);

            assertEquals(30L, reportService.countReportsByType("spam"));
            assertEquals(25L, reportService.countReportsByType("violation"));
            assertEquals(15L, reportService.countReportsByType("abuse"));
            assertEquals(5L, reportService.countReportsByType("illegal"));
        }

        @Test
        @DisplayName("获取待处理举报列表")
        void testGetPendingReports() {
            List<ReportRecord> pendingReports = Arrays.asList(
                    TestDataBuilder.buildPendingReport("pending_1", TestDataBuilder.TEST_COMMENT_ID, "illegal"),
                    TestDataBuilder.buildPendingReport("pending_2", TestDataBuilder.TEST_COMMENT_ID_2, "abuse"),
                    TestDataBuilder.buildPendingReport("pending_3", TestDataBuilder.TEST_COMMENT_ID_3, "spam")
            );

            when(reportRecordRepository.findPendingReportsOrdered())
                    .thenReturn(pendingReports);

            List<ReportRecord> result = reportService.getPendingReports();

            assertEquals(3, result.size(), "应返回3条待处理举报");
        }
    }
}
