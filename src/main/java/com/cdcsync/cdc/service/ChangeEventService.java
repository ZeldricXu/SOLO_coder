package com.cdcsync.cdc.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.cdc.domain.ChangeEvent;

import java.time.LocalDateTime;
import java.util.List;

public interface ChangeEventService extends BaseService<ChangeEvent, String> {

    List<ChangeEvent> findByTaskId(String taskId);

    List<ChangeEvent> findByTaskIdAndTimeRange(String taskId, LocalDateTime startTime, LocalDateTime endTime);

    List<ChangeEvent> findUnprocessedEvents(String taskId, int limit);

    void markAsProcessed(String eventId);
}
