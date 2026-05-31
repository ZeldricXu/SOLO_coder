package com.cdcsync.cdc.connector;

import com.cdcsync.cdc.core.BinlogParser;
import com.cdcsync.cdc.core.EventDispatcher;
import com.cdcsync.cdc.domain.CaptureTask;
import com.cdcsync.cdc.service.ChangeEventService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class MysqlCdcConnector extends AbstractCdcConnector {

    public MysqlCdcConnector(CaptureTask task, BinlogParser binlogParser,
                             EventDispatcher eventDispatcher, ChangeEventService changeEventService) {
        super(task, binlogParser, eventDispatcher, changeEventService);
    }

    @Override
    public void start() {
        log.info("Starting MySQL CDC connector for task: {}", task.getName());
        running = true;
    }

    @Override
    public void stop() {
        log.info("Stopping MySQL CDC connector for task: {}", task.getName());
        running = false;
    }
}
