package com.travelbooking.service;

import com.travelbooking.config.SettlementConfig;
import com.travelbooking.dto.SettlementTaskDTO;
import com.travelbooking.model.Booking;
import com.travelbooking.model.Settlement;
import com.travelbooking.repository.SettlementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SettlementProcessor {

    private final SettlementRepository settlementRepository;
    private final BookingService bookingService;
    private final AnalyticsService analyticsService;
    private final HistoryService historyService;
    private final SettlementConfig settlementConfig;

    public boolean processTask(SettlementTaskDTO task) {
        log.info("开始执行结算处理 - 任务ID: {}, 预订ID: {}", task.getTaskId(), task.getBookingId());

        Optional<Booking> bookingOpt = bookingService.getBookingById(task.getBookingId());
        if (bookingOpt.isEmpty()) {
            log.warn("预订不存在，跳过结算 - 预订ID: {}", task.getBookingId());
            return true;
        }

        Booking booking = bookingOpt.get();

        if ("settled".equals(booking.getBookingStatus())) {
            log.info("预订已结算，跳过处理 - 预订ID: {}", task.getBookingId());
            return true;
        }

        Optional<Settlement> existingSettlement = settlementRepository
                .findByBookingIdAndPaymentStatus(task.getBookingId(), "paid");
        if (existingSettlement.isPresent()) {
            log.info("已存在成功的结算记录，跳过处理 - 预订ID: {}", task.getBookingId());
            bookingService.completeSettlement(task.getBookingId(), booking.getBookingAmount());
            return true;
        }

        try {
            Settlement settlement = createSettlement(task, booking);
            Settlement saved = settlementRepository.save(settlement);

            boolean paymentSuccess = processPayment(task, booking);

            if (paymentSuccess) {
                saved.setPaymentStatus("paid");
                saved.setSettlementStatus("completed");
                settlementRepository.save(saved);

                bookingService.completeSettlement(task.getBookingId(), booking.getBookingAmount());
                analyticsService.updateSettlementStatistics(booking.getBookingAmount());
                historyService.recordHistory("settlement", saved.getSettlementId(),
                        "success", "Redis结算成功，金额: " + booking.getBookingAmount());

                log.info("结算完成 - 任务ID: {}, 预订ID: {}, 金额: {}", 
                        task.getTaskId(), task.getBookingId(), booking.getBookingAmount());
                return true;
            } else {
                log.warn("支付处理失败 - 任务ID: {}, 预订ID: {}", task.getTaskId(), task.getBookingId());
                return false;
            }
        } catch (Exception e) {
            log.error("结算处理异常 - 任务ID: {}, 预订ID: {}", task.getTaskId(), task.getBookingId(), e);
            return false;
        }
    }

    private Settlement createSettlement(SettlementTaskDTO task, Booking booking) {
        Settlement settlement = new Settlement();
        settlement.setSettlementId(task.getTaskId());
        settlement.setBookingId(task.getBookingId());
        settlement.setItineraryId(task.getItineraryId());
        settlement.setTouristId(booking.getTouristId());
        settlement.setSettlementAmount(booking.getBookingAmount());
        settlement.setPaymentMethod(task.getPaymentMethod() != null ? task.getPaymentMethod() : "default");
        settlement.setPaymentStatus("pending");
        settlement.setSettlementStatus("processing");
        settlement.setSettlementTime(Instant.now());
        return settlement;
    }

    private boolean processPayment(SettlementTaskDTO task, Booking booking) {
        log.debug("处理支付 - 预订ID: {}, 金额: {}", task.getBookingId(), booking.getBookingAmount());
        return true;
    }
}
