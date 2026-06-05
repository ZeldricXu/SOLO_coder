package com.datateam.loganalyzer.cli;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.anomaly.AnomalyDetectionEngine;
import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "detect",
    description = "异常检测 - 基于Z-score和移动平均残差分析",
    mixinStandardHelpOptions = true
)
public class DetectCommand implements Callable<Integer> {

    @Parameters(description = "日志文件路径", arity = "0..*")
    private List<String> inputPaths;

    @Option(names = {"-g", "--granularity"}, description = "时间粒度")
    private TimeUtils.Granularity granularity = TimeUtils.Granularity.MINUTE;

    @Option(names = {"-m", "--metric"}, description = "检测指标: total, errors, warns, rate, error_ratio")
    private String metric = "errors";

    @Option(names = {"--zscore-threshold"}, description = "Z-score阈值", defaultValue = "3.0")
    private double zscoreThreshold = 3.0;

    @Option(names = {"--ma-window"}, description = "移动平均窗口大小", defaultValue = "10")
    private int maWindow = 10;

    @Option(names = {"--ma-sigma"}, description = "移动平均sigma乘数", defaultValue = "3.0")
    private double maSigma = 3.0;

    @Option(names = {"--min-data-points"}, description = "最小数据点数量", defaultValue = "10")
    private int minDataPoints = 10;

    @Option(names = {"--baseline-period"}, description = "基线期数据点数量", defaultValue = "30")
    private int baselinePeriod = 30;

    @Option(names = {"--baseline-file"}, description = "基线期日志文件路径")
    private String baselineFile;

    @Option(names = {"--disable-zscore"}, description = "禁用Z-score检测")
    private boolean disableZscore;

    @Option(names = {"--disable-ma"}, description = "禁用移动平均检测")
    private boolean disableMa;

    @Option(names = {"-f", "--format"}, description = "日志格式")
    private LogFormat format = LogFormat.AUTO_DETECT;

    @Option(names = {"-s", "--service"}, description = "服务名称")
    private String serviceName;

    @Option(names = {"--merge-multiline"}, description = "合并多行堆栈", defaultValue = "true")
    private boolean mergeMultiline = true;

    @Option(names = {"--show-all"}, description = "显示所有检测结果，包括非异常")
    private boolean showAll;

    @Option(names = {"--output-json"}, description = "输出JSON格式")
    private boolean outputJson;

    @Override
    public Integer call() throws Exception {
        List<String> rawLines = readInput();
        if (rawLines.isEmpty()) {
            System.err.println("没有输入数据");
            return 1;
        }

        List<TimeSeriesPoint> baselineSeries = null;
        if (baselineFile != null && !baselineFile.isEmpty()) {
            List<String> baselineLines = FileUtils.readAllLines(baselineFile);
            baselineSeries = aggregateSeries(baselineLines);
            System.out.println("基线期数据点: " + baselineSeries.size());
        }

        List<TimeSeriesPoint> targetSeries = aggregateSeries(rawLines);
        System.out.println("检测期数据点: " + targetSeries.size());

        if (targetSeries.size() < minDataPoints) {
            System.err.println("数据点不足，至少需要 " + minDataPoints + " 个点");
            return 1;
        }

        AnomalyDetectionEngine engine = new AnomalyDetectionEngine(metric);
        engine.setUseZScore(!disableZscore);
        engine.setUseMovingAverage(!disableMa);
        engine.configureZScore(zscoreThreshold, minDataPoints);
        engine.configureMovingAverage(maWindow, maSigma, minDataPoints);
        engine.setBaselinePeriodPoints(baselinePeriod);

        List<AnomalyResult> anomalies = engine.analyze(targetSeries, baselineSeries);

        outputResults(anomalies, targetSeries, engine);

        if (!anomalies.isEmpty()) {
            return 2;
        }

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

    private List<TimeSeriesPoint> aggregateSeries(List<String> rawLines) {
        LogFormat detectedFormat = format == LogFormat.AUTO_DETECT ?
            LogParserFactory.detectFormat(rawLines) : format;
        LogParser parser = LogParserFactory.createParser(detectedFormat, null, null, serviceName);

        List<LogEvent> events;
        if (mergeMultiline) {
            MultiLineMerger merger = new MultiLineMerger(parser);
            events = merger.processLines(rawLines);
        } else {
            events = parser.parseAll(rawLines);
        }

        TimeSeriesAggregator aggregator = new TimeSeriesAggregator(granularity);
        for (LogEvent event : events) {
            aggregator.add(event);
        }

        return aggregator.getTimeSeries();
    }

    private void outputResults(List<AnomalyResult> anomalies, List<TimeSeriesPoint> series,
                               AnomalyDetectionEngine engine) throws Exception {
        if (outputJson) {
            System.out.println(com.datateam.loganalyzer.util.JsonUtils.toJson(anomalies));
            return;
        }

        System.out.println("\n" + "=".repeat(100));
        System.out.println("🔍 异常检测报告");
        System.out.println("=".repeat(100));
        System.out.println("指标: " + metric);
        System.out.println("检测方法: " +
            (!disableZscore ? "Z-score(阈值=" + zscoreThreshold + ") " : "") +
            (!disableMa ? "移动平均(窗口=" + maWindow + ", σ=" + maSigma + ")" : ""));
        System.out.println("数据点: " + series.size());

        if (engine.getZScoreBaseline() != null) {
            var b = engine.getZScoreBaseline();
            System.out.printf("Z-score基线: mean=%.2f, std=%.2f, p95=%.2f, p99=%.2f%n",
                b.getMean(), b.getStdDev(), b.getPercentile95(), b.getPercentile99());
        }

        System.out.println("-".repeat(100));

        if (anomalies.isEmpty()) {
            System.out.println("✅ 未检测到异常");
            return;
        }

        System.out.println("⚠️  检测到 " + anomalies.size() + " 个异常:");
        System.out.println();
        System.out.printf("%-20s %-8s %-25s %10s %10s %10s %8s%n",
            "时间", "类型", "指标", "观测值", "期望值", "偏差", "Z-score");
        System.out.println("-".repeat(100));

        for (AnomalyResult anomaly : anomalies) {
            if (!anomaly.isAnomaly() && !showAll) continue;

            String status = anomaly.isAnomaly() ? "🔴" : "🟡";
            System.out.printf("%s %-18s %-8s %-25s %10.2f %10.2f %10.2f %8.2f%n",
                status,
                TimeUtils.formatInstant(anomaly.getTimestamp()),
                anomaly.getType(),
                anomaly.getMetric(),
                anomaly.getObservedValue(),
                anomaly.getExpectedValue(),
                anomaly.getDeviation(),
                anomaly.getzScore());
        }

        System.out.println("-".repeat(100));
        System.out.println("\n异常详情:");
        int count = 1;
        for (AnomalyResult anomaly : anomalies) {
            if (!anomaly.isAnomaly() && !showAll) continue;
            System.out.printf("%n%d. [%s] %s%n", count++,
                TimeUtils.formatInstant(anomaly.getTimestamp()),
                anomaly.getDescription());
        }
    }
}
