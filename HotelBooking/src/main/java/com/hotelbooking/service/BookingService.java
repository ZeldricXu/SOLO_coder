package com.hotelbooking.service;

import com.hotelbooking.dto.BookingCreateRequest;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.Hotel;
import com.hotelbooking.model.Room;
import com.hotelbooking.repository.BookingRepository;
import com.hotelbooking.repository.HotelRepository;
import com.hotelbooking.repository.RoomRepository;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

@Service
public class BookingService {
    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final HotelRepository hotelRepository;
    private final RoomRepository roomRepository;
    private final RoomService roomService;
    private final AnalysisService analysisService;
    private final HistoryService historyService;

    public BookingService(BookingRepository bookingRepository, HotelRepository hotelRepository,
                          RoomRepository roomRepository, RoomService roomService,
                          AnalysisService analysisService, HistoryService historyService) {
        this.bookingRepository = bookingRepository;
        this.hotelRepository = hotelRepository;
        this.roomRepository = roomRepository;
        this.roomService = roomService;
        this.analysisService = analysisService;
        this.historyService = historyService;
    }

    @Transactional
    public Booking createBooking(BookingCreateRequest request) {
        logger.info("开始创建预订: 酒店={}, 房间={}", request.getHotelId(), request.getRoomId());

        Hotel hotel = hotelRepository.findById(request.getHotelId())
                .orElseThrow(() -> new RuntimeException("酒店不存在: " + request.getHotelId()));

        if (!"active".equals(hotel.getHotelStatus())) {
            throw new RuntimeException("酒店已关闭，无法预订");
        }

        Room room = roomRepository.findByIdForUpdate(request.getRoomId())
                .orElseThrow(() -> new RuntimeException("房间不存在: " + request.getRoomId()));

        if (!room.getHotelId().equals(request.getHotelId())) {
            throw new RuntimeException("房间不属于该酒店");
        }

        if (!roomService.isRoomAvailable(request.getRoomId(), request.getCheckInDate(), request.getCheckOutDate())) {
            throw new RuntimeException("房间在该时间段已被预订");
        }

        long days = ChronoUnit.DAYS.between(request.getCheckInDate(), request.getCheckOutDate());
        if (days <= 0) {
            throw new RuntimeException("入住日期必须早于退房日期");
        }

        double bookingAmount = room.getRoomPrice() * days;

        Booking booking = new Booking();
        booking.setBookingId(IdGenerator.generateBookingId());
        booking.setHotelId(request.getHotelId());
        booking.setRoomId(request.getRoomId());
        booking.setCustomerName(request.getCustomerName());
        booking.setCustomerPhone(request.getCustomerPhone());
        booking.setCheckInDate(request.getCheckInDate());
        booking.setCheckOutDate(request.getCheckOutDate());
        booking.setBookingStatus("pending");
        booking.setBookingAmount(bookingAmount);
        booking.setCreatedAt(LocalDateTime.now());

        Booking saved = bookingRepository.save(booking);
        logger.info("预订创建成功: {}", saved.getBookingId());

        analysisService.incrementBookingCount(request.getHotelId());
        historyService.recordBookingHistory(saved, "CREATE", "预订创建成功");

        return saved;
    }

    @Transactional
    public Booking confirmBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("预订不存在: " + bookingId));

        if (!"pending".equals(booking.getBookingStatus())) {
            throw new RuntimeException("预订状态不允许确认，当前状态: " + booking.getBookingStatus());
        }

        booking.setBookingStatus("confirmed");
        Booking confirmed = bookingRepository.save(booking);

        Room room = roomRepository.findByIdForUpdate(booking.getRoomId())
                .orElseThrow(() -> new RuntimeException("房间不存在"));
        room.setRoomStatus("booked");
        roomRepository.save(room);

        logger.info("预订确认成功: {}", bookingId);
        historyService.recordBookingHistory(confirmed, "CONFIRM", "预订确认成功");

        return confirmed;
    }

    @Transactional
    public Booking cancelBooking(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("预订不存在: " + bookingId));

        if ("cancelled".equals(booking.getBookingStatus()) || "completed".equals(booking.getBookingStatus())) {
            throw new RuntimeException("预订已取消或已完成");
        }

        String previousStatus = booking.getBookingStatus();
        booking.setBookingStatus("cancelled");
        Booking cancelled = bookingRepository.save(booking);

        if ("confirmed".equals(previousStatus) || "checked_in".equals(previousStatus)) {
            Room room = roomRepository.findByIdForUpdate(booking.getRoomId())
                    .orElseThrow(() -> new RuntimeException("房间不存在"));
            room.setRoomStatus("available");
            roomRepository.save(room);
        }

        logger.info("预订取消成功: {}", bookingId);
        historyService.recordBookingHistory(cancelled, "CANCEL", "预订取消成功");

        return cancelled;
    }

    public Optional<Booking> getBookingById(String bookingId) {
        return bookingRepository.findById(bookingId);
    }

    public List<Booking> getBookingsByHotel(String hotelId) {
        return bookingRepository.findByHotelId(hotelId);
    }

    public List<Booking> getBookingsByRoom(String roomId) {
        return bookingRepository.findByRoomId(roomId);
    }

    public List<Booking> getBookingsByStatus(String status) {
        return bookingRepository.findByBookingStatus(status);
    }

    public List<Booking> getBookingHistory(String customerPhone) {
        return bookingRepository.findByCustomerPhoneOrderByCreatedAtDesc(customerPhone);
    }

    @Transactional
    public void updateBookingStatus(String bookingId, String status) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("预订不存在: " + bookingId));
        booking.setBookingStatus(status);
        bookingRepository.save(booking);
    }
}
