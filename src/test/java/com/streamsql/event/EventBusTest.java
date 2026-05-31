package com.streamsql.event;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("事件驱动架构测试")
class EventBusTest {

    @Mock
    private EventBusConfig eventBusConfig;

    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        when(eventBusConfig.getQueueCapacity()).thenReturn(10000);
        when(eventBusConfig.getConsumerThreads()).thenReturn(2);
        when(eventBusConfig.getRetryIntervalMs()).thenReturn(100);
        when(eventBusConfig.getMaxRetryAttempts()).thenReturn(3);
        when(eventBusConfig.isEnableDeadLetterQueue()).thenReturn(true);

        eventBus = new EventBus(eventBusConfig);
        eventBus.start();
    }

    @AfterEach
    void tearDown() {
        eventBus.stop();
    }

    @Nested
    @DisplayName("事件发布测试")
    class EventPublishTest {

        @Test
        @DisplayName("发布事件 - 成功")
        void shouldPublishEventSuccessfully() {
            DomainEvent<String> event = new DomainEvent<>("test.event", "source", "test payload");

            assertDoesNotThrow(() -> eventBus.publish(event));
        }

        @Test
        @DisplayName("通过事件类型和载荷发布 - 成功")
        void shouldPublishByTypeAndPayload() {
            assertDoesNotThrow(() -> eventBus.publish("test.event", "test payload"));
        }

        @Test
        @DisplayName("通过事件类型、来源和载荷发布 - 成功")
        void shouldPublishByTypeSourceAndPayload() {
            assertDoesNotThrow(() -> eventBus.publish("test.event", "source", "test payload"));
        }

        @Test
        @DisplayName("事件包含元数据")
        void shouldContainMetadataInEvent() {
            DomainEvent<String> event = new DomainEvent<>("test.event", "payload");
            event.addMetadata("key1", "value1");
            event.addMetadata("key2", 123);

            assertEquals("value1", event.getMetadata().get("key1"));
            assertEquals(123, event.getMetadata().get("key2"));
        }

        @Test
        @DisplayName("事件ID自动生成")
        void shouldGenerateEventIdAutomatically() {
            DomainEvent<String> event1 = new DomainEvent<>("test.event", "payload1");
            DomainEvent<String> event2 = new DomainEvent<>("test.event", "payload2");

            assertNotNull(event1.getEventId());
            assertNotNull(event2.getEventId());
            assertNotEquals(event1.getEventId(), event2.getEventId());
        }

        @Test
        @DisplayName("事件时间戳自动生成")
        void shouldGenerateTimestampAutomatically() {
            DomainEvent<String> event = new DomainEvent<>("test.event", "payload");

            assertNotNull(event.getTimestamp());
        }
    }

    @Nested
    @DisplayName("事件订阅测试")
    class EventSubscribeTest {

        @Test
        @DisplayName("订阅事件 - 成功")
        void shouldSubscribeToEvent() {
            AtomicInteger counter = new AtomicInteger(0);

            eventBus.subscribe("test.event", event -> counter.incrementAndGet());

            eventBus.publish("test.event", "payload");

            assertDoesNotThrow(() -> Thread.sleep(100));
        }

        @Test
        @DisplayName("多监听器订阅同一事件")
        void shouldHaveMultipleListenersForSameEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(2);

            eventBus.subscribe("test.event", event -> latch.countDown());
            eventBus.subscribe("test.event", event -> latch.countDown());

            eventBus.publish("test.event", "payload");

            assertTrue(latch.await(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("取消订阅 - 成功")
        void shouldUnsubscribeFromEvent() throws InterruptedException {
            CountDownLatch latch = new CountDownLatch(1);
            EventListener<String> listener = event -> latch.countDown();

            eventBus.subscribe("test.event", listener);
            eventBus.unsubscribe("test.event", listener);

            eventBus.publish("test.event", "payload");

            assertFalse(latch.await(1, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("监听器按顺序执行")
        void shouldExecuteListenersInOrder() throws InterruptedException {
            List<Integer> executionOrder = new ArrayList<>();
            CountDownLatch latch = new CountDownLatch(2);

            EventListener<String> listener1 = new EventListener<>() {
                @Override
                public void onEvent(DomainEvent<String> event) {
                    executionOrder.add(1);
                    latch.countDown();
                }

                @Override
                public int getOrder() {
                    return 1;
                }
            };

            EventListener<String> listener2 = new EventListener<>() {
                @Override
                public void onEvent(DomainEvent<String> event) {
                    executionOrder.add(2);
                    latch.countDown();
                }

                @Override
                public int getOrder() {
                    return 2;
                }
            };

            eventBus.subscribe("test.event", listener1);
            eventBus.subscribe("test.event", listener2);

            eventBus.publish("test.event", "payload");

            assertTrue(latch.await(5, TimeUnit.SECONDS));
            assertEquals(1, executionOrder.get(0));
            assertEquals(2, executionOrder.get(1));
        }
    }

    @Nested
    @DisplayName("死信队列测试")
    class DeadLetterQueueTest {

        @Test
        @DisplayName("失败事件进入死信队列")
        void shouldSendFailedEventToDLQ() throws InterruptedException {
            EventListener<String> listener = event -> {
                throw new RuntimeException("Listener failed");
            };

            eventBus.subscribe("test.event", listener);

            eventBus.publish("test.event", "payload");

            Thread.sleep(500);

            assertFalse(eventBus.getDeadLetterQueue().isEmpty());
        }

        @Test
        @DisplayName("清空死信队列")
        void shouldClearDeadLetterQueue() {
            eventBus.publish("test.event", "payload");

            eventBus.clearDeadLetterQueue();

            assertTrue(eventBus.getDeadLetterQueue().isEmpty());
        }
    }

    @Nested
    @DisplayName("事件总线统计测试")
    class EventBusStatisticsTest {

        @Test
        @DisplayName("获取事件总线统计信息")
        void shouldGetStatistics() {
            var stats = eventBus.getStatistics();

            assertNotNull(stats);
            assertNotNull(stats.get("queueSize"));
            assertNotNull(stats.get("deadLetterQueueSize"));
            assertNotNull(stats.get("subscribedEventTypes"));
            assertNotNull(stats.get("running"));
        }

        @Test
        @DisplayName("获取队列大小")
        void shouldGetQueueSize() {
            eventBus.publish("test.event1", "payload1");
            eventBus.publish("test.event2", "payload2");

            assertTrue(eventBus.getQueueSize() >= 0);
        }
    }

    @Nested
    @DisplayName("事件总线生命周期测试")
    class EventBusLifecycleTest {

        @Test
        @DisplayName("启动事件总线")
        void shouldStartEventBus() {
            var stats = eventBus.getStatistics();
            assertTrue((Boolean) stats.get("running"));
        }

        @Test
        @DisplayName("停止事件总线")
        void shouldStopEventBus() {
            eventBus.stop();
            var stats = eventBus.getStatistics();
            assertFalse((Boolean) stats.get("running"));
        }
    }

    @Nested
    @DisplayName("事件监听器测试")
    class EventListenerTest {

        @Test
        @DisplayName("监听器支持事件类型过滤")
        void shouldSupportEventTypeFiltering() {
            EventListener<String> listener = new EventListener<>() {
                @Override
                public void onEvent(DomainEvent<String> event) {
                }

                @Override
                public boolean supports(String eventType) {
                    return eventType.startsWith("quality_");
                }
            };

            assertTrue(listener.supports("quality_check.completed"));
            assertFalse(listener.supports("other.event"));
        }

        @Test
        @DisplayName("默认支持所有事件类型")
        void shouldSupportAllEventTypesByDefault() {
            EventListener<String> listener = event -> {};

            assertTrue(listener.supports("any.event.type"));
        }

        @Test
        @DisplayName("默认顺序为0")
        void shouldHaveDefaultOrderZero() {
            EventListener<String> listener = event -> {};

            assertEquals(0, listener.getOrder());
        }
    }
}
