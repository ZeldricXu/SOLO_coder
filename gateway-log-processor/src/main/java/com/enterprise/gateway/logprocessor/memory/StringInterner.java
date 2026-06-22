package com.enterprise.gateway.logprocessor.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 线程本地字符串池化器（String Interner）。
 *
 * <p>通过为每个线程维护独立的 LRU 缓存，实现高效的字符串复用，减少内存占用和 GC 压力。
 * 每个线程的缓存有最大容量限制（默认 10000），防止内存泄漏。</p>
 *
 * <p>与 JVM 内置的 {@link String#intern()} 相比，此实现：</p>
 * <ul>
 *   <li>避免了 PermGen/Metaspace 空间占用</li>
 *   <li>提供了可配置的缓存大小和 eviction 策略</li>
 *   <li>支持命中率等统计信息</li>
 *   <li>线程隔离，减少锁竞争</li>
 * </ul>
 *
 * @author Gateway Team
 * @since 1.0.0
 */
public final class StringInterner {

    private static final int DEFAULT_MAX_CAPACITY = 10000;
    private static final int MAX_LENGTH_FOR_INTERN = 64;
    private static final float LOAD_FACTOR = 0.75f;

    private static final StringInterner INSTANCE = new StringInterner(DEFAULT_MAX_CAPACITY);

    private final int maxCapacity;
    private final ThreadLocal<Map<String, String>> threadLocalCache;

    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);
    private final AtomicLong evictionCount = new AtomicLong(0);

    private StringInterner(int maxCapacity) {
        this.maxCapacity = maxCapacity;
        this.threadLocalCache = ThreadLocal.withInitial(() ->
                new LruHashMap<>(maxCapacity, evictionCount));
    }

    /**
     * 获取字符串池化器的单例实例。
     *
     * @return StringInterner 的单例实例
     */
    public static StringInterner getInstance() {
        return INSTANCE;
    }

    /**
     * 池化字符串。
     *
     * <p>如果字符串已在缓存中，返回缓存的引用；否则将其存入缓存并返回原字符串。</p>
     *
     * @param s 要池化的字符串，可以为 null
     * @return 池化后的字符串，或者原字符串（如果不满足池化条件）
     */
    public String intern(String s) {
        if (s == null) {
            return null;
        }

        if (s.length() > MAX_LENGTH_FOR_INTERN) {
            return s;
        }

        Map<String, String> cache = threadLocalCache.get();
        String cached = cache.get(s);

        if (cached != null) {
            hitCount.incrementAndGet();
            return cached;
        }

        missCount.incrementAndGet();
        cache.put(s, s);
        return s;
    }

    /**
     * 清除当前线程的缓存。
     *
     * <p>在线程池环境中，建议在线程归还前调用此方法，避免内存泄漏。</p>
     */
    public void clear() {
        Map<String, String> cache = threadLocalCache.get();
        cache.clear();
    }

    /**
     * 获取缓存命中次数。
     *
     * @return 命中次数
     */
    public long getHitCount() {
        return hitCount.get();
    }

    /**
     * 获取缓存未命中次数。
     *
     * @return 未命中次数
     */
    public long getMissCount() {
        return missCount.get();
    }

    /**
     * 获取缓存驱逐次数。
     *
     * @return 驱逐次数
     */
    public long getEvictionCount() {
        return evictionCount.get();
    }

    /**
     * 获取缓存命中率。
     *
     * @return 命中率（0.0 - 1.0）
     */
    public double getHitRate() {
        long hits = hitCount.get();
        long total = hits + missCount.get();
        return total == 0 ? 0.0 : (double) hits / total;
    }

    /**
     * 重置所有统计计数器。
     */
    public void resetStatistics() {
        hitCount.set(0);
        missCount.set(0);
        evictionCount.set(0);
    }

    /**
     * 获取每个线程缓存的最大容量。
     *
     * @return 最大容量
     */
    public int getMaxCapacity() {
        return maxCapacity;
    }

    /**
     * 获取当前线程的缓存大小。
     *
     * @return 当前缓存中的条目数
     */
    public int getCurrentSize() {
        return threadLocalCache.get().size();
    }

    /**
     * 带 LRU 驱逐策略的 LinkedHashMap 实现。
     *
     * @param <K> 键类型
     * @param <V> 值类型
     */
    private static final class LruHashMap<K, V> extends LinkedHashMap<K, V> {

        private final int maxCapacity;
        private final AtomicLong evictionCount;

        LruHashMap(int maxCapacity, AtomicLong evictionCount) {
            super((int) Math.ceil(maxCapacity / LOAD_FACTOR) + 1, LOAD_FACTOR, true);
            this.maxCapacity = maxCapacity;
            this.evictionCount = evictionCount;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            boolean shouldRemove = size() > maxCapacity;
            if (shouldRemove) {
                evictionCount.incrementAndGet();
            }
            return shouldRemove;
        }
    }
}
