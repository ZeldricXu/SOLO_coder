package com.observability.config.listener;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ConfigChangeListenerManager 测试")
class ConfigChangeListenerManagerTest {

    private ConfigChangeListenerManager manager;

    @BeforeEach
    void setup() {
        manager = new ConfigChangeListenerManager();
    }

    @Nested
    @DisplayName("基础监听器管理测试")
    class BasicListenerTests {

        @Test
        @DisplayName("正常场景：添加并通知监听器")
        void addListener_NotifyIsCalled() {
            AtomicReference<Map<String, Object>> receivedConfig = new AtomicReference<>();
            Consumer<Map<String, Object>> listener = receivedConfig::set;

            manager.addListener("test-ns", listener);

            Map<String, Object> config = new HashMap<>();
            config.put("key", "value");
            manager.notifyListeners("test-ns", config);

            assertThat(receivedConfig.get()).isNotNull();
            assertThat(receivedConfig.get()).containsEntry("key", "value");
        }

        @Test
        @DisplayName("正常场景：移除监听器后不再通知")
        void removeListener_NoLongerNotified() {
            AtomicInteger callCount = new AtomicInteger(0);
            Consumer<Map<String, Object>> listener = config -> callCount.incrementAndGet();

            manager.addListener("test-ns", listener);
            manager.notifyListeners("test-ns", new HashMap<>());
            assertThat(callCount.get()).isEqualTo(1);

            manager.removeListener("test-ns", listener);
            manager.notifyListeners("test-ns", new HashMap<>());
            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常场景：多个监听器都被通知")
        void multipleListeners_AllNotified() {
            AtomicInteger count1 = new AtomicInteger(0);
            AtomicInteger count2 = new AtomicInteger(0);
            AtomicInteger count3 = new AtomicInteger(0);

            manager.addListener("test-ns", config -> count1.incrementAndGet());
            manager.addListener("test-ns", config -> count2.incrementAndGet());
            manager.addListener("test-ns", config -> count3.incrementAndGet());

            manager.notifyListeners("test-ns", new HashMap<>());

            assertThat(count1.get()).isEqualTo(1);
            assertThat(count2.get()).isEqualTo(1);
            assertThat(count3.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("正常场景：监听器按命名空间隔离")
        void listeners_Namespaced_Isolated() {
            AtomicInteger ns1Count = new AtomicInteger(0);
            AtomicInteger ns2Count = new AtomicInteger(0);

            manager.addListener("ns1", config -> ns1Count.incrementAndGet());
            manager.addListener("ns2", config -> ns2Count.incrementAndGet());

            manager.notifyListeners("ns1", new HashMap<>());

            assertThat(ns1Count.get()).isEqualTo(1);
            assertThat(ns2Count.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("正常场景：无监听器时通知不报错")
        void notifyListeners_NoListeners_NoError() {
            manager.notifyListeners("empty-ns", new HashMap<>());
        }
    }

    @Nested
    @DisplayName("异常场景测试")
    class ExceptionTests {

        @Test
        @DisplayName("异常场景：一个监听器失败不影响其他监听器")
        void listenerThrowsException_OtherListenersStillNotified() {
            AtomicInteger successCount = new AtomicInteger(0);

            manager.addListener("test-ns", config -> {
                throw new RuntimeException("Listener failed");
            });
            manager.addListener("test-ns", config -> successCount.incrementAndGet());

            manager.notifyListeners("test-ns", new HashMap<>());

            assertThat(successCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("异常场景：所有监听器都失败")
        void allListenersThrowException_NoPropagation() {
            manager.addListener("test-ns", config -> {
                throw new RuntimeException("First failed");
            });
            manager.addListener("test-ns", config -> {
                throw new RuntimeException("Second failed");
            });

            manager.notifyListeners("test-ns", new HashMap<>());
        }
    }

    @Nested
    @DisplayName("边界条件测试")
    class BoundaryTests {

        @Test
        @DisplayName("边界场景：添加null监听器")
        void addListener_NullListener_NoError() {
            manager.addListener("test-ns", null);
            manager.notifyListeners("test-ns", new HashMap<>());
        }

        @Test
        @DisplayName("边界场景：通知null配置")
        void notifyListeners_NullConfig_Success() {
            AtomicReference<Map<String, Object>> received = new AtomicReference<>();
            manager.addListener("test-ns", received::set);

            manager.notifyListeners("test-ns", null);

            assertThat(received.get()).isNull();
        }

        @Test
        @DisplayName("边界场景：空命名空间")
        void emptyNamespace_Success() {
            AtomicInteger callCount = new AtomicInteger(0);
            manager.addListener("", config -> callCount.incrementAndGet());

            manager.notifyListeners("", new HashMap<>());

            assertThat(callCount.get()).isEqualTo(1);
        }

        @Test
        @DisplayName("边界场景：移除不存在的监听器")
        void removeListener_Nonexistent_NoError() {
            Consumer<Map<String, Object>> listener = config -> {};
            manager.removeListener("nonexistent", listener);
        }

        @Test
        @DisplayName("边界场景：大量监听器")
        void manyListeners_AllNotified() {
            int listenerCount = 1000;
            AtomicInteger totalCalls = new AtomicInteger(0);

            for (int i = 0; i < listenerCount; i++) {
                manager.addListener("test-ns", config -> totalCalls.incrementAndGet());
            }

            manager.notifyListeners("test-ns", new HashMap<>());

            assertThat(totalCalls.get()).isEqualTo(listenerCount);
        }

        @Test
        @DisplayName("边界场景：添加重复监听器")
        void addListener_Duplicate_NotifiedMultipleTimes() {
            AtomicInteger callCount = new AtomicInteger(0);
            Consumer<Map<String, Object>> listener = config -> callCount.incrementAndGet();

            manager.addListener("test-ns", listener);
            manager.addListener("test-ns", listener);

            manager.notifyListeners("test-ns", new HashMap<>());

            assertThat(callCount.get()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("并发场景测试")
    class ConcurrencyTests {

        @Test
        @DisplayName("并发场景：多线程添加监听器")
        void addListener_Concurrent_ThreadSafe() throws InterruptedException {
            int threadCount = 100;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        manager.addListener("concurrent-ns", config -> {});
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errorCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("并发场景：通知时添加监听器")
        void notifyAndAdd_Concurrent_ThreadSafe() throws InterruptedException {
            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < 10; i++) {
                manager.addListener("test-ns", config -> {});
            }

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                new Thread(() -> {
                    try {
                        if (idx % 2 == 0) {
                            manager.addListener("test-ns", config -> {});
                        } else {
                            manager.notifyListeners("test-ns", new HashMap<>());
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(10, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errorCount.get()).isEqualTo(0);
        }

        @Test
        @DisplayName("并发场景：高并发通知")
        void notifyListeners_HighConcurrency_ThreadSafe() throws InterruptedException {
            int threadCount = 200;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);
            AtomicInteger notificationCount = new AtomicInteger(0);

            manager.addListener("hot-ns", config -> notificationCount.incrementAndGet());

            for (int i = 0; i < threadCount; i++) {
                new Thread(() -> {
                    try {
                        manager.notifyListeners("hot-ns", new HashMap<>());
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                }).start();
            }

            boolean completed = latch.await(15, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errorCount.get()).isEqualTo(0);
            assertThat(notificationCount.get()).isEqualTo(threadCount);
        }
    }
}
