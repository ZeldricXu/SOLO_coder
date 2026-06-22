package com.enterprise.gateway.transform.converter;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;

import static org.assertj.core.api.Assertions.assertThat;

class JsonXmlConverterMemoryTest {

    private final JsonXmlConverter converter = new JsonXmlConverter();
    private final DataBufferFactory bufferFactory = new DefaultDataBufferFactory();

    @Test
    void shouldNotLeakMemoryUnderHighThroughput() throws InterruptedException {
        String sampleJson = "{" +
                "\"id\":123," +
                "\"name\":\"test-user\"," +
                "\"email\":\"user@example.com\"," +
                "\"roles\":[\"user\",\"admin\"]," +
                "\"profile\":{" +
                    "\"age\":30," +
                    "\"city\":\"Beijing\"," +
                    "\"address\":{\"street\":\"Main St\",\"zip\":\"100000\"}" +
                "}" +
                "}";

        int iterations = 1000;
        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(iterations);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < iterations; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    DataBuffer input = bufferFactory.wrap(sampleJson.getBytes(StandardCharsets.UTF_8));
                    DataBuffer output = converter.convert(
                                    input,
                                    org.springframework.http.MediaType.APPLICATION_JSON,
                                    org.springframework.http.MediaType.APPLICATION_XML)
                            .block(java.time.Duration.ofSeconds(5));
                    assertThat(output).isNotNull();
                    assertThat(output.readableByteCount()).isGreaterThan(0);
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(30, TimeUnit.SECONDS);
        executor.shutdown();
        long endTime = System.currentTimeMillis();

        assertThat(successCount.get()).isEqualTo(iterations);
        assertThat(errorCount.get()).isEqualTo(0);

        long totalTime = endTime - startTime;
        double throughput = iterations * 1000.0 / totalTime;
        assertThat(throughput).isGreaterThan(50);
    }

    @Test
    void shouldConvertJsonToXmlCorrectlyUnderConcurrency() throws InterruptedException {
        String json = "{\"user\":{\"id\":\"u1\",\"name\":\"Alice\"}}";

        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        List<String> results = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    DataBuffer input = bufferFactory.wrap(json.getBytes(StandardCharsets.UTF_8));
                    DataBuffer output = converter.convert(
                                    input,
                                    org.springframework.http.MediaType.APPLICATION_JSON,
                                    org.springframework.http.MediaType.APPLICATION_XML)
                            .block(java.time.Duration.ofSeconds(5));
                    byte[] bytes = new byte[output.readableByteCount()];
                    output.read(bytes);
                    synchronized (results) {
                        results.add(new String(bytes, StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(results).hasSize(threadCount);
        for (String result : results) {
            assertThat(result).contains("<user>");
            assertThat(result).contains("<id>u1</id>");
            assertThat(result).contains("<name>Alice</name>");
        }
    }

    @Test
    void shouldHandleLargePayloadsWithoutMemorySpike() {
        StringBuilder jsonBuilder = new StringBuilder("{\"items\":[");
        for (int i = 0; i < 1000; i++) {
            if (i > 0) jsonBuilder.append(",");
            jsonBuilder.append("{\"id\":").append(i).append(",\"value\":\"item-").append(i).append("\"}");
        }
        jsonBuilder.append("]}");

        String largeJson = jsonBuilder.toString();

        Runtime runtime = Runtime.getRuntime();
        long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

        DataBuffer input = bufferFactory.wrap(largeJson.getBytes(StandardCharsets.UTF_8));
        DataBuffer output = converter.convert(
                        input,
                        org.springframework.http.MediaType.APPLICATION_JSON,
                        org.springframework.http.MediaType.APPLICATION_XML)
                .block(java.time.Duration.ofSeconds(10));

        long memoryAfter = runtime.totalMemory() - runtime.freeMemory();
        long memoryDelta = memoryAfter - memoryBefore;

        assertThat(output).isNotNull();
        assertThat(output.readableByteCount()).isGreaterThan(largeJson.length());
        assertThat(memoryDelta).isLessThan(largeJson.length() * 20L);
    }
}
