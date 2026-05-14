package com.logistics.service;

import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.AssignTaskRequest;
import com.logistics.dto.AssignTaskResponse;
import com.logistics.dto.UpdateTaskRequest;
import com.logistics.dto.UpdateTaskResponse;
import com.logistics.entity.*;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.DeliveryTaskRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeliveryService {

    private final DeliveryTaskRepository deliveryTaskRepository;
    private final LogisticsService logisticsService;
    private final CourierService courierService;
    private final StationService stationService;
    private final TrackService trackService;
    private final AsyncNotificationService statusService;
    private final FeeService feeService;
    private final StatisticsService statisticsService;
    private final HistoryService historyService;
    private final CourierLockService courierLockService;
    private final DeliveryTypeService deliveryTypeService;

    @Transactional
    public AssignTaskResponse assignTask(AssignTaskRequest request) {
        Logistics logistics = logisticsService.getLogisticsById(request.getLogisticsId());
        
        String deliveryTypeCode = logistics.getDeliveryTypeCode();
        DeliveryType deliveryType = deliveryTypeService.getDeliveryType(deliveryTypeCode);
        
        String urgency = request.getUrgencyLevel();
        if (urgency == null || urgency.trim().isEmpty()) {
            urgency = deliveryType.getUrgencyLevel();
        }

        return assignTaskWithUrgency(request, urgency);
    }

    @Transactional
    public AssignTaskResponse assignTaskWithUrgency(AssignTaskRequest request, String urgency) {
        Logistics logistics = logisticsService.getLogisticsById(request.getLogisticsId());

        if (deliveryTaskRepository.findByLogisticsId(request.getLogisticsId()).isPresent()) {
            throw new LogisticsException("该物流已有配送任务");
        }

        Courier courier = courierService.getCourierById(request.getCourierId());

        if (!LogisticsConstants.COURIER_STATUS_AVAILABLE.equals(courier.getCourierStatus())) {
            throw new LogisticsException("配送员不可用");
        }

        if (courier.getCourierCurrent() >= courier.getCourierCapacity()) {
            throw new LogisticsException("配送员容量不足");
        }

        if (!courier.getCourierStation().equals(logistics.getStationId())) {
            throw new LogisticsException("配送员不属于该网点");
        }

        boolean lockAcquired = courierLockService.tryLock(
                request.getCourierId(), 
                request.getLogisticsId(), 
                urgency);
        
        if (!lockAcquired) {
            throw new LogisticsException("配送员锁定失败，可能正在被其他任务占用");
        }

        try {
            String deliveryTypeCode = logistics.getDeliveryTypeCode();
            DeliveryType deliveryType = deliveryTypeService.getDeliveryType(deliveryTypeCode);

            DeliveryTask task = new DeliveryTask();
            task.setTaskId(IdGenerator.generateTaskId());
            task.setLogisticsId(request.getLogisticsId());
            task.setCourierId(request.getCourierId());
            task.setStationId(logistics.getStationId());
            task.setDeliveryTypeCode(deliveryTypeCode);
            task.setUrgencyLevel(urgency);
            task.setTaskStatus(LogisticsConstants.TASK_STATUS_ASSIGNED);
            task.setAssignedAt(LocalDateTime.now());

            DeliveryTask savedTask = deliveryTaskRepository.save(task);

            courierService.incrementCourierCurrent(request.getCourierId());
            courierService.updateCourierStatus(request.getCourierId(), LogisticsConstants.COURIER_STATUS_BUSY);

            logisticsService.updateLogistics(request.getLogisticsId(), request.getCourierId(), null);

            LogisticsHistory history = new LogisticsHistory();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setLogisticsId(request.getLogisticsId());
            history.setHistoryType(LogisticsConstants.HISTORY_TYPE_ASSIGN);
            history.setHistoryStatus(LogisticsConstants.TASK_STATUS_ASSIGNED);
            history.setHistoryDetail("配送任务已分配，配送员：" + courier.getCourierName() + 
                    "，配送类型：" + deliveryType.getTypeName() + "，紧急程度：" + urgency);
            historyService.recordHistory(history);

            log.info("分配配送任务 - logisticsId: {}, courierId: {}, deliveryType: {}, urgency: {}", 
                    request.getLogisticsId(), request.getCourierId(), deliveryTypeCode, urgency);

            return AssignTaskResponse.builder()
                    .taskId(savedTask.getTaskId())
                    .status(LogisticsConstants.TASK_STATUS_ASSIGNED)
                    .build();
        } catch (Exception e) {
            courierLockService.releaseLock(request.getCourierId(), request.getLogisticsId());
            throw e;
        }
    }

    @Transactional
    public UpdateTaskResponse updateTask(UpdateTaskRequest request) {
        DeliveryTask task = getTaskById(request.getTaskId());

        if (LogisticsConstants.TASK_STATUS_COMPLETED.equals(task.getTaskStatus())) {
            throw new LogisticsException("配送任务已完成");
        }

        String action = request.getAction();

        switch (action) {
            case LogisticsConstants.ACTION_START:
                return startDelivery(task);
            case LogisticsConstants.ACTION_UPDATE:
                return updateDeliveryLocation(task, request.getLocation(), request.getDetail());
            case LogisticsConstants.ACTION_COMPLETE:
                return completeDelivery(task, request.getLocation());
            case LogisticsConstants.ACTION_CANCEL:
                return cancelDelivery(task);
            default:
                throw new LogisticsException("无效的操作类型");
        }
    }

    @Transactional
    public UpdateTaskResponse startDelivery(DeliveryTask task) {
        task.setTaskStatus(LogisticsConstants.TASK_STATUS_DELIVERING);
        task.setStartedAt(LocalDateTime.now());
        deliveryTaskRepository.save(task);

        logisticsService.updateLogisticsStatus(task.getLogisticsId(), LogisticsConstants.STATUS_DELIVERING);

        statusService.sendNotificationAsync(task.getLogisticsId(), 
                LogisticsConstants.NOTIFY_TYPE_STATUS, LogisticsConstants.STATUS_DELIVERING);

        statisticsService.incrementDeliveringCount();

        LogisticsHistory history = new LogisticsHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setLogisticsId(task.getLogisticsId());
        history.setHistoryType(LogisticsConstants.HISTORY_TYPE_START);
        history.setHistoryStatus(LogisticsConstants.STATUS_DELIVERING);
        history.setHistoryDetail("配送员已开始配送");
        historyService.recordHistory(history);

        return UpdateTaskResponse.builder()
                .taskId(task.getTaskId())
                .status(LogisticsConstants.TASK_STATUS_DELIVERING)
                .message("配送已开始")
                .build();
    }

    @Transactional
    public UpdateTaskResponse updateDeliveryLocation(DeliveryTask task, String location, String detail) {
        if (location == null || location.trim().isEmpty()) {
            throw new LogisticsException("位置信息不能为空");
        }

        trackService.recordTrack(task.getLogisticsId(), LogisticsConstants.STATUS_DELIVERING, location, detail);

        return UpdateTaskResponse.builder()
                .taskId(task.getTaskId())
                .status(task.getTaskStatus())
                .message("轨迹已更新")
                .build();
    }

    @Transactional
    public UpdateTaskResponse completeDelivery(DeliveryTask task, String location) {
        task.setTaskStatus(LogisticsConstants.TASK_STATUS_COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        deliveryTaskRepository.save(task);

        logisticsService.updateLogisticsStatus(task.getLogisticsId(), LogisticsConstants.STATUS_DELIVERED);

        if (location != null && !location.trim().isEmpty()) {
            trackService.recordTrack(task.getLogisticsId(), LogisticsConstants.STATUS_DELIVERED, location, "配送完成");
        }

        statusService.sendNotificationAsync(task.getLogisticsId(), 
                LogisticsConstants.NOTIFY_TYPE_STATUS, LogisticsConstants.STATUS_DELIVERED);

        Double fee = feeService.calculateFee(task.getLogisticsId());
        logisticsService.updateLogistics(task.getLogisticsId(), null, fee);

        courierService.decrementCourierCurrent(task.getCourierId());
        courierService.checkAndSetAvailable(task.getCourierId());

        courierLockService.releaseLock(task.getCourierId(), task.getLogisticsId());

        statisticsService.decrementDeliveringCount();
        statisticsService.incrementDeliveryCount();
        statisticsService.addTotalFee(fee);

        LogisticsHistory history = new LogisticsHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setLogisticsId(task.getLogisticsId());
        history.setHistoryType(LogisticsConstants.HISTORY_TYPE_COMPLETE);
        history.setHistoryStatus(LogisticsConstants.STATUS_DELIVERED);
        history.setHistoryDetail("配送已完成，费用：" + fee);
        historyService.recordHistory(history);

        return UpdateTaskResponse.builder()
                .taskId(task.getTaskId())
                .status(LogisticsConstants.TASK_STATUS_COMPLETED)
                .message("配送已完成，费用：" + fee)
                .build();
    }

    @Transactional
    public UpdateTaskResponse cancelDelivery(DeliveryTask task) {
        task.setTaskStatus(LogisticsConstants.TASK_STATUS_FAILED);
        deliveryTaskRepository.save(task);

        logisticsService.updateLogisticsStatus(task.getLogisticsId(), LogisticsConstants.STATUS_CANCELLED);

        courierService.decrementCourierCurrent(task.getCourierId());
        courierService.checkAndSetAvailable(task.getCourierId());

        courierLockService.releaseLock(task.getCourierId(), task.getLogisticsId());

        LogisticsHistory history = new LogisticsHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setLogisticsId(task.getLogisticsId());
        history.setHistoryType(LogisticsConstants.HISTORY_TYPE_CANCEL);
        history.setHistoryStatus(LogisticsConstants.STATUS_CANCELLED);
        history.setHistoryDetail("配送任务已取消");
        historyService.recordHistory(history);

        return UpdateTaskResponse.builder()
                .taskId(task.getTaskId())
                .status(LogisticsConstants.TASK_STATUS_FAILED)
                .message("配送任务已取消")
                .build();
    }

    public DeliveryTask getTaskById(String taskId) {
        return deliveryTaskRepository.findById(taskId)
                .orElseThrow(() -> new LogisticsException("配送任务不存在"));
    }

    public DeliveryTask getTaskByLogisticsId(String logisticsId) {
        return deliveryTaskRepository.findByLogisticsId(logisticsId)
                .orElseThrow(() -> new LogisticsException("配送任务不存在"));
    }

    public List<DeliveryTask> getTasksByCourierId(String courierId) {
        return deliveryTaskRepository.findByCourierId(courierId);
    }

    public List<DeliveryTask> getAllTasks() {
        return deliveryTaskRepository.findAll();
    }
}
