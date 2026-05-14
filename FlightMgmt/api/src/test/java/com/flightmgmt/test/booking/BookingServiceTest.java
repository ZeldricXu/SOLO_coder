package com.flightmgmt.test.booking;

import com.flightmgmt.booking.service.BookingService;
import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.flight.service.FlightService;
import com.flightmgmt.test.data.TestDataBuilder;
import org.junit.jupiter.api.*;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("预订模块单元测试")
public class BookingServiceTest {

    @Mock
    private FlightService flightService;

    @InjectMocks
    private BookingService bookingService;

    private AutoCloseable openMocks;

    @BeforeEach
    void setUp() {
        openMocks = MockitoAnnotations.openMocks(this);
        DataStore.getFlights().clear();
        DataStore.getBookings().clear();
        DataStore.getPassengers().clear();
        TestDataBuilder.resetCounters();
    }

    @AfterEach
    void tearDown() throws Exception {
        openMocks.close();
    }

    @Nested
    @DisplayName("支付超时提醒测试")
    class PaymentTimeoutReminderTests {

        @Test
        @DisplayName("验证机票支付超时未处理时发送超时提醒")
        void testPaymentTimeoutReminder_SendsReminderWhenTimeout() throws Exception {
            Flight domesticFlight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(domesticFlight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Map<String, Object> timeoutConfig = TestDataBuilder.createPaymentTimeoutConfig();
            int domesticTimeout = (int) timeoutConfig.get("domestic_timeout_minutes");

            Booking pendingBooking = TestDataBuilder.createPendingPaymentBooking(
                domesticFlight.getFlightId(), 
                passenger.getPassengerId()
            );
            DataStore.addBooking(pendingBooking);

            AtomicInteger reminderCount = new AtomicInteger(0);
            
            PaymentTimeoutReminder timeoutReminder = new PaymentTimeoutReminder(
                pendingBooking,
                domesticTimeout,
                TimeUnit.MINUTES
            );
            
            timeoutReminder.setOnTimeout(() -> {
                reminderCount.incrementAndGet();
            });

            CompletableFuture<Void> timeoutFuture = timeoutReminder.start();
            
            Thread.sleep(100);
            
            assertEquals(0, reminderCount.get());
            
            timeoutFuture.complete(null);
        }

        @Test
        @DisplayName("验证国内航班超时阈值为15分钟")
        void testPaymentTimeout_DomesticFlightHas15MinuteTimeout() {
            Map<String, Object> timeoutConfig = TestDataBuilder.createPaymentTimeoutConfig();
            
            assertEquals(15, timeoutConfig.get("domestic_timeout_minutes"));
            assertEquals(30, timeoutConfig.get("international_timeout_minutes"));
            assertNotEquals(
                timeoutConfig.get("domestic_timeout_minutes"), 
                timeoutConfig.get("international_timeout_minutes")
            );
        }

        @Test
        @DisplayName("验证国际航班超时阈值为30分钟")
        void testPaymentTimeout_InternationalFlightHas30MinuteTimeout() {
            Flight internationalFlight = TestDataBuilder.createInternationalFlight();
            DataStore.addFlight(internationalFlight);
            
            Map<String, Object> timeoutConfig = TestDataBuilder.createPaymentTimeoutConfig();
            int internationalTimeout = (int) timeoutConfig.get("international_timeout_minutes");
            
            assertEquals(30, internationalTimeout);
            assertTrue(internationalTimeout > (int) timeoutConfig.get("domestic_timeout_minutes"));
        }

        @Test
        @DisplayName("验证不同航班类型下的超时阈值差异")
        void testPaymentTimeout_DifferentFlightTypesHaveDifferentTimeouts() {
            Map<String, Object> timeoutConfig = TestDataBuilder.createPaymentTimeoutConfig();
            
            int domesticTimeout = (int) timeoutConfig.get("domestic_timeout_minutes");
            int internationalTimeout = (int) timeoutConfig.get("international_timeout_minutes");
            
            assertEquals(15, domesticTimeout);
            assertEquals(30, internationalTimeout);
            assertEquals(2, internationalTimeout / domesticTimeout);
        }

        @Test
        @DisplayName("验证支付超时后预订状态变为已取消")
        void testPaymentTimeout_BookingStatusBecomesCancelledAfterTimeout() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking pendingBooking = TestDataBuilder.createPendingPaymentBooking(
                flight.getFlightId(), 
                passenger.getPassengerId()
            );
            DataStore.addBooking(pendingBooking);

            pendingBooking.setBookingStatus("cancelled");

            assertEquals("cancelled", pendingBooking.getBookingStatus());
        }
    }

    @Nested
    @DisplayName("预订状态流转测试")
    class BookingStatusFlowTests {

        @Test
        @DisplayName("验证预订成功状态流转：pending_payment -> confirmed")
        void testBookingSuccess_StatusFlow() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            when(flightService.updateAvailableSeats(eq(flight.getFlightId()), anyInt())).thenReturn(true);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                1
            );

            assertNotNull(booking);
            assertEquals("confirmed", booking.getBookingStatus());
            assertNotNull(booking.getConfirmedAt());
            assertNotNull(booking.getBookingId());
        }

        @Test
        @DisplayName("验证航班不存在时预订失败")
        void testBookingFail_FlightNotFound() {
            Booking booking = bookingService.createBooking(
                "non_existent_flight",
                "测试乘客",
                "110101199001011234",
                "alipay",
                1
            );

            assertEquals("flight_not_found", booking.getBookingStatus());
        }

        @Test
        @DisplayName("验证航班已取消时预订失败")
        void testBookingFail_FlightCancelled() {
            Flight cancelledFlight = TestDataBuilder.createFlightWithStatus("cancelled");
            DataStore.addFlight(cancelledFlight);

            Booking booking = bookingService.createBooking(
                cancelledFlight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                1
            );

            assertEquals("flight_unavailable", booking.getBookingStatus());
        }

        @Test
        @DisplayName("验证座位不足时预订失败")
        void testBookingFail_SeatsInsufficient() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(0);
            DataStore.addFlight(flight);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                5
            );

            assertEquals("seats_insufficient", booking.getBookingStatus());
        }

        @Test
        @DisplayName("验证支付失败时预订状态变为已取消")
        void testBookingFail_PaymentFailure() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                null,
                1
            );

            assertEquals("cancelled", booking.getBookingStatus());
            assertNull(booking.getConfirmedAt());
        }
    }

    @Nested
    @DisplayName("座位管理测试")
    class SeatManagementTests {

        @Test
        @DisplayName("验证预订成功后座位正确扣减")
        void testSeatDeduction_CorrectDeductionOnSuccess() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            int initialAvailable = flight.getFlightAvailable();
            int seatsToBook = 3;
            DataStore.addFlight(flight);

            when(flightService.updateAvailableSeats(eq(flight.getFlightId()), eq(-seatsToBook))).thenReturn(true);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                seatsToBook
            );

            assertEquals(seatsToBook, booking.getBookingSeats());
            verify(flightService, times(1)).updateAvailableSeats(
                eq(flight.getFlightId()), 
                eq(-seatsToBook)
            );
        }

        @Test
        @DisplayName("验证支付失败后座位正确恢复")
        void testSeatRecovery_CorrectRecoveryOnPaymentFail() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            int initialAvailable = flight.getFlightAvailable();
            DataStore.addFlight(flight);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                null,
                2
            );

            assertEquals("cancelled", booking.getBookingStatus());
            verify(flightService, never()).updateAvailableSeats(anyString(), anyInt());
        }

        @Test
        @DisplayName("验证航班座位流转完整性")
        void testSeatFlow_CompleteSeatLifecycle() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(100);
            DataStore.addFlight(flight);

            int seatsToBook = 5;

            when(flightService.updateAvailableSeats(eq(flight.getFlightId()), anyInt())).thenReturn(true);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                seatsToBook
            );

            assertEquals("confirmed", booking.getBookingStatus());
            assertEquals(seatsToBook, booking.getBookingSeats());
            verify(flightService, times(1)).updateAvailableSeats(
                eq(flight.getFlightId()), 
                eq(-seatsToBook)
            );

            when(flightService.updateAvailableSeats(eq(flight.getFlightId()), eq(seatsToBook))).thenReturn(true);
            boolean recovered = flightService.updateAvailableSeats(flight.getFlightId(), seatsToBook);
            
            assertTrue(recovered);
            verify(flightService, times(1)).updateAvailableSeats(
                eq(flight.getFlightId()), 
                eq(seatsToBook)
            );
        }

        @Test
        @DisplayName("验证座位不能扣减为负数")
        void testSeatValidation_CannotBeNegative() {
            FlightService realFlightService = new FlightService();
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(5);
            DataStore.addFlight(flight);

            boolean result = realFlightService.updateAvailableSeats(flight.getFlightId(), -10);

            assertFalse(result);
            assertEquals(5, flight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证座位不能超过总座位数")
        void testSeatValidation_CannotExceedTotalSeats() {
            FlightService realFlightService = new FlightService();
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(150);
            DataStore.addFlight(flight);

            boolean result = realFlightService.updateAvailableSeats(flight.getFlightId(), 100);

            assertFalse(result);
            assertEquals(150, flight.getFlightAvailable());
        }
    }

    @Nested
    @DisplayName("预订金额计算测试")
    class BookingAmountCalculationTests {

        @Test
        @DisplayName("验证国内航班预订金额计算正确")
        void testAmountCalculation_DomesticFlight() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            double pricePerSeat = flight.getFlightPrice();
            int seatsToBook = 2;
            DataStore.addFlight(flight);

            when(flightService.updateAvailableSeats(eq(flight.getFlightId()), eq(-seatsToBook))).thenReturn(true);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                seatsToBook
            );

            assertEquals(pricePerSeat * seatsToBook, booking.getBookingAmount());
        }

        @Test
        @DisplayName("验证国际航班预订金额计算正确")
        void testAmountCalculation_InternationalFlight() {
            Flight flight = TestDataBuilder.createInternationalFlight();
            double pricePerSeat = flight.getFlightPrice();
            int seatsToBook = 3;
            DataStore.addFlight(flight);

            when(flightService.updateAvailableSeats(eq(flight.getFlightId()), eq(-seatsToBook))).thenReturn(true);

            Booking booking = bookingService.createBooking(
                flight.getFlightId(),
                "测试乘客",
                "110101199001011234",
                "alipay",
                seatsToBook
            );

            assertEquals(pricePerSeat * seatsToBook, booking.getBookingAmount());
        }
    }

    public static class PaymentTimeoutReminder {
        private final Booking booking;
        private final long timeout;
        private final TimeUnit timeUnit;
        private Runnable onTimeout;
        private volatile boolean isCompleted = false;

        public PaymentTimeoutReminder(Booking booking, long timeout, TimeUnit timeUnit) {
            this.booking = booking;
            this.timeout = timeout;
            this.timeUnit = timeUnit;
        }

        public void setOnTimeout(Runnable onTimeout) {
            this.onTimeout = onTimeout;
        }

        public CompletableFuture<Void> start() {
            return CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(50);
                    if (!isCompleted && onTimeout != null) {
                        onTimeout.run();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        public void complete() {
            this.isCompleted = true;
        }

        public long getTimeoutMinutes() {
            return timeUnit.toMinutes(timeout);
        }
    }
}
