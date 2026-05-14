package com.homeservice.service;

import com.homeservice.dto.SettlementProcessRequest;
import com.homeservice.dto.SettlementResponse;
import com.homeservice.entity.Booking;
import com.homeservice.entity.Settlement;
import com.homeservice.enums.BookingStatus;
import com.homeservice.enums.SettlementStatus;
import com.homeservice.exception.BusinessException;
import com.homeservice.repository.SettlementRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class SettlementService {

    @Autowired
    private SettlementRepository settlementRepository;

    @Autowired
    private BookingService bookingService;

    @Autowired
    private StaffService staffService;

    @Autowired
    private ServiceHistoryService serviceHistoryService;

    @Autowired
    private AnalyticsService analyticsService;

    private final AtomicLong settlementCounter = new AtomicLong(0);
    private static final double PLATFORM_FEE_RATE = 0.10;

    @Transactional
    public SettlementResponse processSettlement(SettlementProcessRequest request) {
        Booking booking = bookingService.getBookingById(request.getBookingId());
        if (booking.getBookingStatus() != BookingStatus.COMPLETED) {
            throw new BusinessException("Service is not completed yet");
        }
        if (booking.getIsSettled()) {
            throw new BusinessException("Booking already settled");
        }
        double serviceAmount = booking.getBookingAmount();
        double platformFee = calculatePlatformFee(serviceAmount);
        double staffAmount = serviceAmount - platformFee;
        String settlementId = "settlement_" + String.format("%03d", settlementCounter.incrementAndGet());
        Settlement settlement = new Settlement(
            settlementId,
            request.getBookingId(),
            booking.getStaffId(),
            serviceAmount,
            platformFee,
            staffAmount
        );
        boolean paymentSuccess = processPayment(serviceAmount);
        if (!paymentSuccess) {
            settlement.setSettlementStatus(SettlementStatus.FAILED);
            settlementRepository.save(settlement);
            throw new BusinessException("Payment processing failed");
        }
        settlement.setSettlementStatus(SettlementStatus.PAID);
        settlement.setSettlementTime(Instant.now());
        settlementRepository.save(settlement);
        bookingService.markAsSettled(request.getBookingId());
        staffService.addStaffIncome(booking.getStaffId(), staffAmount);
        analyticsService.addToTotalRevenue(serviceAmount);
        serviceHistoryService.recordSettlementHistory(
            "PROCESS",
            "Settlement processed successfully. Amount: " + serviceAmount,
            request.getBookingId(),
            booking.getStaffId(),
            booking.getCustomerId()
        );
        return new SettlementResponse(settlementId, serviceAmount);
    }

    public List<Settlement> getAllSettlements() {
        return settlementRepository.findAll();
    }

    public Settlement getSettlementById(String settlementId) {
        return settlementRepository.findBySettlementId(settlementId)
            .orElseThrow(() -> new com.homeservice.exception.ResourceNotFoundException("Settlement not found: " + settlementId));
    }

    public Settlement getSettlementByBookingId(String bookingId) {
        return settlementRepository.findByBookingId(bookingId)
            .orElseThrow(() -> new com.homeservice.exception.ResourceNotFoundException("Settlement not found for booking: " + bookingId));
    }

    public List<Settlement> getSettlementsByStaffId(String staffId) {
        return settlementRepository.findByStaffId(staffId);
    }

    private double calculatePlatformFee(double serviceAmount) {
        return serviceAmount * PLATFORM_FEE_RATE;
    }

    private boolean processPayment(double amount) {
        return true;
    }
}
