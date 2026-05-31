package com.metricplatform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.metricplatform.entity.SysCdcConnector;
import com.metricplatform.entity.SysCdcEvent;
import com.metricplatform.mapper.SysCdcConnectorMapper;
import com.metricplatform.mapper.SysCdcEventMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class CdcService extends ServiceImpl<SysCdcConnectorMapper, SysCdcConnector> {

    private final SysCdcEventMapper eventMapper;
    private final ObjectMapper objectMapper;
    private final Cache<String, Object> caffeineCache;

    private final Map<String, CdcRunner> activeConnectors = new ConcurrentHashMap<>();
    private final WebClient webClient = WebClient.create();

    @Data
    public static class BinlogPosition {
        private String fileName;
        private long position;
        private String gtid;

        public BinlogPosition(String fileName, long position) {
            this.fileName = fileName;
            this.position = position;
        }
    }

    private static class CdcRunner implements Runnable {
        private final SysCdcConnector connector;
        private final CdcService cdcService;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private JdbcTemplate jdbcTemplate;
        private BinlogPosition currentPosition;
        private long processedCount = 0;

        public CdcRunner(SysCdcConnector connector, CdcService cdcService) {
            this.connector = connector;
            this.cdcService = cdcService;
        }

        @Override
        public void run() {
            log.info("CDC连接器启动: {}", connector.getConnectorName());
            try {
                jdbcTemplate = cdcService.createJdbcTemplate(connector);
                if (jdbcTemplate == null) {
                    log.error("无法创建CDC连接: {}", connector.getConnectorName());
                    updateStatus("error");
                    return;
                }

                currentPosition = getCurrentBinlogPosition();
                if (connector.getCurrentLsn() != null && !connector.getCurrentLsn().isEmpty()) {
                    try {
                        String[] parts = connector.getCurrentLsn().split(":");
                        if (parts.length >= 2) {
                            currentPosition = new BinlogPosition(parts[0], Long.parseLong(parts[1]));
                            log.info("从指定位置恢复: {}", connector.getCurrentLsn());
                        }
                    } catch (Exception e) {
                        log.warn("解析LSN失败，使用当前位置", e);
                    }
                }

                updateStatus("running");
                connector.setStartedAt(LocalDateTime.now());
                cdcService.updateById(connector);

                simulateBinlogReading();

            } catch (Exception e) {
                log.error("CDC连接器异常: {}", connector.getConnectorName(), e);
                updateStatus("error");
            } finally {
                if (running.get()) {
                    stop();
                }
            }
        }

        private BinlogPosition getCurrentBinlogPosition() {
            try {
                List<Map<String, Object>> results = jdbcTemplate.queryForList("SHOW MASTER STATUS");
                if (!results.isEmpty()) {
                    Map<String, Object> row = results.get(0);
                    String file = (String) row.get("File");
                    Long pos = ((Number) row.get("Position")).longValue();
                    return new BinlogPosition(file, pos);
                }
            } catch (Exception e) {
                log.warn("获取binlog位置失败", e);
            }
            return new BinlogPosition("mysql-bin.000001", 0);
        }

        private void simulateBinlogReading() throws InterruptedException {
            Random random = new Random();
            String[] operations = {"INSERT", "UPDATE", "DELETE"};

            while (running.get()) {
                Thread.sleep(1000 + random.nextInt(2000));

                if (random.nextDouble() < 0.7) {
                    SysCdcEvent event = generateMockEvent(random, operations);
                    cdcService.processEvent(connector, event);
                    processedCount++;
                    currentPosition.setPosition(currentPosition.getPosition() + random.nextInt(100) + 1);

                    connector.setCurrentLsn(currentPosition.getFileName() + ":" + currentPosition.getPosition());
                    connector.setProcessedEvents(processedCount);
                    connector.setLastEventAt(LocalDateTime.now());
                    cdcService.updateById(connector);
                }
            }
        }

        private SysCdcEvent generateMockEvent(Random random, String[] operations) {
            SysCdcEvent event = new SysCdcEvent();
            event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
            event.setConnectorId(connector.getConnectorId());
            event.setOperation(operations[random.nextInt(operations.length)]);
            event.setDatabaseName("test_db");
            event.setTableName(random.nextBoolean() ? "users" : "orders");
            event.setLsn(currentPosition.getFileName() + ":" + currentPosition.getPosition());
            event.setEventTime(LocalDateTime.now());
            event.setProcessedAt(LocalDateTime.now());

            Map<String, Object> data = new HashMap<>();
            data.put("id", random.nextInt(10000));
            data.put("name", "user_" + random.nextInt(1000));
            data.put("status", random.nextBoolean() ? "active" : "inactive");
            data.put("created_at", System.currentTimeMillis());

            if ("UPDATE".equals(event.getOperation())) {
                event.setBeforeData(new HashMap<>(data));
                data.put("status", random.nextBoolean() ? "active" : "inactive");
                event.setAfterData(data);
            } else if ("INSERT".equals(event.getOperation())) {
                event.setAfterData(data);
            } else {
                event.setBeforeData(data);
            }

            Map<String, Object> pk = new HashMap<>();
            pk.put("id", data.get("id"));
            event.setPrimaryKey(pk);

            Map<String, Object> meta = new HashMap<>();
            meta.put("source", connector.getSourceType());
            meta.put("timestamp", System.currentTimeMillis());
            meta.put("thread", Thread.currentThread().getName());
            event.setMetadata(meta);

            try {
                event.setSerializedData(cdcService.objectMapper.writeValueAsString(event));
            } catch (JsonProcessingException e) {
                event.setSerializedData(event.toString());
            }

            return event;
        }

        public void stop() {
            running.set(false);
            updateStatus("stopped");
            connector.setStoppedAt(LocalDateTime.now());
            cdcService.updateById(connector);
            log.info("CDC连接器已停止: {}, 处理事件数: {}", connector.getConnectorName(), processedCount);
        }

        private void updateStatus(String status) {
            connector.setStatus(status);
            cdcService.lambdaUpdate()
                    .eq(SysCdcConnector::getConnectorId, connector.getConnectorId())
                    .set(SysCdcConnector::getStatus, status)
                    .update();
        }

        public BinlogPosition getCurrentPosition() {
            return currentPosition;
        }

        public long getProcessedCount() {
            return processedCount;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public SysCdcConnector createConnector(String name, String sourceType,
                                           Map<String, Object> sourceConfig,
                                           String outputType, Map<String, Object> outputConfig) {
        SysCdcConnector connector = new SysCdcConnector();
        connector.setConnectorId("cdc_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        connector.setConnectorName(name);
        connector.setSourceType(sourceType.toLowerCase());
        connector.setSourceConfig(sourceConfig);
        connector.setOutputType(outputType != null ? outputType.toLowerCase() : "database");
        connector.setOutputConfig(outputConfig);
        connector.setStatus("created");
        connector.setProcessedEvents(0L);

        this.save(connector);
        log.info("已创建CDC连接器: {} (类型: {})", name, sourceType);
        return connector;
    }

    @Async("cdcExecutor")
    public void startConnectorAsync(String connectorId) {
        SysCdcConnector connector = this.getById(connectorId);
        if (connector == null) {
            throw new IllegalArgumentException("连接器不存在: " + connectorId);
        }

        if (activeConnectors.containsKey(connectorId)) {
            log.warn("连接器已在运行: {}", connectorId);
            return;
        }

        CdcRunner runner = new CdcRunner(connector, this);
        activeConnectors.put(connectorId, runner);

        Thread thread = new Thread(runner, "cdc-" + connectorId);
        thread.setDaemon(true);
        thread.start();
    }

    public void stopConnector(String connectorId) {
        CdcRunner runner = activeConnectors.remove(connectorId);
        if (runner != null) {
            runner.stop();
        } else {
            SysCdcConnector connector = this.getById(connectorId);
            if (connector != null) {
                connector.setStatus("stopped");
                connector.setStoppedAt(LocalDateTime.now());
                this.updateById(connector);
            }
        }
    }

    public void processEvent(SysCdcConnector connector, SysCdcEvent event) {
        try {
            eventMapper.insert(event);
            dispatchEvent(connector, event);
            log.debug("CDC事件处理完成: {} {} {}.{}",
                    event.getEventId(), event.getOperation(),
                    event.getDatabaseName(), event.getTableName());
        } catch (Exception e) {
            log.error("处理CDC事件失败: {}", event.getEventId(), e);
        }
    }

    private void dispatchEvent(SysCdcConnector connector, SysCdcEvent event) {
        String outputType = connector.getOutputType();
        Map<String, Object> outputConfig = connector.getOutputConfig();

        if (outputConfig == null) {
            return;
        }

        try {
            switch (outputType) {
                case "webhook":
                    sendToWebhook((String) outputConfig.get("url"), event);
                    break;
                case "kafka":
                    sendToKafka(outputConfig, event);
                    break;
                case "redis":
                    sendToRedis(outputConfig, event);
                    break;
                case "database":
                default:
                    break;
            }
        } catch (Exception e) {
            log.warn("事件分发失败: type={}, event={}", outputType, event.getEventId(), e);
        }
    }

    private void sendToWebhook(String url, SysCdcEvent event) {
        if (url == null || url.isEmpty()) {
            return;
        }
        try {
            webClient.post()
                    .uri(url)
                    .bodyValue(event)
                    .retrieve()
                    .toBodilessEntity()
                    .doOnError(e -> log.warn("Webhook调用失败: {}", url, e))
                    .subscribe();
        } catch (Exception e) {
            log.warn("发送Webhook失败", e);
        }
    }

    private void sendToKafka(Map<String, Object> config, SysCdcEvent event) {
        String topic = (String) config.getOrDefault("topic", "cdc_events");
        log.debug("模拟发送到Kafka: topic={}, event={}", topic, event.getEventId());
    }

    private void sendToRedis(Map<String, Object> config, SysCdcEvent event) {
        String channel = (String) config.getOrDefault("channel", "cdc:events");
        String key = "cdc:event:" + event.getEventId();
        log.debug("模拟发送到Redis: channel={}, key={}", channel, key);
    }

    public List<SysCdcConnector> getAllConnectors() {
        return this.list();
    }

    public SysCdcConnector getConnectorById(String connectorId) {
        return this.getById(connectorId);
    }

    public List<SysCdcEvent> getEvents(String connectorId, String operation,
                                       String database, String table,
                                       LocalDateTime startTime, LocalDateTime endTime,
                                       int limit) {
        LambdaQueryWrapper<SysCdcEvent> wrapper = new LambdaQueryWrapper<>();

        if (connectorId != null && !connectorId.isEmpty()) {
            wrapper.eq(SysCdcEvent::getConnectorId, connectorId);
        }
        if (operation != null && !operation.isEmpty()) {
            wrapper.eq(SysCdcEvent::getOperation, operation.toUpperCase());
        }
        if (database != null && !database.isEmpty()) {
            wrapper.eq(SysCdcEvent::getDatabaseName, database);
        }
        if (table != null && !table.isEmpty()) {
            wrapper.eq(SysCdcEvent::getTableName, table);
        }
        if (startTime != null) {
            wrapper.ge(SysCdcEvent::getEventTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(SysCdcEvent::getEventTime, endTime);
        }

        wrapper.orderByDesc(SysCdcEvent::getEventTime)
                .last("LIMIT " + Math.min(limit, 1000));

        return eventMapper.selectList(wrapper);
    }

    public Map<String, Object> getConnectorStats(String connectorId) {
        SysCdcConnector connector = this.getById(connectorId);
        Map<String, Object> stats = new HashMap<>();

        if (connector != null) {
            stats.put("connectorId", connector.getConnectorId());
            stats.put("connectorName", connector.getConnectorName());
            stats.put("status", connector.getStatus());
            stats.put("sourceType", connector.getSourceType());
            stats.put("outputType", connector.getOutputType());
            stats.put("currentLsn", connector.getCurrentLsn());
            stats.put("processedEvents", connector.getProcessedEvents());
            stats.put("startedAt", connector.getStartedAt());
            stats.put("lastEventAt", connector.getLastEventAt());
        }

        long eventCount = eventMapper.selectCount(new LambdaQueryWrapper<SysCdcEvent>()
                .eq(SysCdcEvent::getConnectorId, connectorId));
        stats.put("totalEvents", eventCount);

        return stats;
    }

    public byte[] serializeEvent(SysCdcEvent event, String format) throws JsonProcessingException {
        return switch (format.toLowerCase()) {
            case "json" -> objectMapper.writeValueAsBytes(event);
            case "avro" -> serializeToAvro(event);
            case "protobuf" -> serializeToProtobuf(event);
            default -> objectMapper.writeValueAsBytes(event);
        };
    }

    private byte[] serializeToAvro(SysCdcEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            ByteBuffer buffer = ByteBuffer.allocate(4 + json.getBytes().length);
            buffer.putInt(json.getBytes().length);
            buffer.put(json.getBytes());
            return buffer.array();
        } catch (Exception e) {
            return event.toString().getBytes();
        }
    }

    private byte[] serializeToProtobuf(SysCdcEvent event) {
        try {
            Map<String, Object> protoMap = new LinkedHashMap<>();
            protoMap.put("eventId", event.getEventId());
            protoMap.put("operation", event.getOperation());
            protoMap.put("databaseName", event.getDatabaseName());
            protoMap.put("tableName", event.getTableName());
            protoMap.put("data", event.getAfterData() != null ? event.getAfterData() : event.getBeforeData());
            protoMap.put("timestamp", event.getEventTime() != null ?
                    event.getEventTime().atZone(ZoneId.systemDefault()).toInstant().getEpochSecond() : 0);

            String json = objectMapper.writeValueAsString(protoMap);
            return json.getBytes();
        } catch (Exception e) {
            return event.toString().getBytes();
        }
    }

    public LocalDateTime parseBinlogTimestamp(String timestamp) {
        try {
            if (timestamp.matches("\\d+")) {
                long ts = Long.parseLong(timestamp);
                return LocalDateTime.ofInstant(Instant.ofEpochSecond(ts), ZoneId.systemDefault());
            }
        } catch (Exception e) {
            log.warn("解析binlog时间戳失败: {}", timestamp);
        }
        return LocalDateTime.now();
    }

    public String formatLsn(String fileName, long position) {
        return fileName + ":" + position;
    }

    public String formatLsn(BigInteger lsn) {
        return "0x" + lsn.toString(16).toUpperCase();
    }

    JdbcTemplate createJdbcTemplate(SysCdcConnector connector) {
        try {
            Map<String, Object> config = connector.getSourceConfig();
            String driverClass = switch (connector.getSourceType().toLowerCase()) {
                case "mysql" -> "com.mysql.cj.jdbc.Driver";
                case "postgresql" -> "org.postgresql.Driver";
                case "oracle" -> "oracle.jdbc.OracleDriver";
                case "sqlserver" -> "com.microsoft.sqlserver.jdbc.SQLServerDriver";
                default -> throw new IllegalArgumentException("不支持的数据源类型: " + connector.getSourceType());
            };

            String host = (String) config.getOrDefault("host", "localhost");
            Integer port = (Integer) config.getOrDefault("port", getDefaultPort(connector.getSourceType()));
            String database = (String) config.getOrDefault("database", "");
            String username = (String) config.get("username");
            String password = (String) config.get("password");

            String url = switch (connector.getSourceType().toLowerCase()) {
                case "mysql" -> String.format(
                        "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=Asia/Shanghai",
                        host, port, database);
                case "postgresql" -> String.format("jdbc:postgresql://%s:%d/%s", host, port, database);
                case "oracle" -> String.format("jdbc:oracle:thin:@%s:%d:%s", host, port,
                        database.isEmpty() ? "ORCL" : database);
                case "sqlserver" -> String.format(
                        "jdbc:sqlserver://%s:%d;databaseName=%s;encrypt=false",
                        host, port, database);
                default -> "";
            };

            DriverManagerDataSource dataSource = new DriverManagerDataSource();
            dataSource.setDriverClassName(driverClass);
            dataSource.setUrl(url);
            dataSource.setUsername(username);
            dataSource.setPassword(password);

            return new JdbcTemplate(dataSource);
        } catch (Exception e) {
            log.error("创建JDBC连接失败", e);
            return null;
        }
    }

    private int getDefaultPort(String sourceType) {
        return switch (sourceType.toLowerCase()) {
            case "mysql" -> 3306;
            case "postgresql" -> 5432;
            case "oracle" -> 1521;
            case "sqlserver" -> 1433;
            default -> 3306;
        };
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteConnector(String connectorId) {
        stopConnector(connectorId);
        eventMapper.delete(new LambdaQueryWrapper<SysCdcEvent>()
                .eq(SysCdcEvent::getConnectorId, connectorId));
        return this.removeById(connectorId);
    }

    public List<Map<String, Object>> getEventStats(String connectorId, LocalDateTime startTime, LocalDateTime endTime) {
        List<Map<String, Object>> stats = new ArrayList<>();

        String[] operations = {"INSERT", "UPDATE", "DELETE"};
        for (String op : operations) {
            long count = eventMapper.selectCount(new LambdaQueryWrapper<SysCdcEvent>()
                    .eq(SysCdcEvent::getConnectorId, connectorId)
                    .eq(SysCdcEvent::getOperation, op)
                    .ge(SysCdcEvent::getEventTime, startTime)
                    .le(SysCdcEvent::getEventTime, endTime));

            Map<String, Object> stat = new HashMap<>();
            stat.put("operation", op);
            stat.put("count", count);
            stats.add(stat);
        }

        return stats;
    }
}
