package com.enterprise.gateway.logprocessor.parser;

import com.enterprise.gateway.logprocessor.model.LogEntry;
import com.enterprise.gateway.logprocessor.model.LogFormat;

public interface LogParser {

    LogFormat getFormat();

    byte getFirstByte();

    boolean tryParse(String line, LogEntry.LogEntryBuilder out);

    default boolean tryParse(String line) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        return tryParse(line, builder);
    }

    default LogEntry parse(String line) {
        LogEntry.LogEntryBuilder builder = LogEntry.builder();
        if (tryParse(line, builder)) {
            builder.format(getFormat());
            builder.rawLine(line);
            return builder.build();
        }
        return null;
    }

}
