package com.loganalytics.alert;

import com.loganalytics.alert.config.AlertEngineConfig;
import com.loganalytics.alert.engine.RuleEvaluator;
import com.loganalytics.alert.notification.NotificationManager;
import com.loganalytics.alert.state.AlertStateManager;
import com.loganalytics.common.model.AlertRule;
import com.loganalytics.common.model.AnomalyEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.IdUtils;
import com.loganalytics.metrics.config.MetricsConfig;
import com.loganalytics.metrics.window.WindowedAggregator;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class AlertEngineMain {
    private static final Logger log = LoggerFactory.getLogger(AlertEngineMain.class);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Scheduler scheduler;
    private final AlertEngineConfig config;
    private final RuleEvaluator ruleEvaluator;

    public AlertEngineMain() {
        this.config = new AlertEngineConfig();
        AlertStateManager stateManager = new AlertStateManager(config);
        NotificationManager notificationManager = new NotificationManager(config);
        MetricsConfig metricsConfig = new MetricsConfig();
        WindowedAggregator aggregator = new WindowedAggregator(metricsConfig);
        this.ruleEvaluator = new RuleEvaluator(config, stateManager, notificationManager, aggregator);
    }

    public void start() throws SchedulerException {
        if (running.compareAndSet(false, true)) {
            log.info("Starting Alert Engine...");

            loadDefaultRules();

            scheduler = StdSchedulerFactory.getDefaultScheduler();

            JobDetail job = JobBuilder.newJob(RuleEvaluationJob.class)
                    .withIdentity("ruleEvaluationJob", "alertEngine")
                    .build();

            job.getJobDataMap().put("ruleEvaluator", ruleEvaluator);

            Trigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("ruleEvaluationTrigger", "alertEngine")
                    .startNow()
                    .withSchedule(SimpleScheduleBuilder.simpleSchedule()
                            .withIntervalInSeconds((int) config.getEvaluationInterval().getSeconds())
                            .repeatForever())
                    .build();

            scheduler.scheduleJob(job, trigger);
            scheduler.start();

            log.info("Alert Engine started with {} rules, evaluation interval: {}s",
                    ruleEvaluator.getRules().size(), config.getEvaluationInterval().getSeconds());
        }
    }

    public void stop() throws SchedulerException {
        if (running.compareAndSet(true, false)) {
            log.info("Stopping Alert Engine...");
            if (scheduler != null) {
                scheduler.shutdown(true);
            }
            log.info("Alert Engine stopped");
        }
    }

    private void loadDefaultRules() {
        AlertRule highErrorRateRule = new AlertRule();
        highErrorRateRule.setId(IdUtils.generateId("rule"));
        highErrorRateRule.setName("High Error Rate");
        highErrorRateRule.setDescription("Alert when error rate exceeds 5% for 1 minute");
        highErrorRateRule.setEnabled(true);
        highErrorRateRule.setConditionType(AlertRule.ConditionType.ERROR_RATE);
        highErrorRateRule.setOperator(AlertRule.Operator.GT);
        highErrorRateRule.setThreshold(0.05);
        highErrorRateRule.setEvaluationWindow(Duration.ofMinutes(5));
        highErrorRateRule.setMinFiringDurationMinutes(1);
        highErrorRateRule.setCooldownPeriod(Duration.ofMinutes(10));
        highErrorRateRule.setSeverity(AnomalyEvent.Severity.HIGH);
        highErrorRateRule.setServiceFilter(List.of("*"));
        highErrorRateRule.setNotificationChannels(List.of(
                AlertRule.NotificationChannel.EMAIL,
                AlertRule.NotificationChannel.SLACK
        ));
        highErrorRateRule.setNotificationTargets(List.of("oncall@example.com"));
        highErrorRateRule.setEscalationDelay(Duration.ofMinutes(5));
        highErrorRateRule.setMaxEscalationLevel(3);
        highErrorRateRule.setCreatedBy("system");
        highErrorRateRule.setCreatedAt(System.currentTimeMillis());
        highErrorRateRule.setUpdatedAt(System.currentTimeMillis());
        ruleEvaluator.addRule(highErrorRateRule);

        AlertRule criticalErrorsRule = new AlertRule();
        criticalErrorsRule.setId(IdUtils.generateId("rule"));
        criticalErrorsRule.setName("Critical Errors Detected");
        criticalErrorsRule.setDescription("Alert when ERROR level logs exceed threshold");
        criticalErrorsRule.setEnabled(true);
        criticalErrorsRule.setConditionType(AlertRule.ConditionType.METRIC_THRESHOLD);
        criticalErrorsRule.setMetricName("error_count");
        criticalErrorsRule.setOperator(AlertRule.Operator.GT);
        criticalErrorsRule.setThreshold(10);
        criticalErrorsRule.setEvaluationWindow(Duration.ofMinutes(5));
        criticalErrorsRule.setMinFiringDurationMinutes(1);
        criticalErrorsRule.setCooldownPeriod(Duration.ofMinutes(5));
        criticalErrorsRule.setSeverity(AnomalyEvent.Severity.CRITICAL);
        criticalErrorsRule.setLevelFilter(List.of(LogLevel.ERROR));
        criticalErrorsRule.setNotificationChannels(List.of(
                AlertRule.NotificationChannel.PAGERDUTY,
                AlertRule.NotificationChannel.SLACK
        ));
        criticalErrorsRule.setNotificationTargets(List.of("critical-alerts"));
        criticalErrorsRule.setEscalationDelay(Duration.ofMinutes(3));
        criticalErrorsRule.setMaxEscalationLevel(5);
        criticalErrorsRule.setCreatedBy("system");
        criticalErrorsRule.setCreatedAt(System.currentTimeMillis());
        criticalErrorsRule.setUpdatedAt(System.currentTimeMillis());
        ruleEvaluator.addRule(criticalErrorsRule);

        AlertRule anomalyDetectionRule = new AlertRule();
        anomalyDetectionRule.setId(IdUtils.generateId("rule"));
        anomalyDetectionRule.setName("Anomaly Detected");
        anomalyDetectionRule.setDescription("Alert when any type of anomaly is detected");
        anomalyDetectionRule.setEnabled(true);
        anomalyDetectionRule.setConditionType(AlertRule.ConditionType.ANOMALY_TYPE);
        anomalyDetectionRule.setAnomalyType(AnomalyEvent.AnomalyType.FREQUENCY);
        anomalyDetectionRule.setEvaluationWindow(Duration.ofMinutes(10));
        anomalyDetectionRule.setMinFiringDurationMinutes(0);
        anomalyDetectionRule.setCooldownPeriod(Duration.ofMinutes(15));
        anomalyDetectionRule.setSeverity(AnomalyEvent.Severity.MEDIUM);
        anomalyDetectionRule.setNotificationChannels(List.of(
                AlertRule.NotificationChannel.SLACK
        ));
        anomalyDetectionRule.setNotificationTargets(List.of("anomaly-alerts"));
        anomalyDetectionRule.setCreatedBy("system");
        anomalyDetectionRule.setCreatedAt(System.currentTimeMillis());
        anomalyDetectionRule.setUpdatedAt(System.currentTimeMillis());
        ruleEvaluator.addRule(anomalyDetectionRule);

        log.info("Loaded {} default alert rules", ruleEvaluator.getRules().size());
    }

    public RuleEvaluator getRuleEvaluator() {
        return ruleEvaluator;
    }

    public static class RuleEvaluationJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            RuleEvaluator evaluator = (RuleEvaluator) context.getJobDetail().getJobDataMap().get("ruleEvaluator");
            if (evaluator != null) {
                evaluator.evaluateAllRules();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        AlertEngineMain engine = new AlertEngineMain();
        engine.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                engine.stop();
            } catch (SchedulerException e) {
                log.error("Error stopping alert engine", e);
            }
        }));

        Thread.currentThread().join();
    }
}
