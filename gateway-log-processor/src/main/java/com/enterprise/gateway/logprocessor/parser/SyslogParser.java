package com.enterprise.gateway.logprocessor.parser;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;

import java.time.LocalDateTime;
import java.time.Month;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyslogParser implements LogParser {

    private static final Pattern PATTERN = Pattern.compile(
            "^<(\\d+)>(\\w{3}) +(\\d{1,2}) (\\d{2}):(\\d{2}):(\\d{2}) (\\S+) (.*)$");

    private static final Map<String, Month> MONTH_MAP = new HashMap<>();

    static {
        MONTH_MAP.put("Jan", Month.JANUARY);
        MONTH_MAP.put("Feb", Month.FEBRUARY);
        MONTH_MAP.put("Mar", Month.MARCH);
        MONTH_MAP.put("Apr", Month.APRIL);
        MONTH_MAP.put("May", Month.MAY);
        MONTH_MAP.put("Jun", Month.JUNE);
        MONTH_MAP.put("Jul", Month.JULY);
        MONTH_MAP.put("Aug", Month.AUGUST);
        MONTH_MAP.put("Sep", Month.SEPTEMBER);
        MONTH_MAP.put("Oct", Month.OCTOBER);
        MONTH_MAP.put("Nov", Month.NOVEMBER);
        MONTH_MAP.put("Dec", Month.DECEMBER);
    }

    @Override
    public boolean tryParse(String line, LogEntry.LogEntryBuilder out) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        if (line.charAt(0) != '<') {
            return false;
        }
        Matcher matcher = PATTERN.matcher(line);
        if (!matcher.matches()) {
            return false;
        }
        try {
            Month month = MONTH_MAP.get(matcher.group(2));
            if (month == null) {
                return false;
            }
            int day = Integer.parseInt(matcher.group(3));
            int hour = Integer.parseInt(matcher.group(4));
            int minute = Integer.parseInt(matcher.group(5));
            int second = Integer.parseInt(matcher.group(6));
            int year = LocalDateTime.now().getYear();
            LocalDateTime dateTime = LocalDateTime.of(year, month, day, hour, minute, second);
            out.timestamp(dateTime.toInstant(ZoneOffset.UTC).toEpochMilli());
            out.service(matcher.group(7));
            out.message(matcher.group(8));
            out.level("INFO");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.SYSLOG;
    }

    @Override
    public byte getFirstByte() {
        return '<';
    }
}
