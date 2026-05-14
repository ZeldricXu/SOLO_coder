package com.schedulebook.service;

import com.schedulebook.config.ApplicationConfig;
import com.schedulebook.model.Booking;
import com.schedulebook.model.BookingStatistics;
import com.schedulebook.repository.BookingRepository;
import com.schedulebook.repository.BookingStatisticsRepository;
import com.schedulebook.repository.ResourceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class StatisticsService {
    
    private static final Logger logger = LoggerFactory.getLogger(StatisticsService.class);
    
    @Autowired
    private BookingStatisticsRepository statisticsRepository;
    
    @Autowired
    private BookingRepository bookingRepository;
    
    @Autowired
    private ResourceRepository resourceRepository;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Transactional
    public void updateStatisticsOnBooking(Booking booking) {
        logger.info("更新预约统计，预约ID: {}", booking.getBookingId());
        
        LocalDate statDate = LocalDate.now();
        BookingStatistics statistics = getOrCreateStatistics(statDate);
        
        statistics.setTotalBookings(statistics.getTotalBookings() + 1);
        statistics.setConfirmedBookings(statistics.getConfirmedBookings() + 1);
        statistics.setUpdatedAt(LocalDateTime.now());
        
        statisticsRepository.save(statistics);
        logger.info("预约统计更新成功，日期: {}", statDate);
    }
    
    @Transactional
    public void updateStatisticsOnCancel(Booking booking) {
        logger.info("更新取消统计，预约ID: {}", booking.getBookingId());
        
        LocalDate statDate = LocalDate.now();
        BookingStatistics statistics = getOrCreateStatistics(statDate);
        
        statistics.setCancelledBookings(statistics.getCancelledBookings() + 1);
        statistics.setUpdatedAt(LocalDateTime.now());
        
        statisticsRepository.save(statistics);
        logger.info("取消统计更新成功，日期: {}", statDate);
    }
    
    private BookingStatistics getOrCreateStatistics(LocalDate statDate) {
        Optional<BookingStatistics> optional = statisticsRepository.findByStatDate(statDate);
        
        if (optional.isPresent()) {
            return optional.get();
        }
        
        BookingStatistics statistics = new BookingStatistics();
        statistics.setStatId(idGeneratorService.generateStatId());
        statistics.setStatDate(statDate);
        statistics.setTotalBookings(0);
        statistics.setConfirmedBookings(0);
        statistics.setCancelledBookings(0);
        statistics.setResourceUtilization(0);
        statistics.setUpdatedAt(LocalDateTime.now());
        
        return statisticsRepository.save(statistics);
    }
    
    public BookingStatistics getStatistics(LocalDate statDate) {
        return statisticsRepository.findByStatDate(statDate)
                .orElseThrow(() -> new RuntimeException("统计数据不存在"));
    }
    
    public BookingStatistics calculateDailyStatistics(LocalDate date) {
        logger.info("计算每日统计，日期: {}", date);
        
        BookingStatistics statistics = getOrCreateStatistics(date);
        
        Long totalBookings = bookingRepository.countByDate(date);
        Long confirmedBookings = bookingRepository.countByDateAndStatus(date, ApplicationConfig.BOOKING_STATUS_CONFIRMED);
        Long cancelledBookings = bookingRepository.countByDateAndStatus(date, ApplicationConfig.BOOKING_STATUS_CANCELLED);
        
        statistics.setTotalBookings(totalBookings.intValue());
        statistics.setConfirmedBookings(confirmedBookings.intValue());
        statistics.setCancelledBookings(cancelledBookings.intValue());
        
        Long totalResources = resourceRepository.count();
        if (totalResources > 0) {
            int utilization = (int) ((confirmedBookings * 100) / (totalResources * 8));
            statistics.setResourceUtilization(Math.min(utilization, 100));
        }
        
        statistics.setUpdatedAt(LocalDateTime.now());
        statistics = statisticsRepository.save(statistics);
        
        logger.info("每日统计计算完成，总预约: {}, 已确认: {}, 已取消: {}", 
                totalBookings, confirmedBookings, cancelledBookings);
        
        return statistics;
    }
}
