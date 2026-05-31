package com.cdcsync.cdc.service;

import com.cdcsync.common.service.BaseService;
import com.cdcsync.cdc.domain.CaptureTask;

public interface CaptureTaskService extends BaseService<CaptureTask, String> {

    void start(String taskId);

    void stop(String taskId);

    void pause(String taskId);

    void resume(String taskId);

    String getStatus(String taskId);
}
