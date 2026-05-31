package com.orchestration.scheduler.service;

import com.orchestration.scheduler.dto.TaskInstanceVO;
import com.orchestration.scheduler.dto.TaskSubmitRequest;
import java.util.List;
import java.util.Map;

public interface TaskSchedulerService {

    String submitTask(TaskSubmitRequest request);

    String submitTaskWithDependencies(Long taskId, Map<Long, Map<String, Object>> inputDataMap);

    TaskInstanceVO getInstanceStatus(String instanceNo);

    List<TaskInstanceVO> getTaskInstances(Long taskId, Integer page, Integer size);

    boolean cancelTask(String instanceNo);

    boolean retryTask(String instanceNo);

    Map<String, Object> getTaskGraph(Long taskId);
}
