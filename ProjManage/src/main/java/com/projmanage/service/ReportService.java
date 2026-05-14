package com.projmanage.service;

import com.projmanage.config.Constants;
import com.projmanage.dto.ProgressResponse;
import com.projmanage.model.Project;
import com.projmanage.model.Report;
import com.projmanage.model.Risk;
import com.projmanage.model.Statistic;
import com.projmanage.repository.ReportRepository;
import com.projmanage.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class ReportService {

    private final ReportRepository reportRepository;
    private final ProjectService projectService;
    private final ProgressService progressService;
    private final StatisticsService statisticsService;
    private final RiskService riskService;

    public ReportService(ReportRepository reportRepository,
                         ProjectService projectService,
                         ProgressService progressService,
                         StatisticsService statisticsService,
                         RiskService riskService) {
        this.reportRepository = reportRepository;
        this.projectService = projectService;
        this.progressService = progressService;
        this.statisticsService = statisticsService;
        this.riskService = riskService;
    }

    @Transactional
    public Report generateDailyReport(String projectId, String generatedBy) {
        return generateReport(projectId, Constants.REPORT_TYPE_DAILY, generatedBy);
    }

    @Transactional
    public Report generateWeeklyReport(String projectId, String generatedBy) {
        return generateReport(projectId, Constants.REPORT_TYPE_WEEKLY, generatedBy);
    }

    @Transactional
    public Report generateMonthlyReport(String projectId, String generatedBy) {
        return generateReport(projectId, Constants.REPORT_TYPE_MONTHLY, generatedBy);
    }

    @Transactional
    public Report generateReport(String projectId, String reportType, String generatedBy) {
        Optional<Project> projectOpt = projectService.getProjectById(projectId);
        if (!projectOpt.isPresent()) {
            return null;
        }

        Project project = projectOpt.get();
        ProgressResponse progress = progressService.getProjectProgress(projectId);
        Optional<Statistic> statistic = statisticsService.getTodayStatistics(projectId);
        List<Risk> activeRisks = riskService.getActiveRisksByProject(projectId);

        StringBuilder content = new StringBuilder();
        content.append("项目名称: ").append(project.getProjectName()).append("\n");
        content.append("项目状态: ").append(project.getProjectStatus()).append("\n");
        content.append("项目进度: ").append(progress.getOverallProgress()).append("%\n");
        content.append("总任务数: ").append(progress.getTotalTasks()).append("\n");
        content.append("已完成任务: ").append(progress.getCompletedTasks()).append("\n");
        content.append("进行中任务: ").append(progress.getInProgressTasks()).append("\n");
        content.append("待开始任务: ").append(progress.getPendingTasks()).append("\n");

        if (statistic.isPresent()) {
            Statistic stat = statistic.get();
            content.append("任务完成率: ").append(stat.getTaskCompletionRate()).append("%\n");
            content.append("按时完成率: ").append(stat.getOnTimeRate()).append("%\n");
            content.append("总工时: ").append(stat.getTotalHours()).append("小时\n");
            content.append("已完成工时: ").append(stat.getCompletedHours()).append("小时\n");
        }

        content.append("\n活跃风险列表:\n");
        if (activeRisks.isEmpty()) {
            content.append("暂无活跃风险\n");
        } else {
            for (Risk risk : activeRisks) {
                content.append("- [").append(risk.getRiskLevel()).append("] ")
                        .append(risk.getRiskDescription()).append("\n");
            }
        }

        Report report = new Report();
        report.setReportId(IdGenerator.generateReportId());
        report.setProjectId(projectId);
        report.setReportType(reportType);
        report.setReportDate(LocalDate.now());
        report.setTitle("项目日报 - " + project.getProjectName());
        report.setContent(content.toString());
        report.setGeneratedBy(generatedBy);
        report.setCreatedAt(LocalDateTime.now());

        return reportRepository.save(report);
    }

    public Optional<Report> getReportById(String reportId) {
        return reportRepository.findById(reportId);
    }

    public List<Report> getReportsByProject(String projectId) {
        return reportRepository.findByProjectIdOrderByCreatedAtDesc(projectId);
    }
}
