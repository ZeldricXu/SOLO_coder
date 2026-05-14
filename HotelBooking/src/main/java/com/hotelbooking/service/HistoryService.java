package com.hotelbooking.service;

import com.hotelbooking.model.Booking;
import com.hotelbooking.model.CheckIn;
import com.hotelbooking.model.Settlement;
import com.hotelbooking.repository.BookingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class HistoryService {
    private static final Logger logger = LoggerFactory.getLogger(HistoryService.class);

    private final BookingRepository bookingRepository;

    public HistoryService(BookingRepository bookingRepository) {
        this.bookingRepository = bookingRepository;
    }

    public void recordBookingHistory(Booking booking, String action, String description) {
        logger.info("预订历史记录: 预订ID={}, 操作={}, 描述={}", booking.getBookingId(), action, description);
    }

    public void recordCheckInHistory(CheckIn checkIn, Booking booking, String action, String description) {
        logger.info("入住历史记录: 入住ID={}, 预订ID={}, 操作={}, 描述={}", 
                checkIn.getCheckinId(), booking.getBookingId(), action, description);
    }

    public void recordSettlementHistory(Settlement settlement, Booking booking, String action, String description) {
        logger.info("结算历史记录: 结算ID={}, 预订ID={}, 操作={}, 描述={}", 
                settlement.getSettlementId(), booking.getBookingId(), action, description);
    }

    public List<Booking> getCustomerBookingHistory(String customerPhone) {
        return bookingRepository.findByCustomerPhoneOrderByCreatedAtDesc(customerPhone);
    }

    public List<Booking> getHotelBookingHistory(String hotelId) {
        return bookingRepository.findByHotelId(hotelId);
    }
}
