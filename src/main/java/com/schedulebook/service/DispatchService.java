package com.schedulebook.service;

import com.schedulebook.config.ApplicationConfig;
import com.schedulebook.config.DispatchStrategyConfig;
import com.schedulebook.exception.BookingException;
import com.schedulebook.model.*;
import com.schedulebook.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class DispatchService {
    
    private static final Logger logger = LoggerFactory.getLogger(DispatchService.class);
    
    @Autowired
    private DispatchRepository dispatchRepository;
    
    @Autowired
    private ResourceRepository resourceRepository;
    
    @Autowired
    private ScheduleRepository scheduleRepository;
    
    @Autowired
    private ScheduleSlotRepository scheduleSlotRepository;
    
    @Autowired
    private IdGeneratorService idGeneratorService;
    
    @Autowired
    private DispatchStrategyConfig dispatchStrategyConfig;
    
    @Transactional
    public Dispatch allocateResource(Booking booking, String requestedResourceId) {
        return allocateResource(booking, requestedResourceId, null);
    }
    
    @Transactional
    public Dispatch allocateResource(Booking booking, String requestedResourceId, String strategyName) {
        logger.info("开始分配资源，预约ID: {}, 资源类型: {}, 策略: {}", 
                booking.getBookingId(), booking.getResourceType(), 
                strategyName != null ? strategyName : dispatchStrategyConfig.getDefaultStrategy());
        
        List<Resource> availableResources;
        
        if (requestedResourceId != null) {
            Optional<Resource> requestedResource = resourceRepository.findByResourceId(requestedResourceId);
            if (!requestedResource.isPresent()) {
                throw new BookingException(404, "请求的资源不存在");
            }
            Resource resource = requestedResource.get();
            if (!ApplicationConfig.RESOURCE_STATUS_AVAILABLE.equals(resource.getResourceStatus())) {
                throw new BookingException(400, "请求的资源当前不可用");
            }
            availableResources = List.of(resource);
        } else {
            availableResources = resourceRepository.findAvailableResourcesByType(booking.getResourceType());
        }
        
        if (availableResources.isEmpty()) {
            logger.warn("没有可用的资源，资源类型: {}", booking.getResourceType());
            return null;
        }
        
        String effectiveStrategy = strategyName != null ? strategyName : dispatchStrategyConfig.getDefaultStrategy();
        
        if (!dispatchStrategyConfig.isStrategyEnabled(effectiveStrategy)) {
            logger.warn("策略 {} 未启用，使用默认策略", effectiveStrategy);
            effectiveStrategy = dispatchStrategyConfig.getDefaultStrategy();
        }
        
        List<Resource> sortedResources = sortResourcesByStrategy(availableResources, effectiveStrategy);
        
        logger.debug("应用调度策略 {} 后，资源排序完成，候选资源数: {}", 
                effectiveStrategy, sortedResources.size());
        
        Resource selectedResource = null;
        ScheduleSlot selectedSlot = null;
        
        for (Resource resource : sortedResources) {
            Optional<Schedule> scheduleOptional = scheduleRepository.findByResourceIdAndScheduleDate(
                    resource.getResourceId(), booking.getBookingDate());
            
            if (!scheduleOptional.isPresent()) {
                continue;
            }
            
            Schedule schedule = scheduleOptional.get();
            Optional<ScheduleSlot> slotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                    schedule.getId(), booking.getBookingTime());
            
            if (slotOptional.isPresent()) {
                ScheduleSlot slot = slotOptional.get();
                if (ApplicationConfig.SLOT_STATUS_AVAILABLE.equals(slot.getSlotStatus()) &&
                        slot.getCurrentBookings() < schedule.getMaxBookingPerSlot()) {
                    selectedResource = resource;
                    selectedSlot = slot;
                    logger.debug("选择资源 {}，策略: {}", resource.getResourceId(), effectiveStrategy);
                    break;
                }
            }
        }
        
        if (selectedResource == null) {
            logger.warn("在指定时间段没有可用的资源，资源类型: {}, 时间: {}", 
                    booking.getResourceType(), booking.getBookingTime());
            return null;
        }
        
        Dispatch dispatch = new Dispatch();
        dispatch.setDispatchId(idGeneratorService.generateDispatchId());
        dispatch.setBookingId(booking.getBookingId());
        dispatch.setResourceId(selectedResource.getResourceId());
        dispatch.setDispatchTime(booking.getBookingTime());
        dispatch.setDispatchStatus(ApplicationConfig.DISPATCH_STATUS_ASSIGNED);
        dispatch.setDispatchedAt(LocalDateTime.now());
        
        dispatch = dispatchRepository.save(dispatch);
        
        selectedSlot.setSlotStatus(ApplicationConfig.SLOT_STATUS_BOOKED);
        selectedSlot.setCurrentBookings(selectedSlot.getCurrentBookings() + 1);
        selectedSlot.setBookingId(booking.getBookingId());
        scheduleSlotRepository.save(selectedSlot);
        
        selectedResource.setCurrentOccupancy(selectedResource.getCurrentOccupancy() + 1);
        resourceRepository.save(selectedResource);
        
        logger.info("资源分配成功，预约ID: {}, 资源ID: {}, 策略: {}", 
                booking.getBookingId(), selectedResource.getResourceId(), effectiveStrategy);
        return dispatch;
    }
    
    private List<Resource> sortResourcesByStrategy(List<Resource> resources, String strategyName) {
        DispatchStrategyConfig.StrategyConfig strategy = dispatchStrategyConfig.getStrategyConfig(strategyName);
        
        if (strategy == null) {
            logger.warn("未找到策略配置 {}，使用默认排序", strategyName);
            return resources;
        }
        
        String sortBy = strategy.getSortBy();
        String sortOrder = strategy.getSortOrder();
        List<String> sortFields = strategy.getSortFields();
        
        logger.debug("应用调度策略: {}, 排序字段: {}, 排序方向: {}", 
                strategyName, sortBy, sortOrder);
        
        Comparator<Resource> comparator = getComparatorForStrategy(sortBy, sortOrder, sortFields);
        
        return resources.stream()
                .sorted(comparator)
                .collect(java.util.stream.Collectors.toList());
    }
    
    private Comparator<Resource> getComparatorForStrategy(String sortBy, String sortOrder, List<String> sortFields) {
        Comparator<Resource> comparator;
        
        switch (sortBy != null ? sortBy : "priority") {
            case "priority":
                comparator = Comparator.comparingInt(Resource::getPriority);
                break;
            case "currentOccupancy":
                comparator = Comparator.comparingInt(Resource::getCurrentOccupancy);
                break;
            case "usageCount":
                comparator = Comparator.comparingInt(this::getUsageCount);
                break;
            case "capacity":
                comparator = Comparator.comparingInt(r -> r.getResourceCapacity() != null ? r.getResourceCapacity() : 0);
                break;
            default:
                comparator = Comparator.comparingInt(Resource::getPriority);
        }
        
        if ("desc".equalsIgnoreCase(sortOrder)) {
            comparator = comparator.reversed();
        }
        
        if (sortFields != null && !sortFields.isEmpty()) {
            for (int i = 1; i < sortFields.size(); i++) {
                String secondaryField = sortFields.get(i);
                Comparator<Resource> secondaryComparator = getSecondaryComparator(secondaryField);
                if (secondaryComparator != null) {
                    if ("desc".equalsIgnoreCase(sortOrder)) {
                        secondaryComparator = secondaryComparator.reversed();
                    }
                    comparator = comparator.thenComparing(secondaryComparator);
                }
            }
        }
        
        return comparator;
    }
    
    private Comparator<Resource> getSecondaryComparator(String field) {
        switch (field) {
            case "priority":
                return Comparator.comparingInt(Resource::getPriority);
            case "currentOccupancy":
                return Comparator.comparingInt(Resource::getCurrentOccupancy);
            case "usageCount":
                return Comparator.comparingInt(this::getUsageCount);
            case "capacity":
                return Comparator.comparingInt(r -> r.getResourceCapacity() != null ? r.getResourceCapacity() : 0);
            default:
                return null;
        }
    }
    
    private int getUsageCount(Resource resource) {
        return resource.getCurrentOccupancy();
    }
    
    @Transactional
    public void releaseResource(Booking booking) {
        logger.info("开始释放资源，预约ID: {}", booking.getBookingId());
        
        Optional<Dispatch> dispatchOptional = dispatchRepository.findByBookingIdAndDispatchStatus(
                booking.getBookingId(), ApplicationConfig.DISPATCH_STATUS_ASSIGNED);
        
        if (!dispatchOptional.isPresent()) {
            logger.warn("没有找到分配的调度记录，预约ID: {}", booking.getBookingId());
            return;
        }
        
        Dispatch dispatch = dispatchOptional.get();
        dispatch.setDispatchStatus(ApplicationConfig.DISPATCH_STATUS_RELEASED);
        dispatch.setReleasedAt(LocalDateTime.now());
        dispatchRepository.save(dispatch);
        
        Optional<Resource> resourceOptional = resourceRepository.findByResourceId(dispatch.getResourceId());
        if (resourceOptional.isPresent()) {
            Resource resource = resourceOptional.get();
            if (resource.getCurrentOccupancy() > 0) {
                resource.setCurrentOccupancy(resource.getCurrentOccupancy() - 1);
            }
            resourceRepository.save(resource);
        }
        
        Optional<Schedule> scheduleOptional = scheduleRepository.findByResourceIdAndScheduleDate(
                dispatch.getResourceId(), booking.getBookingDate());
        
        if (scheduleOptional.isPresent()) {
            Optional<ScheduleSlot> slotOptional = scheduleSlotRepository.findByScheduleIdAndSlotTime(
                    scheduleOptional.get().getId(), booking.getBookingTime());
            
            if (slotOptional.isPresent()) {
                ScheduleSlot slot = slotOptional.get();
                if (slot.getCurrentBookings() > 0) {
                    slot.setCurrentBookings(slot.getCurrentBookings() - 1);
                }
                if (slot.getCurrentBookings() == 0) {
                    slot.setSlotStatus(ApplicationConfig.SLOT_STATUS_AVAILABLE);
                }
                slot.setBookingId(null);
                scheduleSlotRepository.save(slot);
            }
        }
        
        logger.info("资源释放成功，预约ID: {}, 资源ID: {}", booking.getBookingId(), dispatch.getResourceId());
    }
    
    public Dispatch getDispatch(String dispatchId) {
        return dispatchRepository.findByDispatchId(dispatchId)
                .orElseThrow(() -> new BookingException(404, "调度记录不存在"));
    }
    
    public List<Dispatch> getDispatchesByBooking(String bookingId) {
        return dispatchRepository.findByBookingId(bookingId);
    }
    
    public String getDefaultStrategy() {
        return dispatchStrategyConfig.getDefaultStrategy();
    }
    
    public Map<String, DispatchStrategyConfig.StrategyConfig> getAvailableStrategies() {
        return dispatchStrategyConfig.getStrategies();
    }
    
    public boolean isStrategyEnabled(String strategyName) {
        return dispatchStrategyConfig.isStrategyEnabled(strategyName);
    }
    
    public void setDefaultStrategy(String strategyName) {
        if (dispatchStrategyConfig.isStrategyEnabled(strategyName)) {
            dispatchStrategyConfig.setDefaultStrategy(strategyName);
            logger.info("默认调度策略已更新为: {}", strategyName);
        } else {
            logger.warn("策略 {} 未启用，无法设置为默认策略", strategyName);
        }
    }
    
    public void addStrategy(String name, DispatchStrategyConfig.StrategyConfig config) {
        dispatchStrategyConfig.addStrategy(name, config);
        logger.info("添加新的调度策略: {}", name);
    }
    
    public void removeStrategy(String name) {
        if (!dispatchStrategyConfig.getDefaultStrategy().equals(name)) {
            dispatchStrategyConfig.removeStrategy(name);
            logger.info("移除调度策略: {}", name);
        } else {
            logger.warn("不能移除默认策略: {}", name);
        }
    }
    
    public void updateStrategy(String name, DispatchStrategyConfig.StrategyConfig config) {
        dispatchStrategyConfig.updateStrategy(name, config);
        logger.info("更新调度策略: {}", name);
    }
}
