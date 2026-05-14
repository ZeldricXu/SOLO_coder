package com.schedulebook.service;

import com.schedulebook.config.ApplicationConfig;
import com.schedulebook.exception.BookingException;
import com.schedulebook.model.Booking;
import com.schedulebook.model.Schedule;
import com.schedulebook.model.ScheduleSlot;
import com.schedulebook.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AdjustmentService {
    
    private static final Logger logger = LoggerFactory.getLogger(AdjustmentService.class);
    
    @Autowired
    private ScheduleRepository scheduleRepository;
    
    @Autowired
    private ScheduleSlotRepository scheduleSlotRepository;
    
    @Autowired
    private BookingRepository bookingRepository;
    
    @Autowired
    private HistoryService historyService;
    
    private final Map<String, ConflictDetectionResult> conflictDetectionCache = new ConcurrentHashMap<>();
    
    @Transactional
    public Schedule adjustScheduleTime(String scheduleId, LocalTime oldTime, LocalTime newTime) {
        logger.info("调整排班时间，排班ID: {}, 原时间: {}, 新时间: {}", scheduleId, oldTime, newTime);
        
        ConflictDetectionResult result = checkScheduleAdjustmentConflict(scheduleId, oldTime, newTime);
        
        if (result.hasConflict) {
            throw new BookingException(400, result.conflictReason);
        }
        
        Schedule schedule = scheduleRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new BookingException(404, "排班计划不存在"));
        
        Optional<ScheduleSlot> slotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                schedule.getId(), oldTime);
        
        if (!slotOptional.isPresent()) {
            throw new BookingException(404, "时间段不存在");
        }
        
        ScheduleSlot slot = slotOptional.get();
        
        if (ApplicationConfig.SLOT_STATUS_BOOKED.equals(slot.getSlotStatus())) {
            throw new BookingException(400, "该时间段已被预约，无法调整");
        }
        
        slot.setSlotTime(newTime);
        scheduleSlotRepository.save(slot);
        
        schedule.setUpdatedAt(LocalDateTime.now());
        schedule = scheduleRepository.save(schedule);
        
        logger.info("排班时间调整成功，排班ID: {}", scheduleId);
        return schedule;
    }
    
    @Transactional
    public void adjustBookingTime(String bookingId, LocalDate newDate, LocalTime newTime) {
        logger.info("调整预约时间，预约ID: {}, 新日期: {}, 新时间: {}", bookingId, newDate, newTime);
        
        ConflictDetectionResult result = checkBookingAdjustmentConflict(bookingId, newDate, newTime);
        
        if (result.hasConflict) {
            throw new BookingException(400, result.conflictReason);
        }
        
        Booking booking = bookingRepository.findByBookingId(bookingId)
                .orElseThrow(() -> new BookingException(404, "预约不存在"));
        
        if (!ApplicationConfig.BOOKING_STATUS_CONFIRMED.equals(booking.getBookingStatus())) {
            throw new BookingException(400, "只有已确认的预约才能调整时间");
        }
        
        Optional<Schedule> scheduleOptional = scheduleRepository.findByResourceIdAndScheduleDate(
                booking.getResourceId(), newDate);
        
        if (!scheduleOptional.isPresent()) {
            throw new BookingException(404, "新日期没有排班计划");
        }
        
        Optional<ScheduleSlot> slotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                scheduleOptional.get().getId(), newTime);
        
        if (!slotOptional.isPresent() || !ApplicationConfig.SLOT_STATUS_AVAILABLE.equals(slotOptional.get().getSlotStatus())) {
            throw new BookingException(400, "新时间段不可用");
        }
        
        Optional<Schedule> oldScheduleOptional = scheduleRepository.findByResourceIdAndScheduleDate(
                booking.getResourceId(), booking.getBookingDate());
        
        if (oldScheduleOptional.isPresent()) {
            Optional<ScheduleSlot> oldSlotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                    oldScheduleOptional.get().getId(), booking.getBookingTime());
            
            if (oldSlotOptional.isPresent()) {
                ScheduleSlot oldSlot = oldSlotOptional.get();
                oldSlot.setSlotStatus(ApplicationConfig.SLOT_STATUS_AVAILABLE);
                oldSlot.setCurrentBookings(Math.max(0, oldSlot.getCurrentBookings() - 1));
                oldSlot.setBookingId(null);
                scheduleSlotRepository.save(oldSlot);
            }
        }
        
        ScheduleSlot newSlot = slotOptional.get();
        newSlot.setSlotStatus(ApplicationConfig.SLOT_STATUS_BOOKED);
        newSlot.setCurrentBookings(newSlot.getCurrentBookings() + 1);
        newSlot.setBookingId(bookingId);
        scheduleSlotRepository.save(newSlot);
        
        booking.setBookingDate(newDate);
        booking.setBookingTime(newTime);
        bookingRepository.save(booking);
        
        historyService.recordHistory(booking, ApplicationConfig.ACTION_TYPE_ADJUST, 
                "预约时间已调整至 " + newDate + " " + newTime);
        
        logger.info("预约时间调整成功，预约ID: {}", bookingId);
    }
    
    public ConflictDetectionResult checkScheduleAdjustmentConflict(String scheduleId, LocalTime oldTime, LocalTime newTime) {
        String cacheKey = "schedule_" + scheduleId + "_" + oldTime + "_" + newTime;
        
        if (conflictDetectionCache.containsKey(cacheKey)) {
            ConflictDetectionResult cached = conflictDetectionCache.get(cacheKey);
            if (!cached.isExpired()) {
                return cached;
            }
        }
        
        ConflictDetectionResult result = performScheduleConflictDetection(scheduleId, oldTime, newTime);
        conflictDetectionCache.put(cacheKey, result);
        
        return result;
    }
    
    private ConflictDetectionResult performScheduleConflictDetection(String scheduleId, LocalTime oldTime, LocalTime newTime) {
        Optional<Schedule> scheduleOptional = scheduleRepository.findByScheduleId(scheduleId);
        
        if (!scheduleOptional.isPresent()) {
            return new ConflictDetectionResult(false, null, null);
        }
        
        Schedule schedule = scheduleOptional.get();
        
        Optional<ScheduleSlot> oldSlotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                schedule.getId(), oldTime);
        
        if (!oldSlotOptional.isPresent()) {
            return new ConflictDetectionResult(true, "要调整的时间段不存在", new ArrayList<>());
        }
        
        ScheduleSlot oldSlot = oldSlotOptional.get();
        
        if (ApplicationConfig.SLOT_STATUS_BOOKED.equals(oldSlot.getSlotStatus())) {
            String bookingId = oldSlot.getBookingId();
            List<String> conflictingBookings = new ArrayList<>();
            if (bookingId != null) {
                conflictingBookings.add(bookingId);
            }
            return new ConflictDetectionResult(true, "该时间段已被预约，无法调整", conflictingBookings);
        }
        
        Optional<ScheduleSlot> newSlotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                schedule.getId(), newTime);
        
        if (newSlotOptional.isPresent()) {
            ScheduleSlot newSlot = newSlotOptional.get();
            if (ApplicationConfig.SLOT_STATUS_BOOKED.equals(newSlot.getSlotStatus())) {
                List<String> conflictingBookings = new ArrayList<>();
                if (newSlot.getBookingId() != null) {
                    conflictingBookings.add(newSlot.getBookingId());
                }
                return new ConflictDetectionResult(true, "目标时间段已被预约", conflictingBookings);
            }
        }
        
        return new ConflictDetectionResult(false, null, null);
    }
    
    public ConflictDetectionResult checkBookingAdjustmentConflict(String bookingId, LocalDate newDate, LocalTime newTime) {
        String cacheKey = "booking_" + bookingId + "_" + newDate + "_" + newTime;
        
        if (conflictDetectionCache.containsKey(cacheKey)) {
            ConflictDetectionResult cached = conflictDetectionCache.get(cacheKey);
            if (!cached.isExpired()) {
                return cached;
            }
        }
        
        ConflictDetectionResult result = performBookingConflictDetection(bookingId, newDate, newTime);
        conflictDetectionCache.put(cacheKey, result);
        
        return result;
    }
    
    private ConflictDetectionResult performBookingConflictDetection(String bookingId, LocalDate newDate, LocalTime newTime) {
        Optional<Booking> bookingOptional = bookingRepository.findByBookingId(bookingId);
        
        if (!bookingOptional.isPresent()) {
            return new ConflictDetectionResult(true, "预约不存在", new ArrayList<>());
        }
        
        Booking booking = bookingOptional.get();
        
        if (!ApplicationConfig.BOOKING_STATUS_CONFIRMED.equals(booking.getBookingStatus())) {
            return new ConflictDetectionResult(true, "只有已确认的预约才能调整时间", new ArrayList<>());
        }
        
        Optional<Schedule> scheduleOptional = scheduleRepository.findByResourceIdAndScheduleDate(
                booking.getResourceId(), newDate);
        
        if (!scheduleOptional.isPresent()) {
            return new ConflictDetectionResult(true, "新日期没有排班计划", new ArrayList<>());
        }
        
        Optional<ScheduleSlot> slotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                scheduleOptional.get().getId(), newTime);
        
        if (!slotOptional.isPresent()) {
            return new ConflictDetectionResult(true, "目标时间段不存在", new ArrayList<>());
        }
        
        ScheduleSlot slot = slotOptional.get();
        
        if (!ApplicationConfig.SLOT_STATUS_AVAILABLE.equals(slot.getSlotStatus())) {
            List<String> conflictingBookings = new ArrayList<>();
            if (slot.getBookingId() != null && !slot.getBookingId().equals(bookingId)) {
                conflictingBookings.add(slot.getBookingId());
            }
            return new ConflictDetectionResult(true, "目标时间段已被占用", conflictingBookings);
        }
        
        return new ConflictDetectionResult(false, null, null);
    }
    
    @Async
    public CompletableFuture<ConflictDetectionResult> checkBookingAdjustmentConflictAsync(
            String bookingId, LocalDate newDate, LocalTime newTime) {
        logger.info("异步检查预约调整冲突，预约ID: {}", bookingId);
        ConflictDetectionResult result = checkBookingAdjustmentConflict(bookingId, newDate, newTime);
        return CompletableFuture.completedFuture(result);
    }
    
    @Async
    public CompletableFuture<ConflictDetectionResult> checkScheduleAdjustmentConflictAsync(
            String scheduleId, LocalTime oldTime, LocalTime newTime) {
        logger.info("异步检查排班调整冲突，排班ID: {}", scheduleId);
        ConflictDetectionResult result = checkScheduleAdjustmentConflict(scheduleId, oldTime, newTime);
        return CompletableFuture.completedFuture(result);
    }
    
    public List<Schedule> getAdjustableSchedules(String resourceId) {
        return scheduleRepository.findByResourceId(resourceId);
    }
    
    public void clearConflictDetectionCache() {
        conflictDetectionCache.clear();
        logger.info("冲突检测缓存已清除");
    }
    
    public static class ConflictDetectionResult {
        public final boolean hasConflict;
        public final String conflictReason;
        public final List<String> conflictingBookings;
        private final LocalDateTime createdAt;
        private static final long CACHE_TTL_MS = 30000;
        
        public ConflictDetectionResult(boolean hasConflict, String conflictReason, List<String> conflictingBookings) {
            this.hasConflict = hasConflict;
            this.conflictReason = conflictReason;
            this.conflictingBookings = conflictingBookings;
            this.createdAt = LocalDateTime.now();
        }
        
        public boolean isExpired() {
            return java.time.Duration.between(createdAt, LocalDateTime.now()).toMillis() > CACHE_TTL_MS;
        }
        
        public boolean hasNoConflict() {
            return !hasConflict;
        }
    }
}
