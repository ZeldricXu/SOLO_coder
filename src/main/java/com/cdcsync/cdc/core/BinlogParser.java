package com.cdcsync.cdc.core;

import com.cdcsync.cdc.domain.ChangeEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Slf4j
@Component
public class BinlogParser {

    public ChangeEvent parse(String taskId, String database, String table, String operation,
                             Map<String, Object> before, Map<String, Object> after, LocalDateTime eventTime) {
        ChangeEvent event = new ChangeEvent();
        event.setTaskId(taskId);
        event.setSourceDatabase(database);
        event.setSourceTable(table);
        event.setOperationType(operation);
        event.setBeforeData(before != null ? before.toString() : null);
        event.setAfterData(after != null ? after.toString() : null);
        event.setEventTs(eventTime);
        event.setProcessed(false);
        return event;
    }
}
