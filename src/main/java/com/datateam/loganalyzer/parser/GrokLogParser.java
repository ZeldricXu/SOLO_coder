package com.datateam.loganalyzer.parser;

import com.datateam.loganalyzer.model.LogEvent;
import com.datateam.loganalyzer.model.LogLevel;
import com.datateam.loganalyzer.util.TimeUtils;
import io.krakens.grok.api.Grok;
import io.krakens.grok.api.GrokCompiler;
import io.krakens.grok.api.Match;
import io.krakens.grok.api.exception.GrokException;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GrokLogParser extends AbstractLogParser {

    private Grok grok;
    private String pattern;
    private String timestampField = "timestamp";
    private String levelField = "level";
    private String messageField = "message";
    private String serviceField = "service";
    private String loggerField = "logger";
    private String threadField = "thread";

    public GrokLogParser(String pattern) throws GrokException {
        this.pattern = pattern;
        GrokCompiler compiler = GrokCompiler.newInstance();
        compiler.registerDefaultPatterns();
        this.grok = compiler.compile(pattern);
    }

    public GrokLogParser(String pattern, String customPatternsDir) throws GrokException {
        this.pattern = pattern;
        GrokCompiler compiler = GrokCompiler.newInstance();
        compiler.registerDefaultPatterns();
        if (customPatternsDir != null) {
            File dir = new File(customPatternsDir);
            if (dir.isDirectory()) {
                File[] files = dir.listFiles((f) -> f.isFile() && !f.isHidden());
                if (files != null) {
                    for (File f : files) {
                        try (InputStream is = new FileInputStream(f)) {
                            compiler.register(is);
                        } catch (Exception e) {
                            // ignore
                        }
                    }
                }
            }
        }
        this.grok = compiler.compile(pattern);
    }

    @Override
    protected Pattern getLinePattern() {
        return null;
    }

    @Override
    protected LogEvent extractFields(Matcher matcher, String rawLine) {
        return null;
    }

    @Override
    public LogEvent parse(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }

        Match gm = grok.match(line);
        Map<String, Object> captures = gm.capture();

        if (captures == null || captures.isEmpty()) {
            return parseGeneric(line);
        }

        LogEvent event = new LogEvent();
        event.setRawLine(line);

        Object tsObj = captures.get(timestampField);
        if (tsObj != null) {
            String tsStr = tsObj.toString();
            if (!tsStr.isEmpty()) {
                Instant ts = TimeUtils.parseTimestamp(tsStr);
                event.setTimestamp(ts != null ? ts : Instant.now());
            }
        }
        if (event.getTimestamp() == null) {
            event.setTimestamp(Instant.now());
        }

        Object levelObj = captures.get(levelField);
        if (levelObj != null) {
            event.setLevel(LogLevel.fromString(levelObj.toString()));
        }
        if (event.getLevel() == null) {
            event.setLevel(LogLevel.UNKNOWN);
        }

        Object msgObj = captures.get(messageField);
        if (msgObj != null && !msgObj.toString().isEmpty()) {
            event.setMessage(msgObj.toString());
        } else {
            event.setMessage(line);
        }

        Object serviceObj = captures.get(serviceField);
        if (serviceObj != null && !serviceObj.toString().isEmpty()) {
            event.setService(serviceObj.toString());
        } else if (serviceName != null) {
            event.setService(serviceName);
        }

        Object loggerObj = captures.get(loggerField);
        if (loggerObj != null && !loggerObj.toString().isEmpty()) {
            event.setLogger(loggerObj.toString());
        }

        Object threadObj = captures.get(threadField);
        if (threadObj != null && !threadObj.toString().isEmpty()) {
            event.setThread(threadObj.toString());
        }

        for (Map.Entry<String, Object> entry : captures.entrySet()) {
            String key = entry.getKey();
            if (!key.equals(timestampField) && !key.equals(levelField) &&
                !key.equals(messageField) && !key.equals(serviceField) &&
                !key.equals(loggerField) && !key.equals(threadField)) {
                Object value = entry.getValue();
                if (value != null && !value.toString().isEmpty()) {
                    event.addField(key, value.toString());
                }
            }
        }

        event.setErrorType(event.extractErrorType());

        return event;
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.CUSTOM_GROK;
    }

    public void setTimestampField(String timestampField) {
        this.timestampField = timestampField;
    }

    public void setLevelField(String levelField) {
        this.levelField = levelField;
    }

    public void setMessageField(String messageField) {
        this.messageField = messageField;
    }

    public void setServiceField(String serviceField) {
        this.serviceField = serviceField;
    }

    public void setLoggerField(String loggerField) {
        this.loggerField = loggerField;
    }

    public void setThreadField(String threadField) {
        this.threadField = threadField;
    }
}
