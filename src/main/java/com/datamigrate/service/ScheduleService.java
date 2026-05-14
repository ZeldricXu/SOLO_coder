package com.datamigrate.service;

import com.datamigrate.common.FailStatus;
import com.datamigrate.entity.FailRecord;
import com.datamigrate.entity.MigrateTask;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScheduleService {

    private final TaskService taskService;
    private final MigrateService migrateService;
    private final FailService failService;
    private final LogService logService;

    @Scheduled(fixedRate = 60000)
    public void checkPendingTasks() {
        try {
            List<MigrateTask> pendingTasks = taskService.getPendingTasks();
            for (MigrateTask task : pendingTasks) {
                if (!migrateService.isTaskRunning(task.getTaskId())) {
                    logService.logSchedule(task.getTaskId(), "调度执行待处理任务");
                    migrateService.startMigrate(task.getTaskId());
                }
            }
        } catch (Exception e) {
            log.error("调度任务检查异常", e);
        }
    }

    @Scheduled(fixedRate = 30000)
    public void processRetryRecords() {
        try {
            List<FailRecord> pendingRetry = failService.getPendingRetryRecords();
            for (FailRecord record : pendingRetry) {
                log.info("处理重试记录: taskId={}, key={}", record.getTaskId(), record.getRecordKey());
                logService.logRetry(record.getTaskId(), "处理重试记录: key=" + record.getRecordKey());
            }
        } catch (Exception e) {
            log.error("重试处理异常", e);
        }
    }
}
