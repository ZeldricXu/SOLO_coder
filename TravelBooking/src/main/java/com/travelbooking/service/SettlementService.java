package com.travelbooking.service;

import com.travelbooking.exception.BusinessException;
import com.travelbooking.model.Booking;
import com.travelbooking.model.Settlement;
import com.travelbooking.repository.SettlementRepository;
import com.travelbooking.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SettlementService {

    private final SettlementRepository settlementRepository;
    private final BookingService bookingService;
    private final AnalyticsService analyticsService;
    private final HistoryService historyService;

    @Transactional
    public Settlement createSettlement(String bookingId, String paymentMethod) {
        Booking booking = bookingService.getBookingById(bookingId)
                .orElseThrow(() -> new BusinessException(404, "预订不存在"));

        if (!"completed".equals(booking.getBookingStatus()) && !"confirmed".equals(booking.getBookingStatus())) {
            throw new BusinessException(400, "行程未完成");
        }

        Optional<Settlement> existing = settlementRepository.findByBookingIdAndPaymentStatus(bookingId, "paid");
        if (existing.isPresent()) {
            throw new BusinessException(400, "重复结算");
        }

        Settlement settlement = new Settlement();
        settlement.setSettlementId(IdGenerator.generateSettlementId());
        settlement.setBookingId(bookingId);
        settlement.setTouristId(booking.getTouristId());
        settlement.setSettlementAmount(booking.getBookingAmount());
        settlement.setPaymentMethod(paymentMethod);
        settlement.setPaymentStatus("pending");
        settlement.setSettlementTime(Instant.now());

        boolean paymentSuccess = true;
        if (paymentSuccess) {
            settlement.setPaymentStatus("paid");
            bookingService.updateBookingStatus(bookingId, "settled");
            analyticsService.updateSettlementStatistics(booking.getBookingAmount());
            historyService.recordHistory("settlement", settlement.getSettlementId(),
                    "create", "结算成功，金额: " + booking.getBookingAmount());
        } else {
            throw new BusinessException(400, "支付失败");
        }

        return settlementRepository.save(settlement);
    }

    public List<Settlement> getAllSettlements() {
        return settlementRepository.findAll();
    }

    public Optional<Settlement> getSettlementById(String settlementId) {
        return settlementRepository.findById(settlementId);
    }

    public List<Settlement> getSettlementsByBookingId(String bookingId) {
        return settlementRepository.findByBookingId(bookingId);
    }

    public List<Settlement> getSettlementsByTouristId(String touristId) {
        return settlementRepository.findByTouristId(touristId);
    }

    @Transactional
    public Settlement updateSettlement(String settlementId, Settlement settlement) {
        Settlement existing = settlementRepository.findById(settlementId)
                .orElseThrow(() -> new BusinessException(404, "结算记录不存在"));

        if (settlement.getPaymentMethod() != null) {
            existing.setPaymentMethod(settlement.getPaymentMethod());
        }
        if (settlement.getPaymentStatus() != null) {
            existing.setPaymentStatus(settlement.getPaymentStatus());
        }

        return settlementRepository.save(existing);
    }

    public void deleteSettlement(String settlementId) {
        settlementRepository.deleteById(settlementId);
    }

    @Transactional
    public Settlement createSettlement(Settlement settlement) {
        return settlementRepository.save(settlement);
    }
}
