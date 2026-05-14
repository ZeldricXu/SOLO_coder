package com.reviewsystem.service;

import com.reviewsystem.dto.ReportRequest;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.ReportRecord;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.ReportRecordRepository;
import com.reviewsystem.util.IdGenerator;
import com.reviewsystem.util.ReportPriorityCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
public class ReportService {

    private static final Logger logger = LoggerFactory.getLogger(ReportService.class);

    @Autowired
    private ReportRecordRepository reportRecordRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private ReportPriorityCalculator priorityCalculator;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Map<String, Object> submitReport(ReportRequest request) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(request.getCommentId());
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        int existingReports = reportRecordRepository.findByCommentId(request.getCommentId()).size();
        int priority = priorityCalculator.calculatePriority(request.getReportType(), existingReports);

        ReportRecord report = new ReportRecord();
        report.setReportId(IdGenerator.generateReportId());
        report.setCommentId(request.getCommentId());
        report.setReportUserId(request.getReportUserId());
        report.setReportType(request.getReportType());
        report.setReportReason(request.getReportReason());
        report.setReportStatus("pending");
        report.setPriority(priority);
        reportRecordRepository.save(report);

        historyService.recordHistory(request.getCommentId(), "REPORT_SUBMIT",
                "提交举报: " + request.getReportType() + " - " + request.getReportReason(),
                null, null, null, null,
                request.getReportUserId(), "user");

        result.put("success", true);
        result.put("report_id", report.getReportId());
        result.put("status", "pending");
        result.put("priority", priority);
        result.put("reported_at", report.getReportedAt());

        logger.info("举报提交成功: reportId={}, commentId={}, type={}",
                report.getReportId(), request.getCommentId(), request.getReportType());
        return result;
    }

    public List<ReportRecord> getPendingReports() {
        return reportRecordRepository.findPendingReportsOrdered();
    }

    public List<ReportRecord> getReportsByComment(String commentId) {
        return reportRecordRepository.findByCommentIdOrderByReportedAtDesc(commentId);
    }

    public List<ReportRecord> getReportsByStatus(String status) {
        return reportRecordRepository.findByReportStatus(status);
    }

    public Optional<ReportRecord> getReport(String reportId) {
        return reportRecordRepository.findById(reportId);
    }

    @Transactional
    public Map<String, Object> handleReport(String reportId, String handler,
                                             String resultType, String handleNote) {
        Map<String, Object> result = new HashMap<>();

        Optional<ReportRecord> reportOpt = reportRecordRepository.findById(reportId);
        if (reportOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "举报记录不存在");
            return result;
        }

        ReportRecord report = reportOpt.get();
        report.setHandledBy(handler);
        report.setHandleResult(resultType);
        report.setHandleNote(handleNote);
        report.setHandledAt(LocalDateTime.now());

        if ("valid".equalsIgnoreCase(resultType)) {
            report.setReportStatus("resolved");
            handleValidReport(report, handler);
        } else if ("invalid".equalsIgnoreCase(resultType)) {
            report.setReportStatus("rejected");
        } else {
            result.put("success", false);
            result.put("message", "无效的处理结果");
            return result;
        }

        reportRecordRepository.save(report);

        historyService.recordHistory(report.getCommentId(), "REPORT_HANDLE",
                "举报处理: " + resultType + " - " + handleNote,
                null, null, null, null,
                handler, "admin");

        result.put("success", true);
        result.put("report_id", reportId);
        result.put("status", report.getReportStatus());
        result.put("handler", handler);
        result.put("handle_result", resultType);
        result.put("handled_at", report.getHandledAt());

        logger.info("举报处理完成: reportId={}, result={}, handler={}",
                reportId, resultType, handler);
        return result;
    }

    private void handleValidReport(ReportRecord report, String handler) {
        Optional<Comment> commentOpt = commentRepository.findById(report.getCommentId());
        if (commentOpt.isPresent()) {
            Comment comment = commentOpt.get();
            String oldStatus = comment.getCommentStatus();
            comment.setCommentStatus("hidden");
            commentRepository.save(comment);

            historyService.recordHistory(comment.getCommentId(), "COMMENT_HIDE",
                    "因举报隐藏评论",
                    oldStatus, "hidden", null, null,
                    handler, "admin");
        }
    }

    @Transactional
    public Map<String, Object> batchHandleReports(List<String> reportIds, String handler,
                                                   String resultType, String handleNote) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        int successCount = 0;
        int failCount = 0;

        for (String reportId : reportIds) {
            try {
                Map<String, Object> singleResult = handleReport(reportId, handler, resultType, handleNote);
                if (Boolean.TRUE.equals(singleResult.get("success"))) {
                    successCount++;
                } else {
                    failCount++;
                }
                results.add(singleResult);
            } catch (Exception e) {
                failCount++;
                Map<String, Object> errorResult = new HashMap<>();
                errorResult.put("report_id", reportId);
                errorResult.put("success", false);
                errorResult.put("message", e.getMessage());
                results.add(errorResult);
            }
        }

        result.put("success", true);
        result.put("total", reportIds.size());
        result.put("success_count", successCount);
        result.put("fail_count", failCount);
        result.put("results", results);

        logger.info("批量处理举报完成: total={}, success={}, fail={}",
                reportIds.size(), successCount, failCount);
        return result;
    }

    public Map<String, Long> getReportStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("pending", reportRecordRepository.countByReportStatus("pending"));
        stats.put("resolved", reportRecordRepository.countByReportStatus("resolved"));
        stats.put("rejected", reportRecordRepository.countByReportStatus("rejected"));
        return stats;
    }

    public long countReportsByType(String reportType) {
        return reportRecordRepository.countByReportType(reportType);
    }
}
