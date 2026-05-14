package com.flightmgmt.test.data;

import com.flightmgmt.common.model.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

public class TestDataBuilder {

    private static int bookingCounter = 0;
    private static int flightCounter = 0;
    private static int passengerCounter = 0;

    public static Flight createDomesticFlight() {
        flightCounter++;
        Flight flight = new Flight();
        flight.setFlightId("flight_domestic_" + flightCounter);
        flight.setFlightNumber("CA" + (1000 + flightCounter));
        flight.setFlightRoute("北京-上海");
        flight.setDeparture("北京");
        flight.setDestination("上海");
        flight.setFlightDeparture(LocalDateTime.now().plusDays(1));
        flight.setFlightArrival(LocalDateTime.now().plusDays(1).plusHours(2));
        flight.setFlightStatus("scheduled");
        flight.setFlightSeats(200);
        flight.setFlightAvailable(200);
        flight.setFlightPrice(800.0);
        flight.setFlightType("domestic");
        flight.setCreatedAt(LocalDateTime.now());
        return flight;
    }

    public static Flight createInternationalFlight() {
        flightCounter++;
        Flight flight = new Flight();
        flight.setFlightId("flight_international_" + flightCounter);
        flight.setFlightNumber("CA" + (9000 + flightCounter));
        flight.setFlightRoute("北京-纽约");
        flight.setDeparture("北京");
        flight.setDestination("纽约");
        flight.setFlightDeparture(LocalDateTime.now().plusDays(3));
        flight.setFlightArrival(LocalDateTime.now().plusDays(3).plusHours(14));
        flight.setFlightStatus("scheduled");
        flight.setFlightSeats(300);
        flight.setFlightAvailable(300);
        flight.setFlightPrice(5000.0);
        flight.setFlightType("international");
        flight.setCreatedAt(LocalDateTime.now());
        return flight;
    }

    public static Flight createFlightWithStatus(String status) {
        Flight flight = createDomesticFlight();
        flight.setFlightStatus(status);
        return flight;
    }

    public static Flight createFlightWithAvailableSeats(int availableSeats) {
        Flight flight = createDomesticFlight();
        flight.setFlightSeats(200);
        flight.setFlightAvailable(availableSeats);
        return flight;
    }

    public static Flight createFlightWithType(String flightType) {
        if ("international".equalsIgnoreCase(flightType)) {
            return createInternationalFlight();
        }
        return createDomesticFlight();
    }

    public static Passenger createPassenger() {
        passengerCounter++;
        Passenger passenger = new Passenger();
        passenger.setPassengerId("passenger_" + passengerCounter);
        passenger.setPassengerName("测试乘客" + passengerCounter);
        passenger.setPassengerIdType("id_card");
        passenger.setPassengerIdNumber("11010119900101" + String.format("%04d", passengerCounter));
        passenger.setPassengerPhone("138" + String.format("%08d", passengerCounter));
        passenger.setCreatedAt(LocalDateTime.now());
        return passenger;
    }

    public static Passenger createPassengerWithPhone(String phone) {
        Passenger passenger = createPassenger();
        passenger.setPassengerPhone(phone);
        return passenger;
    }

    public static Booking createConfirmedBooking(String flightId, String passengerId) {
        bookingCounter++;
        Booking booking = new Booking();
        booking.setBookingId("booking_confirmed_" + bookingCounter);
        booking.setFlightId(flightId);
        booking.setPassengerId(passengerId);
        booking.setBookingSeats(1);
        booking.setBookingAmount(800.0);
        booking.setBookingStatus("confirmed");
        booking.setPaymentMethod("alipay");
        booking.setCreatedAt(LocalDateTime.now().minusMinutes(30));
        booking.setConfirmedAt(LocalDateTime.now().minusMinutes(25));
        return booking;
    }

    public static Booking createPendingPaymentBooking(String flightId, String passengerId) {
        bookingCounter++;
        Booking booking = new Booking();
        booking.setBookingId("booking_pending_" + bookingCounter);
        booking.setFlightId(flightId);
        booking.setPassengerId(passengerId);
        booking.setBookingSeats(2);
        booking.setBookingAmount(1600.0);
        booking.setBookingStatus("pending_payment");
        booking.setPaymentMethod("alipay");
        booking.setCreatedAt(LocalDateTime.now().minusMinutes(15));
        return booking;
    }

    public static Booking createCancelledBooking(String flightId, String passengerId) {
        bookingCounter++;
        Booking booking = new Booking();
        booking.setBookingId("booking_cancelled_" + bookingCounter);
        booking.setFlightId(flightId);
        booking.setPassengerId(passengerId);
        booking.setBookingSeats(1);
        booking.setBookingAmount(800.0);
        booking.setBookingStatus("cancelled");
        booking.setPaymentMethod("wechat");
        booking.setCreatedAt(LocalDateTime.now().minusHours(2));
        return booking;
    }

    public static ChangeRecord createRefundRecord(String bookingId) {
        ChangeRecord record = new ChangeRecord();
        record.setChangeId("change_refund_001");
        record.setBookingId(bookingId);
        record.setChangeType("refund");
        record.setChangeReason("行程变更");
        record.setChangeAmount(720.0);
        record.setChangeStatus("approved");
        record.setChangeTime(LocalDateTime.now());
        return record;
    }

    public static ChangeRecord createRebookingRecord(String bookingId, String oldFlightId, String newFlightId) {
        ChangeRecord record = new ChangeRecord();
        record.setChangeId("change_rebook_001");
        record.setBookingId(bookingId);
        record.setChangeType("rebook");
        record.setChangeReason("时间调整");
        record.setChangeAmount(500.0);
        record.setChangeStatus("approved");
        record.setChangeTime(LocalDateTime.now());
        return record;
    }

    public static FlightStatus createDelayStatus(String flightId) {
        FlightStatus status = new FlightStatus();
        status.setStatusId("status_delay_001");
        status.setFlightId(flightId);
        status.setStatusType("delay");
        status.setStatusDetail("航班延误30分钟，因天气原因");
        status.setStatusTime(LocalDateTime.now());
        return status;
    }

    public static FlightStatus createCancelStatus(String flightId) {
        FlightStatus status = new FlightStatus();
        status.setStatusId("status_cancel_001");
        status.setFlightId(flightId);
        status.setStatusType("cancelled");
        status.setStatusDetail("航班因机械故障取消");
        status.setStatusTime(LocalDateTime.now());
        return status;
    }

    public static FlightStatus createNormalStatus(String flightId) {
        FlightStatus status = new FlightStatus();
        status.setStatusId("status_normal_001");
        status.setFlightId(flightId);
        status.setStatusType("on_time");
        status.setStatusDetail("航班正点运行");
        status.setStatusTime(LocalDateTime.now());
        return status;
    }

    public static NotificationTask createNotificationTask(String bookingId, String passengerId, String flightId) {
        return NotificationTask.create(
            bookingId,
            passengerId,
            flightId,
            "delay",
            "航班延误通知",
            "尊敬的乘客，您的航班已延误30分钟。",
            3
        );
    }

    public static NotificationTask createNotificationTaskWithPhone(String bookingId, String passengerId, 
                                                                   String flightId, String phone) {
        NotificationTask task = createNotificationTask(bookingId, passengerId, flightId);
        task.setPassengerPhone(phone);
        return task;
    }

    public static PaymentTimeoutConfig createDefaultPaymentTimeoutConfig() {
        return PaymentTimeoutConfig.createDefault();
    }

    public static ChangeRuleConfig createDefaultChangeRuleConfig() {
        return ChangeRuleConfig.createDefault();
    }

    public static PaymentTimeoutConfig createCustomPaymentTimeoutConfig(int domesticMinutes, 
                                                                          int internationalMinutes) {
        PaymentTimeoutConfig config = new PaymentTimeoutConfig();
        config.setConfigId("custom_payment_timeout");
        config.setConfigName("自定义支付超时配置");
        config.setUpdatedAt(LocalDateTime.now());
        config.getTimeoutMinutesByType().put("domestic", domesticMinutes);
        config.getTimeoutMinutesByType().put("international", internationalMinutes);
        return config;
    }

    public static ChangeRuleConfig createCustomChangeRuleConfig(double domesticRefundFee, 
                                                                  double internationalRefundFee,
                                                                  double domesticRebookFee,
                                                                  double internationalRebookFee) {
        ChangeRuleConfig config = new ChangeRuleConfig();
        config.setConfigId("custom_change_rules");
        config.setConfigName("自定义退改规则配置");
        config.setUpdatedAt(LocalDateTime.now());
        config.getRefundFeeRates().put("domestic", domesticRefundFee);
        config.getRefundFeeRates().put("international", internationalRefundFee);
        config.getRebookFeeRates().put("domestic", domesticRebookFee);
        config.getRebookFeeRates().put("international", internationalRebookFee);
        return config;
    }

    public static Map<String, Object> createRefundRuleConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("domestic_fee_rate", 0.10);
        config.put("international_fee_rate", 0.20);
        config.put("last_minute_fee_rate", 0.50);
        config.put("free_cancel_hours", 72);
        return config;
    }

    public static Map<String, Object> createPaymentTimeoutConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("domestic_timeout_minutes", 15);
        config.put("international_timeout_minutes", 30);
        config.put("max_retry_count", 3);
        config.put("retry_interval_seconds", 60);
        return config;
    }

    public static Map<String, Object> createNotificationConfig() {
        Map<String, Object> config = new HashMap<>();
        config.put("max_retry_attempts", 3);
        config.put("retry_interval_minutes", 5);
        config.put("confirmation_timeout_minutes", 30);
        config.put("sms_enabled", true);
        config.put("email_enabled", true);
        return config;
    }

    public static void resetCounters() {
        bookingCounter = 0;
        flightCounter = 0;
        passengerCounter = 0;
    }
}
