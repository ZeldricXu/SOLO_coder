package com.meteorology.nwp.storage;

import com.meteorology.nwp.common.NWPConfig;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public class KafkaTaskCoordinator implements Serializable {
    private static final Logger logger = LoggerFactory.getLogger(KafkaTaskCoordinator.class);
    private final NWPConfig config;
    private final String bootstrapServers;
    private final String clientId;
    private final String topicTasks, topicResults, topicStatus;
    private final int pollTimeoutMs;
    private transient Producer<String, String> producer;
    private transient Consumer<String, String> consumer;
    private transient final ExecutorService executor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong msgsSent = new AtomicLong(0);
    private final AtomicLong msgsRecv = new AtomicLong(0);

    public interface TaskHandler {
        Map<String, Object> handleTask(String taskId, Map<String, Object> taskPayload);
    }

    public interface ResultHandler {
        void onResult(String taskId, Map<String, Object> resultPayload);
    }

    public KafkaTaskCoordinator(NWPConfig config) {
        this.config = config;
        this.bootstrapServers = config.getString("nwp.kafka.bootstrap", "localhost:9092");
        this.clientId = "nwp-solver-" + UUID.randomUUID().toString().substring(0, 8);
        this.topicTasks = config.getString("nwp.kafka.topicTasks", "nwp_tasks");
        this.topicResults = config.getString("nwp.kafka.topicResults", "nwp_results");
        this.topicStatus = config.getString("nwp.kafka.topicStatus", "nwp_status");
        this.pollTimeoutMs = config.getInt("nwp.kafka.pollTimeoutMs", 500);
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "kafka-worker");
            t.setDaemon(true);
            return t;
        });
        logger.info("Kafka协调器初始化: bootstrap={} tasks={} results={}",
                bootstrapServers, topicTasks, topicResults);
        initProducer();
    }

    private void initProducer() {
        try {
            Properties p = new Properties();
            p.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            p.put(ProducerConfig.CLIENT_ID_CONFIG, clientId + "-prod");
            p.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            p.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            p.put(ProducerConfig.ACKS_CONFIG, "1");
            p.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
            p.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
            p.put(ProducerConfig.LINGER_MS_CONFIG, 10);
            p.put(ProducerConfig.RETRIES_CONFIG, 3);
            producer = new KafkaProducer<>(p);
            logger.info("Kafka生产者就绪");
        } catch (Exception e) {
            logger.warn("Kafka生产者初始化失败（降级为本地模式）: {}", e.getMessage());
        }
    }

    private void initConsumer(String groupId) {
        try {
            Properties p = new Properties();
            p.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            p.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
            p.put(ConsumerConfig.CLIENT_ID_CONFIG, clientId + "-cons");
            p.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            p.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
            p.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
            p.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
            p.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
            consumer = new KafkaConsumer<>(p);
            logger.info("Kafka消费者就绪: group={}", groupId);
        } catch (Exception e) {
            logger.warn("Kafka消费者初始化失败: {}", e.getMessage());
        }
    }

    public String submitForecastTask(Instant initTime, int forecastHours,
                                      String domain, String modelVersion) {
        String taskId = "NWP-" + initTime.getEpochSecond() + "-" + forecastHours + "h-"
                + UUID.randomUUID().toString().substring(0, 6);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("type", "FORECAST");
        payload.put("initTime", initTime.toString());
        payload.put("forecastHours", forecastHours);
        payload.put("domain", domain);
        payload.put("modelVersion", modelVersion);
        payload.put("nx", config.getNX());
        payload.put("ny", config.getNY());
        payload.put("nz", config.getNZ());
        payload.put("createdAt", Instant.now().toString());
        send(topicTasks, taskId, payload);
        logger.info("提交预报任务: {}", taskId);
        return taskId;
    }

    public void submitAssimilationTask(Instant analysisTime, int windowHours, String obsSet) {
        String taskId = "3DVAR-" + analysisTime.getEpochSecond();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("taskId", taskId);
        p.put("type", "ASSIMILATION");
        p.put("analysisTime", analysisTime.toString());
        p.put("windowHours", windowHours);
        p.put("observationSet", obsSet);
        send(topicTasks, taskId, p);
    }

    public void sendTaskResult(String taskId, String status, Map<String, Object> result) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("taskId", taskId);
        payload.put("status", status);
        payload.put("timestamp", Instant.now().toString());
        if (result != null) payload.putAll(result);
        send(topicResults, taskId, payload);
    }

    public void updateStatus(String runId, int currentStep, int totalSteps,
                              Map<String, Object> stats) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("runId", runId);
        p.put("type", "STATUS");
        p.put("step", currentStep);
        p.put("totalSteps", totalSteps);
        p.put("progressPct", 100.0 * currentStep / Math.max(1, totalSteps));
        if (stats != null) p.put("stats", stats);
        p.put("timestamp", Instant.now().toString());
        send(topicStatus, runId, p);
    }

    private void send(String topic, String key, Map<String, Object> payload) {
        if (producer == null) return;
        String json = mapToJson(payload);
        try {
            producer.send(new ProducerRecord<>(topic, key, json), (md, ex) -> {
                if (ex != null) {
                    logger.warn("发送Kafka消息失败 {}:{} : {}", topic, key, ex.getMessage());
                } else {
                    msgsSent.incrementAndGet();
                }
            });
        } catch (Exception e) {
            logger.warn("发送Kafka异常: {}", e.getMessage());
        }
    }

    public Future<Void> startTaskConsumer(String groupId, TaskHandler handler) {
        if (consumer == null) initConsumer(groupId);
        if (consumer == null) {
            logger.warn("Kafka消费者不可用，无法启动任务监听");
            return CompletableFuture.completedFuture(null);
        }
        running.set(true);
        consumer.subscribe(Collections.singletonList(topicTasks));
        return executor.submit(() -> {
            try {
                while (running.get()) {
                    ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                    if (recs.isEmpty()) continue;
                    for (ConsumerRecord<String, String> rec : recs) {
                        handleIncoming(rec, handler);
                    }
                    consumer.commitAsync();
                }
            } catch (Exception e) {
                if (running.get()) logger.error("任务消费者异常: {}", e.getMessage());
            } finally {
                try { consumer.close(); } catch (Exception ignored) {}
            }
            return null;
        });
    }

    public Future<Void> startResultConsumer(String groupId, ResultHandler handler) {
        if (consumer == null) initConsumer(groupId + "-result");
        if (consumer == null) return CompletableFuture.completedFuture(null);
        running.set(true);
        consumer.subscribe(Collections.singletonList(topicResults));
        return executor.submit(() -> {
            try {
                while (running.get()) {
                    ConsumerRecords<String, String> recs = consumer.poll(Duration.ofMillis(pollTimeoutMs));
                    for (ConsumerRecord<String, String> rec : recs) {
                        Map<String, Object> payload = parseJson(rec.value());
                        String taskId = (String) payload.getOrDefault("taskId", rec.key());
                        handler.onResult(taskId, payload);
                        msgsRecv.incrementAndGet();
                    }
                }
            } catch (Exception e) {
                if (running.get()) logger.error("结果消费者异常: {}", e.getMessage());
            }
            return null;
        });
    }

    private void handleIncoming(ConsumerRecord<String, String> rec, TaskHandler handler) {
        msgsRecv.incrementAndGet();
        Map<String, Object> payload = parseJson(rec.value());
        String taskId = (String) payload.getOrDefault("taskId", rec.key());
        logger.info("收到任务: {} @ t={}", taskId, rec.timestamp());
        try {
            Map<String, Object> result = handler.handleTask(taskId, payload);
            sendTaskResult(taskId, "COMPLETED", result);
        } catch (Exception e) {
            logger.error("任务处理异常 {}: {}", taskId, e.getMessage());
            Map<String, Object> fail = new HashMap<>();
            fail.put("error", e.toString());
            sendTaskResult(taskId, "FAILED", fail);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        try {
            return (Map<String, Object>) minimalParse(json);
        } catch (Exception e) {
            Map<String, Object> m = new HashMap<>();
            m.put("raw", json);
            return m;
        }
    }

    private Object minimalParse(String json) {
        json = json.trim();
        if (json.startsWith("{")) return parseObj(json, new int[] {0});
        if (json.startsWith("[")) return parseArr(json, new int[] {0});
        if (json.startsWith("\"")) return parseStr(json, new int[] {0});
        if (json.startsWith("t") || json.startsWith("f")) return json.startsWith("t");
        if (json.startsWith("n")) return null;
        try { return Long.parseLong(json); } catch (Exception e) {
            try { return Double.parseDouble(json); } catch (Exception e2) {
                return json;
            }
        }
    }

    private Map<String, Object> parseObj(String s, int[] pos) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos[0]++;
        while (pos[0] < s.length() && s.charAt(pos[0]) != '}') {
            skipWs(s, pos);
            if (s.charAt(pos[0]) == '}') break;
            String key = parseStr(s, pos);
            skipWs(s, pos); pos[0]++; skipWs(s, pos);
            Object val = parseAny(s, pos);
            if (key != null) map.put(key, val);
            skipWs(s, pos);
            if (s.charAt(pos[0]) == ',') pos[0]++;
        }
        pos[0]++;
        return map;
    }

    private List<Object> parseArr(String s, int[] pos) {
        List<Object> list = new ArrayList<>();
        pos[0]++;
        while (pos[0] < s.length() && s.charAt(pos[0]) != ']') {
            skipWs(s, pos);
            list.add(parseAny(s, pos));
            skipWs(s, pos);
            if (s.charAt(pos[0]) == ',') pos[0]++;
        }
        pos[0]++;
        return list;
    }

    private String parseStr(String s, int[] pos) {
        pos[0]++;
        StringBuilder sb = new StringBuilder();
        while (pos[0] < s.length() && s.charAt(pos[0]) != '"') {
            char c = s.charAt(pos[0]);
            if (c == '\\' && pos[0] + 1 < s.length()) {
                pos[0]++;
                c = s.charAt(pos[0]);
                switch (c) {
                    case 'n': c = '\n'; break; case 't': c = '\t'; break;
                    case 'r': c = '\r'; break; case '"': case '\\': break;
                    default: sb.append('\\');
                }
            }
            sb.append(c); pos[0]++;
        }
        pos[0]++;
        return sb.toString();
    }

    private Object parseAny(String s, int[] pos) {
        skipWs(s, pos);
        char c = s.charAt(pos[0]);
        if (c == '{') return parseObj(s, pos);
        if (c == '[') return parseArr(s, pos);
        if (c == '"') return parseStr(s, pos);
        int start = pos[0];
        while (pos[0] < s.length() && ",}] \t\n\r".indexOf(s.charAt(pos[0])) < 0) pos[0];
        String tok = s.substring(start, pos[0]);
        if (tok.equals("true")) return true;
        if (tok.equals("false")) return false;
        if (tok.equals("null")) return null;
        try { return Long.parseLong(tok); } catch (Exception e) {
            try { return Double.parseDouble(tok); } catch (Exception e2) {
                return tok;
            }
        }
    }

    private void skipWs(String s, int[] pos) {
        while (pos[0] < s.length() && Character.isWhitespace(s.charAt(pos[0]))) pos[0]++;
    }

    private String mapToJson(Map<String, Object> map) {
        StringBuilder sb = new StringBuilder();
        writeObj(sb, map);
        return sb.toString();
    }

    private void writeObj(StringBuilder sb, Map<String, Object> map) {
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : map.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            writeStr(sb, e.getKey()); sb.append(':');
            writeVal(sb, e.getValue());
        }
        sb.append('}');
    }

    private void writeVal(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof Map) writeObj(sb, (Map<String, Object>) v);
        else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                writeVal(sb, o);
            }
            sb.append(']');
        } else if (v instanceof Number) {
            if (v instanceof Float || v instanceof Double) {
                double d = ((Number) v).doubleValue();
                if (Double.isNaN(d)) sb.append("null");
                else if (d == Math.floor(d) && Math.abs(d) < 1e16) sb.append(String.format("%.1f", d));
                else sb.append(v.toString());
            } else sb.append(v.toString());
        } else if (v instanceof Boolean) sb.append(v);
        else writeStr(sb, v.toString());
    }

    private void writeStr(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    public long getMessagesSent() { return msgsSent.get(); }
    public long getMessagesReceived() { return msgsRecv.get(); }

    public void shutdown() {
        running.set(false);
        executor.shutdown();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        if (producer != null) { try { producer.flush(); producer.close(); } catch (Exception ignored) {} }
        if (consumer != null) { try { consumer.close(); } catch (Exception ignored) {} }
        logger.info("Kafka协调器关闭: sent={} recv={}", msgsSent.get(), msgsRecv.get());
    }
}
