package com.datateam.loganalyzer.cli;

import com.datateam.loganalyzer.aggregator.TimeSeriesAggregator;
import com.datateam.loganalyzer.alert.AlertRuleEngine;
import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.AlertRule;
import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.NotificationConfig;
import com.datateam.loganalyzer.model.TimeSeriesPoint;
import com.datateam.loganalyzer.notification.NotificationManager;
import com.datateam.loganalyzer.parser.LogFormat;
import com.datateam.loganalyzer.parser.LogParser;
import com.datateam.loganalyzer.parser.LogParserFactory;
import com.datateam.loganalyzer.parser.MultiLineMerger;
import com.datateam.loganalyzer.util.FileUtils;
import com.datateam.loganalyzer.util.JsonUtils;
import com.datateam.loganalyzer.util.TimeUtils;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(
    name = "alert",
    description = "告警规则评估和通知推送",
    mixinStandardHelpOptions = true
)
public class AlertCommand implements Callable<Integer> {

    @Parameters(description = "日志文件路径", arity = "0..*")
    private List<String> inputPaths;

    @Option(names = {"-g", "--granularity"}, description = "时间粒度")
    private TimeUtils.Granularity granularity = TimeUtils.Granularity.MINUTE;

    @Option(names = {"--rules-config"}, description = "告警规则JSON配置文件")
    private String rulesConfigFile;

    @Option(names = {"--notifications-config"}, description = "通知通道JSON配置文件")
    private String notificationsConfigFile;

    @Option(names = {"--threshold-metric"}, description = "阈值规则指标")
    private String thresholdMetric = "errors";

    @Option(names = {"--threshold-compare"}, description = "比较符: GT, LT, GTE, LTE, EQ, NEQ")
    private AlertRule.Comparison thresholdCompare = AlertRule.Comparison.GT;

    @Option(names = {"--threshold-value"}, description = "阈值")
    private Double thresholdValue;

    @Option(names = {"--threshold-min-violations"}, description = "连续违规次数", defaultValue = "1")
    private int minViolations = 1;

    @Option(names = {"--cooldown"}, description = "冷却期(秒)", defaultValue = "300")
    private int cooldownSeconds = 300;

    @Option(names = {"--escalation-minutes"}, description = "告警升级时间(分钟)", defaultValue = "10")
    private int escalationMinutes = 10;

    @Option(names = {"--severity"}, description = "告警级别: INFO, WARNING, ERROR, CRITICAL")
    private String severity = "WARNING";

    @Option(names = {"--rule-name"}, description = "规则名称")
    private String ruleName = "error-rate-alert";

    @Option(names = {"--rule-desc"}, description = "规则描述")
    private String ruleDescription;

    @Option(names = {"--send-notification"}, description = "发送通知")
    private boolean sendNotification;

    @Option(names = {"--channels"}, description = "通知通道名称，多个用逗号分隔")
    private String channels;

    @Option(names = {"-f", "--format"}, description = "日志格式")
    private LogFormat format = LogFormat.AUTO_DETECT;

    @Option(names = {"-s", "--service"}, description = "服务名称")
    private String serviceName;

    @Option(names = {"--merge-multiline"}, description = "合并多行堆栈", defaultValue = "true")
    private boolean mergeMultiline = true;

    @Option(names = {"--dry-run"}, description = "只评估不发送通知", defaultValue = "true")
    private boolean dryRun = true;

    @Override
    public Integer call() throws Exception {
        List<String> rawLines = readInput();
        if (rawLines.isEmpty()) {
            System.err.println("没有输入数据");
            return 1;
        }

        List<TimeSeriesPoint> timeSeries = aggregateSeries(rawLines);
        if (timeSeries.isEmpty()) {
            System.err.println("没有数据点");
            return 1;
        }

        AlertRuleEngine engine = new AlertRuleEngine();

        if (rulesConfigFile != null && !rulesConfigFile.isEmpty()) {
            List<AlertRule> rules = JsonUtils.fromFile(new File(rulesConfigFile),
                new com.fasterxml.jackson.core.type.TypeReference<List<AlertRule>>() {});
            engine.addRules(rules);
            System.out.println("从配置文件加载 " + rules.size() + " 条规则");
        } else {
            if (thresholdValue == null) {
                System.err.println("请指定 --threshold-value 或 --rules-config");
                return 1;
            }

            AlertRule rule = new AlertRule();
            rule.setId("rule-" + System.currentTimeMillis());
            rule.setName(ruleName);
            rule.setDescription(ruleDescription != null ? ruleDescription :
                "Alert when " + thresholdMetric + " " + thresholdCompare + " " + thresholdValue);
            rule.setType(AlertRule.RuleType.THRESHOLD);
            rule.setSeverity(com.datateam.loganalyzer.model.AlertSeverity.valueOf(severity.toUpperCase()));
            rule.setMetric(thresholdMetric);
            rule.setComparison(thresholdCompare);
            rule.setThreshold(thresholdValue);
            rule.setMinViolations(minViolations);
            rule.setCooldownSeconds(cooldownSeconds);
            rule.setEscalationMinutes(escalationMinutes);
            if (channels != null) {
                for (String ch : channels.split(",")) {
                    rule.addNotificationChannel(ch.trim());
                }
            }
            engine.addRule(rule);
        }

        NotificationManager notificationManager = null;
        if (!dryRun && sendNotification) {
            notificationManager = new NotificationManager();
            if (notificationsConfigFile != null && !notificationsConfigFile.isEmpty()) {
                List<NotificationConfig> configs = JsonUtils.fromFile(new File(notificationsConfigFile),
                    new com.fasterxml.jackson.core.type.TypeReference<List<NotificationConfig>>() {});
                notificationManager.addChannels(configs);
                System.out.println("从配置文件加载 " + configs.size() + " 个通知通道");
            }
        }

        List<AlertEvent> alerts = engine.evaluate(timeSeries);

        outputResults(alerts, engine);

        if (!dryRun && sendNotification && notificationManager != null && !alerts.isEmpty()) {
            for (AlertEvent alert : alerts) {
                List<String> channelNames = null;
                AlertRule rule = engine.getRules().stream()
                    .filter(r -> r.getId().equals(alert.getRuleId()))
                    .findFirst().orElse(null);
                if (rule != null && !rule.getNotificationChannels().isEmpty()) {
                    channelNames = rule.getNotificationChannels();
                }

                boolean sent = notificationManager.sendNotification(alert, channelNames);
                System.out.println((sent ? "✅" : "❌") + " 通知 '" + alert.getRuleName() + "' -> " +
                    (channelNames != null ? channelNames : "所有通道"));
            }
        }

        if (!alerts.isEmpty()) {
            return alerts.size();
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

    private void outputResults(List<AlertEvent> alerts, AlertRuleEngine engine) {
        System.out.println("\n" + "=".repeat(100));
        System.out.println("🚨 告警规则评估结果");
        System.out.println("=".repeat(100));

        System.out.println("\n已配置规则:");
        for (AlertRule rule : engine.getRules()) {
            System.out.printf("  - %s: %s %s %s (severity=%s, cooldown=%ds)%n",
                rule.getName(), rule.getMetric(), rule.getComparison(),
                rule.getThreshold(), rule.getSeverity(), rule.getCooldownSeconds());
        }

        if (alerts.isEmpty()) {
            System.out.println("\n✅ 没有触发告警");
            return;
        }

        System.out.println("\n触发的告警 (" + alerts.size() + "):");
        System.out.println("-".repeat(100));
        System.out.printf("%-20s %-10s %-30s %-8s %s%n",
            "触发时间", "级别", "规则", "持续", "描述");
        System.out.println("-".repeat(100));

        for (AlertEvent alert : alerts) {
            String status = alert.isActive() ? "🔴" : "🟢";
            System.out.printf("%s %-18s %-10s %-30s %-8d %s%n",
                status,
                TimeUtils.formatInstant(alert.getTriggeredAt()),
                alert.getSeverity(),
                alert.getRuleName(),
                alert.getDurationMinutes(),
                alert.getDescription() != null && alert.getDescription().length() > 40 ?
                    alert.getDescription().substring(0, 40) + "..." : alert.getDescription());
        }

        System.out.println("-".repeat(100));

        System.out.println("\n活跃告警:");
        for (Map.Entry<String, AlertEvent> entry : engine.getActiveAlerts().entrySet()) {
            AlertEvent alert = entry.getValue();
            if (alert.isActive()) {
                System.out.printf("  - %s: 活跃 %d 分钟, 级别=%s, 升级次数=%d%n",
                    alert.getRuleName(), alert.getDurationMinutes(),
                    alert.getSeverity(), alert.getEscalationCount());
            }
        }

        if (dryRun) {
            System.out.println("\n⚠️  DRY RUN模式，未发送实际通知。使用 --dry-run=false 真正发送通知。");
        }
    }
}
