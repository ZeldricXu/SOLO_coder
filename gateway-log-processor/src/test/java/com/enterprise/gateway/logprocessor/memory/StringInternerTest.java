package com.enterprise.gateway.logprocessor.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.*;

class StringInternerTest {

    private StringInterner interner;

    @BeforeEach
    void setUp() throws Exception {
        Constructor<StringInterner> constructor = StringInterner.class.getDeclaredConstructor(int.class);
        constructor.setAccessible(true);
        interner = constructor.newInstance(100);
        interner.resetStatistics();
        interner.clear();
    }

    @Test
    @DisplayName("相同字符串应返回相同引用")
    void testSameStringReturnsSameReference() {
        String s1 = new String("testString");
        String s2 = new String("testString");

        assertNotSame(s1, s2);

        String interned1 = interner.intern(s1);
        String interned2 = interner.intern(s2);

        assertSame(interned1, interned2);
        assertSame(interned1, s1);
    }

    @Test
    @DisplayName("长度超过64的字符串不应被池化")
    void testLongStringsNotInterned() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 65; i++) {
            sb.append('a');
        }
        String longString = sb.toString();
        assertEquals(65, longString.length());

        String s1 = new String(longString);
        String s2 = new String(longString);

        String interned1 = interner.intern(s1);
        String interned2 = interner.intern(s2);

        assertNotSame(interned1, interned2);
        assertSame(s1, interned1);
        assertSame(s2, interned2);
    }

    @Test
    @DisplayName("LRU 驱逐在超出最大容量时正常工作")
    void testLruEvictionWorks() throws Exception {
        Field maxCapacityField = StringInterner.class.getDeclaredField("maxCapacity");
        maxCapacityField.setAccessible(true);
        int maxCapacity = (int) maxCapacityField.get(interner);

        for (int i = 0; i < maxCapacity + 50; i++) {
            interner.intern("string-" + i);
        }

        assertTrue(interner.getEvictionCount() >= 50,
                "应发生至少50次驱逐，实际为: " + interner.getEvictionCount());

        long initialEvictions = interner.getEvictionCount();

        for (int i = 0; i < 10; i++) {
            interner.intern("new-string-" + i);
        }

        assertTrue(interner.getEvictionCount() > initialEvictions,
                "继续添加应继续发生驱逐");
    }

    @Test
    @DisplayName("统计计数器准确")
    void testStatisticsCounters() {
        interner.resetStatistics();

        assertEquals(0, interner.getHitCount());
        assertEquals(0, interner.getMissCount());
        assertEquals(0, interner.getEvictionCount());
        assertEquals(0.0, interner.getHitRate(), 0.001);

        interner.intern("test1");
        assertEquals(0, interner.getHitCount());
        assertEquals(1, interner.getMissCount());
        assertEquals(0.0, interner.getHitRate(), 0.001);

        interner.intern("test1");
        assertEquals(1, interner.getHitCount());
        assertEquals(1, interner.getMissCount());
        assertEquals(0.5, interner.getHitRate(), 0.001);

        interner.intern("test2");
        interner.intern("test1");
        interner.intern("test2");
        assertEquals(2, interner.getHitCount());
        assertEquals(2, interner.getMissCount());
        assertEquals(0.5, interner.getHitRate(), 0.001);

        interner.resetStatistics();
        assertEquals(0, interner.getHitCount());
        assertEquals(0, interner.getMissCount());
        assertEquals(0.0, interner.getHitRate(), 0.001);
    }

    @Test
    @DisplayName("null 处理")
    void testNullHandling() {
        assertNull(interner.intern(null));

        assertDoesNotThrow(() -> interner.intern(null));

        assertEquals(0, interner.getHitCount());
        assertEquals(0, interner.getMissCount());
    }

    @Test
    @DisplayName("边界长度测试")
    void testBoundaryLengths() {
        String exactly64 = "a".repeat(64);
        String s1 = new String(exactly64);
        String s2 = new String(exactly64);

        String interned1 = interner.intern(s1);
        String interned2 = interner.intern(s2);
        assertSame(interned1, interned2);

        String exactly65 = "a".repeat(65);
        String s3 = new String(exactly65);
        String s4 = new String(exactly65);

        String interned3 = interner.intern(s3);
        String interned4 = interner.intern(s4);
        assertNotSame(interned3, interned4);
    }

    @Test
    @DisplayName("clear 方法应清空缓存")
    void testClearMethod() {
        interner.intern("test1");
        interner.intern("test2");

        interner.clear();

        String s1 = new String("test1");
        String s2 = new String("test1");

        interner.intern(s1);
        assertEquals(1, interner.getMissCount());
        assertEquals(0, interner.getHitCount());

        interner.intern(s2);
        assertEquals(1, interner.getHitCount());
    }

    @Test
    @DisplayName("getMaxCapacity 和 getCurrentSize 方法")
    void testCapacityMethods() {
        assertEquals(100, interner.getMaxCapacity());

        interner.intern("test1");
        interner.intern("test2");

        assertTrue(interner.getCurrentSize() >= 2);
    }

    @Test
    @DisplayName("空字符串处理")
    void testEmptyString() {
        String s1 = new String("");
        String s2 = new String("");

        String interned1 = interner.intern(s1);
        String interned2 = interner.intern(s2);

        assertSame(interned1, interned2);
        assertEquals(0, interned1.length());
    }

    @Test
    @DisplayName("高并发场景下的线程安全性")
    void testThreadSafety() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 1000;

        Thread[] threads = new Thread[threadCount];
        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    interner.intern("shared-" + (i % 100));
                    interner.intern("thread-" + threadId + "-" + i);
                }
            });
        }

        for (Thread thread : threads) {
            thread.start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        assertTrue(interner.getHitCount() > 0);
        assertTrue(interner.getMissCount() > 0);

        for (int i = 0; i < 100; i++) {
            String s1 = new String("shared-" + i);
            String s2 = new String("shared-" + i);
            assertSame(interner.intern(s1), interner.intern(s2));
        }
    }
}
