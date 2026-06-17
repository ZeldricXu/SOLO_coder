package com.enterprise.risk.gateway.validator;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Schema注册中心
 * 管理各业务线事件的必填字段列表配置
 * 支持：
 * 1. 默认内置Schema配置
 * 2. 通过配置文件自定义覆盖
 * 3. 运行时动态注册/更新Schema
 */
@Slf4j
@Configuration
@ConfigurationProperties(prefix = "risk.gateway.schema")
public class EventSchemaRegistry {

    /**
     * 各业务线必填字段配置
     * key: 业务线编码（如payment、login等）
     * value: 必填字段名列表
     */
    private Map<String, List<String>> businessLineRequiredFields = new ConcurrentHashMap<>();

    /**
     * 全局通用必填字段（所有业务线都需要）
     */
    private List<String> globalRequiredFields = new ArrayList<>();

    /**
     * 初始化默认Schema配置
     * 内置各业务线的推荐必填字段
     */
    @PostConstruct
    public void initDefaultSchemas() {
        log.info("初始化事件Schema注册中心...");

        registerDefaultSchema("payment", Arrays.asList(
                "source", "ip", "user_id", "session_id",
                "amount", "currency", "payment_method", "order_id"
        ));

        registerDefaultSchema("login", Arrays.asList(
                "source", "ip", "user_id", "session_id",
                "device_id", "login_type", "client_type"
        ));

        registerDefaultSchema("transaction", Arrays.asList(
                "source", "ip", "user_id", "session_id",
                "amount", "currency", "from_account", "to_account", "transaction_type"
        ));

        registerDefaultSchema("marketing", Arrays.asList(
                "source", "ip", "user_id", "session_id",
                "campaign_id", "activity_type", "channel"
        ));

        registerDefaultSchema("register", Arrays.asList(
                "source", "ip", "session_id",
                "register_channel", "device_id", "invite_code"
        ));

        registerDefaultSchema("withdraw", Arrays.asList(
                "source", "ip", "user_id", "session_id",
                "amount", "currency", "target_account", "withdraw_channel"
        ));

        registerDefaultSchema("deposit", Arrays.asList(
                "source", "ip", "user_id", "session_id",
                "amount", "currency", "source_account", "deposit_channel"
        ));

        log.info("事件Schema注册中心初始化完成, 注册业务线条数: {}", businessLineRequiredFields.size());
    }

    /**
     * 注册默认Schema（不覆盖已存在的配置）
     */
    private void registerDefaultSchema(String businessLine, List<String> requiredFields) {
        if (!businessLineRequiredFields.containsKey(businessLine)) {
            businessLineRequiredFields.put(businessLine, new ArrayList<>(requiredFields));
            log.debug("注册默认Schema - 业务线: {}, 必填字段数: {}", businessLine, requiredFields.size());
        }
    }

    /**
     * 获取指定业务线的必填字段列表
     * 包含全局必填字段 + 业务线特有必填字段
     *
     * @param businessLine 业务线编码
     * @return 合并后的必填字段列表
     */
    public List<String> getRequiredFields(String businessLine) {
        List<String> result = new ArrayList<>(globalRequiredFields);

        if (businessLine != null && businessLineRequiredFields.containsKey(businessLine)) {
            result.addAll(businessLineRequiredFields.get(businessLine));
        }

        return result;
    }

    /**
     * 运行时动态注册或更新业务线Schema
     *
     * @param businessLine   业务线编码
     * @param requiredFields 必填字段列表
     */
    public void registerSchema(String businessLine, List<String> requiredFields) {
        if (businessLine == null || requiredFields == null) {
            throw new IllegalArgumentException("业务线编码和必填字段列表不能为空");
        }

        businessLineRequiredFields.put(businessLine, new ArrayList<>(requiredFields));
        log.info("动态注册Schema - 业务线: {}, 必填字段: {}", businessLine, requiredFields);
    }

    /**
     * 移除指定业务线的Schema配置
     *
     * @param businessLine 业务线编码
     */
    public void removeSchema(String businessLine) {
        businessLineRequiredFields.remove(businessLine);
        log.info("移除Schema配置 - 业务线: {}", businessLine);
    }

    /**
     * 检查指定业务线是否已配置Schema
     *
     * @param businessLine 业务线编码
     * @return 是否已配置
     */
    public boolean hasSchema(String businessLine) {
        return businessLine != null && businessLineRequiredFields.containsKey(businessLine);
    }

    /**
     * 获取所有已注册的业务线列表
     *
     * @return 业务线编码集合
     */
    public Set<String> getAllRegisteredBusinessLines() {
        return Collections.unmodifiableSet(businessLineRequiredFields.keySet());
    }

    /**
     * 添加全局必填字段
     *
     * @param field 字段名
     */
    public void addGlobalRequiredField(String field) {
        if (field != null && !globalRequiredFields.contains(field)) {
            globalRequiredFields.add(field);
            log.info("添加全局必填字段: {}", field);
        }
    }

    /**
     * 移除全局必填字段
     *
     * @param field 字段名
     */
    public void removeGlobalRequiredField(String field) {
        globalRequiredFields.remove(field);
        log.info("移除全局必填字段: {}", field);
    }

    public Map<String, List<String>> getBusinessLineRequiredFields() {
        return businessLineRequiredFields;
    }

    public void setBusinessLineRequiredFields(Map<String, List<String>> businessLineRequiredFields) {
        if (businessLineRequiredFields != null) {
            this.businessLineRequiredFields = new ConcurrentHashMap<>(businessLineRequiredFields);
        }
    }

    public List<String> getGlobalRequiredFields() {
        return globalRequiredFields;
    }

    public void setGlobalRequiredFields(List<String> globalRequiredFields) {
        if (globalRequiredFields != null) {
            this.globalRequiredFields = new ArrayList<>(globalRequiredFields);
        }
    }
}
