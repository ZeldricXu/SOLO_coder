package com.datateam.loganalyzer.cli;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.report.AsciiChartRenderer;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.concurrent.Callable;

@Command(
    name = "aggregate",
    description = "按时间粒度聚合日志，输出计数和速率",
    mixinStandardHelpOptions = true
)
public class AggregateCommand implements Callable<Integer> {

    @Parameters(description = "日志文件路径", arity = "0..*")
    private List<String> inputPaths;

    @Option(names = {"-g", "--granularity"}, description = "时间粒度: SECOND, MINUTE, HOUR, DAY")
    private TimeUtils.Granularity granularity = TimeUtils.Granularity.MINUTE;

    @Option(names = {"--sliding-window"}, description = "使用滑动窗口（秒）")
    private Integer slidingWindowSeconds;

    @Option(names = {"--sliding-slide"}, description = "滑动窗口步长（秒）", defaultValue = "60")
    private int slidingSlideSeconds = 60;

    @Option(names = {"-f", "--format"}, description = "日志格式")
    private LogFormat format = LogFormat.AUTO_DETECT;

    @Option(names = {"-p", "--pattern"}, description = "自定义模式")
    private String pattern;

    @Option(names = {"-s", "--service"}, description = "服务名称")
    private String serviceName;

    @Option(names = {"--merge-multiline"}, description = "合并多行堆栈", defaultValue = "true")
    private boolean mergeMultiline = true;

    @Option(names = {"--metric"}, description = "聚合指标: total, errors, warns, rate")
    private String metric = "total";

    @Option(names = {"--top-services"}, description = "显示Top N服务统计", defaultValue = "5")
    private int topServices = 5;

    @Option(names = {"--top-errors"}, description = "显示Top N错误类型", defaultValue = "5")
    private int topErrors = 5;

    @Option(names = {"--chart"}, description = "显示ASCII趋势图")
    private boolean showChart;

    @Option(names = {"--chart-width"}, description = "图表宽度", defaultValue = "80")
    private int chartWidth = 80;

    @Option(names = {"--chart-height"}, description = "图表高度", defaultValue = "10")
    private int chartHeight = 10;

    @Override
    public Integer call() throws Exception {
        List<String> rawLines;
        if (inputPaths == null || inputPaths.isEmpty()) {
            rawLines = FileUtils.readFromStdin();
        } else {
            rawLines = new ArrayList<>();
            List<File> files = FileUtils.expandFilePaths(inputPaths);
            for (File file : files) {
                rawLines.addAll(FileUtils.readAllLines(file));
            }
        }

        if (rawLines.isEmpty()) {
            System.err.println("没有输入数据");
            return 1;
        }

        LogFormat detectedFormat = format == LogFormat.AUTO_DETECT ?
            LogParserFactory.detectFormat(rawLines) : format;
        LogParser parser = LogParserFactory.createParser(detectedFormat, pattern, null, serviceName);

        List<LogEvent> events;
        if (mergeMultiline) {
            MultiLineMerger merger = new MultiLineMerger(parser);
            events = merger.processLines(rawLines);
        } else {
            events = parser.parseAll(rawLines);
        }

        TimeSeriesAggregator aggregator;
        if (slidingWindowSeconds != null) {
            aggregator = new TimeSeriesAggregator(slidingWindowSeconds, slidingSlideSeconds);
        } else {
            aggregator = new TimeSeriesAggregator(granularity);
        }

        for (LogEvent event : events) {
            aggregator.add(event);
        }

        List<TimeSeriesPoint> timeSeries = aggregator.getTimeSeries();

        System.out.println("\n时间序列聚合结果 (" + granularity + " 粒度):");
        System.out.println("=".repeat(100));
        System.out.printf("%-25s %-25s %10s %10s %10s %12s%n",
            "窗口开始", "窗口结束", "总数", "错误", "警告", "速率/分");
        System.out.println("-".repeat(100));

        for (TimeSeriesPoint point : timeSeries) {
            System.out.printf("%-25s %-25s %,10d %,10d %,10d %,12.2f%n",
                TimeUtils.formatInstant(point.getWindowStart()),
                TimeUtils.formatInstant(point.getWindowEnd()),
                point.getTotalCount(),
                point.getErrorCount(),
                point.getWarnCount(),
                point.getRatePerMinute());
        }
        System.out.println("-".repeat(100));

        printSummary(aggregator);
        printTopServices(aggregator);
        printTopErrors(aggregator);

        if (showChart) {
            printChart(timeSeries);
        }

        return 0;
    }

    private void printSummary(TimeSeriesAggregator aggregator) {
        System.out.println("\n📊 聚合摘要:");
        System.out.printf("  时间范围: %s - %s%n",
            TimeUtils.formatInstant(aggregator.getStartTime()),
            TimeUtils.formatInstant(aggregator.getEndTime()));
        System.out.printf("  日志总数: %,d%n", aggregator.getTotalCount());
        System.out.printf("  错误总数: %,d%n", aggregator.getErrorCount());
        System.out.printf("  警告总数: %,d%n", aggregator.getWarnCount());
        System.out.printf("  服务数量: %d%n", aggregator.getServiceTotals().size());
        System.out.printf("  错误类型: %d%n", aggregator.getErrorTypeTotals().size());
    }

    private void printTopServices(TimeSeriesAggregator aggregator) {
        if (topServices <= 0) return;

        Map<String, Long> services = aggregator.getServiceTotals();
        if (services.isEmpty()) return;

        System.out.println("\n🏢 Top " + topServices + " 服务:");
        long max = services.values().stream().max(Long::compareTo).orElse(1L);

        List<Map.Entry<String, Long>> sorted = services.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(topServices)
            .collect(Collectors.toList());

        AsciiChartRenderer renderer = new AsciiChartRenderer();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            System.out.printf("  %2d. %s%n", i + 1,
                renderer.renderHorizontalBarChart(entry.getKey(), entry.getValue(), max, 30, true));
        }
    }

    private void printTopErrors(TimeSeriesAggregator aggregator) {
        if (topErrors <= 0) return;

        Map<String, Long> errors = aggregator.getErrorTypeTotals();
        if (errors.isEmpty()) return;

        System.out.println("\n🔥 Top " + topErrors + " 错误类型:");
        long max = errors.values().stream().max(Long::compareTo).orElse(1L);

        List<Map.Entry<String, Long>> sorted = errors.entrySet().stream()
            .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
            .limit(topErrors)
            .collect(Collectors.toList());

        AsciiChartRenderer renderer = new AsciiChartRenderer();
        for (int i = 0; i < sorted.size(); i++) {
            Map.Entry<String, Long> entry = sorted.get(i);
            System.out.printf("  %2d. %s%n", i + 1,
                renderer.renderHorizontalBarChart(entry.getKey(), entry.getValue(), max, 30, true));
        }
    }

    private void printChart(List<TimeSeriesPoint> timeSeries) {
        System.out.println("\n📈 趋势图:");

        List<Double> values = new ArrayList<>();
        for (TimeSeriesPoint point : timeSeries) {
            double value;
            switch (metric.toLowerCase()) {
                case "errors": value = point.getErrorCount(); break;
                case "warns": value = point.getWarnCount(); break;
                case "rate": value = point.getRatePerMinute(); break;
                default: value = point.getTotalCount(); break;
            }
            values.add(value);
        }

        AsciiChartRenderer renderer = new AsciiChartRenderer();
        System.out.println(renderer.renderBarChart(values, chartHeight, chartWidth, true));
    }
}
