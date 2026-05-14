package com.schedulebook.service;

import com.schedulebook.config.ApplicationConfig;
import com.schedulebook.exception.BookingException;
import com.schedulebook.model.*;
import com.schedulebook.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ScheduleService {
    
    private static final Logger logger = LoggerFactory.getLogger(ScheduleService.class);
    
    @Autowired
    private ScheduleRepository scheduleRepository;
    
    @Autowired
    private ScheduleSlotRepository scheduleSlotRepository;
    
    @Autowired
    private ResourceRepository resourceRepository;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Transactional
    public Schedule createSchedule(String resourceId, LocalDate scheduleDate, List<LocalTime> slotTimes) {
        logger.info("开始创建排班计划，资源ID: {}, 日期: {}", resourceId, scheduleDate);
        
        if (!resourceRepository.existsByResourceId(resourceId)) {
            throw new BookingException(404, "资源不存在");
        }
        
        if (scheduleRepository.existsByResourceIdAndScheduleDate(resourceId, scheduleDate)) {
            throw new BookingException(400, "该资源在指定日期已有排班计划");
        }
        
        Schedule schedule = new Schedule();
        schedule.setScheduleId(idGeneratorService.generateScheduleId());
        schedule.setResourceId(resourceId);
        schedule.setScheduleDate(scheduleDate);
        schedule.setMaxBookingPerSlot(1);
        schedule.setCreatedAt(LocalDateTime.now());
        schedule.setUpdatedAt(LocalDateTime.now());
        
        for (LocalTime slotTime : slotTimes) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setSlotTime(slotTime);
            slot.setSlotStatus(ApplicationConfig.SLOT_STATUS_AVAILABLE);
            slot.setCurrentBookings(0);
            schedule.addSlot(slot);
        }
        
        schedule = scheduleRepository.save(schedule);
        logger.info("排班计划创建成功，排班ID: {}", schedule.getScheduleId());
        return schedule;
    }
    
    public Schedule getSchedule(String scheduleId) {
        return scheduleRepository.findByScheduleId(scheduleId)
                .orElseThrow(() -> new BookingException(404, "排班计划不存在"));
    }
    
    public List<Map<String, Object>> querySchedule(String resourceId, LocalDate scheduleDate) {
        logger.info("查询排班信息，资源ID: {}, 日期: {}", resourceId, scheduleDate);
        
        List<Map<String, Object>> slots = new ArrayList<>();
        
        Optional<Schedule> scheduleOptional = scheduleRepository.findByResourceIdAndScheduleDate(resourceId, scheduleDate);
        
        if (!scheduleOptional.isPresent()) {
            return slots;
        }
        
        Schedule schedule = scheduleOptional.get();
        List<ScheduleSlot> scheduleSlots = scheduleSlotRepository.findByScheduleId(schedule.getId());
        
        for (ScheduleSlot slot : scheduleSlots) {
            Map<String, Object> slotInfo = new HashMap<>();
            slotInfo.put("slot_time", slot.getSlotTime().toString());
            slotInfo.put("status", slot.getSlotStatus());
            slots.add(slotInfo);
        }
        
        return slots;
    }
    
    @Transactional
    public Schedule updateSchedule(String scheduleId, List<LocalTime> newSlotTimes) {
        logger.info("更新排班计划，排班ID: {}", scheduleId);
        
        Schedule schedule = getSchedule(scheduleId);
        
        schedule.getSlots().clear();
        
        for (LocalTime slotTime : newSlotTimes) {
            ScheduleSlot slot = new ScheduleSlot();
            slot.setSlotTime(slotTime);
            slot.setSlotStatus(ApplicationConfig.SLOT_STATUS_AVAILABLE);
            slot.setCurrentBookings(0);
            schedule.addSlot(slot);
        }
        
        schedule.setUpdatedAt(LocalDateTime.now());
        schedule = scheduleRepository.save(schedule);
        logger.info("排班计划更新成功，排班ID: {}", scheduleId);
        return schedule;
    }
    
    @Transactional
    public void deleteSchedule(String scheduleId) {
        logger.info("删除排班计划，排班ID: {}", scheduleId);
        
        Schedule schedule = getSchedule(scheduleId);
        scheduleRepository.delete(schedule);
        logger.info("排班计划删除成功，排班ID: {}", scheduleId);
    }
    
    public List<Schedule> getSchedulesByResource(String resourceId) {
        return scheduleRepository.findByResourceId(resourceId);
    }
    
    public List<Schedule> getSchedulesByDate(LocalDate scheduleDate) {
        return scheduleRepository.findByScheduleDate(scheduleDate);
    }
}
