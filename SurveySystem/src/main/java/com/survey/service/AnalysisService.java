package com.survey.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.survey.common.SurveyConstants;
import com.survey.dto.StatQueryResponse;
import com.survey.entity.AnalysisReport;
import com.survey.entity.Survey;
import com.survey.exception.SurveyException;
import com.survey.repository.AnalysisReportRepository;
import com.survey.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisService {

    private final AnalysisReportRepository reportRepository;
    private final StatisticsService statisticsService;
    private final SurveyService surveyService;
    private final HistoryService historyService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public AnalysisReport generateReport(String surveyId) {
        log.info("生成分析报告，问卷ID: {}", surveyId);

        Survey survey = surveyService.findSurvey(surveyId)
                .orElseThrow(() -> SurveyException.surveyNotFound(surveyId));

        StatQueryResponse statistics = statisticsService.getStatistics(surveyId);

        Map<String, Object> reportContent = buildReportContent(survey, statistics);

        AnalysisReport report = new AnalysisReport();
        report.setReportId(IdGenerator.generateReportId());
        report.setSurveyId(surveyId);
        report.setReportName(survey.getSurveyName() + " - 分析报告");

        try {
            report.setReportContent(objectMapper.writeValueAsString(reportContent));
        } catch (JsonProcessingException e) {
            log.error("序列化报告内容失败", e);
            report.setReportContent("{}");
        }

        report.setReportStatus(SurveyConstants.REPORT_STATUS_GENERATED);
        report.setCreatedAt(LocalDateTime.now());

        AnalysisReport saved = reportRepository.save(report);

        historyService.recordReportHistory(saved.getReportId(), "GENERATE_REPORT",
                "生成分析报告，问卷ID: " + surveyId + ", 报告ID: " + saved.getReportId(), null);
        historyService.recordSurveyHistory(surveyId, "REPORT_GENERATED",
                "分析报告已生成，报告ID: " + saved.getReportId(), null);

        log.info("分析报告生成成功: {}", saved.getReportId());
        return saved;
    }

    private Map<String, Object> buildReportContent(Survey survey, StatQueryResponse statistics) {
        Map<String, Object> content = new HashMap<>();

        Map<String, Object> surveyInfo = new HashMap<>();
        surveyInfo.put("surveyId", survey.getSurveyId());
        surveyInfo.put("surveyName", survey.getSurveyName());
        surveyInfo.put("surveyType", survey.getSurveyType());
        surveyInfo.put("surveyStatus", survey.getSurveyStatus());
        surveyInfo.put("createdAt", survey.getCreatedAt() != null ?
                survey.getCreatedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        surveyInfo.put("publishedAt", survey.getPublishedAt() != null ?
                survey.getPublishedAt().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null);
        content.put("surveyInfo", surveyInfo);

        Map<String, Object> summary = new HashMap<>();
        summary.put("totalAnswers", statistics.getAnswerCount());
        summary.put("reviewedAnswers", statistics.getReviewedCount());
        summary.put("completionRate", statistics.getCompletionRate());
        summary.put("generatedAt", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        content.put("summary", summary);

        Map<String, Object> questionStats = parseQuestionStats(statistics.getQuestionStat());
        content.put("questionStatistics", questionStats);

        Map<String, Object> analysis = new HashMap<>();
        analysis.put("responseSufficient", statistics.getAnswerCount() >= 30);
        analysis.put("hasEnoughData", statistics.getAnswerCount() > 0);
        if (statistics.getAnswerCount() > 0) {
            double rate = statistics.getCompletionRate();
            if (rate >= 0.8) {
                analysis.put("completionLevel", "high");
                analysis.put("completionComment", "完成率良好");
            } else if (rate >= 0.5) {
                analysis.put("completionLevel", "medium");
                analysis.put("completionComment", "完成率一般");
            } else {
                analysis.put("completionLevel", "low");
                analysis.put("completionComment", "完成率较低");
            }
        }
        content.put("analysis", analysis);

        List<String> recommendations = java.util.ArrayList.of();
        if (statistics.getAnswerCount() < 30) {
            recommendations = java.util.List.of("建议继续收集更多答卷数据以提高分析可靠性",
                    "考虑扩大发布范围或延长收集时间");
        }
        content.put("recommendations", recommendations);

        return content;
    }

    private Map<String, Object> parseQuestionStats(String statJson) {
        try {
            return objectMapper.readValue(statJson, Map.class);
        } catch (JsonProcessingException e) {
            log.error("解析题目统计数据失败", e);
            return new HashMap<>();
        }
    }

    public AnalysisReport getReport(String reportId) {
        return reportRepository.findByReportId(reportId)
                .orElseThrow(() -> new SurveyException(404, "报告不存在: " + reportId));
    }

    public List<AnalysisReport> getReportsBySurvey(String surveyId) {
        return reportRepository.findBySurveyIdOrderByCreatedAtDesc(surveyId);
    }

    public Map<String, Object> getReportContent(String reportId) {
        AnalysisReport report = getReport(reportId);
        try {
            return objectMapper.readValue(report.getReportContent(), Map.class);
        } catch (JsonProcessingException e) {
            log.error("解析报告内容失败", e);
            return new HashMap<>();
        }
    }
}
