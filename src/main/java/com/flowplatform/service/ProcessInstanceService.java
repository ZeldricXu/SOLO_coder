package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.ProcessInstance;
import com.flowplatform.entity.ProcessTask;
import java.util.List;
import java.util.Map;

public interface ProcessInstanceService extends IService<ProcessInstance> {
    ProcessInstance startProcess(Long processId, Long initiatorId, String title, String formData);
    boolean approveTask(Long taskId, Long userId, String comment);
    boolean rejectTask(Long taskId, Long userId, String comment);
    boolean returnTask(Long taskId, Long userId, String comment);
    boolean transferTask(Long taskId, Long fromUserId, Long toUserId, String comment);
    boolean addSignTask(Long taskId, Long userId, String comment);
    List<ProcessTask> getPendingTasks(Long userId);
    List<ProcessTask> getCompletedTasks(Long userId);
    List<ProcessInstance> getMyInstances(Long userId);
    boolean urgeInstance(Long instanceId, Long userId);
    List<Map<String, Object>> getStatusStats();
    List<Map<String, Object>> getDateTrend();
    Map<String, Object> getAvgApprovalTime();
    List<Map<String, Object>> getNodeAvgTime();
    List<Map<String, Object>> getFormRanking();
}
