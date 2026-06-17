package com.enterprise.risk.storage.redis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RDeque;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class SequenceStateService {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    private static final String SEQUENCE_KEY_PREFIX = "risk:sequence:";

    /**
     * 添加事件到序列
     *
     * @param ruleId       规则ID
     * @param entityKey    实体键（entityType:entityId）
     * @param eventType    事件类型
     * @param eventId      事件ID
     * @param attributes   事件属性
     * @param timestamp    事件时间戳
     * @param windowSizeMs 时间窗口大小
     * @param maxSize      序列最大长度
     */
    public void addEvent(String ruleId, String entityKey, String eventType,
                         String eventId, Map<String, Object> attributes,
                         long timestamp, long windowSizeMs, int maxSize) {
        String key = buildSequenceKey(ruleId, entityKey);
        RDeque<String> deque = redissonClient.getDeque(key);

        Map<String, Object> eventData = new HashMap<>();
        eventData.put("eventId", eventId);
        eventData.put("eventType", eventType);
        eventData.put("timestamp", timestamp);
        eventData.put("attributes", attributes != null ? attributes : new HashMap<>());

        try {
            String json = objectMapper.writeValueAsString(eventData);
            deque.addLast(json);
            while (deque.size() > maxSize) {
                deque.removeFirst();
            }
            cleanupExpired(deque, timestamp - windowSizeMs);
            deque.expire(windowSizeMs * 2, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.error("序列化序列事件失败: ruleId={}, eventId={}", ruleId, eventId, e);
        }
    }

    /**
     * 获取当前序列
     *
     * @param ruleId       规则ID
     * @param entityKey    实体键
     * @param windowSizeMs 时间窗口大小
     * @return 序列事件列表
     */
    public List<Map<String, Object>> getSequence(String ruleId, String entityKey, long windowSizeMs) {
        String key = buildSequenceKey(ruleId, entityKey);
        RDeque<String> deque = redissonClient.getDeque(key);
        long now = Instant.now().toEpochMilli();
        cleanupExpired(deque, now - windowSizeMs);

        List<Map<String, Object>> result = new ArrayList<>();
        for (String json : deque.readAll()) {
            try {
                Map<String, Object> event = objectMapper.readValue(json, new TypeReference<>() {});
                result.add(event);
            } catch (Exception e) {
                log.warn("反序列化序列事件失败: {}", json, e);
            }
        }
        return result;
    }

    /**
     * 检查序列是否匹配指定模式（如A->B->C）
     *
     * @param ruleId       规则ID
     * @param entityKey    实体键
     * @param pattern      模式列表，按顺序的事件类型
     * @param conditions   每一步的条件映射（stepName -> 条件表达式，这里简化为属性匹配）
     * @param windowSizeMs 时间窗口大小
     * @return 是否匹配成功，返回匹配到的事件序列（空列表表示未匹配）
     */
    public List<Map<String, Object>> matchSequencePattern(
            String ruleId, String entityKey,
            List<String> pattern,
            Map<String, Map<String, Object>> conditions,
            long windowSizeMs) {
        List<Map<String, Object>> sequence = getSequence(ruleId, entityKey, windowSizeMs);
        if (sequence.size() < pattern.size()) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> matched = new ArrayList<>();
        int patternIndex = 0;

        for (Map<String, Object> event : sequence) {
            String eventType = (String) event.get("eventType");

            if (pattern.get(patternIndex).equals(eventType)) {
                if (conditions == null || matchConditions(event, conditions.get(String.valueOf(patternIndex)))) {
                    matched.add(event);
                    patternIndex++;
                    if (patternIndex == pattern.size()) {
                        return matched;
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    /**
     * 使用正则表达式匹配事件序列
     * 支持简单的正则模式：A+ (一个或多个A), A* (零个或多个A), A? (零个或一个A)
     *
     * @param ruleId       规则ID
     * @param entityKey    实体键
     * @param regexPattern 正则模式（如 "A.B*C"）
     * @param windowSizeMs 时间窗口大小
     * @return 是否匹配
     */
    public boolean matchRegexPattern(String ruleId, String entityKey, String regexPattern, long windowSizeMs) {
        List<Map<String, Object>> sequence = getSequence(ruleId, entityKey, windowSizeMs);
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> event : sequence) {
            sb.append(event.get("eventType"));
        }
        Pattern pattern = Pattern.compile(regexPattern);
        Matcher matcher = pattern.matcher(sb.toString());
        return matcher.find();
    }

    /**
     * 清除序列状态
     *
     * @param ruleId    规则ID
     * @param entityKey 实体键
     */
    public void clearSequence(String ruleId, String entityKey) {
        String key = buildSequenceKey(ruleId, entityKey);
        redissonClient.getDeque(key).delete();
    }

    /**
     * 获取序列长度
     *
     * @param ruleId    规则ID
     * @param entityKey 实体键
     * @return 序列长度
     */
    public int getSequenceSize(String ruleId, String entityKey) {
        String key = buildSequenceKey(ruleId, entityKey);
        return redissonClient.getDeque(key).size();
    }

    /**
     * 清理过期事件
     */
    private void cleanupExpired(RDeque<String> deque, long expireBefore) {
        while (!deque.isEmpty()) {
            String first = deque.peekFirst();
            if (first == null) break;
            try {
                Map<String, Object> event = objectMapper.readValue(first, new TypeReference<>() {});
                long ts = ((Number) event.get("timestamp")).longValue();
                if (ts < expireBefore) {
                    deque.removeFirst();
                } else {
                    break;
                }
            } catch (Exception e) {
                deque.removeFirst();
            }
        }
    }

    /**
     * 简单条件匹配（属性完全相等）
     */
    @SuppressWarnings("unchecked")
    private boolean matchConditions(Map<String, Object> event, Map<String, Object> expectedConditions) {
        if (expectedConditions == null || expectedConditions.isEmpty()) {
            return true;
        }
        Map<String, Object> attributes = (Map<String, Object>) event.get("attributes");
        if (attributes == null) {
            return false;
        }
        for (Map.Entry<String, Object> entry : expectedConditions.entrySet()) {
            Object actual = attributes.get(entry.getKey());
            Object expected = entry.getValue();
            if (!Objects.equals(actual, expected)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 构建序列Redis键
     */
    private String buildSequenceKey(String ruleId, String entityKey) {
        return SEQUENCE_KEY_PREFIX + ruleId + ":" + entityKey;
    }
}
