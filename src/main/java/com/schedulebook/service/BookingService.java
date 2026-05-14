package com.schedulebook.service;

import com.schedulebook.config.ApplicationConfig;
import com.schedulebook.dto.CancelBookingRequest;
import com.schedulebook.dto.CreateBookingRequest;
import com.schedulebook.exception.BookingException;
import com.schedulebook.model.*;
import com.schedulebook.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
public class BookingService {
    
    private static final Logger logger = LoggerFactory.getLogger(BookingService.class);
    
    @Autowired
    private BookingRepository bookingRepository;
    
    @Autowired
    private DispatchService dispatchService;
    
    @Autowired
    private ScheduleService scheduleService;
    
    @Autowired
    private ReminderService reminderService;
    
    @Autowired
    private StatisticsService statisticsService;
    
    @Autowired
    private HistoryService historyService;
    
    @Autowired
    private StatusTrackingService statusTrackingService;
    
    @Autowired
    private CancelService cancelService;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Transactional
    public Map<String, Object> createBooking(CreateBookingRequest request) {
        logger.info("开始创建预约，用户ID: {}, 资源类型: {}", request.getUserId(), request.getResourceType());
        
        Booking booking = new Booking();
        booking.setBookingId(idGeneratorService.generateBookingId());
        booking.setUserId(request.getUserId());
        booking.setResourceType(request.getResourceType());
        booking.setBookingDate(request.getBookingDate());
        booking.setBookingTime(request.getBookingTime());
        booking.setBookingDuration(request.getBookingDuration() != null ? request.getBookingDuration() : 60);
        booking.setBookingStatus(ApplicationConfig.BOOKING_STATUS_PENDING);
        booking.setCreatedAt(LocalDateTime.now());
        
        if (request.getResourceId() != null) {
            booking.setResourceId(request.getResourceId());
        }
        
        booking = bookingRepository.save(booking);
        logger.info("预约记录已创建，预约ID: {}", booking.getBookingId());
        
        historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_CREATE, "预约创建成功");
        
        try {
            Dispatch dispatch = dispatchService.allocateResource(booking, request.getResourceId());
            
            if (dispatch != null) {
                booking.setResourceId(dispatch.getResourceId());
                booking.setBookingStatus(ApplicationConfig.BOOKING_STATUS_CONFIRMED);
                booking.setConfirmedAt(LocalDateTime.now());
                booking = bookingRepository.save(booking);
                
                statusTrackingService.updateStatus(booking.getBookingId(), ApplicationConfig.BOOKING_STATUS_CONFIRMED);
                reminderService.createReminder(booking);
                statisticsService.updateStatisticsOnBooking(booking);
                historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_CONFIRM, "预约已确认，资源分配成功");
                
                Map<String, Object> result = new HashMap<>();
                result.put("booking_id", booking.getBookingId());
                result.put("status", ApplicationConfig.BOOKING_STATUS_CONFIRMED);
                result.put("resource_id", dispatch.getResourceId());
                
                logger.info("预约创建成功，预约ID: {}", booking.getBookingId());
                return result;
            } else {
                booking.setBookingStatus(ApplicationConfig.BOOKING_STATUS_REJECTED);
                booking = bookingRepository.save(booking);
                
                statusTrackingService.updateStatus(booking.getBookingId(), ApplicationConfig.BOOKING_STATUS_REJECTED);
                historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_CREATE, "预约被拒绝，资源不可用");
                
                throw new BookingException(400, "资源不可用，预约失败");
            }
        } catch (BookingException e) {
            booking.setBookingStatus(ApplicationConfig.BOOKING_STATUS_REJECTED);
            booking = bookingRepository.save(booking);
            historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_CREATE, "预约失败: " + e.getMessage());
            throw e;
        }
    }
    
    @Transactional
    public Map<String, Object> cancelBooking(CancelBookingRequest request) {
        logger.info("开始取消预约，预约ID: {}", request.getBookingId());
        
        Optional<Booking> bookingOptional = bookingRepository.findByBookingId(request.getBookingId());
        
        if (!bookingOptional.isPresent()) {
            throw new BookingException(404, "预约不存在");
        }
        
        Booking booking = bookingOptional.get();
        
        if (ApplicationConfig.BOOKING_STATUS_COMPLETED.equals(booking.getBookingStatus())) {
            throw new BookingException(400, "已完成的预约无法取消");
        }
        
        if (ApplicationConfig.BOOKING_STATUS_CANCELLED.equals(booking.getBookingStatus())) {
            throw new BookingException(400, "预约已经被取消");
        }
        
        CancelRecord cancelRecord = cancelService.processCancel(booking, request);
        
        booking.setBookingStatus(ApplicationConfig.BOOKING_STATUS_CANCELLED);
        booking.setCancelledAt(LocalDateTime.now());
        booking = bookingRepository.save(booking);
        
        dispatchService.releaseResource(booking);
        reminderService.cancelReminders(booking.getBookingId());
        statusTrackingService.updateStatus(booking.getBookingId(), ApplicationConfig.BOOKING_STATUS_CANCELLED);
        statisticsService.updateStatisticsOnCancel(booking);
        historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_CANCEL, "预约已取消: " + request.getCancelReason());
        
        Map<String, Object> result = new HashMap<>();
        result.put("cancel_id", cancelRecord.getCancelId());
        result.put("status", ApplicationConfig.BOOKING_STATUS_CANCELLED);
        
        logger.info("预约取消成功，预约ID: {}", booking.getBookingId());
        return result;
    }
    
    public Booking getBooking(String bookingId) {
        return bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new BookingException(404, "预约不存在"));
    }
    
    @Transactional
    public Booking updateBookingStatus(String bookingId, String newStatus) {
        Booking booking = getBooking(bookingId);
        booking.setBookingStatus(newStatus);
        
        if (ApplicationConfig.BOOKING_STATUS_COMPLETED.equals(newStatus)) {
            historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_COMPLETE, "预约已完成");
        }
        
        return bookingRepository.save(booking);
    }
}
