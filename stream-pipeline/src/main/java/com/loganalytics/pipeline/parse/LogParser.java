package com.loganalytics.pipeline.parse;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.common.util.TimeUtils;
import com.loganalytics.pipeline.config.PipelineConfig;
import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import io.krakens.grok.api.Match;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LogParser {
    private static final Logger log = LoggerFactory.getLogger(LogParser.class);

    private final PipelineConfig config;
    private final List<Grok> grokPatterns;
    private final List<Pattern> regexPatterns;
    private final Pattern traceIdPattern;
    private final Pattern errorCodePattern;

    public LogParser(PipelineConfig config) {
        this.config = config;
        this.grokPatterns = new ArrayList<>();
        this.regexPatterns = new ArrayList<>();

        GrokCompiler grokCompiler = GrokCompiler.newInstance();
        grokCompiler.registerDefaultPatterns();

        for (String pattern : config.getGrokPatterns()) {
            try {
                Grok grok = grokCompiler.compile(pattern);
                grokPatterns.add(grok);
            } catch (Exception e) {
                log.warn("Failed to compile grok pattern: {}", pattern, e);
            }
        }

        regexPatterns.add(Pattern.compile("^(?<timestamp>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]?\\d*)\\s+" +
                "(?<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\s+" +
                "(?<service>[\\w-]+)\\s+" +
                "(?<message>.*)$"));

        regexPatterns.add(Pattern.compile("^(?<timestamp>\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}[.,]?\\d*)\\s+" +
                "\\[(?<level>TRACE|DEBUG|INFO|WARN|WARNING|ERROR|FATAL)\\]\\s+" +
                "(?<message>.*)$"));

        regexPatterns.add(Pattern.compile("^(?<timestamp>\\d{2}/\\d{2}/\\d{4}:\\d{2}:\\d{2}:\\d{2}\\s+[+-]\\d{4})\\s+" +
                "(?<message>.*)$"));

        traceIdPattern = Pattern.compile("[tT]race[Ii]d[=:]?\\s*([a-f0-9-]{16,64})");
        errorCodePattern = Pattern.compile("error[ _-]?code[=:]\\s*(\\w+)", Pattern.CASE_INSENSITIVE);
    }

    public LogEvent parse(LogEvent event) {
        String rawMessage = event.getRawMessage();
        if (rawMessage == null || rawMessage.isBlank()) {
            return event;
        }

        boolean parsed = tryGrokParse(event, rawMessage);

        if (!parsed) {
            parsed = tryRegexParse(event, rawMessage);
        }

        if (!parsed) {
            parseBasicFields(event, rawMessage);
        }

        extractTraceId(event, rawMessage);
        extractErrorCode(event, rawMessage);

        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        if (event.getLevel() == null) {
            event.setLevel(LogLevel.UNKNOWN);
        }

        event.addTag("parsed", String.valueOf(parsed));
        return event;
    }

    private boolean tryGrokParse(LogEvent event, String rawMessage) {
        for (Grok grok : grokPatterns) {
            try {
                Match match = grok.match(rawMessage);
                Map<String, Object> captures = match.capture();
                if (!captures.isEmpty()) {
                    applyCaptures(event, captures);
                    return true;
                }
            } catch (Exception e) {
                log.debug("Grok match failed", e);
            }
        }
        return false;
    }

    private boolean tryRegexParse(LogEvent event, String rawMessage) {
        for (Pattern pattern : regexPatterns) {
            Matcher matcher = pattern.matcher(rawMessage);
            if (matcher.find()) {
                try {
                    String timestamp = getGroupValue(matcher, "timestamp");
                    String level = getGroupValue(matcher, "level");
                    String service = getGroupValue(matcher, "service");
                    String message = getGroupValue(matcher, "message");

                    if (timestamp != null) {
                        event.setTimestamp(TimeUtils.parseTimestamp(timestamp));
                        event.addField("timestamp", timestamp);
                    }
                    if (level != null) {
                        event.setLevel(LogLevel.fromString(level));
                        event.addField("level", level);
                    }
                    if (service != null && event.getServiceName() == null) {
                        event.setServiceName(service);
                        event.addField("service", service);
                    }
                    if (message != null) {
                        event.setMessage(message);
                    }

                    return true;
                } catch (Exception e) {
                    log.debug("Regex match failed", e);
                }
            }
        }
        return false;
    }

    private String getGroupValue(Matcher matcher, String groupName) {
        try {
            return matcher.group(groupName);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void parseBasicFields(LogEvent event, String rawMessage) {
        if (event.getLevel() == null || event.getLevel() == LogLevel.UNKNOWN) {
            String upper = rawMessage.toUpperCase();
            if (upper.contains("ERROR") || upper.contains("EXCEPTION")) {
                event.setLevel(LogLevel.ERROR);
            } else if (upper.contains("WARN")) {
                event.setLevel(LogLevel.WARN);
            } else if (upper.contains("INFO")) {
                event.setLevel(LogLevel.INFO);
            } else if (upper.contains("DEBUG")) {
                event.setLevel(LogLevel.DEBUG);
            } else if (upper.contains("TRACE")) {
                event.setLevel(LogLevel.TRACE);
            } else if (upper.contains("FATAL")) {
                event.setLevel(LogLevel.FATAL);
            }
        }

        if (event.getMessage() == null) {
            event.setMessage(rawMessage);
        }
    }

    private void applyCaptures(LogEvent event, Map<String, Object> captures) {
        for (Map.Entry<String, Object> entry : captures.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (value == null) continue;
            String strValue = value.toString().trim();
            if (strValue.isEmpty()) continue;

            switch (key.toLowerCase()) {
                case "timestamp":
                    event.setTimestamp(TimeUtils.parseTimestamp(strValue));
                    break;
                case "level":
                case "loglevel":
                    event.setLevel(LogLevel.fromString(strValue));
                    break;
                case "service":
                case "servicename":
                case "app":
                    if (event.getServiceName() == null || event.getServiceName().equals("unknown-service")) {
                        event.setServiceName(strValue);
                    }
                    break;
                case "traceid":
                case "trace_id":
                    event.setTraceId(strValue);
                    break;
                case "spanid":
                case "span_id":
                    event.setSpanId(strValue);
                    break;
                case "message":
                case "msg":
                    event.setMessage(strValue);
                    break;
                case "host":
                case "hostname":
                    if (event.getHostname() == null) {
                        event.setHostname(strValue);
                    }
                    break;
                case "ip":
                case "clientip":
                case "sourceip":
                    if (event.getSourceIp() == null) {
                        event.setSourceIp(strValue);
                    }
                    break;
                default:
                    event.addField(key, strValue);
                    break;
            }
            event.addField(key, strValue);
        }
    }

    private void extractTraceId(LogEvent event, String rawMessage) {
        if (event.getTraceId() != null) return;

        Matcher matcher = traceIdPattern.matcher(rawMessage);
        if (matcher.find()) {
            event.setTraceId(matcher.group(1));
        }
    }

    private void extractErrorCode(LogEvent event, String rawMessage) {
        Matcher matcher = errorCodePattern.matcher(rawMessage);
        if (matcher.find()) {
            event.addField("error_code", matcher.group(1));
        }
    }
}
