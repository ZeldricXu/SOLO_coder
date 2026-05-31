package com.streamsql.modules.cdc_capture;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.shyiko.mysql.binlog.BinaryLogClient;
import com.github.shyiko.mysql.binlog.event.*;
import com.streamsql.common.PageResult;
import com.streamsql.dto.CdcTaskDTO;
import com.streamsql.entity.CdcCaptureTask;
import com.streamsql.entity.CdcEventRecord;
import com.streamsql.entity.DatasourceInfo;
import com.streamsql.mapper.CdcCaptureTaskMapper;
import com.streamsql.mapper.CdcEventRecordMapper;
import com.streamsql.mapper.DatasourceInfoMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcCaptureService {

    private final CdcCaptureTaskMapper cdcCaptureTaskMapper;
    private final CdcEventRecordMapper cdcEventRecordMapper;
    private final DatasourceInfoMapper datasourceInfoMapper;
    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;

    @Value("${streamsql.cdc.server-id:1001}")
    private long serverId;

    @Value("${streamsql.cdc.batch-size:1000}")
    private int batchSize;

    private final Map<String, BinaryLogClient> activeClients = new ConcurrentHashMap<>();
    private final Map<String, Thread> clientThreads = new ConcurrentHashMap<>();

    @Transactional(rollbackFor = Exception.class)
    public CdcCaptureTask createTask(CdcTaskDTO dto) throws JsonProcessingException {
        CdcCaptureTask task = new CdcCaptureTask();
        task.setTaskName(dto.getTaskName());
        task.setDatasourceId(dto.getDatasourceId());
        task.setSchemaName(dto.getSchemaName());
        task.setTableNames(objectMapper.writeValueAsString(dto.getTableNames()));
        task.setOutputType(dto.getOutputType());
        task.setOutputConfig(objectMapper.writeValueAsString(dto.getOutputConfig()));
        task.setStatus("stopped");

        cdcCaptureTaskMapper.insert(task);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteTask(String taskId) {
        stopTask(taskId);
        cdcCaptureTaskMapper.deleteById(taskId);
    }

    public CdcCaptureTask getTask(String taskId) {
        return cdcCaptureTaskMapper.selectById(taskId);
    }

    public PageResult<CdcCaptureTask> listTasks(int page, int size, String datasourceId, String status) {
        LambdaQueryWrapper<CdcCaptureTask> wrapper = new LambdaQueryWrapper<>();
        if (datasourceId != null) {
            wrapper.eq(CdcCaptureTask::getDatasourceId, datasourceId);
        }
        if (status != null) {
            wrapper.eq(CdcCaptureTask::getStatus, status);
        }
        wrapper.orderByDesc(CdcCaptureTask::getCreatedAt);

        IPage<CdcCaptureTask> pageResult = cdcCaptureTaskMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @Transactional(rollbackFor = Exception.class)
    public CdcCaptureTask startTask(String taskId) throws JsonProcessingException {
        CdcCaptureTask task = cdcCaptureTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        if ("running".equals(task.getStatus())) {
            return task;
        }

        DatasourceInfo datasource = datasourceInfoMapper.selectById(task.getDatasourceId());
        if (datasource == null) {
            throw new IllegalArgumentException("数据源不存在: " + task.getDatasourceId());
        }

        Map<String, Object> connConfig = objectMapper.readValue(datasource.getConnectionConfig(), Map.class);

        BinaryLogClient client = createBinlogClient(task, connConfig);
        activeClients.put(taskId, client);

        Thread thread = new Thread(() -> {
            try {
                client.connect();
            } catch (Exception e) {
                log.error("Binlog client connection failed for task: {}", taskId, e);
                task.setStatus("error");
                cdcCaptureTaskMapper.updateById(task);
            }
        }, "cdc-client-" + taskId);
        thread.start();
        clientThreads.put(taskId, thread);

        task.setStatus("running");
        cdcCaptureTaskMapper.updateById(task);

        log.info("CDC task started: {}", taskId);
        return task;
    }

    @Transactional(rollbackFor = Exception.class)
    public CdcCaptureTask stopTask(String taskId) {
        CdcCaptureTask task = cdcCaptureTaskMapper.selectById(taskId);
        if (task == null) {
            throw new IllegalArgumentException("任务不存在: " + taskId);
        }

        BinaryLogClient client = activeClients.remove(taskId);
        if (client != null) {
            try {
                client.disconnect();
            } catch (IOException e) {
                log.warn("Failed to disconnect binlog client: {}", taskId, e);
            }
        }

        Thread thread = clientThreads.remove(taskId);
        if (thread != null) {
            thread.interrupt();
        }

        task.setStatus("stopped");
        cdcCaptureTaskMapper.updateById(task);

        log.info("CDC task stopped: {}", taskId);
        return task;
    }

    private BinaryLogClient createBinlogClient(CdcCaptureTask task, Map<String, Object> connConfig) throws JsonProcessingException {
        String host = (String) connConfig.getOrDefault("host", "localhost");
        int port = Integer.parseInt(connConfig.getOrDefault("port", 3306).toString());
        String username = (String) connConfig.get("username");
        String password = (String) connConfig.get("password");

        BinaryLogClient client = new BinaryLogClient(host, port, username, password);
        client.setServerId(serverId);
        client.setBlocking(true);

        List<String> tableNames = null;
        if (task.getTableNames() != null) {
            tableNames = objectMapper.readValue(task.getTableNames(), List.class);
        }

        List<String> finalTableNames = tableNames;
        client.registerEventListener(event -> {
            EventData data = event.getData();
            if (data instanceof TableMapEventData) {
                return;
            }

            if (data instanceof WriteRowsEventData writeEvent) {
                handleEvent(task, "INSERT", writeEvent.getTableId(), 
                    writeEvent.getRows(), null, finalTableNames);
            } else if (data instanceof UpdateRowsEventData updateEvent) {
                handleEvent(task, "UPDATE", updateEvent.getTableId(),
                    null, updateEvent.getRows(), finalTableNames);
            } else if (data instanceof DeleteRowsEventData deleteEvent) {
                handleEvent(task, "DELETE", deleteEvent.getTableId(),
                    deleteEvent.getRows(), null, finalTableNames);
            }
        });

        return client;
    }

    @SuppressWarnings("unchecked")
    private void handleEvent(CdcCaptureTask task, String eventType, long tableId,
                             List<Serializable[]> rows, List<Map.Entry<Serializable[], Serializable[]>> updateRows,
                             List<String> tableNames) {
        try {
            String tableName = "table_" + tableId;

            if (tableNames != null && !tableNames.isEmpty()) {
                boolean matched = tableNames.stream().anyMatch(tn -> tableName.contains(tn) || tn.contains(tableName));
                if (!matched) {
                    return;
                }
            }

            if (rows != null) {
                for (Serializable[] row : rows) {
                    saveEvent(task, eventType, tableName, null, Arrays.asList(row));
                }
            }

            if (updateRows != null) {
                for (Map.Entry<Serializable[], Serializable[]> entry : updateRows) {
                    saveEvent(task, eventType, tableName, entry.getKey(), Arrays.asList(entry.getValue()));
                }
            }

        } catch (Exception e) {
            log.error("Failed to handle CDC event", e);
        }
    }

    private void saveEvent(CdcCaptureTask task, String eventType, String tableName,
                            Serializable[] beforeData, List<Serializable[]> afterDataList) throws Exception {
        Map<String, Object> afterData = null;
        Map<String, Object> beforeDataMap = null;

        if (afterDataList != null && !afterDataList.isEmpty()) {
            afterData = convertRowToMap(afterDataList.get(0));
        }

        if (beforeData != null) {
            beforeDataMap = convertRowToMap(beforeData);
        }

        CdcEventRecord record = new CdcEventRecord();
        record.setTaskId(task.getTaskId());
        record.setEventType(eventType);
        record.setSchemaName(task.getSchemaName());
        record.setTableName(tableName);
        record.setPrimaryKeyValue(extractPrimaryKey(afterData, beforeDataMap));
        record.setBeforeData(beforeDataMap != null ? objectMapper.writeValueAsString(beforeDataMap) : null);
        record.setAfterData(afterData != null ? objectMapper.writeValueAsString(afterData) : null);
        record.setEventTime(LocalDateTime.now());
        record.setSerializedData(serializeEvent(record));

        cdcEventRecordMapper.insert(record);

        sendToOutput(task, record);

        task.setLastEventTime(LocalDateTime.now());
        cdcCaptureTaskMapper.updateById(task);
    }

    private Map<String, Object> convertRowToMap(Serializable[] row) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < row.length; i++) {
            map.put("col_" + i, row[i]);
        }
        return map;
    }

    private String extractPrimaryKey(Map<String, Object> afterData, Map<String, Object> beforeData) {
        if (afterData != null && afterData.containsKey("col_0")) {
            return String.valueOf(afterData.get("col_0"));
        }
        if (beforeData != null && beforeData.containsKey("col_0")) {
            return String.valueOf(beforeData.get("col_0"));
        }
        return null;
    }

    private byte[] serializeEvent(CdcEventRecord record) throws IOException {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eventId", record.getEventId());
        event.put("eventType", record.getEventType());
        event.put("schemaName", record.getSchemaName());
        event.put("tableName", record.getTableName());
        event.put("primaryKeyValue", record.getPrimaryKeyValue());
        event.put("beforeData", record.getBeforeData());
        event.put("afterData", record.getAfterData());
        event.put("eventTime", record.getEventTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(event);
        }
        return baos.toByteArray();
    }

    private void sendToOutput(CdcCaptureTask task, CdcEventRecord record) throws JsonProcessingException {
        Map<String, Object> outputConfig = objectMapper.readValue(task.getOutputConfig(), Map.class);

        switch (task.getOutputType().toLowerCase()) {
            case "kafka":
                String topic = (String) outputConfig.get("topic");
                if (topic != null && kafkaTemplate != null) {
                    try {
                        kafkaTemplate.send(topic, record.getSerializedData());
                    } catch (Exception e) {
                        log.warn("Failed to send event to Kafka", e);
                    }
                }
                break;
            case "http":
                String endpoint = (String) outputConfig.get("endpoint");
                log.debug("Would send event to HTTP endpoint: {}", endpoint);
                break;
            default:
                log.debug("Output type not implemented: {}", task.getOutputType());
        }
    }

    public PageResult<CdcEventRecord> getEventRecords(int page, int size, String taskId, String eventType) {
        LambdaQueryWrapper<CdcEventRecord> wrapper = new LambdaQueryWrapper<>();
        if (taskId != null) {
            wrapper.eq(CdcEventRecord::getTaskId, taskId);
        }
        if (eventType != null) {
            wrapper.eq(CdcEventRecord::getEventType, eventType);
        }
        wrapper.orderByDesc(CdcEventRecord::getEventTime);

        IPage<CdcEventRecord> pageResult = cdcEventRecordMapper.selectPage(new Page<>(page, size), wrapper);
        return PageResult.of(pageResult.getRecords(), pageResult.getTotal(), page, size);
    }

    @PreDestroy
    public void cleanup() {
        for (String taskId : new ArrayList<>(activeClients.keySet())) {
            stopTask(taskId);
        }
    }
}
