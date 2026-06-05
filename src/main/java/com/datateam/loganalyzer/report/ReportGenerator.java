package com.datateam.loganalyzer.report;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.util.TimeUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ReportGenerator {

    private final AsciiChartRenderer chartRenderer;
    private int topN = 10;
    private int chartWidth = 80;
    private int chartHeight = 15;

    public ReportGenerator() {
        this.chartRenderer = new AsciiChartRenderer();
    }

    public String generateOverviewReport(TimeSeriesAggregator aggregator,
                                         List<AlertEvent> alerts,
                                         List<AnomalyResult> anomalies) {
        StringBuilder sb = new StringBuilder();

        sb.append("\n");
        sb.append("╔══════════════════════════════════════════════════════════════════════════╗\n");
        sb.append("║                    📊 生产日志分析报告                                     ║\n");
        sb.append("╚══════════════════════════════════════════════════════════════════════════╝\n");
        sb.append("\n");

        sb.append(generateSummarySection(aggregator));
        sb.append("\n");
        sb.append(generateTopErrorsSection(aggregator));
        sb.append("\n");
        sb.append(generateAvailabilitySection(aggregator));
        sb.append("\n");
        sb.append(generateTrendChartSection(aggregator));
        sb.append("\n");
        sb.append(generateAnomalyTimeline(anomalies));
        sb.append("\n");
        sb.append(generateAlertsSection(alerts));
        sb.append("\n");
        sb.append(generateServiceBreakdown(aggregator));
        sb.append("\n");

        return sb.toString();
    }

    public String generateSummarySection(TimeSeriesAggregator aggregator) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("  📈 日志概览\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Instant startTime = aggregator.getStartTime();
        Instant endTime = aggregator.getEndTime();
        long durationMinutes = startTime != null && endTime != null ?
            Duration.between(startTime, endTime).toMinutes() : 0;

        long total = aggregator.getTotalCount();
        long errors = aggregator.getErrorCount();
        long warnings = aggregator.getWarnCount();
        double errorRate = total > 0 ? (double) errors / total * 100 : 0;
        double avgRatePerMin = durationMinutes > 0 ? (double) total / durationMinutes : 0;

        String[] headers = {"指标", "数值"};
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"时间范围", String.format("%s - %s",
            startTime != null ? TimeUtils.formatInstant(startTime) : "N/A",
            endTime != null ? TimeUtils.formatInstant(endTime) : "N/A")});
        rows.add(new String[]{"持续时间", durationMinutes + " 分钟"});
        rows.add(new String[]{"日志总数", String.format("%,d", total)});
        rows.add(new String[]{"错误数", String.format("%,d", errors)});
        rows.add(new String[]{"警告数", String.format("%,d", warnings)});
        rows.add(new String[]{"错误率", String.format("%.2f%%", errorRate)});
        rows.add(new String[]{"平均速率", String.format("%.0f 条/分钟", avgRatePerMin)});
        rows.add(new String[]{"服务数量", String.valueOf(aggregator.getServiceTotals().size())});
        rows.add(new String[]{"错误类型数量", String.valueOf(aggregator.getErrorTypeTotals().size())});

        sb.append(chartRenderer.renderTable(headers, rows));

        return sb.toString();
    }

    public String generateTopErrorsSection(TimeSeriesAggregator aggregator) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append(String.format("  🔥 Top %d 错误类型\n", topN));
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Map<String, Long> errorTypes = aggregator.getErrorTypeTotals();
        if (errorTypes.isEmpty()) {
            sb.append("  未发现错误\n");
            return sb.toString();
        }

        List<Map.Entry<String, Long>> sorted = errorTypes.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(topN)
            .collect(Collectors.toList());

        long max = sorted.get(0).getValue();

        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            String rank = String.format("%2d.", i + 1);
            sb.append(String.format("  %s %s\n",
                rank,
                chartRenderer.renderHorizontalBarChart(entry.getKey(), entry.getValue(), max, 40, true)));
        }

        return sb.toString();
    }

    public String generateAvailabilitySection(TimeSeriesAggregator aggregator) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("  ✅ 服务可用性\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Map<String, Long> serviceTotals = aggregator.getServiceTotals();
        if (serviceTotals.isEmpty()) {
            sb.append("  无服务数据\n");
            return sb.toString();
        }

        Map<String, Long> serviceErrors = aggregator.getServiceErrorTotals();

        String[] headers = {"服务", "总请求", "错误数", "可用性"};
        List<String[]> rows = new ArrayList<>();

        for (Map.Entry<String, Long> entry : serviceTotals.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList())) {
            String service = entry.getKey();
            long total = entry.getValue();
            long errors = serviceErrors.getOrDefault(service, 0L);
            double availability = total > 0 ? (1.0 - (double) errors / total) * 100 : 100.0;

            String status = availability >= 99.9 ? "🟢" :
                availability >= 99.0 ? "🟡" :
                availability >= 95.0 ? "🟠" : "🔴";

            rows.add(new String[]{
                status + " " + service,
                String.format("%,d", total),
                String.format("%,d", errors),
                String.format("%.3f%%", availability)
            });
        }

        sb.append(chartRenderer.renderTable(headers, rows));

        return sb.toString();
    }

    public String generateTrendChartSection(TimeSeriesAggregator aggregator) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("  📉 错误趋势图 (按分钟)\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        List<TimeSeriesPoint> points = aggregator.getTimeSeries();
        if (points.isEmpty()) {
            sb.append("  无数据\n");
            return sb.toString();
        }

        List<Double> errorCounts = new ArrayList<>();
        List<Double> totalCounts = new ArrayList<>();
        for (TimeSeriesPoint point : points) {
            errorCounts.add((double) point.getErrorCount());
            totalCounts.add((double) point.getTotalCount());
        }

        sb.append("\n  错误数量趋势:\n");
        sb.append(chartRenderer.renderBarChart(errorCounts, chartHeight, chartWidth, true));

        sb.append("\n  总日志量趋势:\n");
        sb.append(chartRenderer.renderBarChart(totalCounts, chartHeight, chartWidth, true));

        sb.append("\n  Sparkline (错误): ").append(chartRenderer.renderSparkline(errorCounts)).append("\n");
        sb.append("  Sparkline (总数): ").append(chartRenderer.renderSparkline(totalCounts)).append("\n");

        return sb.toString();
    }

    public String generateAnomalyTimeline(List<AnomalyResult> anomalies) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("  ⚠️  异常事件时间线\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (anomalies == null || anomalies.isEmpty()) {
            sb.append("  未检测到异常\n");
            return sb.toString();
        }

        String[] headers = {"时间", "类型", "指标", "观测值", "期望值", "Z-Score", "状态"};
        List<String[]> rows = new ArrayList<>();

        anomalies.sort(Comparator.comparing(AnomalyResult::getTimestamp));

        for (AnomalyResult anomaly : anomalies) {
            String status = anomaly.isAnomaly() ? "🔴 异常" : "🟡 预警";
            rows.add(new String[]{
                TimeUtils.formatInstant(anomaly.getTimestamp()),
                anomaly.getType() != null ? anomaly.getType().name() : "UNKNOWN",
                anomaly.getMetric(),
                String.format("%.2f", anomaly.getObservedValue()),
                String.format("%.2f", anomaly.getExpectedValue()),
                String.format("%.2f", anomaly.getzScore()),
                status
            });
        }

        sb.append(chartRenderer.renderTable(headers, rows));

        return sb.toString();
    }

    public String generateAlertsSection(List<AlertEvent> alerts) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("  🚨 告警事件\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        if (alerts == null || alerts.isEmpty()) {
            sb.append("  无告警事件\n");
            return sb.toString();
        }

        String[] headers = {"时间", "级别", "规则名称", "描述", "持续时间", "状态"};
        List<String[]> rows = new ArrayList<>();

        alerts.sort(Comparator.comparing(AlertEvent::getTriggeredAt).reversed());

        for (AlertEvent alert : alerts) {
            String status = alert.isActive() ? "🔴 活跃" : "🟢 已恢复";
            String severity = alert.getSeverity() != null ?
                getSeverityEmoji(alert.getSeverity()) + " " + alert.getSeverity() : "UNKNOWN";
            String desc = alert.getDescription() != null && alert.getDescription().length() > 50 ?
                alert.getDescription().substring(0, 50) + "..." : alert.getDescription();

            rows.add(new String[]{
                TimeUtils.formatInstant(alert.getTriggeredAt()),
                severity,
                alert.getRuleName(),
                desc,
                alert.getDurationMinutes() + " 分钟",
                status
            });
        }

        sb.append(chartRenderer.renderTable(headers, rows));

        return sb.toString();
    }

    public String generateServiceBreakdown(TimeSeriesAggregator aggregator) {
        StringBuilder sb = new StringBuilder();

        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");
        sb.append("  📊 服务分布\n");
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n");

        Map<String, Long> serviceTotals = aggregator.getServiceTotals();
        if (serviceTotals.isEmpty()) {
            sb.append("  无服务数据\n");
            return sb.toString();
        }

        long total = serviceTotals.values().stream().mapToLong(Long::longValue).sum();
        long max = serviceTotals.values().stream().max(Long::compareTo).orElse(1L);

        List<Map.Entry<String, Long>> sorted = serviceTotals.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .collect(Collectors.toList());

        for (Map.Entry<String, Long> entry : sorted) {
            double percent = total > 0 ? (double) entry.getValue() / total * 100 : 0;
            String label = String.format("%s (%.1f%%)", entry.getKey(), percent);
            sb.append("  ").append(chartRenderer.renderHorizontalBarChart(
                label, entry.getValue(), max, 30, true)).append("\n");
        }

        return sb.toString();
    }

    private String getSeverityEmoji(Enum<?> severity) {
        switch (severity.name()) {
            case "CRITICAL": return "🔴";
            case "ERROR": return "🟠";
            case "WARNING": return "🟡";
            case "INFO": return "🔵";
            default: return "⚪";
        }
    }

    public void setTopN(int topN) {
        this.topN = topN;
    }

    public void setChartWidth(int chartWidth) {
        this.chartWidth = chartWidth;
    }

    public void setChartHeight(int chartHeight) {
        this.chartHeight = chartHeight;
    }
}
