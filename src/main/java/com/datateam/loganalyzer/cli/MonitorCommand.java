package com.datateam.loganalyzer.cli;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.anomaly.AnomalyDetectionEngine;
import com.datateam.loganalyzer.alert.AlertRuleEngine;
import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertRule;
import com.datateam.loganalyzer.model.AnomalyResult;
import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import com.datateam.loganalyzer.notification.NotificationManager;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.report.ReportGenerator;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.JsonUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Command(
    name = "monitor",
    description = "实时监控模式 - 持续解析日志、检测异常、触发告警",
    mixinStandardHelpOptions = true
)
public class MonitorCommand implements Callable<Integer> {

    @Parameters(description = "日志文件路径（支持tail -f 模式）", arity = "0..*")
    private List<String> inputPaths;

    @Option(names = {"--interval"}, description = "检查间隔(秒)", defaultValue = "60")
    private int intervalSeconds = 60;

    @Option(names = {"--window"}, description = "滚动窗口大小(分钟)", defaultValue = "30")
    private int windowMinutes = 30;

    @Option(names = {"-g", "--granularity"}, description = "聚合粒度")
    private TimeUtils.Granularity granularity = TimeUtils.Granularity.MINUTE;

    @Option(names = {"--anomaly-metric"}, description = "异常检测指标", defaultValue = "errors")
    private String anomalyMetric = "errors";

    @Option(names = {"--zscore-threshold"}, description = "Z-score阈值", defaultValue = "3.0")
    private double zscoreThreshold = 3.0;

    @Option(names = {"--threshold-metric"}, description = "告警指标", defaultValue = "errors")
    private String thresholdMetric = "errors";

    @Option(names = {"--threshold-value"}, description = "告警阈值", defaultValue = "10")
    private double thresholdValue = 10;

    @Option(names = {"--severity"}, description = "告警级别", defaultValue = "WARNING")
    private String severity = "WARNING";

    @Option(names = {"--cooldown"}, description = "告警冷却期(秒)", defaultValue = "300")
    private int cooldownSeconds = 300;

    @Option(names = {"--escalation-minutes"}, description = "升级时间(分钟)", defaultValue = "10")
    private int escalationMinutes = 10;

    @Option(names = {"--rules-config"}, description = "告警规则配置文件")
    private String rulesConfigFile;

    @Option(names = {"--notifications-config"}, description = "通知配置文件")
    private String notificationsConfigFile;

    @Option(names = {"--report-interval"}, description = "报告输出间隔(分钟)", defaultValue = "5")
    private int reportIntervalMinutes = 5;

    @Option(names = {"--send-alerts"}, description = "发送告警通知")
    private boolean sendAlerts;

    @Option(names = {"-f", "--format"}, description = "日志格式")
    private LogFormat format = LogFormat.AUTO_DETECT;

    @Option(names = {"-s", "--service"}, description = "服务名称")
    private String serviceName;

    @Option(names = {"--follow"}, description = "跟随文件模式（类似tail -f）")
    private boolean follow;

    @Option(names = {"--top-n"}, description = "Top N数量", defaultValue = "5")
    private int topN = 5;

    private volatile boolean running = true;
    private TimeSeriesAggregator aggregator;
    private AnomalyDetectionEngine anomalyEngine;
    private AlertRuleEngine alertEngine;
    private NotificationManager notificationManager;
    private ReportGenerator reportGenerator;
    private LogParser parser;
    private Instant lastReportTime;

    @Override
    public Integer call() throws Exception {
        initializeComponents();

        System.out.println("🔍 日志监控模式启动...");
        System.out.println("   检查间隔: " + intervalSeconds + " 秒");
        System.out.println("   滚动窗口: " + windowMinutes + " 分钟");
        System.out.println("   聚合粒度: " + granularity);
        System.out.println("   异常检测指标: " + anomalyMetric);
        System.out.println("   告警阈值: " + thresholdMetric + " > " + thresholdValue);
        System.out.println("   按 Ctrl+C 停止");
        System.out.println("-".repeat(80));

        if (follow || (inputPaths != null && !inputPaths.isEmpty())) {
            return runFileMonitor();
        } else {
            return runStdinMonitor();
        }
    }

    private void initializeComponents() throws Exception {
        aggregator = new TimeSeriesAggregator(granularity);

        anomalyEngine = new AnomalyDetectionEngine(anomalyMetric);
        anomalyEngine.configureZScore(zscoreThreshold, 10);
        anomalyEngine.configureMovingAverage(10, 3.0, 20);

        alertEngine = new AlertRuleEngine();
        if (rulesConfigFile != null && !rulesConfigFile.isEmpty()) {
            List<AlertRule> rules = JsonUtils.fromFile(new File(rulesConfigFile),
                new com.fasterxml.jackson.core.type.TypeReference<List<AlertRule>>() {});
            alertEngine.addRules(rules);
        } else {
            AlertRule rule = new AlertRule();
            rule.setId("monitor-rule");
            rule.setName("Monitor Alert");
            rule.setType(AlertRule.RuleType.THRESHOLD);
            rule.setSeverity(com.datateam.loganalyzer.model.AlertSeverity.valueOf(severity.toUpperCase()));
            rule.setMetric(thresholdMetric);
            rule.setComparison(AlertRule.Comparison.GT);
            rule.setThreshold(thresholdValue);
            rule.setCooldownSeconds(cooldownSeconds);
            rule.setEscalationMinutes(escalationMinutes);
            alertEngine.addRule(rule);
        }

        notificationManager = new NotificationManager();
        if (notificationsConfigFile != null && !notificationsConfigFile.isEmpty()) {
            List<NotificationConfig> configs = JsonUtils.fromFile(new File(notificationsConfigFile),
                new com.fasterxml.jackson.core.type.TypeReference<List<NotificationConfig>>() {});
            notificationManager.addChannels(configs);
        }

        reportGenerator = new ReportGenerator();
        reportGenerator.setTopN(topN);
        lastReportTime = Instant.now();
    }

    private int runStdinMonitor() throws Exception {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::processCurrentState, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String line;
        List<String> buffer = new ArrayList<>();

        while (running && (line = reader.readLine()) != null) {
            buffer.add(line);

            if (buffer.size() >= 100) {
                processBuffer(buffer);
                buffer.clear();
            }
        }

        if (!buffer.isEmpty()) {
            processBuffer(buffer);
        }

        scheduler.shutdown();
        processCurrentState();

        return 0;
    }

    private int runFileMonitor() throws Exception {
        if (inputPaths == null || inputPaths.isEmpty()) {
            System.err.println("请指定要监控的文件路径");
            return 1;
        }

        List<File> files = FileUtils.expandFilePaths(inputPaths);
        System.out.println("监控文件: " + files.size() + " 个");

        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(() -> {
            try {
                for (File file : files) {
                    List<String> newLines = readNewLines(file);
                    if (!newLines.isEmpty()) {
                        processBuffer(newLines);
                    }
                }
                processCurrentState();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, intervalSeconds, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n正在停止监控...");
            running = false;
            scheduler.shutdown();
        }));

        while (running) {
            Thread.sleep(1000);
        }

        return 0;
    }

    private final java.util.Map<String, Long> filePositions = new java.util.HashMap<>();

    private List<String> readNewLines(File file) throws Exception {
        List<String> lines = new ArrayList<>();
        long lastPos = filePositions.getOrDefault(file.getAbsolutePath(), 0L);
        long currentSize = file.length();

        if (currentSize < lastPos) {
            lastPos = 0;
        }

        if (currentSize > lastPos) {
            List<String> allLines = FileUtils.readAllLines(file);
            if (lastPos == 0) {
                lines.addAll(allLines);
            } else {
                double ratio = (double) lastPos / currentSize;
                int startLine = (int) (allLines.size() * ratio);
                startLine = Math.max(0, Math.min(startLine, allLines.size()));
                lines.addAll(allLines.subList(startLine, allLines.size()));
            }
            filePositions.put(file.getAbsolutePath(), currentSize);
        }

        return lines;
    }

    private void processBuffer(List<String> lines) {
        if (parser == null && !lines.isEmpty()) {
            LogFormat detectedFormat = format == LogFormat.AUTO_DETECT ?
                LogParserFactory.detectFormat(lines) : format;
            parser = LogParserFactory.createParser(detectedFormat, null, null, serviceName);
            System.out.println("检测到日志格式: " + detectedFormat);
        }

        if (parser == null) return;

        MultiLineMerger merger = new MultiLineMerger(parser);
        List<LogEvent> events = merger.processLines(lines);

        Instant cutoff = Instant.now().minus(Duration.ofMinutes(windowMinutes));
        for (LogEvent event : events) {
            if (event.getTimestamp() != null && event.getTimestamp().isAfter(cutoff)) {
                aggregator.add(event);
            }
        }
    }

    private synchronized void processCurrentState() {
        try {
            List<com.datateam.loganalyzer.model.TimeSeriesPoint> timeSeries = aggregator.getTimeSeries();
            if (timeSeries.isEmpty()) return;

            List<AnomalyResult> anomalies = anomalyEngine.analyze(timeSeries);

            List<AlertEvent> alerts = alertEngine.evaluate(timeSeries);

            if (sendAlerts && !alerts.isEmpty() && notificationManager != null) {
                for (AlertEvent alert : alerts) {
                    notificationManager.sendNotification(alert);
                    System.out.printf("[%s] 🚨 告警: %s - %s%n",
                        TimeUtils.formatInstant(Instant.now()),
                        alert.getSeverity(), alert.getRuleName());
                }
            }

            if (!anomalies.isEmpty()) {
                for (AnomalyResult anomaly : anomalies) {
                    if (anomaly.isAnomaly()) {
                        System.out.printf("[%s] ⚠️  异常: %s Z=%.2f value=%.2f%n",
                            TimeUtils.formatInstant(anomaly.getTimestamp()),
                            anomaly.getType(), anomaly.getzScore(), anomaly.getObservedValue());
                    }
                }
            }

            if (Duration.between(lastReportTime, Instant.now()).toMinutes() >= reportIntervalMinutes) {
                System.out.println("\n" + "=".repeat(80));
                System.out.println("📊 监控报告 - " + TimeUtils.formatInstant(Instant.now()));
                System.out.println("=".repeat(80));

                long total = aggregator.getTotalCount();
                long errors = aggregator.getErrorCount();
                System.out.printf("  日志总数: %,d | 错误: %,d | 服务: %d%n",
                    total, errors, aggregator.getServiceTotals().size());

                if (!anomalies.isEmpty()) {
                    System.out.println("  异常检测: " + anomalies.size() + " 个异常点");
                }

                if (!alertEngine.getActiveAlerts().isEmpty()) {
                    System.out.println("  活跃告警: " + alertEngine.getActiveAlerts().size() + " 个");
                }

                lastReportTime = Instant.now();
            }

        } catch (Exception e) {
            System.err.println("处理出错: " + e.getMessage());
        }
    }
}
