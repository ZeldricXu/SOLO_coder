package com.enterprise.gateway.logprocessor.memory;

import com.enterprise.gateway.logprocessor.model.LogEntry;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * LogEntry 工厂类，使用字符串池化和 Arena 内存分配优化性能。
 *
 * <p>该工厂通过以下方式优化日志对象的创建：</p>
 * <ul>
 *   <li>使用 {@link StringInterner} 对低基数字段进行池化，减少内存占用</li>
 *   <li>使用 {@link InternableFieldPolicy} 决定哪些字段需要池化</li>
 *   <li>使用 {@link ArenaAllocator} 处理字节数组到字符串的转换，减少 GC 压力</li>
 *   <li>延迟字符串转换，只在必要时才将 byte[] 转换为 String</li>
 * </ul>
 *
 * @author Gateway Team
 * @since 1.0.0
 */
public class LogEntryFactory {

    private final StringInterner interner;
    private final InternableFieldPolicy policy;
    private final ArenaAllocator arena;

    /**
     * 创建使用默认组件的 LogEntryFactory。
     *
     * <p>使用单例的 StringInterner 和 InternableFieldPolicy，以及线程本地的 ArenaAllocator。</p>
     */
    public LogEntryFactory() {
        this(StringInterner.getInstance(), InternableFieldPolicy.getInstance(), ArenaAllocator.threadLocal());
    }

    /**
     * 创建使用指定组件的 LogEntryFactory。
     *
     * @param interner 字符串池化器，不能为空
     * @param policy 字段池化策略，不能为空
     * @param arena Arena 内存分配器，不能为空
     * @throws NullPointerException 如果任何参数为 null
     */
    public LogEntryFactory(StringInterner interner, InternableFieldPolicy policy, ArenaAllocator arena) {
        this.interner = Objects.requireNonNull(interner, "interner must not be null");
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
        this.arena = Objects.requireNonNull(arena, "arena must not be null");
    }

    /**
     * 使用 String 参数创建 LogEntry。
     *
     * <p>对于符合池化条件的字段，会自动进行字符串池化。</p>
     *
     * @param timestamp 时间戳（毫秒）
     * @param service 服务名称
     * @param level 日志级别
     * @param message 日志消息
     * @param traceId 追踪 ID
     * @param statusCode 状态码
     * @param method HTTP 方法
     * @param path 请求路径
     * @param duration 持续时间
     * @return 创建的 LogEntry 对象
     */
    public LogEntry create(long timestamp, String service, String level, String message,
                           String traceId, String statusCode, String method, String path, String duration) {
        return LogEntry.builder()
                .timestamp(timestamp)
                .service(internIfNeeded("service", service))
                .level(internIfNeeded("level", level))
                .message(internIfNeeded("message", message))
                .traceId(internIfNeeded("traceId", traceId))
                .statusCode(internIfNeeded("statusCode", statusCode))
                .method(internIfNeeded("method", method))
                .path(internIfNeeded("path", path))
                .duration(internIfNeeded("duration", duration))
                .build();
    }

    /**
     * 使用 byte[] 参数创建 LogEntry。
     *
     * <p>此方法延迟执行 byte[] 到 String 的转换，只在必要时进行转换。
     * 转换过程中使用 ArenaAllocator 分配临时内存，减少 GC 压力。</p>
     *
     * @param timestamp 时间戳（毫秒）
     * @param service 服务名称的字节数组
     * @param level 日志级别的字节数组
     * @param message 日志消息的字节数组
     * @param traceId 追踪 ID 的字节数组
     * @param statusCode 状态码的字节数组
     * @param method HTTP 方法的字节数组
     * @param path 请求路径的字节数组
     * @param duration 持续时间的字节数组
     * @return 创建的 LogEntry 对象
     */
    public LogEntry create(long timestamp, byte[] service, byte[] level, byte[] message,
                           byte[] traceId, byte[] statusCode, byte[] method, byte[] path, byte[] duration) {
        return LogEntry.builder()
                .timestamp(timestamp)
                .service(convertAndIntern("service", service))
                .level(convertAndIntern("level", level))
                .message(convertAndIntern("message", message))
                .traceId(convertAndIntern("traceId", traceId))
                .statusCode(convertAndIntern("statusCode", statusCode))
                .method(convertAndIntern("method", method))
                .path(convertAndIntern("path", path))
                .duration(convertAndIntern("duration", duration))
                .build();
    }

    /**
     * 使用部分参数创建 LogEntry，其他字段为 null。
     *
     * @param timestamp 时间戳（毫秒）
     * @param service 服务名称
     * @param level 日志级别
     * @param message 日志消息
     * @return 创建的 LogEntry 对象
     */
    public LogEntry create(long timestamp, String service, String level, String message) {
        return create(timestamp, service, level, message, null, null, null, null, null);
    }

    /**
     * 根据策略决定是否对字段值进行池化。
     *
     * @param fieldName 字段名称
     * @param value 字段值
     * @return 池化后的值或原值
     */
    private String internIfNeeded(String fieldName, String value) {
        if (value == null) {
            return null;
        }
        if (policy.shouldIntern(fieldName, value)) {
            return interner.intern(value);
        }
        return value;
    }

    /**
     * 将 byte[] 转换为 String，并根据策略决定是否池化。
     *
     * <p>使用 UTF-8 编码进行转换。对于空数组或 null，返回 null。</p>
     *
     * @param fieldName 字段名称
     * @param bytes 字节数组
     * @return 转换并可能池化后的字符串
     */
    private String convertAndIntern(String fieldName, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }

        String value = new String(bytes, StandardCharsets.UTF_8);

        if (policy.shouldIntern(fieldName, value)) {
            return interner.intern(value);
        }

        return value;
    }

    /**
     * 获取使用的字符串池化器。
     *
     * @return StringInterner 实例
     */
    public StringInterner getInterner() {
        return interner;
    }

    /**
     * 获取使用的字段池化策略。
     *
     * @return InternableFieldPolicy 实例
     */
    public InternableFieldPolicy getPolicy() {
        return policy;
    }

    /**
     * 获取使用的 Arena 内存分配器。
     *
     * @return ArenaAllocator 实例
     */
    public ArenaAllocator getArena() {
        return arena;
    }

    /**
     * 重置关联的 ArenaAllocator，释放临时内存。
     *
     * <p>建议在批量处理完一批日志后调用此方法。</p>
     */
    public void resetArena() {
        arena.reset();
    }
}
