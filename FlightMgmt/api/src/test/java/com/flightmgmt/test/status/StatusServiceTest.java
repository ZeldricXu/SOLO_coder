package com.flightmgmt.test.status;

import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.status.service.StatusService;
import com.flightmgmt.test.data.TestDataBuilder;
import org.junit.jupiter.api.*;
import org.mockito.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("状态模块单元测试")
public class StatusServiceTest {

    private StatusService statusService;

    @BeforeEach
    void setUp() {
        statusService = new StatusService();
        DataStore.getFlights().clear();
        DataStore.getBookings().clear();
        DataStore.getPassengers().clear();
        DataStore.getStatusHistory().clear();
        DataStore.getFlightStatuses().clear();
        TestDataBuilder.resetCounters();
    }

    @Nested
    @DisplayName("通知确认机制测试")
    class NotificationConfirmationTests {

        @Test
        @DisplayName("验证状态通知发送后等待乘客确认")
        void testNotification_SendsNotificationAndWaitsForConfirmation() throws Exception {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);
            
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);
            
            Booking booking = TestDataBuilder.createConfirmedBooking(flight.getFlightId(), passenger.getPassengerId());
            DataStore.addBooking(booking);

            NotificationManager notificationManager = new NotificationManager();
            notificationManager.setNotificationSender(message -> {
                System.out.println("发送通知: " + message);
            });

            AtomicInteger confirmations = new AtomicInteger(0);
            notificationManager.sendNotification(passenger, "测试通知", "测试内容");
            
            assertEquals(1, notificationManager.getSentCount());

            notificationManager.confirmReceived(notificationManager.getLastNotificationId());
            
            assertTrue(notificationManager.isConfirmed(notificationManager.getLastNotificationId()));
        }

        @Test
        @DisplayName("验证未确认通知重试发送机制")
        void testNotification_RetriesUnconfirmedNotifications() throws Exception {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            NotificationManager notificationManager = new NotificationManager();
            AtomicInteger sendCount = new AtomicInteger(0);
            
            notificationManager.setNotificationSender(message -> {
                sendCount.incrementAndGet();
            });

            Map<String, Object> config = TestDataBuilder.createNotificationConfig();
            int maxRetries = (int) config.get("max_retry_attempts");
            notificationManager.setMaxRetryAttempts(maxRetries);

            Passenger passenger = TestDataBuilder.createPassenger();
            notificationManager.sendNotification(passenger, "延误通知", "航班延误30分钟");

            String notificationId = notificationManager.getLastNotificationId();
            
            for (int i = 0; i < maxRetries; i++) {
                notificationManager.retryIfNotConfirmed();
            }

            assertEquals(maxRetries + 1, sendCount.get());
        }

        @Test
        @DisplayName("验证达到最大重试次数后停止重试")
        void testNotification_StopsRetryingAfterMaxAttempts() {
            NotificationManager notificationManager = new NotificationManager();
            AtomicInteger sendCount = new AtomicInteger(0);
            
            notificationManager.setNotificationSender(message -> {
                sendCount.incrementAndGet();
            });

            Map<String, Object> config = TestDataBuilder.createNotificationConfig();
            int maxRetries = (int) config.get("max_retry_attempts");
            notificationManager.setMaxRetryAttempts(maxRetries);

            Passenger passenger = TestDataBuilder.createPassenger();
            notificationManager.sendNotification(passenger, "测试", "测试内容");

            for (int i = 0; i < maxRetries + 5; i++) {
                notificationManager.retryIfNotConfirmed();
            }

            assertEquals(maxRetries + 1, sendCount.get());
        }

        @Test
        @DisplayName("验证确认后不再重试")
        void testNotification_NoRetryAfterConfirmation() {
            NotificationManager notificationManager = new NotificationManager();
            AtomicInteger sendCount = new AtomicInteger(0);
            
            notificationManager.setNotificationSender(message -> {
                sendCount.incrementAndGet();
            });

            Map<String, Object> config = TestDataBuilder.createNotificationConfig();
            int maxRetries = (int) config.get("max_retry_attempts");
            notificationManager.setMaxRetryAttempts(maxRetries);

            Passenger passenger = TestDataBuilder.createPassenger();
            notificationManager.sendNotification(passenger, "测试", "测试内容");

            String notificationId = notificationManager.getLastNotificationId();
            notificationManager.confirmReceived(notificationId);

            for (int i = 0; i < maxRetries; i++) {
                notificationManager.retryIfNotConfirmed();
            }

            assertEquals(1, sendCount.get());
        }
    }

    @Nested
    @DisplayName("状态通知正确性测试")
    class StatusNotificationCorrectnessTests {

        @Test
        @DisplayName("验证延误状态通知的正确性")
        void testDelayNotification_CorrectTypeAndDetail() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(flight.getFlightId(), passenger.getPassengerId());
            DataStore.addBooking(booking);

            FlightStatus status = statusService.updateFlightStatus(
                flight.getFlightId(),
                "delay",
                "航班延误30分钟，因天气原因"
            );

            assertNotNull(status);
            assertEquals("delay", status.getStatusType());
            assertEquals("航班延误30分钟，因天气原因", status.getStatusDetail());
            assertNotNull(status.getStatusTime());
        }

        @Test
        @DisplayName("验证延误后航班状态更新为delayed")
        void testDelayNotification_FlightStatusUpdated() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "delay",
                "航班延误30分钟"
            );

            assertEquals("delayed", flight.getFlightStatus());
        }

        @Test
        @DisplayName("验证取消状态通知的正确性")
        void testCancelNotification_CorrectTypeAndDetail() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(flight.getFlightId(), passenger.getPassengerId());
            DataStore.addBooking(booking);

            FlightStatus status = statusService.updateFlightStatus(
                flight.getFlightId(),
                "cancelled",
                "航班因机械故障取消"
            );

            assertNotNull(status);
            assertEquals("cancelled", status.getStatusType());
            assertEquals("航班因机械故障取消", status.getStatusDetail());
        }

        @Test
        @DisplayName("验证取消后航班状态更新为cancelled")
        void testCancelNotification_FlightStatusUpdated() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "cancelled",
                "航班取消"
            );

            assertEquals("cancelled", flight.getFlightStatus());
        }

        @Test
        @DisplayName("验证取消后预订状态更新为refunded")
        void testCancelNotification_BookingStatusUpdated() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(flight.getFlightId(), passenger.getPassengerId());
            DataStore.addBooking(booking);

            assertEquals("confirmed", booking.getBookingStatus());

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "cancelled",
                "航班取消"
            );

            assertEquals("refunded", booking.getBookingStatus());
        }

        @Test
        @DisplayName("验证正常状态通知的正确性")
        void testNormalNotification_CorrectTypeAndDetail() {
            Flight flight = TestDataBuilder.createFlightWithStatus("delayed");
            DataStore.addFlight(flight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(flight.getFlightId(), passenger.getPassengerId());
            DataStore.addBooking(booking);

            FlightStatus status = statusService.updateFlightStatus(
                flight.getFlightId(),
                "on_time",
                "航班正点运行"
            );

            assertNotNull(status);
            assertEquals("on_time", status.getStatusType());
            assertEquals("航班正点运行", status.getStatusDetail());
        }

        @Test
        @DisplayName("验证正常后航班状态更新为on_time")
        void testNormalNotification_FlightStatusUpdated() {
            Flight flight = TestDataBuilder.createFlightWithStatus("delayed");
            DataStore.addFlight(flight);

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "on_time",
                "航班正点运行"
            );

            assertEquals("on_time", flight.getFlightStatus());
        }

        @Test
        @DisplayName("验证航班不存在时状态更新失败")
        void testStatusUpdate_FailsForNonExistentFlight() {
            FlightStatus status = statusService.updateFlightStatus(
                "non_existent_flight",
                "delay",
                "测试"
            );

            assertNull(status);
        }
    }

    @Nested
    @DisplayName("状态通知异步化测试")
    class AsyncNotificationTests {

        @Test
        @DisplayName("验证状态通知异步发送")
        void testAsyncNotification_SendsInBackground() throws Exception {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            AsyncNotificationManager asyncManager = new AsyncNotificationManager();
            AtomicInteger sendCount = new AtomicInteger(0);
            List<String> sentMessages = new CopyOnWriteArrayList<>();

            asyncManager.setNotificationSender(message -> {
                try {
                    Thread.sleep(10);
                    sentMessages.add(message);
                    sendCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            Passenger passenger = TestDataBuilder.createPassenger();

            long startTime = System.currentTimeMillis();
            CompletableFuture<Void> future = asyncManager.sendAsyncNotification(
                passenger,
                "异步通知",
                "这是异步通知内容"
            );

            assertTrue(System.currentTimeMillis() - startTime < 50);

            future.get(5, TimeUnit.SECONDS);

            assertEquals(1, sendCount.get());
            assertFalse(sentMessages.isEmpty());
        }

        @Test
        @DisplayName("验证多个通知异步并行发送")
        void testAsyncNotification_MultipleNotificationsParallel() throws Exception {
            AsyncNotificationManager asyncManager = new AsyncNotificationManager();
            AtomicInteger sendCount = new AtomicInteger(0);

            asyncManager.setNotificationSender(message -> {
                try {
                    Thread.sleep(50);
                    sendCount.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            List<CompletableFuture<Void>> futures = new ArrayList<>();
            long startTime = System.currentTimeMillis();

            for (int i = 0; i < 5; i++) {
                Passenger passenger = TestDataBuilder.createPassenger();
                futures.add(asyncManager.sendAsyncNotification(
                    passenger,
                    "通知" + i,
                    "内容" + i
                ));
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();

            long totalTime = System.currentTimeMillis() - startTime;
            assertEquals(5, sendCount.get());
            assertTrue(totalTime < 500);
        }

        @Test
        @DisplayName("验证异步通知异常处理")
        void testAsyncNotification_HandlesExceptions() throws Exception {
            AsyncNotificationManager asyncManager = new AsyncNotificationManager();
            AtomicInteger errorCount = new AtomicInteger(0);

            asyncManager.setNotificationSender(message -> {
                throw new RuntimeException("模拟发送失败");
            });

            asyncManager.setErrorHandler((msg, ex) -> {
                errorCount.incrementAndGet();
            });

            Passenger passenger = TestDataBuilder.createPassenger();
            CompletableFuture<Void> future = asyncManager.sendAsyncNotification(
                passenger,
                "测试",
                "测试内容"
            );

            future.get(5, TimeUnit.SECONDS);

            assertEquals(1, errorCount.get());
        }
    }

    @Nested
    @DisplayName("状态历史记录测试")
    class StatusHistoryTests {

        @Test
        @DisplayName("验证状态历史正确记录")
        void testStatusHistory_CorrectlyRecorded() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "delay",
                "第一次延误"
            );

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "delay",
                "第二次延误"
            );

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "on_time",
                "恢复正常"
            );

            List<FlightStatus> history = statusService.getFlightStatusHistory(flight.getFlightId());

            assertEquals(3, history.size());
            assertEquals("delay", history.get(0).getStatusType());
            assertEquals("on_time", history.get(2).getStatusType());
        }

        @Test
        @DisplayName("验证最新状态获取正确")
        void testStatusHistory_LatestStatus() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "delay",
                "延误"
            );

            statusService.updateFlightStatus(
                flight.getFlightId(),
                "on_time",
                "恢复"
            );

            FlightStatus latest = statusService.getLatestFlightStatus(flight.getFlightId());

            assertNotNull(latest);
            assertEquals("on_time", latest.getStatusType());
            assertEquals("恢复", latest.getStatusDetail());
        }
    }

    public static class NotificationManager {
        private Consumer<String> notificationSender;
        private int maxRetryAttempts = 3;
        private final Map<String, NotificationRecord> notifications = new ConcurrentHashMap<>();
        private String lastNotificationId;

        public void setNotificationSender(Consumer<String> sender) {
            this.notificationSender = sender;
        }

        public void setMaxRetryAttempts(int max) {
            this.maxRetryAttempts = max;
        }

        public void sendNotification(Passenger passenger, String title, String content) {
            String notificationId = "notif_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 8);
            this.lastNotificationId = notificationId;

            NotificationRecord record = new NotificationRecord(notificationId, passenger, title, content);
            notifications.put(notificationId, record);

            if (notificationSender != null) {
                notificationSender.accept(title + ": " + content);
            }
            record.incrementAttempts();
        }

        public void retryIfNotConfirmed() {
            for (NotificationRecord record : notifications.values()) {
                if (!record.isConfirmed() && record.getAttempts() <= maxRetryAttempts) {
                    if (notificationSender != null) {
                        notificationSender.accept("[重试] " + record.getTitle() + ": " + record.getContent());
                    }
                    record.incrementAttempts();
                }
            }
        }

        public void confirmReceived(String notificationId) {
            NotificationRecord record = notifications.get(notificationId);
            if (record != null) {
                record.setConfirmed(true);
            }
        }

        public boolean isConfirmed(String notificationId) {
            NotificationRecord record = notifications.get(notificationId);
            return record != null && record.isConfirmed();
        }

        public int getSentCount() {
            return (int) notifications.values().stream()
                .mapToInt(NotificationRecord::getAttempts)
                .sum();
        }

        public String getLastNotificationId() {
            return lastNotificationId;
        }
    }

    public static class NotificationRecord {
        private final String id;
        private final Passenger passenger;
        private final String title;
        private final String content;
        private int attempts = 0;
        private boolean confirmed = false;

        public NotificationRecord(String id, Passenger passenger, String title, String content) {
            this.id = id;
            this.passenger = passenger;
            this.title = title;
            this.content = content;
        }

        public String getId() { return id; }
        public Passenger getPassenger() { return passenger; }
        public String getTitle() { return title; }
        public String getContent() { return content; }
        public int getAttempts() { return attempts; }
        public void incrementAttempts() { this.attempts++; }
        public boolean isConfirmed() { return confirmed; }
        public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }
    }

    public static class AsyncNotificationManager {
        private Consumer<String> notificationSender;
        private BiConsumer<String, Exception> errorHandler;
        private final ExecutorService executor = Executors.newCachedThreadPool();

        public void setNotificationSender(Consumer<String> sender) {
            this.notificationSender = sender;
        }

        public void setErrorHandler(BiConsumer<String, Exception> handler) {
            this.errorHandler = handler;
        }

        public CompletableFuture<Void> sendAsyncNotification(Passenger passenger, String title, String content) {
            return CompletableFuture.runAsync(() -> {
                try {
                    if (notificationSender != null) {
                        notificationSender.accept(title + ": " + content);
                    }
                } catch (Exception e) {
                    if (errorHandler != null) {
                        errorHandler.accept(title + ": " + content, e);
                    }
                }
            }, executor);
        }
    }

    @FunctionalInterface
    public interface BiConsumer<T, U> {
        void accept(T t, U u);
    }
}
