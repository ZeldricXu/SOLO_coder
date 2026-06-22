package com.enterprise.gateway.logprocessor.memory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * 基于 Arena（内存区域）的字节数组分配器。
 *
 * <p>通过预先分配大块内存（Arena），然后从这些大块中提供小的分配请求，
 * 从而减少内存分配的开销和 GC 压力。特别适合短期存活的小对象分配场景，
 * 如日志解析过程中的临时字节数组。</p>
 *
 * <p>设计特点：</p>
 * <ul>
 *   <li>每个 Arena 默认大小为 64KB</li>
 *   <li>过大的分配请求（超过 Arena 大小的 1/4）直接分配，不使用 Arena</li>
 *   <li>支持线程本地分配，避免多线程竞争</li>
 *   <li>支持重置操作，快速回收所有已分配的 Arena</li>
 * </ul>
 *
 * <p>注意：此类不是线程安全的。建议使用 {@link #threadLocal()} 获取线程本地实例。</p>
 *
 * @author Gateway Team
 * @since 1.0.0
 */
public class ArenaAllocator {

    private static final int DEFAULT_ARENA_SIZE = 64 * 1024;
    private static final int LARGE_ALLOCATION_DIVISOR = 4;

    private static final ThreadLocal<ArenaAllocator> THREAD_LOCAL =
            ThreadLocal.withInitial(ArenaAllocator::new);

    private final int arenaSize;
    private final Deque<byte[]> arenas;
    private int currentOffset;
    private long totalAllocatedBytes;
    private long allocationCount;

    /**
     * 创建使用默认 Arena 大小（64KB）的分配器。
     */
    public ArenaAllocator() {
        this(DEFAULT_ARENA_SIZE);
    }

    /**
     * 创建使用指定 Arena 大小的分配器。
     *
     * @param arenaSize 每个 Arena 的大小（字节），必须大于 0
     * @throws IllegalArgumentException 如果 arenaSize 小于等于 0
     */
    public ArenaAllocator(int arenaSize) {
        if (arenaSize <= 0) {
            throw new IllegalArgumentException("arenaSize must be positive: " + arenaSize);
        }
        this.arenaSize = arenaSize;
        this.arenas = new ArrayDeque<>();
        this.currentOffset = 0;
    }

    /**
     * 获取线程本地的 ArenaAllocator 实例。
     *
     * @return 当前线程的 ArenaAllocator 实例
     */
    public static ArenaAllocator threadLocal() {
        return THREAD_LOCAL.get();
    }

    /**
     * 分配指定大小的字节数组。
     *
     * <p>对于小的分配请求，会从当前 Arena 中分配。如果当前 Arena 空间不足，
     * 会创建新的 Arena。对于过大的分配请求，会直接分配新数组，不使用 Arena。</p>
     *
     * @param size 要分配的字节数，必须大于等于 0
     * @return 分配的字节数组
     * @throws IllegalArgumentException 如果 size 小于 0
     */
    public byte[] allocate(int size) {
        if (size < 0) {
            throw new IllegalArgumentException("size must be non-negative: " + size);
        }

        if (size == 0) {
            return new byte[0];
        }

        if (size > arenaSize / LARGE_ALLOCATION_DIVISOR) {
            allocationCount++;
            totalAllocatedBytes += size;
            return new byte[size];
        }

        byte[] currentArena = arenas.peekLast();
        if (currentArena == null || currentOffset + size > arenaSize) {
            currentArena = new byte[arenaSize];
            arenas.addLast(currentArena);
            currentOffset = 0;
        }

        byte[] result = new byte[size];
        System.arraycopy(currentArena, currentOffset, result, 0, size);
        currentOffset += size;

        allocationCount++;
        totalAllocatedBytes += size;

        return result;
    }

    /**
     * 分配字节数组并从源数组复制数据。
     *
     * @param src 源数组
     * @param srcPos 源数组起始位置
     * @param length 要复制的长度
     * @return 新的字节数组，包含指定范围的数据
     * @throws NullPointerException 如果 src 为 null
     * @throws IndexOutOfBoundsException 如果 srcPos 或 length 无效
     */
    public byte[] allocateAndCopy(byte[] src, int srcPos, int length) {
        Objects.requireNonNull(src, "src must not be null");
        if (srcPos < 0 || length < 0 || srcPos + length > src.length) {
            throw new IndexOutOfBoundsException(
                    String.format("srcPos=%d, length=%d, src.length=%d", srcPos, length, src.length));
        }

        byte[] result = allocate(length);
        System.arraycopy(src, srcPos, result, 0, length);
        return result;
    }

    /**
     * 重置分配器状态。
     *
     * <p>清除所有已分配的 Arena，重置偏移量和统计信息。
     * 此方法应该在一批分配操作完成后调用，以释放内存。</p>
     */
    public void reset() {
        arenas.clear();
        currentOffset = 0;
        totalAllocatedBytes = 0;
        allocationCount = 0;
    }

    /**
     * 获取已分配的 Arena 数量。
     *
     * @return Arena 数量
     */
    public int getAllocatedArenas() {
        return arenas.size();
    }

    /**
     * 获取每个 Arena 的大小。
     *
     * @return Arena 大小（字节）
     */
    public int getArenaSize() {
        return arenaSize;
    }

    /**
     * 获取当前 Arena 的使用偏移量。
     *
     * @return 当前偏移量（字节）
     */
    public int getCurrentOffset() {
        return currentOffset;
    }

    /**
     * 获取累计分配的总字节数。
     *
     * @return 总分配字节数
     */
    public long getTotalAllocatedBytes() {
        return totalAllocatedBytes;
    }

    /**
     * 获取累计分配次数。
     *
     * @return 分配次数
     */
    public long getAllocationCount() {
        return allocationCount;
    }

    /**
     * 获取当前使用的内存总量（Arena 数量 * Arena 大小）。
     *
     * @return 当前使用的内存（字节）
     */
    public long getCurrentMemoryUsage() {
        return (long) arenas.size() * arenaSize;
    }
}
