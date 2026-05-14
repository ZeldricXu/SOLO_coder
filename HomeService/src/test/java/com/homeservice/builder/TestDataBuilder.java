package com.homeservice.builder;

import com.homeservice.entity.*;
import com.homeservice.enums.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

public class TestDataBuilder {

    private static final AtomicLong staffCounter = new AtomicLong(0);
    private static final AtomicLong customerCounter = new AtomicLong(0);
    private static final AtomicLong bookingCounter = new AtomicLong(0);
    private static final AtomicLong reviewCounter = new AtomicLong(0);
    private static final AtomicLong settlementCounter = new AtomicLong(0);
    private static final AtomicLong historyCounter = new AtomicLong(0);
    private static final AtomicLong statCounter = new AtomicLong(0);

    public static Staff createStaff() {
        String staffId = "staff_test_" + staffCounter.incrementAndGet();
        Staff staff = new Staff(
            staffId,
            "测试服务人员" + staffCounter.get(),
            "cleaning",
            "138" + String.format("%08d", staffCounter.get()),
            "北京朝阳区",
            100.0
        );
        staff.setStaffStatus(StaffStatus.AVAILABLE);
        staff.setStaffRating(4.5);
        staff.setTotalBookings(0);
        staff.setTotalReviews(0);
        staff.setTotalIncome(0.0);
        return staff;
    }

    public static Staff createStaff(String staffType, String region, double price) {
        String staffId = "staff_test_" + staffCounter.incrementAndGet();
        Staff staff = new Staff(
            staffId,
            "测试服务人员" + staffCounter.get(),
            staffType,
            "138" + String.format("%08d", staffCounter.get()),
            region,
            price
        );
        staff.setStaffStatus(StaffStatus.AVAILABLE);
        return staff;
    }

    public static Staff createAvailableStaff() {
        Staff staff = createStaff();
        staff.setStaffStatus(StaffStatus.AVAILABLE);
        return staff;
    }

    public static Staff createBookedStaff() {
        Staff staff = createStaff();
        staff.setStaffStatus(StaffStatus.BOOKED);
        return staff;
    }

    public static Staff createUnavailableStaff() {
        Staff staff = createStaff();
        staff.setStaffStatus(StaffStatus.UNAVAILABLE);
        return staff;
    }

    public static Staff createStaffWithRating(double rating, int totalReviews) {
        Staff staff = createStaff();
        staff.setStaffRating(rating);
        staff.setTotalReviews(totalReviews);
        return staff;
    }

    public static Customer createCustomer() {
        String customerId = "customer_test_" + customerCounter.incrementAndGet();
        Customer customer = new Customer(
            customerId,
            "测试客户" + customerCounter.get(),
            "139" + String.format("%08d", customerCounter.get()),
            "北京市朝阳区测试地址" + customerCounter.get(),
            "北京朝阳区"
        );
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        customer.setTotalBookings(0);
        return customer;
    }

    public static Customer createActiveCustomer() {
        Customer customer = createCustomer();
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        return customer;
    }

    public static Customer createFrozenCustomer() {
        Customer customer = createCustomer();
        customer.setCustomerStatus(CustomerStatus.FROZEN);
        return customer;
    }

    public static Customer createVIPCustomer() {
        Customer customer = createCustomer();
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        customer.setTotalBookings(10);
        return customer;
    }

    public static Customer createInactiveCustomer() {
        Customer customer = createCustomer();
        customer.setCustomerStatus(CustomerStatus.ACTIVE);
        customer.setTotalBookings(1);
        return customer;
    }

    public static Booking createBooking(Staff staff, Customer customer) {
        String bookingId = "booking_test_" + bookingCounter.incrementAndGet();
        Booking booking = new Booking(
            bookingId,
            staff.getStaffId(),
            customer.getCustomerId(),
            staff.getStaffType(),
            Instant.now(),
            2,
            staff.getStaffPrice() * 2
        );
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        booking.setIsReviewed(false);
        booking.setIsSettled(false);
        return booking;
    }

    public static Booking createConfirmedBooking(Staff staff, Customer customer) {
        Booking booking = createBooking(staff, customer);
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        return booking;
    }

    public static Booking createCompletedBooking(Staff staff, Customer customer) {
        Booking booking = createBooking(staff, customer);
        booking.setBookingStatus(BookingStatus.COMPLETED);
        return booking;
    }

    public static Booking createReviewedBooking(Staff staff, Customer customer) {
        Booking booking = createCompletedBooking(staff, customer);
        booking.setIsReviewed(true);
        return booking;
    }

    public static Booking createSettledBooking(Staff staff, Customer customer) {
        Booking booking = createCompletedBooking(staff, customer);
        booking.setIsSettled(true);
        return booking;
    }

    public static Booking createBookingWithServiceTime(Staff staff, Customer customer, Instant serviceTime) {
        String bookingId = "booking_test_" + bookingCounter.incrementAndGet();
        Booking booking = new Booking(
            bookingId,
            staff.getStaffId(),
            customer.getCustomerId(),
            staff.getStaffType(),
            serviceTime,
            2,
            staff.getStaffPrice() * 2
        );
        booking.setBookingStatus(BookingStatus.CONFIRMED);
        return booking;
    }

    public static Review createReview(Booking booking, int rating, String content) {
        String reviewId = "review_test_" + reviewCounter.incrementAndGet();
        return new Review(
            reviewId,
            booking.getBookingId(),
            booking.getCustomerId(),
            booking.getStaffId(),
            rating,
            content
        );
    }

    public static Review createFiveStarReview(Booking booking) {
        return createReview(booking, 5, "服务非常好，非常满意！");
    }

    public static Review createOneStarReview(Booking booking) {
        return createReview(booking, 1, "服务较差，不满意。");
    }

    public static Settlement createSettlement(Booking booking) {
        String settlementId = "settlement_test_" + settlementCounter.incrementAndGet();
        double serviceAmount = booking.getBookingAmount();
        double platformFee = serviceAmount * 0.10;
        double staffAmount = serviceAmount - platformFee;
        return new Settlement(
            settlementId,
            booking.getBookingId(),
            booking.getStaffId(),
            serviceAmount,
            platformFee,
            staffAmount
        );
    }

    public static Settlement createPaidSettlement(Booking booking) {
        Settlement settlement = createSettlement(booking);
        settlement.setSettlementStatus(SettlementStatus.PAID);
        settlement.setSettlementTime(Instant.now());
        return settlement;
    }

    public static Settlement createPendingSettlement(Booking booking) {
        Settlement settlement = createSettlement(booking);
        settlement.setSettlementStatus(SettlementStatus.PENDING);
        return settlement;
    }

    public static ServiceHistory createBookingHistory(Booking booking) {
        String historyId = "history_test_" + historyCounter.incrementAndGet();
        ServiceHistory history = new ServiceHistory(historyId, "BOOKING", "CREATE");
        history.setBookingId(booking.getBookingId());
        history.setStaffId(booking.getStaffId());
        history.setCustomerId(booking.getCustomerId());
        history.setDescription("预约创建成功");
        return history;
    }

    public static ServiceHistory createReviewHistory(Review review) {
        String historyId = "history_test_" + historyCounter.incrementAndGet();
        ServiceHistory history = new ServiceHistory(historyId, "REVIEW", "CREATE");
        history.setBookingId(review.getBookingId());
        history.setStaffId(review.getStaffId());
        history.setCustomerId(review.getCustomerId());
        history.setDescription("评价提交成功");
        return history;
    }

    public static ServiceStat createMonthlyStat(String month) {
        String statId = "stat_test_" + statCounter.incrementAndGet();
        return new ServiceStat(statId, month);
    }

    public static ServiceStat createStatWithData(String month, int staffCount, int bookingCount, 
                                                 int reviewCount, double totalAmount) {
        ServiceStat stat = createMonthlyStat(month);
        stat.setStaffCount(staffCount);
        stat.setBookingCount(bookingCount);
        stat.setReviewCount(reviewCount);
        stat.setTotalAmount(totalAmount);
        return stat;
    }

    public static ServiceType createServiceType() {
        return new ServiceType(
            "cleaning",
            "家庭保洁",
            "家庭日常清洁服务",
            100.0
        );
    }

    public static ServiceType createServiceType(String code, String name, double price) {
        return new ServiceType(code, name, name + "服务", price);
    }

    public static ServiceRegion createServiceRegion() {
        return new ServiceRegion(
            "bj-chaoyang",
            "北京朝阳区",
            "北京市",
            "北京市",
            "朝阳区"
        );
    }

    public static ServiceRegion createServiceRegion(String code, String name) {
        return new ServiceRegion(code, name, "北京市", "北京市", name);
    }

    public static void resetCounters() {
        staffCounter.set(0);
        customerCounter.set(0);
        bookingCounter.set(0);
        reviewCounter.set(0);
        settlementCounter.set(0);
        historyCounter.set(0);
        statCounter.set(0);
    }
}
