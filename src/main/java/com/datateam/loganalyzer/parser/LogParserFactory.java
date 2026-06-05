package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.util.JsonUtils;
import io.krakens.grok.api.exception.GrokException;

import java.util.List;
import java.util.regex.Pattern;

public class LogParserFactory {

    private static final Pattern JSON_LINE_PATTERN = Pattern.compile("^\\s*\\{.*\\}\\s*$");
    private static final Pattern SYSLOG_PATTERN = Pattern.compile("^<\\d+>");
    private static final Pattern LOG4J_PATTERN = Pattern.compile(
        "^\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}[.,]?\\d*\\s+[A-Z]+\\s+"
    );
    private static final Pattern NGINX_PATTERN = Pattern.compile(
        "^\\d+\\.\\d+\\.\\d+\\.\\d+\\s+-\\s+\\S+\\s+\\[\\d{2}/[A-Z][a-z]{2}/\\d{4}:"
    );

    public static LogParser createParser(LogFormat format) {
        return createParser(format, null, null, null);
    }

    public static LogParser createParser(LogFormat format, String pattern,
                                         String customPatternsDir, String serviceName) {
        LogParser parser;

        switch (format) {
            case LOG4J:
            case LOGBACK:
                parser = new Log4jParser();
                break;
            case SYSLOG:
                parser = new SyslogParser();
                break;
            case JSON_LINES:
                parser = new JsonLogParser();
                break;
            case CUSTOM_REGEX:
                if (pattern == null) {
                    throw new IllegalArgumentException("Custom regex pattern is required");
                }
                parser = new RegexLogParser(pattern);
                break;
            case CUSTOM_GROK:
                if (pattern == null) {
                    throw new IllegalArgumentException("Grok pattern is required");
                }
                try {
                    parser = new GrokLogParser(pattern, customPatternsDir);
                } catch (GrokException e) {
                    throw new RuntimeException("Failed to compile grok pattern: " + e.getMessage(), e);
                }
                break;
            case AUTO_DETECT:
            default:
                parser = new GenericParser();
                break;
        }

        if (parser instanceof AbstractLogParser && serviceName != null) {
            ((AbstractLogParser) parser).setServiceName(serviceName);
        }

        return parser;
    }

    public static LogFormat detectFormat(String sampleLine) {
        if (sampleLine == null || sampleLine.trim().isEmpty()) {
            return LogFormat.UNKNOWN;
        }

        String trimmed = sampleLine.trim();

        if (JsonUtils.isValidJson(trimmed) || JSON_LINE_PATTERN.matcher(trimmed).matches()) {
            return LogFormat.JSON_LINES;
        }

        if (SYSLOG_PATTERN.matcher(trimmed).find()) {
            return LogFormat.SYSLOG;
        }

        if (LOG4J_PATTERN.matcher(trimmed).find()) {
            return LogFormat.LOG4J;
        }

        if (NGINX_PATTERN.matcher(trimmed).find()) {
            return LogFormat.NGINX;
        }

        return LogFormat.UNKNOWN;
    }

    public static LogFormat detectFormat(List<String> sampleLines) {
        if (sampleLines == null || sampleLines.isEmpty()) {
            return LogFormat.UNKNOWN;
        }

        int jsonCount = 0;
        int syslogCount = 0;
        int log4jCount = 0;
        int nginxCount = 0;

        int limit = Math.min(sampleLines.size(), 50);
        for (int i = 0; i < limit; i++) {
            String line = sampleLines.get(i);
            LogFormat detected = detectFormat(line);
            switch (detected) {
                case JSON_LINES: jsonCount++; break;
                case SYSLOG: syslogCount++; break;
                case LOG4J: log4jCount++; break;
                case NGINX: nginxCount++; break;
            }
        }

        int threshold = limit / 4;
        if (jsonCount >= threshold) return LogFormat.JSON_LINES;
        if (log4jCount >= threshold) return LogFormat.LOG4J;
        if (syslogCount >= threshold) return LogFormat.SYSLOG;
        if (nginxCount >= threshold) return LogFormat.NGINX;

        return LogFormat.UNKNOWN;
    }

    public static LogParser autoDetect(List<String> sampleLines, String serviceName) {
        LogFormat format = detectFormat(sampleLines);
        return createParser(format, null, null, serviceName);
    }

    private static class GenericParser extends AbstractLogParser {
        @Override
        protected Pattern getLinePattern() {
            return null;
        }

        @Override
        protected LogEvent extractFields(java.util.regex.Matcher matcher, String rawLine) {
            return null;
        }

        @Override
        public LogFormat getFormat() {
            return LogFormat.UNKNOWN;
        }

        @Override
        public LogEvent parse(String line) {
            return parseGeneric(line);
        }
    }
}
