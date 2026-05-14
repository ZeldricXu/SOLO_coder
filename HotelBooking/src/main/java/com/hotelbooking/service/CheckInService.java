package com.hotelbooking.service;

import com.hotelbooking.dto.CheckInRequest;
import com.hotelbooking.model.Booking;
import com.hotelbooking.model.CheckIn;
import com.hotelbooking.model.Room;
import com.hotelbooking.repository.BookingRepository;
import com.hotelbooking.repository.CheckInRepository;
import com.hotelbooking.repository.RoomRepository;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class CheckInService {
    private static final Logger logger = LoggerFactory.getLogger(CheckInService.class);

    private final CheckInRepository checkInRepository;
    private final BookingRepository bookingRepository;
    private final RoomRepository roomRepository;
    private final AnalysisService analysisService;
    private final HistoryService historyService;

    public CheckInService(CheckInRepository checkInRepository, BookingRepository bookingRepository,
                          RoomRepository roomRepository, AnalysisService analysisService,
                          HistoryService historyService) {
        this.checkInRepository = checkInRepository;
        this.bookingRepository = bookingRepository;
        this.roomRepository = roomRepository;
        this.analysisService = analysisService;
        this.historyService = historyService;
    }

    @Transactional
    public CheckIn checkIn(CheckInRequest request) {
        logger.info("开始入住登记: 预订ID={}", request.getBookingId());

        Booking booking = bookingRepository.findById(request.getBookingId())
                .orElseThrow(() -> new RuntimeException("预订不存在: " + request.getBookingId()));

        if ("cancelled".equals(booking.getBookingStatus())) {
            throw new RuntimeException("预订已取消，无法办理入住");
        }

        if ("checked_in".equals(booking.getBookingStatus())) {
            throw new RuntimeException("已办理入住，请勿重复操作");
        }

        if ("completed".equals(booking.getBookingStatus())) {
            throw new RuntimeException("预订已完成，无法办理入住");
        }

        if (!"confirmed".equals(booking.getBookingStatus()) && !"pending".equals(booking.getBookingStatus())) {
            throw new RuntimeException("预订状态不允许入住，当前状态: " + booking.getBookingStatus());
        }

        String idType = request.getCustomerIdType() != null ? request.getCustomerIdType() : "id_card";

        if (request.getCustomerIdNumber() == null || request.getCustomerIdNumber().trim().isEmpty()) {
            throw new RuntimeException("证件号码不能为空");
        }

        if (!verifyIdentity(idType, request.getCustomerIdNumber())) {
            throw new RuntimeException("身份验证失败");
        }

        CheckIn checkIn = new CheckIn();
        checkIn.setCheckinId(IdGenerator.generateCheckInId());
        checkIn.setBookingId(request.getBookingId());
        checkIn.setCheckinTime(LocalDateTime.now());
        checkIn.setCheckinStatus("checked_in");
        checkIn.setCustomerIdType(idType);
        checkIn.setCustomerIdNumber(request.getCustomerIdNumber());

        CheckIn saved = checkInRepository.save(checkIn);

        booking.setBookingStatus("checked_in");
        bookingRepository.save(booking);

        Room room = roomRepository.findByIdForUpdate(booking.getRoomId())
                .orElseThrow(() -> new RuntimeException("房间不存在"));
        room.setRoomStatus("occupied");
        roomRepository.save(room);

        logger.info("入住登记成功: {}", saved.getCheckinId());

        analysisService.incrementCheckInCount(booking.getHotelId());
        historyService.recordCheckInHistory(saved, booking, "CHECKIN", "入住登记成功");

        return saved;
    }

    private boolean verifyIdentity(String idType, String idNumber) {
        if (idNumber == null || idNumber.length() < 6) {
            return false;
        }
        return true;
    }

    @Transactional
    public CheckIn checkOut(String checkinId) {
        CheckIn checkIn = checkInRepository.findById(checkinId)
                .orElseThrow(() -> new RuntimeException("入住记录不存在: " + checkinId));

        if (!"checked_in".equals(checkIn.getCheckinStatus())) {
            throw new RuntimeException("当前状态不允许退房");
        }

        checkIn.setCheckinStatus("checked_out");
        CheckIn updated = checkInRepository.save(checkIn);

        logger.info("退房登记成功: {}", checkinId);

        return updated;
    }

    public Optional<CheckIn> getCheckInById(String checkinId) {
        return checkInRepository.findById(checkinId);
    }

    public Optional<CheckIn> getCheckInByBookingId(String bookingId) {
        return checkInRepository.findByBookingId(bookingId);
    }
}
