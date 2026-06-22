/**
 * 日志格式检测器包，提供多种日志格式识别策略。
 *
 * <p>该包包含以下核心组件：
 * <ul>
 *   <li>{@link com.enterprise.gateway.logprocessor.detector.FastFeatureExtractor} -
 *       O(1) 时间复杂度的日志特征提取器，单次扫描提取多种统计特征</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.detector.SingleBytePrefixFilter} -
 *       基于首字节的预过滤器，快速缩小候选解析器范围</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.detector.FormatDetector} -
 *       朴素实现的格式检测器，遍历所有解析器尝试匹配</li>
 *   <li>{@link com.enterprise.gateway.logprocessor.detector.TwoPhaseFormatDetector} -
 *       两阶段优化检测器，结合特征提取和预过滤实现高性能格式识别</li>
 * </ul>
 *
 * <p>性能优化策略：
 * <ol>
 *   <li>Phase 1: 快速特征提取 + 首字节过滤，将候选集从 N 缩小到 1-2 个</li>
 *   <li>Phase 2: 仅对候选集执行完整解析尝试</li>
 *   <li>降级策略: 快速路径失败时回退到全量遍历（罕见情况）</li>
 * </ol>
 */
package com.enterprise.gateway.logprocessor.detector;
