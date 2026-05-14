package com.projmanage.controller;

import com.projmanage.dto.ApiResponse;
import com.projmanage.model.Report;
import com.projmanage.service.ReportService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/v1/reports")
public class ReportController {

    private final ReportService reportService;

    public ReportController(ReportService reportService) {
        this.reportService = reportService;
    }

    @PostMapping("/daily")
    public ApiResponse<Report> generateDailyReport(@RequestParam String projectId,
                                                    @RequestParam String generatedBy) {
        Report report = reportService.generateDailyReport(projectId, generatedBy);
        if (report != null) {
            return ApiResponse.success(report);
        }
        return ApiResponse.error(404, "项目不存在");
    }

    @PostMapping("/weekly")
    public ApiResponse<Report> generateWeeklyReport(@RequestParam String projectId,
                                                    @RequestParam String generatedBy) {
        Report report = reportService.generateWeeklyReport(projectId, generatedBy);
        if (report != null) {
            return ApiResponse.success(report);
        }
        return ApiResponse.error(404, "项目不存在");
    }

    @PostMapping("/monthly")
    public ApiResponse<Report> generateMonthlyReport(@RequestParam String projectId,
                                                      @RequestParam String generatedBy) {
        Report report = reportService.generateMonthlyReport(projectId, generatedBy);
        if (report != null) {
            return ApiResponse.success(report);
        }
        return ApiResponse.error(404, "项目不存在");
    }

    @GetMapping("/{reportId}")
    public ApiResponse<Report> getReportById(@PathVariable String reportId) {
        Optional<Report> reportOpt = reportService.getReportById(reportId);
        if (reportOpt.isPresent()) {
            return ApiResponse.success(reportOpt.get());
        }
        return ApiResponse.error(404, "报告不存在");
    }

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Report>> getReportsByProject(@PathVariable String projectId) {
        return ApiResponse.success(reportService.getReportsByProject(projectId));
    }
}
