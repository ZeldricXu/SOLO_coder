/**
 * JMH基准测试包，包含日志处理器核心组件的性能基准测试。
 *
 * <p>测试内容包括：
 * <ul>
 *   <li>{@link com.enterprise.gateway.logprocessor.benchmark.FormatDetectionBenchmark} - 格式检测性能对比</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.benchmark.WindowStoreBenchmark} - 窗口存储性能对比</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.benchmark.StringInternerBenchmark} - 字符串池化性能对比</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.benchmark.LogParserBenchmark} - 日志解析器性能基准</li>
 * </ul>
 * </p>
 *
 * @author Gateway Team
 * @since 1.0.0
 */
package com.enterprise.gateway.logprocessor.benchmark;
