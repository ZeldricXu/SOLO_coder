package com.cdcsync.cdc.connector;

import com.cdcsync.cdc.core.BinlogParser;
import com.cdcsync.cdc.core.EventDispatcher;
import com.cdcsync.cdc.domain.CaptureTask;
import com.cdcsync.cdc.domain.ChangeEvent;
import com.cdcsync.cdc.service.ChangeEventService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractCdcConnector {

    @Getter
    protected final CaptureTask task;
    protected final BinlogParser binlogParser;
    protected final EventDispatcher eventDispatcher;
    protected final ChangeEventService changeEventService;

    protected volatile boolean running = false;

    public abstract void start();

    public abstract void stop();

    protected void processEvent(String database, String table, String operation,
                                Object before, Object after, LocalDateTime eventTime) {
        ChangeEvent event = binlogParser.parse(
                task.getId(),
                database,
                table,
                operation,
                null,
                null,
                eventTime
        );
        changeEventService.create(event);
        eventDispatcher.dispatch(event);
    }

    public boolean isRunning() {
        return running;
    }
}
