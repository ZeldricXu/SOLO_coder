package com.tsdbproxy.cdc.parser;

import cn.hutool.json.JSONUtil;
import com.tsdbproxy.common.entity.CdcEvent;
import com.tsdbproxy.common.entity.CdcTask;
import com.tsdbproxy.common.mapper.CdcEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class BinlogParser {

    private final CdcEventMapper cdcEventMapper;
    private final Map<Long, ScheduledExecutorService> executorMap = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> runningMap = new ConcurrentHashMap<>();

    public void startCdc(CdcTask task, String host, int port, String username, String password) {
        stopCdc(task.getId());

        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        runningMap.put(task.getId(), true);

        executor.scheduleAtFixedRate(() -> {
            if (Boolean.TRUE.equals(runningMap.get(task.getId()))) {
                simulateBinlogEvent(task);
            }
        }, 0, 5, TimeUnit.SECONDS);

        executorMap.put(task.getId(), executor);

        log.info("CDC任务启动: taskId={}, host={}:{}", task.getId(), host, port);
    }

    private void simulateBinlogEvent(CdcTask task) {
        try {
            String eventType = Math.random() > 0.5 ? "INSERT" : "UPDATE";
            Map<String, Object> data = new HashMap<>();
            data.put("id", System.currentTimeMillis());
            data.put("name", "test_" + System.currentTimeMillis());
            data.put("value", Math.random() * 100);
            data.put("created_at", LocalDateTime.now().toString());

            CdcEvent event = new CdcEvent();
            event.setTaskId(task.getId());
            event.setEventType(eventType);
            event.setDatabase(task.getDatasourceId().toString());
            event.setTableName(task.getTableName());

            if ("UPDATE".equals(eventType)) {
                Map<String, Object> before = new HashMap<>(data);
                before.put("value", Math.random() * 100);
                event.setBeforeData(JSONUtil.toJsonStr(before));
            }

            event.setAfterData(JSONUtil.toJsonStr(data));
            event.setBinlogPosition("mysql-bin.000001:" + System.currentTimeMillis());
            event.setEventTime(LocalDateTime.now());
            cdcEventMapper.insert(event);

            log.info("模拟CDC事件: taskId={}, type={}, table={}", task.getId(), eventType, task.getTableName());
        } catch (Exception e) {
            log.error("处理CDC事件失败", e);
        }
    }

    public void stopCdc(Long taskId) {
        runningMap.remove(taskId);
        ScheduledExecutorService executor = executorMap.remove(taskId);
        if (executor != null) {
            executor.shutdownNow();
        }
        log.info("CDC任务停止: taskId={}", taskId);
    }

    public boolean isRunning(Long taskId) {
        return Boolean.TRUE.equals(runningMap.get(taskId));
    }
}
