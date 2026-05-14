package com.reviewsystem.controller;

import com.reviewsystem.dto.ApiResponse;
import com.reviewsystem.model.ReportRecord;
import com.reviewsystem.service.ReportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private static final Logger logger = LoggerFactory.getLogger(ReportController.class);

    @Autowired
    private ReportService reportService;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<ReportRecord>>> getPendingReports() {
        List<ReportRecord> reports = reportService.getPendingReports();
        return ResponseEntity.ok(ApiResponse.success(reports));
    }

    @GetMapping("/{reportId}")
    public ResponseEntity<ApiResponse<ReportRecord>> getReport(@PathVariable String reportId) {
        Optional<ReportRecord> report = reportService.getReport(reportId);
        if (report.isPresent()) {
            return ResponseEntity.ok(ApiResponse.success(report.get()));
        } else {
            return ResponseEntity.ok(ApiResponse.notFound("举报记录不存在"));
        }
    }

    @GetMapping("/comment/{commentId}")
    public ResponseEntity<ApiResponse<List<ReportRecord>>> getReportsByComment(
            @PathVariable String commentId) {
        List<ReportRecord> reports = reportService.getReportsByComment(commentId);
        return ResponseEntity.ok(ApiResponse.success(reports));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<ReportRecord>>> getReportsByStatus(
            @PathVariable String status) {
        List<ReportRecord> reports = reportService.getReportsByStatus(status);
        return ResponseEntity.ok(ApiResponse.success(reports));
    }

    @PostMapping("/{reportId}/handle")
    public ResponseEntity<ApiResponse<Map<String, Object>>> handleReport(
            @PathVariable String reportId,
            @RequestBody Map<String, String> request) {
        String handler = request.get("handler");
        String resultType = request.get("result");
        String note = request.get("note");

        if (resultType == null) {
            return ResponseEntity.ok(ApiResponse.badRequest("缺少处理结果"));
        }

        logger.info("处理举报: reportId={}, result={}", reportId, resultType);

        Map<String, Object> result = reportService.handleReport(
                reportId, handler != null ? handler : "admin", resultType, note);

        if (Boolean.TRUE.equals(result.get("success"))) {
            return ResponseEntity.ok(ApiResponse.success(result));
        } else {
            return ResponseEntity.ok(ApiResponse.error(400, (String) result.get("message")));
        }
    }

    @PostMapping("/batch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> batchHandleReports(
            @RequestBody Map<String, Object> request) {
        @SuppressWarnings("unchecked")
        List<String> reportIds = (List<String>) request.get("report_ids");
        String handler = (String) request.get("handler");
        String resultType = (String) request.get("result");
        String note = (String) request.get("note");

        if (reportIds == null || reportIds.isEmpty() || resultType == null) {
            return ResponseEntity.ok(ApiResponse.badRequest("缺少必要参数"));
        }

        logger.info("批量处理举报: count={}", reportIds.size());

        Map<String, Object> result = reportService.batchHandleReports(
                reportIds, handler != null ? handler : "admin", resultType, note);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<Map<String, Long>>> getReportStats() {
        Map<String, Long> stats = reportService.getReportStats();
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/type/{type}/count")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCountByType(
            @PathVariable String type) {
        long count = reportService.countReportsByType(type);
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("report_type", type);
        data.put("count", count);
        return ResponseEntity.ok(ApiResponse.success(data));
    }
}
