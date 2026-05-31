package com.streamsql.streaming;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("流式处理模式测试")
class StreamingTest {

    @Mock
    private StreamingConfig streamingConfig;

    @InjectMocks
    private BatchProcessor batchProcessor;

    @InjectMocks
    private StreamingService streamingService;

    @Nested
    @DisplayName("批量处理器测试")
    class BatchProcessorTest {

        @Test
        @DisplayName("数据分片 - 正确分割")
        void shouldPartitionDataCorrectly() {
            List<Integer> data = IntStream.range(0, 100).boxed().collect(Collectors.toList());

            List<List<Integer>> batches = batchProcessor.partition(data, 10);

            assertEquals(10, batches.size());
            assertEquals(10, batches.get(0).size());
            assertEquals(10, batches.get(9).size());
        }

        @Test
        @DisplayName("数据分片 - 最后一批不足")
        void shouldPartitionDataWithLastBatchSmaller() {
            List<Integer> data = IntStream.range(0, 25).boxed().collect(Collectors.toList());

            List<List<Integer>> batches = batchProcessor.partition(data, 10);

            assertEquals(3, batches.size());
            assertEquals(10, batches.get(0).size());
            assertEquals(5, batches.get(2).size());
        }

        @Test
        @DisplayName("数据分片 - 空数据")
        void shouldPartitionEmptyData() {
            List<Integer> data = new ArrayList<>();

            List<List<Integer>> batches = batchProcessor.partition(data, 10);

            assertTrue(batches.isEmpty());
        }

        @Test
        @DisplayName("批量处理 - 成功")
        void shouldProcessBatchSuccessfully() {
            List<Integer> data = IntStream.range(0, 100).boxed().collect(Collectors.toList());
            when(streamingConfig.getBatchSize()).thenReturn(10);

            List<String> results = batchProcessor.processBatch(
                    data,
                    batch -> "Processed " + batch.size() + " items"
            );

            assertEquals(10, results.size());
            assertEquals("Processed 10 items", results.get(0));
        }

        @Test
        @DisplayName("批量处理 - 自定义批次大小")
        void shouldProcessBatchWithCustomSize() {
            List<Integer> data = IntStream.range(0, 100).boxed().collect(Collectors.toList());

            List<String> results = batchProcessor.processBatch(
                    data,
                    batch -> "Processed " + batch.size() + " items",
                    25
            );

            assertEquals(4, results.size());
            assertEquals("Processed 25 items", results.get(0));
        }

        @Test
        @DisplayName("异步批量处理 - 成功")
        void shouldProcessBatchAsync() {
            List<Integer> data = IntStream.range(0, 50).boxed().collect(Collectors.toList());
            AtomicInteger counter = new AtomicInteger(0);

            assertDoesNotThrow(() -> batchProcessor.processBatchAsync(
                    data,
                    batch -> counter.addAndGet(batch.size()),
                    10
            ));
        }

        @Test
        @DisplayName("并行处理 - 成功")
        void shouldProcessParallel() {
            List<Integer> data = IntStream.range(0, 20).boxed().collect(Collectors.toList());

            List<Integer> results = batchProcessor.processParallel(
                    data,
                    item -> item * 2
            );

            assertEquals(20, results.size());
            assertTrue(results.contains(0));
            assertTrue(results.contains(38));
        }
    }

    @Nested
    @DisplayName("流式处理器测试")
    class StreamProcessorTest {

        @Test
        @DisplayName("启动流式处理器")
        void shouldStartStreamProcessor() {
            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            AtomicInteger processedCount = new AtomicInteger(0);

            processor.start(batch -> processedCount.addAndGet(batch.size()));

            assertTrue(processor.isRunning());
            processor.stop();
        }

        @Test
        @DisplayName("停止流式处理器")
        void shouldStopStreamProcessor() {
            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            processor.start(batch -> {});

            processor.stop();

            assertFalse(processor.isRunning());
        }

        @Test
        @DisplayName("添加数据 - 成功")
        void shouldAddDataToStream() {
            when(streamingConfig.getQueueCapacity()).thenReturn(10000);
            when(streamingConfig.getFlushIntervalMs()).thenReturn(1000);

            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            processor.start(batch -> {});

            processor.add(1);
            processor.add(2);
            processor.add(3);

            assertEquals(3, processor.getQueueSize());
            processor.stop();
        }

        @Test
        @DisplayName("批量添加数据 - 成功")
        void shouldAddAllDataToStream() {
            when(streamingConfig.getQueueCapacity()).thenReturn(10000);
            when(streamingConfig.getFlushIntervalMs()).thenReturn(1000);

            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            processor.start(batch -> {});

            List<Integer> data = IntStream.range(0, 10).boxed().collect(Collectors.toList());
            processor.addAll(data);

            assertEquals(10, processor.getQueueSize());
            processor.stop();
        }

        @Test
        @DisplayName("获取处理指标")
        void shouldGetProcessingMetrics() {
            when(streamingConfig.getFlushIntervalMs()).thenReturn(1000);

            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            processor.start(batch -> {});

            var metrics = processor.getMetrics();

            assertNotNull(metrics);
            assertEquals(0, metrics.processedCount());
            assertEquals(0, metrics.errorCount());
            assertEquals(0, metrics.queueSize());
            assertTrue(metrics.running());

            processor.stop();
        }

        @Test
        @DisplayName("重复启动 - 幂等性")
        void shouldHandleMultipleStartCalls() {
            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            processor.start(batch -> {});
            processor.start(batch -> {});

            assertTrue(processor.isRunning());
            processor.stop();
        }

        @Test
        @DisplayName("重复停止 - 幂等性")
        void shouldHandleMultipleStopCalls() {
            StreamProcessor<Integer> processor = new StreamProcessor<>(streamingConfig);
            processor.start(batch -> {});
            processor.stop();
            processor.stop();

            assertFalse(processor.isRunning());
        }
    }

    @Nested
    @DisplayName("流式服务测试")
    class StreamingServiceTest {

        @Test
        @DisplayName("批量模式处理大数据集")
        void shouldProcessInBatchMode() {
            List<Integer> data = IntStream.range(0, 1000).boxed().collect(Collectors.toList());
            when(streamingConfig.getBatchSize()).thenReturn(100);

            List<String> results = streamingService.processLargeDataset(
                    data,
                    batch -> "Batch: " + batch.size(),
                    "batch"
            );

            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("流式模式处理大数据集")
        void shouldProcessInStreamingMode() {
            List<Integer> data = IntStream.range(0, 1000).boxed().collect(Collectors.toList());
            when(streamingConfig.getBatchSize()).thenReturn(100);

            List<String> results = streamingService.processLargeDataset(
                    data,
                    batch -> "Batch: " + batch.size(),
                    "streaming"
            );

            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("微批模式处理大数据集")
        void shouldProcessInMicroBatchMode() {
            List<Integer> data = IntStream.range(0, 1000).boxed().collect(Collectors.toList());
            when(streamingConfig.getBatchSize()).thenReturn(100);

            List<String> results = streamingService.processLargeDataset(
                    data,
                    batch -> "Batch: " + batch.size(),
                    "micro_batch"
            );

            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("未知模式默认使用批量模式")
        void shouldUseBatchModeForUnknownMode() {
            List<Integer> data = IntStream.range(0, 100).boxed().collect(Collectors.toList());
            when(streamingConfig.getBatchSize()).thenReturn(10);

            List<String> results = streamingService.processLargeDataset(
                    data,
                    batch -> "Batch: " + batch.size(),
                    "unknown_mode"
            );

            assertFalse(results.isEmpty());
        }

        @Test
        @DisplayName("异步处理 - 成功")
        void shouldProcessAsync() {
            List<Integer> data = IntStream.range(0, 100).boxed().collect(Collectors.toList());

            var future = streamingService.processAsync(
                    data,
                    batch -> "Batch: " + batch.size(),
                    10
            );

            assertNotNull(future);
        }

        @Test
        @DisplayName("并行处理 - 成功")
        void shouldProcessParallel() {
            List<Integer> data = IntStream.range(0, 20).boxed().collect(Collectors.toList());

            List<Integer> results = streamingService.processParallel(
                    data,
                    item -> item * 2
            );

            assertEquals(20, results.size());
        }
    }
}
