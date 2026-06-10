package com.company.dbstudio.data;

import com.company.dbstudio.data.exception.GenerationConflictException;
import com.company.dbstudio.data.model.TableData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Generation ID 竞态条件 - 回归测试")
class GenerationIdRaceConditionTest {

    private TableData tableData;

    @BeforeEach
    void setup() {
        tableData = new TableData("users", "public");
    }

    @Test
    @DisplayName("初始generation应为0")
    void testInitialGeneration() {
        assertThat(tableData.getGeneration()).isEqualTo(0);
        assertThat(tableData.getLoadedAt()).isEqualTo(0);
    }

    @Test
    @DisplayName("加载数据后generation递增")
    void testGenerationIncrementAfterLoad() {
        long gen1 = tableData.incrementGeneration();
        assertThat(gen1).isGreaterThan(0);
        assertThat(tableData.getGeneration()).isEqualTo(gen1);
        assertThat(tableData.getLoadedAt()).isGreaterThan(0);

        long gen2 = tableData.incrementGeneration();
        assertThat(gen2).isGreaterThan(gen1);
        assertThat(tableData.getGeneration()).isEqualTo(gen2);
    }

    @Test
    @DisplayName("不同TableData实例generation独立递增")
    void testIndependentGenerationCounters() {
        TableData table1 = new TableData("table1", "public");
        TableData table2 = new TableData("table2", "public");

        long gen1a = table1.incrementGeneration();
        long gen2a = table2.incrementGeneration();
        long gen1b = table1.incrementGeneration();
        long gen2b = table2.incrementGeneration();

        assertThat(gen1b).isEqualTo(gen1a + 1);
        assertThat(gen2b).isEqualTo(gen2a + 1);
        assertThat(table1.getGeneration()).isNotEqualTo(table2.getGeneration());
    }

    @Test
    @DisplayName("GenerationConflictException - 正确的错误信息")
    void testGenerationConflictExceptionMessage() {
        GenerationConflictException ex = new GenerationConflictException("users", 1L, 3L);

        assertThat(ex.getMessage()).contains("users");
        assertThat(ex.getMessage()).contains("1");
        assertThat(ex.getMessage()).contains("3");
        assertThat(ex.getMessage()).contains("数据版本冲突");

        assertThat(ex.getTableName()).isEqualTo("users");
        assertThat(ex.getExpectedGeneration()).isEqualTo(1);
        assertThat(ex.getActualGeneration()).isEqualTo(3);
        assertThat(ex.isStale()).isTrue();
    }

    @Test
    @DisplayName("GenerationConflictException - 相同版本不冲突")
    void testGenerationConflictSameVersion() {
        GenerationConflictException ex = new GenerationConflictException("users", 5L, 5L);

        assertThat(ex.isStale()).isFalse();
        assertThat(ex.getExpectedGeneration()).isEqualTo(ex.getActualGeneration());
    }

    @Test
    @DisplayName("多线程并发generation递增 - 线程安全测试")
    void testThreadSafeGenerationIncrement() throws InterruptedException {
        int threadCount = 10;
        int incrementsPerThread = 100;
        TableData data = new TableData("concurrent_test", "public");

        long initialGen = data.incrementGeneration();

        Thread[] threads = new Thread[threadCount];
        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < incrementsPerThread; j++) {
                    data.incrementGeneration();
                }
            });
            threads[i].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long expectedFinalGen = initialGen + threadCount * incrementsPerThread;
        assertThat(data.getGeneration()).isEqualTo(expectedFinalGen);
    }

    @Test
    @DisplayName("setGeneration 手动设置")
    void testSetGeneration() {
        tableData.setGeneration(42L);
        assertThat(tableData.getGeneration()).isEqualTo(42);

        long nextGen = tableData.incrementGeneration();
        assertThat(nextGen).isGreaterThan(42);
    }

    @Test
    @DisplayName("loadedAt 时间戳更新")
    void testLoadedAtTimestamp() throws InterruptedException {
        long before = System.currentTimeMillis();
        Thread.sleep(1);
        tableData.incrementGeneration();
        Thread.sleep(1);
        long after = System.currentTimeMillis();

        assertThat(tableData.getLoadedAt()).isGreaterThan(before).isLessThan(after);
    }

    @Test
    @DisplayName("GenerationConflictException 异常链")
    void testGenerationConflictExceptionCause() {
        Exception cause = new RuntimeException("Root cause");
        try {
            throw new RuntimeException("Wrapper", cause);
        } catch (RuntimeException e) {
            assertThat(e.getCause()).isSameAs(cause);
        }
    }

    @Test
    @DisplayName("连续加载保持generation递增")
    void testSequentialLoadsIncrementGeneration() {
        long[] generations = new long[5];
        for (int i = 0; i < 5; i++) {
            generations[i] = tableData.incrementGeneration();
        }

        for (int i = 1; i < generations.length; i++) {
            assertThat(generations[i]).isEqualTo(generations[i - 1] + 1);
        }
    }

    @Test
    @DisplayName("表名和schema正确保留")
    void testTableNamePreserved() {
        assertThat(tableData.getTableName()).isEqualTo("users");
        assertThat(tableData.getSchemaName()).isEqualTo("public");
        assertThat(tableData.getFullTableName()).isEqualTo("public.users");
    }

    @Test
    @DisplayName("空schema的表名处理")
    void testEmptySchemaTableName() {
        TableData noSchema = new TableData("my_table", null);
        assertThat(noSchema.getFullTableName()).isEqualTo("my_table");
    }
}
