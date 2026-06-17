package com.enterprise.risk.orchestration.action;

import com.enterprise.risk.common.alert.AlertEvent;
import com.enterprise.risk.orchestration.core.ActionContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RSet;
import org.redisson.api.RedissonClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 拉黑IP动作
 * 将IP写入Redis黑名单（可设置TTL），并通过Kafka通知下游防火墙系统
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BlockIpAction implements Action {

    private static final String ACTION_ID = "block_ip";
    private static final String ACTION_NAME = "拉黑IP动作";

    /**
     * IP黑名单Redis键
     */
    private static final String IP_BLACKLIST_KEY = "risk:ip_blacklist";

    /**
     * IP黑名单带TTL的键前缀
     */
    private static final String IP_BLOCK_KEY_PREFIX = "risk:blocked_ip:";

    /**
     * 防火墙通知Topic
     */
    private static final String FIREWALL_NOTIFY_TOPIC = "risk.firewall.block";

    /**
     * 默认封禁时长（秒），-1表示永久
     */
    private static final long DEFAULT_TTL_SECONDS = -1;

    private final RedissonClient redissonClient;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @Override
    public String getActionId() {
        return ACTION_ID;
    }

    @Override
    public String getActionName() {
        return ACTION_NAME;
    }

    @Override
    public boolean execute(AlertEvent alertEvent, ActionContext context) {
        String ip = extractIp(alertEvent);
        if (ip == null || ip.isEmpty()) {
            log.warn("[BlockIpAction] IP地址为空，跳过拉黑，alertId={}", alertEvent.getAlertId());
            return false;
        }

        Long ttlSeconds = context.getParameterOrDefault("ttl_seconds", DEFAULT_TTL_SECONDS);
        String reason = context.getParameterOrDefault("reason", alertEvent.getDescription());

        try {
            addToBlacklist(ip, ttlSeconds);
            notifyFirewall(ip, ttlSeconds, reason, alertEvent);
            log.info("[BlockIpAction] IP拉黑成功, ip={}, ttl={}s, alertId={}", ip, ttlSeconds, alertEvent.getAlertId());
            context.saveResult(ACTION_ID, true);
            return true;
        } catch (Exception e) {
            log.error("[BlockIpAction] IP拉黑失败, ip={}, alertId={}", ip, alertEvent.getAlertId(), e);
            context.saveResult(ACTION_ID, false);
            return false;
        }
    }

    /**
     * 从告警事件中提取IP
     */
    private String extractIp(AlertEvent alertEvent) {
        Map<String, Object> metadata = alertEvent.getMetadata();
        if (metadata != null && metadata.containsKey("ip")) {
            return (String) metadata.get("ip");
        }
        return null;
    }

    /**
     * 将IP加入黑名单
     */
    private void addToBlacklist(String ip, long ttlSeconds) {
        RSet<String> blacklist = redissonClient.getSet(IP_BLACKLIST_KEY);
        blacklist.add(ip);

        if (ttlSeconds > 0) {
            String ttlKey = IP_BLOCK_KEY_PREFIX + ip;
            redissonClient.getBucket(ttlKey).set(System.currentTimeMillis() + ttlSeconds * 1000, ttlSeconds, TimeUnit.SECONDS);
        }
    }

    /**
     * 通知下游防火墙系统
     */
    private void notifyFirewall(String ip, long ttlSeconds, String reason, AlertEvent alertEvent) {
        Map<String, Object> message = new HashMap<>();
        message.put("ip", ip);
        message.put("ttl_seconds", ttlSeconds);
        message.put("reason", reason);
        message.put("alert_id", alertEvent.getAlertId());
        message.put("rule_id", alertEvent.getRuleId());
        message.put("severity", alertEvent.getSeverity());
        message.put("blocked_at", System.currentTimeMillis());

        kafkaTemplate.send(FIREWALL_NOTIFY_TOPIC, ip, message);
    }
}
