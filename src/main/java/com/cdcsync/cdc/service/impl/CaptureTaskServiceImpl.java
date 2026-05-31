package com.cdcsync.cdc.service.impl;

import com.cdcsync.common.service.AbstractBaseService;
import com.cdcsync.cdc.domain.CaptureTask;
import com.cdcsync.cdc.mapper.CaptureTaskMapper;
import com.cdcsync.cdc.service.CaptureTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
public class CaptureTaskServiceImpl extends AbstractBaseService<CaptureTask, String, CaptureTaskMapper>
        implements CaptureTaskService {

    public CaptureTaskServiceImpl(CaptureTaskMapper mapper) {
        super(mapper);
    }

    @Override
    protected void setId(CaptureTask entity, String id) {
    }

    @Override
    protected String getId(CaptureTask entity) {
        return entity.getId();
    }

    @Override
    public void start(String taskId) {
        CaptureTask task = findById(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }
        task.setStatus("RUNNING");
        task.setLastCaptureAt(LocalDateTime.now());
        update(task);
        log.info("Started capture task: {}", taskId);
    }

    @Override
    public void stop(String taskId) {
        CaptureTask task = findById(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }
        task.setStatus("STOPPED");
        update(task);
        log.info("Stopped capture task: {}", taskId);
    }

    @Override
    public void pause(String taskId) {
        CaptureTask task = findById(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }
        task.setStatus("PAUSED");
        update(task);
        log.info("Paused capture task: {}", taskId);
    }

    @Override
    public void resume(String taskId) {
        CaptureTask task = findById(taskId);
        if (task == null) {
            throw new RuntimeException("Task not found: " + taskId);
        }
        task.setStatus("RUNNING");
        update(task);
        log.info("Resumed capture task: {}", taskId);
    }

    @Override
    public String getStatus(String taskId) {
        CaptureTask task = findById(taskId);
        return task != null ? task.getStatus() : "UNKNOWN";
    }
}
