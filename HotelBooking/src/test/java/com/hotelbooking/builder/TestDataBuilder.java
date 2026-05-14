package com.hotelbooking.builder;

import com.hotelbooking.dto.BookingCreateRequest;
import com.hotelbooking.dto.CheckInRequest;
import com.hotelbooking.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.UUID;

public class TestDataBuilder {

    private long counter = 0;

    public Hotel buildActiveHotel() {
        return buildHotel("active");
    }

    public Hotel buildInactiveHotel() {
        return buildHotel("inactive");
    }

    private Hotel buildHotel(String status) {
        Hotel hotel = new Hotel();
        hotel.setHotelId("hotel_" + UUID.randomUUID().toString().substring(0, 8));
        hotel.setHotelName("测试酒店" + (++counter));
        hotel.setHotelType("business");
        hotel.setHotelAddress("测试地址" + counter + "号");
        hotel.setHotelRating(4);
        hotel.setHotelRooms(100);
        hotel.setHotelStatus(status);
        hotel.setCreatedAt(LocalDateTime.now());
        return hotel;
    }

    public Room buildAvailableRoom(Hotel hotel) {
        return buildRoom(hotel, "available", 300.0);
    }

    public Room buildBookedRoom(Hotel hotel) {
        return buildRoom(hotel, "booked", 300.0);
    }

    public Room buildOccupiedRoom(Hotel hotel) {
        return buildRoom(hotel, "occupied", 300.0);
    }

    public Room buildRoom(Hotel hotel, String status, double price) {
        Room room = new Room();
        room.setRoomId("room_" + UUID.randomUUID().toString().substring(0, 8));
        room.setHotel(hotel);
        room.setHotelId(hotel.getHotelId());
        room.setRoomNumber("1" + String.format("%03d", ++counter));
        room.setRoomType("standard");
        room.setRoomPrice(price);
        room.setRoomStatus(status);
        room.setRoomFeatures(Arrays.asList("空调", "电视", "WiFi"));
        room.setCreatedAt(LocalDateTime.now());
        return room;
    }

    public Room buildStandardRoom(Hotel hotel) {
        return buildRoom(hotel, "available", 300.0);
    }

    public Room buildDeluxeRoom(Hotel hotel) {
        Room room = buildRoom(hotel, "available", 500.0);
        room.setRoomType("deluxe");
        return room;
    }

    public Room buildSuiteRoom(Hotel hotel) {
        Room room = buildRoom(hotel, "available", 800.0);
        room.setRoomType("suite");
        return room;
    }

    public Booking buildPendingBooking(Hotel hotel, Room room, LocalDate checkIn, LocalDate checkOut) {
        return buildBooking(hotel, room, checkIn, checkOut, "pending");
    }

    public Booking buildConfirmedBooking(Hotel hotel, Room room, LocalDate checkIn, LocalDate checkOut) {
        return buildBooking(hotel, room, checkIn, checkOut, "confirmed");
    }

    public Booking buildCheckedInBooking(Hotel hotel, Room room, LocalDate checkIn, LocalDate checkOut) {
        return buildBooking(hotel, room, checkIn, checkOut, "checked_in");
    }

    private Booking buildBooking(Hotel hotel, Room room, LocalDate checkIn, LocalDate checkOut, String status) {
        long days = java.time.temporal.ChronoUnit.DAYS.between(checkIn, checkOut);
        Booking booking = new Booking();
        booking.setBookingId("booking_" + UUID.randomUUID().toString().substring(0, 12));
        booking.setHotelId(hotel.getHotelId());
        booking.setRoomId(room.getRoomId());
        booking.setCustomerName("测试客户" + (++counter));
        booking.setCustomerPhone("13800" + String.format("%06d", counter));
        booking.setCheckInDate(checkIn);
        booking.setCheckOutDate(checkOut);
        booking.setBookingStatus(status);
        booking.setBookingAmount(room.getRoomPrice() * days);
        booking.setCreatedAt(LocalDateTime.now());
        return booking;
    }

    public BookingCreateRequest buildBookingCreateRequest(String hotelId, String roomId, LocalDate checkIn, LocalDate checkOut) {
        BookingCreateRequest request = new BookingCreateRequest();
        request.setHotelId(hotelId);
        request.setRoomId(roomId);
        request.setCheckInDate(checkIn);
        request.setCheckOutDate(checkOut);
        request.setCustomerName("测试客户" + (++counter));
        request.setCustomerPhone("13800" + String.format("%06d", counter));
        return request;
    }

    public CheckIn buildCheckIn(Booking booking, String status) {
        CheckIn checkIn = new CheckIn();
        checkIn.setCheckinId("checkin_" + UUID.randomUUID().toString().substring(0, 12));
        checkIn.setBookingId(booking.getBookingId());
        checkIn.setCheckinTime(LocalDateTime.now());
        checkIn.setCheckinStatus(status);
        checkIn.setCustomerIdType("id_card");
        checkIn.setCustomerIdNumber("11010119900101" + String.format("%04d", ++counter));
        return checkIn;
    }

    public CheckInRequest buildCheckInRequest(String bookingId) {
        CheckInRequest request = new CheckInRequest();
        request.setBookingId(bookingId);
        request.setCustomerIdNumber("110101199001011234");
        request.setCustomerIdType("id_card");
        return request;
    }

    public ServiceRecord buildCleaningService(Room room, String status) {
        return buildServiceRecord(room, "cleaning", "客房清洁", status, 50.0);
    }

    public ServiceRecord buildLaundryService(Room room, String status) {
        return buildServiceRecord(room, "laundry", "洗衣服务", status, 30.0);
    }

    public ServiceRecord buildRoomService(Room room, String status) {
        return buildServiceRecord(room, "room_service", "送餐服务", status, 100.0);
    }

    private ServiceRecord buildServiceRecord(Room room, String type, String request, String status, double charge) {
        ServiceRecord service = new ServiceRecord();
        service.setServiceId("service_" + UUID.randomUUID().toString().substring(0, 12));
        service.setRoomId(room.getRoomId());
        service.setServiceType(type);
        service.setServiceRequest(request);
        service.setServiceStatus(status);
        service.setServiceTime(LocalDateTime.now());
        service.setServiceCharge(charge);
        return service;
    }

    public Settlement buildSettlement(Booking booking, double roomCharge, double serviceCharge) {
        Settlement settlement = new Settlement();
        settlement.setSettlementId("settlement_" + UUID.randomUUID().toString().substring(0, 12));
        settlement.setBookingId(booking.getBookingId());
        settlement.setRoomCharge(roomCharge);
        settlement.setServiceCharge(serviceCharge);
        settlement.setTotalAmount(roomCharge + serviceCharge);
        settlement.setSettlementStatus("paid");
        settlement.setSettlementTime(LocalDateTime.now());
        settlement.setPaymentMethod("cash");
        return settlement;
    }

    public Review buildReview(Booking booking, int rating) {
        Review review = new Review();
        review.setReviewId("review_" + UUID.randomUUID().toString().substring(0, 12));
        review.setBookingId(booking.getBookingId());
        review.setHotelId(booking.getHotelId());
        review.setCustomerName(booking.getCustomerName());
        review.setRating(rating);
        review.setComment(rating >= 4 ? "非常满意的入住体验" : "有一些改进空间");
        review.setCreatedAt(LocalDateTime.now());
        return review;
    }

    public HotelStat buildHotelStat(Hotel hotel, String month) {
        HotelStat stat = new HotelStat();
        stat.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 12));
        stat.setHotelId(hotel.getHotelId());
        stat.setStatMonth(month);
        stat.setCheckinCount(0);
        stat.setBookingCount(0);
        stat.setTotalAmount(0.0);
        stat.setOccupancyRate(0.0);
        return stat;
    }

    public LocalDate today() {
        return LocalDate.now();
    }

    public LocalDate tomorrow() {
        return LocalDate.now().plusDays(1);
    }

    public LocalDate daysFromNow(int days) {
        return LocalDate.now().plusDays(days);
    }

    public String getNextId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public TestDataBuilder reset() {
        this.counter = 0;
        return this;
    }
}
