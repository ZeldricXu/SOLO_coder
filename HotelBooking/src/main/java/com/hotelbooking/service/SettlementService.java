package com.hotelbooking.service;

import com.hotelbooking.model.*;
import com.hotelbooking.repository.*;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Service
public class SettlementService {
    private static final Logger logger = LoggerFactory.getLogger(SettlementService.class);

    private final SettlementRepository settlementRepository;
    private final BookingRepository bookingRepository;
    private final CheckInRepository checkInRepository;
    private final RoomRepository roomRepository;
    private final ServiceRecordRepository serviceRecordRepository;
    private final AnalysisService analysisService;
    private final HistoryService historyService;
    private final ReviewService reviewService;

    public SettlementService(SettlementRepository settlementRepository, BookingRepository bookingRepository,
                             CheckInRepository checkInRepository, RoomRepository roomRepository,
                             ServiceRecordRepository serviceRecordRepository, AnalysisService analysisService,
                             HistoryService historyService, ReviewService reviewService) {
        this.settlementRepository = settlementRepository;
        this.bookingRepository = bookingRepository;
        this.checkInRepository = checkInRepository;
        this.roomRepository = roomRepository;
        this.serviceRecordRepository = serviceRecordRepository;
        this.analysisService = analysisService;
        this.historyService = historyService;
        this.reviewService = reviewService;
    }

    @Transactional
    public Settlement checkOutAndSettle(String bookingId, String paymentMethod) {
        logger.info("开始退房结算: 预订ID={}", bookingId);

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("预订不存在: " + bookingId));

        CheckIn checkIn = checkInRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("入住记录不存在"));

        if (!"checked_in".equals(checkIn.getCheckinStatus())) {
            throw new RuntimeException("当前状态不允许退房结算");
        }

        if (settlementRepository.findByBookingId(bookingId).isPresent()) {
            throw new RuntimeException("该预订已完成结算");
        }

        Room room = roomRepository.findById(booking.getRoomId())
                .orElseThrow(() -> new RuntimeException("房间不存在"));

        long actualDays = ChronoUnit.DAYS.between(
                checkIn.getCheckinTime().toLocalDate(),
                LocalDateTime.now().toLocalDate()
        );
        if (actualDays <= 0) {
            actualDays = 1;
        }
        double roomCharge = room.getRoomPrice() * actualDays;

        List<ServiceRecord> serviceRecords = serviceRecordRepository.findByRoomId(booking.getRoomId());
        double serviceCharge = serviceRecords.stream()
                .filter(s -> "completed".equals(s.getServiceStatus()))
                .mapToDouble(s -> s.getServiceCharge() != null ? s.getServiceCharge() : 0.0)
                .sum();

        double totalAmount = roomCharge + serviceCharge;

        if (totalAmount < 0) {
            logger.error("费用计算异常: roomCharge={}, serviceCharge={}", roomCharge, serviceCharge);
            throw new RuntimeException("费用计算异常");
        }

        String payment = paymentMethod != null ? paymentMethod : "cash";
        boolean paymentSuccess = processPayment(totalAmount, payment);
        if (!paymentSuccess) {
            throw new RuntimeException("支付失败，请重试");
        }

        Settlement settlement = new Settlement();
        settlement.setSettlementId(IdGenerator.generateSettlementId());
        settlement.setBookingId(bookingId);
        settlement.setRoomCharge(roomCharge);
        settlement.setServiceCharge(serviceCharge);
        settlement.setTotalAmount(totalAmount);
        settlement.setSettlementStatus("paid");
        settlement.setSettlementTime(LocalDateTime.now());
        settlement.setPaymentMethod(payment);

        Settlement saved = settlementRepository.save(settlement);

        checkIn.setCheckinStatus("checked_out");
        checkInRepository.save(checkIn);

        booking.setBookingStatus("completed");
        bookingRepository.save(booking);

        Room roomForUpdate = roomRepository.findByIdForUpdate(booking.getRoomId())
                .orElseThrow(() -> new RuntimeException("房间不存在"));
        roomForUpdate.setRoomStatus("available");
        roomRepository.save(roomForUpdate);

        logger.info("退房结算成功: 结算ID={}, 总金额={}", saved.getSettlementId(), totalAmount);

        analysisService.addRevenue(booking.getHotelId(), totalAmount);
        historyService.recordSettlementHistory(saved, booking, "SETTLEMENT", "退房结算成功");

        reviewService.requestReview(booking);

        return saved;
    }

    private boolean processPayment(double amount, String paymentMethod) {
        logger.info("处理支付: 金额={}, 方式={}", amount, paymentMethod);
        return true;
    }

    public Settlement calculateFee(String bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new RuntimeException("预订不存在: " + bookingId));

        CheckIn checkIn = checkInRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new RuntimeException("入住记录不存在"));

        Room room = roomRepository.findById(booking.getRoomId())
                .orElseThrow(() -> new RuntimeException("房间不存在"));

        long actualDays = ChronoUnit.DAYS.between(
                checkIn.getCheckinTime().toLocalDate(),
                LocalDateTime.now().toLocalDate()
        );
        if (actualDays <= 0) {
            actualDays = 1;
        }
        double roomCharge = room.getRoomPrice() * actualDays;

        List<ServiceRecord> serviceRecords = serviceRecordRepository.findByRoomId(booking.getRoomId());
        double serviceCharge = serviceRecords.stream()
                .filter(s -> "completed".equals(s.getServiceStatus()))
                .mapToDouble(s -> s.getServiceCharge() != null ? s.getServiceCharge() : 0.0)
                .sum();

        Settlement settlement = new Settlement();
        settlement.setBookingId(bookingId);
        settlement.setRoomCharge(roomCharge);
        settlement.setServiceCharge(serviceCharge);
        settlement.setTotalAmount(roomCharge + serviceCharge);

        return settlement;
    }
}
