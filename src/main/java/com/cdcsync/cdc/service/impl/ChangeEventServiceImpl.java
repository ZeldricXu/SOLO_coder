package com.cdcsync.cdc.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.cdc.domain.ChangeEvent;
import com.cdcsync.cdc.mapper.ChangeEventMapper;
import com.cdcsync.cdc.service.ChangeEventService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChangeEventServiceImpl extends AbstractBaseService<ChangeEvent, String, ChangeEventMapper>
        implements ChangeEventService {

    public ChangeEventServiceImpl(ChangeEventMapper mapper) {
        super(mapper);
    }

    @Override
    protected void setId(ChangeEvent entity, String id) {
    }

    @Override
    protected String getId(ChangeEvent entity) {
        return entity.getId();
    }

    @Override
    public List<ChangeEvent> findByTaskId(String taskId) {
        QueryWrapper<ChangeEvent> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        wrapper.orderByDesc("event_ts");
        return mapper.selectList(wrapper);
    }

    @Override
    public List<ChangeEvent> findByTaskIdAndTimeRange(String taskId, LocalDateTime startTime, LocalDateTime endTime) {
        QueryWrapper<ChangeEvent> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        wrapper.between("event_ts", startTime, endTime);
        wrapper.orderByDesc("event_ts");
        return mapper.selectList(wrapper);
    }

    @Override
    public List<ChangeEvent> findUnprocessedEvents(String taskId, int limit) {
        QueryWrapper<ChangeEvent> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        wrapper.eq("processed", false);
        wrapper.orderByAsc("event_ts");
        wrapper.last("LIMIT " + limit);
        return mapper.selectList(wrapper);
    }

    @Override
    public void markAsProcessed(String eventId) {
        ChangeEvent event = findById(eventId);
        if (event != null) {
            event.setProcessed(true);
            update(event);
        }
    }
}
