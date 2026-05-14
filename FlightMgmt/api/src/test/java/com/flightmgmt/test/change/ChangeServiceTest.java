package com.flightmgmt.test.change;

import com.flightmgmt.change.service.ChangeService;
import com.flightmgmt.common.model.*;
import com.flightmgmt.common.util.DataStore;
import com.flightmgmt.flight.service.FlightService;
import com.flightmgmt.test.data.TestDataBuilder;
import org.junit.jupiter.api.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("退改模块单元测试")
public class ChangeServiceTest {

    private ChangeService changeService;
    private FlightService flightService;

    @BeforeEach
    void setUp() {
        changeService = new ChangeService();
        flightService = new FlightService();
        DataStore.getFlights().clear();
        DataStore.getBookings().clear();
        DataStore.getPassengers().clear();
        DataStore.getChangeRecords().clear();
        DataStore.getChangeHistory().clear();
        TestDataBuilder.resetCounters();
    }

    @Nested
    @DisplayName("退票金额计算测试")
    class RefundAmountCalculationTests {

        @Test
        @DisplayName("验证国内航班退票手续费计算正确（10%费率）")
        void testRefundCalculation_DomesticFlight10PercentFee() {
            Flight domesticFlight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(domesticFlight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);
            Booking booking = TestDataBuilder.createConfirmedBooking(domesticFlight.getFlightId(), passenger.getPassengerId());
            DataStore.addBooking(booking);

            double originalAmount = booking.getBookingAmount();
            Map<String, Object> refundConfig = TestDataBuilder.createRefundRuleConfig();
            double feeRate = (double) refundConfig.get("domestic_fee_rate");

            RefundCalculator calculator = new RefundCalculator();
            double refundAmount = calculator.calculateRefund(originalAmount, feeRate);

            double expectedFee = originalAmount * feeRate;
            double expectedRefund = originalAmount - expectedFee;

            assertEquals(expectedRefund, refundAmount, 0.01);
            assertEquals(originalAmount * 0.90, refundAmount, 0.01);
        }

        @Test
        @DisplayName("验证国际航班退票手续费计算正确（20%费率）")
        void testRefundCalculation_InternationalFlight20PercentFee() {
            Flight internationalFlight = TestDataBuilder.createInternationalFlight();
            DataStore.addFlight(internationalFlight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            double originalAmount = 5000.0;
            Map<String, Object> refundConfig = TestDataBuilder.createRefundRuleConfig();
            double feeRate = (double) refundConfig.get("international_fee_rate");

            RefundCalculator calculator = new RefundCalculator();
            double refundAmount = calculator.calculateRefund(originalAmount, feeRate);

            double expectedFee = originalAmount * feeRate;
            double expectedRefund = originalAmount - expectedFee;

            assertEquals(expectedRefund, refundAmount, 0.01);
            assertEquals(originalAmount * 0.80, refundAmount, 0.01);
        }

        @Test
        @DisplayName("验证临近起飞时退票手续费增加（50%费率）")
        void testRefundCalculation_LastMinuteCancellation() {
            Map<String, Object> refundConfig = TestDataBuilder.createRefundRuleConfig();
            double lastMinuteFeeRate = (double) refundConfig.get("last_minute_fee_rate");
            int freeCancelHours = (int) refundConfig.get("free_cancel_hours");

            assertEquals(0.50, lastMinuteFeeRate, 0.01);
            assertEquals(72, freeCancelHours);

            double originalAmount = 1000.0;
            RefundCalculator calculator = new RefundCalculator();
            double refundAmount = calculator.calculateRefund(originalAmount, lastMinuteFeeRate);

            assertEquals(originalAmount * 0.50, refundAmount, 0.01);
        }

        @Test
        @DisplayName("验证多座位退票金额计算正确")
        void testRefundCalculation_MultipleSeats() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = new Booking();
            booking.setBookingId("multi_seat_booking");
            booking.setFlightId(flight.getFlightId());
            booking.setPassengerId(passenger.getPassengerId());
            booking.setBookingSeats(5);
            booking.setBookingAmount(flight.getFlightPrice() * 5);
            booking.setBookingStatus("confirmed");
            DataStore.addBooking(booking);

            Map<String, Object> refundConfig = TestDataBuilder.createRefundRuleConfig();
            double feeRate = (double) refundConfig.get("domestic_fee_rate");

            RefundCalculator calculator = new RefundCalculator();
            double refundAmount = calculator.calculateRefund(booking.getBookingAmount(), feeRate);

            double perSeatPrice = flight.getFlightPrice();
            double totalOriginal = perSeatPrice * 5;
            double expectedRefund = totalOriginal * (1 - feeRate);

            assertEquals(expectedRefund, refundAmount, 0.01);
        }

        @Test
        @DisplayName("验证退票手续费不能超过原始金额")
        void testRefundCalculation_FeeCannotExceedOriginal() {
            double originalAmount = 100.0;
            double feeRate = 1.5;

            RefundCalculator calculator = new RefundCalculator();
            double refundAmount = calculator.calculateRefund(originalAmount, feeRate);

            assertTrue(refundAmount >= 0);
        }
    }

    @Nested
    @DisplayName("改签差价计算测试")
    class RebookingPriceDifferenceTests {

        @Test
        @DisplayName("验证改签价格上涨时计算补差金额")
        void testRebooking_PriceIncreaseCalculatesDifference() {
            Flight oldFlight = TestDataBuilder.createDomesticFlight();
            oldFlight.setFlightPrice(800.0);
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createDomesticFlight();
            newFlight.setFlightPrice(1200.0);
            DataStore.addFlight(newFlight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            int seats = 2;
            double oldPrice = oldFlight.getFlightPrice();
            double newPrice = newFlight.getFlightPrice();

            RebookingCalculator calculator = new RebookingCalculator();
            double difference = calculator.calculatePriceDifference(oldPrice, newPrice, seats);

            double expectedDifference = (newPrice - oldPrice) * seats;
            assertEquals(expectedDifference, difference, 0.01);
            assertEquals(800.0, difference, 0.01);
            assertTrue(difference > 0);
        }

        @Test
        @DisplayName("验证改签价格下降时计算退差金额")
        void testRebooking_PriceDecreaseCalculatesRefund() {
            Flight oldFlight = TestDataBuilder.createDomesticFlight();
            oldFlight.setFlightPrice(1500.0);
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createDomesticFlight();
            newFlight.setFlightPrice(1000.0);
            DataStore.addFlight(newFlight);

            int seats = 3;
            double oldPrice = oldFlight.getFlightPrice();
            double newPrice = newFlight.getFlightPrice();

            RebookingCalculator calculator = new RebookingCalculator();
            double difference = calculator.calculatePriceDifference(oldPrice, newPrice, seats);

            double expectedDifference = (newPrice - oldPrice) * seats;
            assertEquals(expectedDifference, difference, 0.01);
            assertEquals(-1500.0, difference, 0.01);
            assertTrue(difference < 0);
        }

        @Test
        @DisplayName("验证改签价格相同时差价为0")
        void testRebooking_SamePriceNoDifference() {
            Flight oldFlight = TestDataBuilder.createDomesticFlight();
            oldFlight.setFlightPrice(800.0);
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createDomesticFlight();
            newFlight.setFlightPrice(800.0);
            DataStore.addFlight(newFlight);

            int seats = 2;
            double oldPrice = oldFlight.getFlightPrice();
            double newPrice = newFlight.getFlightPrice();

            RebookingCalculator calculator = new RebookingCalculator();
            double difference = calculator.calculatePriceDifference(oldPrice, newPrice, seats);

            assertEquals(0.0, difference, 0.01);
        }

        @Test
        @DisplayName("验证多座位改签差价计算正确")
        void testRebooking_MultipleSeatsDifference() {
            double oldPrice = 500.0;
            double newPrice = 700.0;
            int seats = 4;

            RebookingCalculator calculator = new RebookingCalculator();
            double difference = calculator.calculatePriceDifference(oldPrice, newPrice, seats);

            double expected = (700 - 500) * 4;
            assertEquals(expected, difference, 0.01);
            assertEquals(800.0, difference, 0.01);
        }

        @Test
        @DisplayName("验证改签预订金额更新正确")
        void testRebooking_BookingAmountUpdated() {
            Flight oldFlight = TestDataBuilder.createDomesticFlight();
            oldFlight.setFlightPrice(800.0);
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createDomesticFlight();
            newFlight.setFlightPrice(1200.0);
            newFlight.setFlightAvailable(50);
            DataStore.addFlight(newFlight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            int seats = 2;
            Booking booking = TestDataBuilder.createConfirmedBooking(oldFlight.getFlightId(), passenger.getPassengerId());
            booking.setBookingSeats(seats);
            booking.setBookingAmount(oldFlight.getFlightPrice() * seats);
            DataStore.addBooking(booking);

            double oldAmount = booking.getBookingAmount();
            booking.setFlightId(newFlight.getFlightId());
            booking.setBookingAmount(newFlight.getFlightPrice() * seats);

            assertEquals(newFlight.getFlightPrice() * seats, booking.getBookingAmount(), 0.01);
            assertEquals(2400.0, booking.getBookingAmount(), 0.01);
        }
    }

    @Nested
    @DisplayName("退改座位状态测试")
    class ChangeSeatStatusTests {

        @Test
        @DisplayName("验证退票后座位正确恢复")
        void testRefund_SeatsAreRecovered() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(150);
            int initialAvailable = flight.getFlightAvailable();
            DataStore.addFlight(flight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            int seatsBooked = 3;
            Booking booking = TestDataBuilder.createConfirmedBooking(flight.getFlightId(), passenger.getPassengerId());
            booking.setBookingSeats(seatsBooked);
            DataStore.addBooking(booking);

            flight.setFlightAvailable(initialAvailable - seatsBooked);
            int availableBeforeRefund = flight.getFlightAvailable();

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), seatsBooked));

            assertEquals(initialAvailable, flight.getFlightAvailable());
            assertTrue(flight.getFlightAvailable() > availableBeforeRefund);
        }

        @Test
        @DisplayName("验证改签时扣减新航班座位")
        void testRebooking_NewFlightSeatsDeducted() {
            Flight oldFlight = TestDataBuilder.createFlightWithAvailableSeats(150);
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createFlightWithAvailableSeats(100);
            int newFlightInitial = newFlight.getFlightAvailable();
            DataStore.addFlight(newFlight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            int seatsToMove = 2;
            Booking booking = TestDataBuilder.createConfirmedBooking(oldFlight.getFlightId(), passenger.getPassengerId());
            booking.setBookingSeats(seatsToMove);
            DataStore.addBooking(booking);

            assertTrue(flightService.updateAvailableSeats(newFlight.getFlightId(), -seatsToMove));

            assertEquals(newFlightInitial - seatsToMove, newFlight.getFlightAvailable());
        }

        @Test
        @DisplayName("验证改签时恢复原航班座位")
        void testRebooking_OldFlightSeatsRecovered() {
            Flight oldFlight = TestDataBuilder.createFlightWithAvailableSeats(150);
            int oldFlightInitial = oldFlight.getFlightAvailable();
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createFlightWithAvailableSeats(100);
            DataStore.addFlight(newFlight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            int seatsToMove = 5;
            oldFlight.setFlightAvailable(oldFlightInitial - seatsToMove);
            int availableBefore = oldFlight.getFlightAvailable();

            Booking booking = TestDataBuilder.createConfirmedBooking(oldFlight.getFlightId(), passenger.getPassengerId());
            booking.setBookingSeats(seatsToMove);
            DataStore.addBooking(booking);

            assertTrue(flightService.updateAvailableSeats(oldFlight.getFlightId(), seatsToMove));

            assertEquals(oldFlightInitial, oldFlight.getFlightAvailable());
            assertTrue(oldFlight.getFlightAvailable() > availableBefore);
        }

        @Test
        @DisplayName("验证新航班座位不足时改签失败")
        void testRebooking_FailsWhenNewFlightInsufficientSeats() {
            Flight oldFlight = TestDataBuilder.createFlightWithAvailableSeats(150);
            DataStore.addFlight(oldFlight);

            Flight newFlight = TestDataBuilder.createFlightWithAvailableSeats(2);
            DataStore.addFlight(newFlight);

            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(oldFlight.getFlightId(), passenger.getPassengerId());
            booking.setBookingSeats(5);
            DataStore.addBooking(booking);

            ChangeRecord result = changeService.processRebooking(
                booking.getBookingId(),
                newFlight.getFlightId(),
                "时间调整"
            );

            assertEquals("seats_insufficient", result.getChangeStatus());
        }

        @Test
        @DisplayName("验证座位流转完整性：预订-退票-再预订")
        void testSeatFlow_CompleteLifecycle() {
            Flight flight = TestDataBuilder.createFlightWithAvailableSeats(200);
            int totalSeats = flight.getFlightSeats();
            DataStore.addFlight(flight);

            int seatsToBook = 10;
            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), -seatsToBook));
            assertEquals(totalSeats - seatsToBook, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), seatsToBook));
            assertEquals(totalSeats, flight.getFlightAvailable());

            assertTrue(flightService.updateAvailableSeats(flight.getFlightId(), -seatsToBook));
            assertEquals(totalSeats - seatsToBook, flight.getFlightAvailable());
        }
    }

    @Nested
    @DisplayName("退改规则配置测试")
    class ChangeRuleConfigTests {

        @Test
        @DisplayName("验证退改规则配置正确加载")
        void testConfig_CorrectlyLoaded() {
            Map<String, Object> config = TestDataBuilder.createRefundRuleConfig();

            assertNotNull(config);
            assertEquals(0.10, config.get("domestic_fee_rate"));
            assertEquals(0.20, config.get("international_fee_rate"));
            assertEquals(0.50, config.get("last_minute_fee_rate"));
            assertEquals(72, config.get("free_cancel_hours"));
        }

        @Test
        @DisplayName("验证退改规则配置动态加载")
        void testConfig_DynamicLoading() {
            RefundRuleConfigManager configManager = new RefundRuleConfigManager();

            Map<String, Object> initialConfig = configManager.getConfig();
            assertNotNull(initialConfig);

            Map<String, Object> newConfig = new HashMap<>();
            newConfig.put("domestic_fee_rate", 0.15);
            newConfig.put("international_fee_rate", 0.25);
            configManager.loadConfig(newConfig);

            assertEquals(0.15, configManager.getConfig().get("domestic_fee_rate"));
            assertEquals(0.25, configManager.getConfig().get("international_fee_rate"));
        }

        @Test
        @DisplayName("验证规则配置变更后计算使用新规则")
        void testConfig_ChangesAffectCalculation() {
            RefundRuleConfigManager configManager = new RefundRuleConfigManager();
            RefundCalculator calculator = new RefundCalculator();

            double originalAmount = 1000.0;
            Map<String, Object> config1 = configManager.getConfig();
            double refund1 = calculator.calculateRefund(
                originalAmount, 
                (double) config1.get("domestic_fee_rate")
            );

            Map<String, Object> newConfig = new HashMap<>();
            newConfig.put("domestic_fee_rate", 0.15);
            newConfig.put("international_fee_rate", 0.25);
            newConfig.put("last_minute_fee_rate", 0.50);
            newConfig.put("free_cancel_hours", 72);
            configManager.loadConfig(newConfig);

            double refund2 = calculator.calculateRefund(
                originalAmount, 
                (double) configManager.getConfig().get("domestic_fee_rate")
            );

            assertTrue(refund2 < refund1);
            assertEquals(850.0, refund2, 0.01);
        }

        @Test
        @DisplayName("验证配置版本管理")
        void testConfig_VersionManagement() {
            RefundRuleConfigManager configManager = new RefundRuleConfigManager();
            int version1 = configManager.getVersion();

            Map<String, Object> newConfig = new HashMap<>();
            newConfig.put("domestic_fee_rate", 0.15);
            configManager.loadConfig(newConfig);
            int version2 = configManager.getVersion();

            assertTrue(version2 > version1);
        }
    }

    @Nested
    @DisplayName("退改状态验证测试")
    class ChangeStatusValidationTests {

        @Test
        @DisplayName("验证仅已确认预订可退票")
        void testRefund_OnlyConfirmedBookingsCanBeRefunded() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking pendingBooking = TestDataBuilder.createPendingPaymentBooking(
                flight.getFlightId(), 
                passenger.getPassengerId()
            );
            DataStore.addBooking(pendingBooking);

            ChangeRecord result = changeService.processRefund(
                pendingBooking.getBookingId(),
                "行程变更"
            );

            assertEquals("invalid_status", result.getChangeStatus());
        }

        @Test
        @DisplayName("验证不存在预订退票失败")
        void testRefund_FailsForNonExistentBooking() {
            ChangeRecord result = changeService.processRefund(
                "non_existent_booking",
                "测试"
            );

            assertNull(result);
        }

        @Test
        @DisplayName("验证已退票预订不能再次退票")
        void testRefund_AlreadyRefundedCannotBeRefundedAgain() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(
                flight.getFlightId(), 
                passenger.getPassengerId()
            );
            DataStore.addBooking(booking);

            ChangeRecord firstRefund = changeService.processRefund(
                booking.getBookingId(),
                "第一次退票"
            );

            assertEquals("approved", firstRefund.getChangeStatus());
            assertEquals("refunded", booking.getBookingStatus());

            ChangeRecord secondRefund = changeService.processRefund(
                booking.getBookingId(),
                "第二次退票"
            );

            assertEquals("invalid_status", secondRefund.getChangeStatus());
        }

        @Test
        @DisplayName("验证退改记录正确保存")
        void testChangeRecord_SavedCorrectly() {
            Flight flight = TestDataBuilder.createDomesticFlight();
            DataStore.addFlight(flight);
            Passenger passenger = TestDataBuilder.createPassenger();
            DataStore.addPassenger(passenger);

            Booking booking = TestDataBuilder.createConfirmedBooking(
                flight.getFlightId(), 
                passenger.getPassengerId()
            );
            DataStore.addBooking(booking);

            int initialHistorySize = DataStore.getChangeHistory().size();

            ChangeRecord record = changeService.processRefund(
                booking.getBookingId(),
                "行程变更"
            );

            assertEquals(initialHistorySize + 1, DataStore.getChangeHistory().size());
            assertEquals(booking.getBookingId(), record.getBookingId());
            assertEquals("refund", record.getChangeType());
            assertEquals("approved", record.getChangeStatus());
        }
    }

    public static class RefundCalculator {
        public double calculateRefund(double originalAmount, double feeRate) {
            double fee = originalAmount * feeRate;
            if (fee > originalAmount) {
                return 0;
            }
            return originalAmount - fee;
        }
    }

    public static class RebookingCalculator {
        public double calculatePriceDifference(double oldPrice, double newPrice, int seats) {
            return (newPrice - oldPrice) * seats;
        }
    }

    public static class RefundRuleConfigManager {
        private Map<String, Object> config;
        private int version = 0;

        public RefundRuleConfigManager() {
            this.config = TestDataBuilder.createRefundRuleConfig();
        }

        public Map<String, Object> getConfig() {
            return new HashMap<>(config);
        }

        public void loadConfig(Map<String, Object> newConfig) {
            this.config = new HashMap<>(newConfig);
            this.version++;
        }

        public int getVersion() {
            return version;
        }
    }
}
