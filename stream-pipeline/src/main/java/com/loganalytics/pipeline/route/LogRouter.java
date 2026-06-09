package com.loganalytics.pipeline.route;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.pipeline.config.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public class LogRouter {
    private static final Logger log = LoggerFactory.getLogger(LogRouter.class);

    public enum RouteTarget {
        ERROR_LOGS,
        ANOMALY_DETECTION,
        ARCHIVE,
        ANALYTICS,
        DROP
    }

    public static class RoutingRule {
        private String name;
        private RouteTarget target;
        private String serviceMatch;
        private LogLevel minLevel;
        private String patternMatch;
        private String tagMatch;
        private boolean matchAll;
        private int priority;

        public RoutingRule() {}

        public boolean matches(LogEvent event) {
            if (matchAll) return true;

            if (serviceMatch != null && !event.getServiceName().equals(serviceMatch)) {
                return false;
            }

            if (minLevel != null && event.getLevel() != null
                    && event.getLevel().ordinal() < minLevel.ordinal()) {
                return false;
            }

            if (patternMatch != null && event.getMessage() != null) {
                Pattern p = Pattern.compile(patternMatch);
                if (!p.matcher(event.getMessage()).find()) {
                    return false;
                }
            }

            if (tagMatch != null && event.getTags() != null) {
                if (!event.getTags().containsKey(tagMatch)) {
                    return false;
                }
            }

            return true;
        }

        public String getName() { return name; }
        public void setName(String name) { this.name = name; }

        public RouteTarget getTarget() { return target; }
        public void setTarget(RouteTarget target) { this.target = target; }

        public String getServiceMatch() { return serviceMatch; }
        public void setServiceMatch(String serviceMatch) { this.serviceMatch = serviceMatch; }

        public LogLevel getMinLevel() { return minLevel; }
        public void setMinLevel(LogLevel minLevel) { this.minLevel = minLevel; }

        public String getPatternMatch() { return patternMatch; }
        public void setPatternMatch(String patternMatch) { this.patternMatch = patternMatch; }

        public String getTagMatch() { return tagMatch; }
        public void setTagMatch(String tagMatch) { this.tagMatch = tagMatch; }

        public boolean isMatchAll() { return matchAll; }
        public void setMatchAll(boolean matchAll) { this.matchAll = matchAll; }

        public int getPriority() { return priority; }
        public void setPriority(int priority) { this.priority = priority; }
    }

    private final PipelineConfig config;
    private final List<RoutingRule> rules;
    private final Map<RouteTarget, Long> routeCounts;

    public LogRouter(PipelineConfig config) {
        this.config = config;
        this.rules = new ArrayList<>();
        this.routeCounts = new ConcurrentHashMap<>();
        initializeDefaultRules();
    }

    private void initializeDefaultRules() {
        RoutingRule errorRule = new RoutingRule();
        errorRule.setName("error-logs");
        errorRule.setTarget(RouteTarget.ERROR_LOGS);
        errorRule.setMinLevel(LogLevel.ERROR);
        errorRule.setPriority(100);
        rules.add(errorRule);

        RoutingRule anomalyRule = new RoutingRule();
        anomalyRule.setName("anomaly-candidates");
        anomalyRule.setTarget(RouteTarget.ANOMALY_DETECTION);
        anomalyRule.setMinLevel(LogLevel.WARN);
        anomalyRule.setPriority(90);
        rules.add(anomalyRule);

        RoutingRule analyticsRule = new RoutingRule();
        analyticsRule.setName("analytics");
        analyticsRule.setTarget(RouteTarget.ANALYTICS);
        analyticsRule.setMinLevel(LogLevel.INFO);
        analyticsRule.setPriority(50);
        rules.add(analyticsRule);

        RoutingRule archiveRule = new RoutingRule();
        archiveRule.setName("archive-all");
        archiveRule.setTarget(RouteTarget.ARCHIVE);
        archiveRule.setMatchAll(true);
        archiveRule.setPriority(0);
        rules.add(archiveRule);

        rules.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    public List<RouteTarget> route(LogEvent event) {
        List<RouteTarget> targets = new ArrayList<>();

        for (RoutingRule rule : rules) {
            if (rule.matches(event)) {
                if (rule.getTarget() == RouteTarget.DROP) {
                    event.addTag("routed", "dropped");
                    return targets;
                }
                if (!targets.contains(rule.getTarget())) {
                    targets.add(rule.getTarget());
                    routeCounts.merge(rule.getTarget(), 1L, Long::sum);
                    event.addTag("routed_" + rule.getTarget().name().toLowerCase(), "true");
                }
            }
        }

        return targets;
    }

    public void addRule(RoutingRule rule) {
        rules.add(rule);
        rules.sort((a, b) -> Integer.compare(b.getPriority(), a.getPriority()));
    }

    public Map<RouteTarget, Long> getRouteCounts() {
        return new ConcurrentHashMap<>(routeCounts);
    }

    public List<RoutingRule> getRules() {
        return new ArrayList<>(rules);
    }
}
