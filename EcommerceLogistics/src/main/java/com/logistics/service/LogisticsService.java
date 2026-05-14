package com.logistics.service;

import com.logistics.constant.LogisticsConstants;
import com.logistics.dto.CreateLogisticsRequest;
import com.logistics.dto.CreateLogisticsResponse;
import com.logistics.entity.*;
import com.logistics.exception.LogisticsException;
import com.logistics.repository.LogisticsRepository;
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
public class LogisticsService {

    private final LogisticsRepository logisticsRepository;
    private final StationService stationService;
    private final CourierService courierService;
    private final HistoryService historyService;
    private final StatisticsService statisticsService;
    private final DeliveryTypeService deliveryTypeService;

    @Transactional
    public CreateLogisticsResponse createLogistics(CreateLogisticsRequest request) {
        validateOrder(request.getOrderId());

        if (logisticsRepository.existsByOrderId(request.getOrderId())) {
            throw new LogisticsException("该订单已存在物流记录");
        }

        Station station = stationService.getStationById(request.getStationId());

        if (!LogisticsConstants.STATION_STATUS_ACTIVE.equals(station.getStationStatus())) {
            throw new LogisticsException("网点不可用");
        }

        if (station.getStationCurrent() >= station.getStationCapacity()) {
            throw new LogisticsException("网点容量不足");
        }

        List<Courier> availableCouriers = courierService.getAvailableCouriersByStation(request.getStationId());

        if (availableCouriers.isEmpty()) {
            throw new LogisticsException("该网点暂无可用配送员");
        }

        String deliveryTypeCode = request.getDeliveryTypeCode();
        if (deliveryTypeCode == null || deliveryTypeCode.trim().isEmpty()) {
            deliveryTypeCode = DeliveryTypeService.DEFAULT_TYPE_CODE;
        }

        DeliveryType deliveryType = deliveryTypeService.getDeliveryType(deliveryTypeCode);

        Logistics logistics = new Logistics();
        logistics.setLogisticsId(IdGenerator.generateLogisticsId());
        logistics.setLogisticsNumber(IdGenerator.generateLogisticsNumber());
        logistics.setOrderId(request.getOrderId());
        logistics.setStationId(request.getStationId());
        logistics.setDeliveryTypeCode(deliveryTypeCode);
        logistics.setLogisticsStatus(LogisticsConstants.STATUS_SHIPPING);
        logistics.setShippingTime(LocalDateTime.now());

        Logistics savedLogistics = logisticsRepository.save(logistics);

        stationService.incrementStationCurrent(request.getStationId());

        statisticsService.incrementLogisticsCount();

        LogisticsHistory history = new LogisticsHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setLogisticsId(savedLogistics.getLogisticsId());
        history.setHistoryType(LogisticsConstants.HISTORY_TYPE_CREATE);
        history.setHistoryStatus(LogisticsConstants.STATUS_SHIPPING);
        history.setHistoryDetail("物流已创建，状态：已发货，配送类型：" + deliveryType.getTypeName());
        historyService.recordHistory(history);

        log.info("创建物流记录 - orderId: {}, deliveryType: {}, urgency: {}", 
                request.getOrderId(), deliveryTypeCode, deliveryType.getUrgencyLevel());

        return CreateLogisticsResponse.builder()
                .logisticsId(savedLogistics.getLogisticsId())
                .logisticsNumber(savedLogistics.getLogisticsNumber())
                .deliveryTypeCode(deliveryTypeCode)
                .urgencyLevel(deliveryType.getUrgencyLevel())
                .build();
    }

    @Transactional
    public Logistics updateLogisticsStatus(String logisticsId, String status) {
        Logistics logistics = getLogisticsById(logisticsId);
        logistics.setLogisticsStatus(status);
        if (LogisticsConstants.STATUS_DELIVERED.equals(status)) {
            logistics.setDeliveryTime(LocalDateTime.now());
        }
        return logisticsRepository.save(logistics);
    }

    public Logistics getLogisticsById(String logisticsId) {
        return logisticsRepository.findById(logisticsId)
                .orElseThrow(() -> new LogisticsException("物流记录不存在"));
    }

    public Logistics getLogisticsByNumber(String logisticsNumber) {
        return logisticsRepository.findByLogisticsNumber(logisticsNumber)
                .orElseThrow(() -> new LogisticsException("物流记录不存在"));
    }

    public Logistics getLogisticsByOrderId(String orderId) {
        return logisticsRepository.findByOrderId(orderId)
                .orElseThrow(() -> new LogisticsException("物流记录不存在"));
    }

    public List<Logistics> getAllLogistics() {
        return logisticsRepository.findAll();
    }

    @Transactional
    public Logistics updateLogistics(String logisticsId, String courierId, Double fee) {
        Logistics logistics = getLogisticsById(logisticsId);
        if (courierId != null) {
            logistics.setCourierId(courierId);
        }
        if (fee != null) {
            logistics.setLogisticsFee(fee);
        }
        return logisticsRepository.save(logistics);
    }

    private void validateOrder(String orderId) {
        if (orderId == null || orderId.trim().isEmpty()) {
            throw new LogisticsException("订单ID不能为空");
        }
    }
}
