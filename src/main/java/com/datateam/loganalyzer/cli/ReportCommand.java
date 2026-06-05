package com.datateam.loganalyzer.cli;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.anomaly.AnomalyDetectionEngine;
import com.datateam.loganalyzer.alert.AlertRuleEngine;
import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertRule;
import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.report.ReportGenerator;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "report",
    description = "生成日志分析报告",
    mixinStandardHelpOptions = true
)
public class ReportCommand implements Callable<Integer> {

    @Parameters(description = "日志文件路径", arity = "0..*")
    private List<String> inputPaths;

    @Option(names = {"-g", "--granularity"}, description = "时间粒度")
    private TimeUtils.Granularity granularity = TimeUtils.Granularity.MINUTE;

    @Option(names = {"-o", "--output"}, description = "输出文件路径，不指定则输出到控制台")
    private String outputFile;

    @Option(names = {"--format"}, description = "输出格式: console, html, markdown")
    private String outputFormat = "console";

    @Option(names = {"--top-n"}, description = "Top N数量", defaultValue = "10")
    private int topN = 10;

    @Option(names = {"--chart-width"}, description = "图表宽度", defaultValue = "80")
    private int chartWidth = 80;

    @Option(names = {"--chart-height"}, description = "图表高度", defaultValue = "15")
    private int chartHeight = 15;

    @Option(names = {"--anomaly-metric"}, description = "异常检测指标")
    private String anomalyMetric = "errors";

    @Option(names = {"--zscore-threshold"}, description = "Z-score阈值", defaultValue = "3.0")
    private double zscoreThreshold = 3.0;

    @Option(names = {"--ma-window"}, description = "移动平均窗口", defaultValue = "10")
    private int maWindow = 10;

    @Option(names = {"--ma-sigma"}, description = "移动平均sigma", defaultValue = "3.0")
    private double maSigma = 3.0;

    @Option(names = {"--baseline-file"}, description = "基线日志文件")
    private String baselineFile;

    @Option(names = {"--threshold-value"}, description = "告警阈值")
    private Double thresholdValue;

    @Option(names = {"--threshold-metric"}, description = "告警指标")
    private String thresholdMetric = "errors";

    @Option(names = {"--severity"}, description = "告警级别")
    private String severity = "WARNING";

    @Option(names = {"-f", "--log-format"}, description = "日志格式")
    private LogFormat logFormat = LogFormat.AUTO_DETECT;

    @Option(names = {"-s", "--service"}, description = "服务名称")
    private String serviceName;

    @Option(names = {"--merge-multiline"}, description = "合并多行堆栈", defaultValue = "true")
    private boolean mergeMultiline = true;

    @Option(names = {"--include-summary"}, description = "包含概览", defaultValue = "true")
    private boolean includeSummary = true;

    @Option(names = {"--include-top-errors"}, description = "包含Top错误", defaultValue = "true")
    private boolean includeTopErrors = true;

    @Option(names = {"--include-availability"}, description = "包含可用性", defaultValue = "true")
    private boolean includeAvailability = true;

    @Option(names = {"--include-trend"}, description = "包含趋势图", defaultValue = "true")
    private boolean includeTrend = true;

    @Option(names = {"--include-anomalies"}, description = "包含异常检测", defaultValue = "true")
    private boolean includeAnomalies = true;

    @Option(names = {"--include-alerts"}, description = "包含告警", defaultValue = "true")
    private boolean includeAlerts = true;

    @Option(names = {"--include-services"}, description = "包含服务分布", defaultValue = "true")
    private boolean includeServices = true;

    @Override
    public Integer call() throws Exception {
        List<String> rawLines = readInput();
        if (rawLines.isEmpty()) {
            System.err.println("没有输入数据");
            return 1;
        }

        List<LogEvent> events = parseEvents(rawLines);
        TimeSeriesAggregator aggregator = aggregate(events);
        List<TimeSeriesPoint> timeSeries = aggregator.getTimeSeries();

        List<TimeSeriesPoint> baselineSeries = null;
        if (baselineFile != null && !baselineFile.isEmpty()) {
            List<String> baselineLines = FileUtils.readAllLines(baselineFile);
            List<LogEvent> baselineEvents = parseEvents(baselineLines);
            TimeSeriesAggregator baselineAggregator = aggregate(baselineEvents);
            baselineSeries = baselineAggregator.getTimeSeries();
            System.out.println("基线数据点: " + baselineSeries.size());
        }

        List<AnomalyResult> anomalies = new ArrayList<>();
        if (includeAnomalies) {
            AnomalyDetectionEngine engine = new AnomalyDetectionEngine(anomalyMetric);
            engine.configureZScore(zscoreThreshold, 10);
            engine.configureMovingAverage(maWindow, maSigma, 20);
            anomalies = engine.analyze(timeSeries, baselineSeries);
        }

        List<AlertEvent> alerts = new ArrayList<>();
        if (includeAlerts && thresholdValue != null) {
            AlertRuleEngine engine = new AlertRuleEngine();
            AlertRule rule = new AlertRule();
            rule.setId("report-rule");
            rule.setName("Report Alert");
            rule.setType(AlertRule.RuleType.THRESHOLD);
            rule.setSeverity(com.datateam.loganalyzer.model.AlertSeverity.valueOf(severity.toUpperCase()));
            rule.setMetric(thresholdMetric);
            rule.setComparison(AlertRule.Comparison.GT);
            rule.setThreshold(thresholdValue);
            engine.addRule(rule);
            alerts = engine.evaluate(timeSeries);
        }

        ReportGenerator generator = new ReportGenerator();
        generator.setTopN(topN);
        generator.setChartWidth(chartWidth);
        generator.setChartHeight(chartHeight);

        String report = generator.generateOverviewReport(aggregator, alerts, anomalies);

        outputReport(report);

        return 0;
    }

    private List<String> readInput() throws Exception {
        if (inputPaths == null || inputPaths.isEmpty()) {
            return FileUtils.readFromStdin();
        }
        List<String> rawLines = new ArrayList<>();
        List<File> files = FileUtils.expandFilePaths(inputPaths);
        for (File file : files) {
            rawLines.addAll(FileUtils.readAllLines(file));
        }
        return rawLines;
    }

    private List<LogEvent> parseEvents(List<String> rawLines) {
        LogFormat detectedFormat = logFormat == LogFormat.AUTO_DETECT ?
            LogParserFactory.detectFormat(rawLines) : logFormat;
        LogParser parser = LogParserFactory.createParser(detectedFormat, null, null, serviceName);

        if (mergeMultiline) {
            MultiLineMerger merger = new MultiLineMerger(parser);
            return merger.processLines(rawLines);
        } else {
            return parser.parseAll(rawLines);
        }
    }

    private TimeSeriesAggregator aggregate(List<LogEvent> events) {
        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(granularity);
        for (LogEvent event : events) {
            aggregator.add(event);
        }
        return aggregator;
    }

    private void outputReport(String report) throws Exception {
        if (outputFile != null && !outputFile.isEmpty()) {
            try (FileWriter writer = new FileWriter(outputFile)) {
                writer.write(report);
            }
            System.out.println("报告已写入: " + outputFile);
        } else {
            System.out.println(report);
        }
    }
}
