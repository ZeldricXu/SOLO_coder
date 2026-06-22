/**
 * 内存优化包，提供字符串池化和 Arena 内存分配等性能优化组件。
 *
 * <p>该包针对日志处理场景中的高内存分配和 GC 压力问题，提供以下核心组件：
 * <ul>
 *   <li>{@link com.enterprise.gateway.logprocessor.memory.InternableFieldPolicy} -
 *       字段池化策略，决定哪些日志字段应该进行字符串池化</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.memory.StringInterner} -
 *       线程本地字符串池，使用 LRU 缓存避免内存泄漏，减少重复字符串的内存占用</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.memory.ArenaAllocator} -
 *       基于 Arena 的内存分配器，预分配大块内存服务小对象分配，降低 GC 压力</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.memory.LogEntryFactory} -
 *       LogEntry 工厂类，整合字符串池化和 Arena 分配优化日志对象创建</li>
 * </ul>
 *
 * <p>内存优化策略：
 * <ol>
 *   <li>字符串池化：对低基数字段（service、level、statusCode、method）进行池化，减少内存占用</li>
 *   <li>线程隔离：使用 ThreadLocal 避免多线程竞争，每个线程维护独立的缓存</li>
 *   <li>容量限制：每个线程缓存最大 10000 条，使用 LRU 策略驱逐旧条目，防止内存泄漏</li>
 *   <li>Arena 分配：预分配 64KB 内存块，服务小字节数组分配，减少 malloc 系统调用和 GC</li>
 *   <li>智能决策：长度超过 64 的字符串不池化，大于 Arena 1/4 的分配直接走堆分配</li>
 * </ol>
 *
 * <p>使用建议：
 * <ul>
 *   <li>在线程池环境中，线程归还前调用 {@link StringInterner#clear()} 清理缓存</li>
 *   <li>批量处理完日志后调用 {@link ArenaAllocator#reset()} 或 {@link LogEntryFactory#resetArena()} 释放内存</li>
 *   <li>通过 {@link StringInterner#getHitRate()} 监控池化效果，必要时调整策略</li>
 * </ul>
 */
package com.enterprise.gateway.logprocessor.memory;
