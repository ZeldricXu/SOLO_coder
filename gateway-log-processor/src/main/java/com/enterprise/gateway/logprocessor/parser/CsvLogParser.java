package com.enterprise.gateway.logprocessor.parser;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;

public class CsvLogParser implements LogParser {

    @Override
    public boolean tryParse(String line, LogEntry.LogEntryBuilder out) {
        if (line == null || line.isEmpty()) {
            return false;
        }
        int firstCommaIndex = line.indexOf(',');
        if (firstCommaIndex == -1 || firstCommaIndex >= 32) {
            return false;
        }
        String[] parts = line.split(",");
        if (parts.length < 2) {
            return false;
        }
        try {
            if (!parts[0].isEmpty()) {
                out.timestamp(Long.parseLong(parts[0].trim()));
            }
            if (parts.length > 1) {
                out.service(parts[1].trim());
            }
            if (parts.length > 2) {
                out.level(parts[2].trim());
            }
            if (parts.length > 3) {
                out.message(parts[3].trim());
            }
            if (parts.length > 4) {
                out.traceId(parts[4].trim());
            }
            if (parts.length > 5) {
                out.statusCode(parts[5].trim());
            }
            if (parts.length > 6) {
                out.method(parts[6].trim());
            }
            if (parts.length > 7) {
                out.path(parts[7].trim());
            }
            if (parts.length > 8) {
                out.duration(parts[8].trim());
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public LogFormat getFormat() {
        return LogFormat.CSV;
    }

    @Override
    public byte getFirstByte() {
        return '0';
    }
}
