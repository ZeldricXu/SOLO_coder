package com.hotelbooking.service;

import com.hotelbooking.model.ServiceRecord;
import com.hotelbooking.repository.ServiceRecordRepository;
import com.hotelbooking.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class GuestService {
    private static final Logger logger = LoggerFactory.getLogger(GuestService.class);

    private final ServiceRecordRepository serviceRecordRepository;

    public GuestService(ServiceRecordRepository serviceRecordRepository) {
        this.serviceRecordRepository = serviceRecordRepository;
    }

    @Transactional
    public ServiceRecord createServiceRequest(String roomId, String serviceType, 
                                               String serviceRequest, Double serviceCharge) {
        ServiceRecord record = new ServiceRecord();
        record.setServiceId(IdGenerator.generateServiceId());
        record.setRoomId(roomId);
        record.setServiceType(serviceType);
        record.setServiceRequest(serviceRequest);
        record.setServiceStatus("pending");
        record.setServiceTime(LocalDateTime.now());
        record.setServiceCharge(serviceCharge);

        ServiceRecord saved = serviceRecordRepository.save(record);
        logger.info("客房服务请求创建成功: {}", saved.getServiceId());
        return saved;
    }

    @Transactional
    public ServiceRecord processService(String serviceId) {
        ServiceRecord record = serviceRecordRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("服务记录不存在: " + serviceId));

        if (!"pending".equals(record.getServiceStatus())) {
            throw new RuntimeException("服务状态不允许处理，当前状态: " + record.getServiceStatus());
        }

        record.setServiceStatus("processing");
        ServiceRecord updated = serviceRecordRepository.save(record);
        logger.info("客房服务开始处理: {}", serviceId);
        return updated;
    }

    @Transactional
    public ServiceRecord completeService(String serviceId) {
        ServiceRecord record = serviceRecordRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("服务记录不存在: " + serviceId));

        if (!"processing".equals(record.getServiceStatus()) && !"pending".equals(record.getServiceStatus())) {
            throw new RuntimeException("服务状态不允许完成，当前状态: " + record.getServiceStatus());
        }

        record.setServiceStatus("completed");
        ServiceRecord updated = serviceRecordRepository.save(record);
        logger.info("客房服务完成: {}", serviceId);
        return updated;
    }

    @Transactional
    public ServiceRecord cancelService(String serviceId) {
        ServiceRecord record = serviceRecordRepository.findById(serviceId)
                .orElseThrow(() -> new RuntimeException("服务记录不存在: " + serviceId));

        if ("completed".equals(record.getServiceStatus())) {
            throw new RuntimeException("已完成的服务无法取消");
        }

        record.setServiceStatus("cancelled");
        ServiceRecord updated = serviceRecordRepository.save(record);
        logger.info("客房服务取消: {}", serviceId);
        return updated;
    }

    public List<ServiceRecord> getServicesByRoom(String roomId) {
        return serviceRecordRepository.findByRoomId(roomId);
    }

    public List<ServiceRecord> getServicesByStatus(String status) {
        return serviceRecordRepository.findByServiceStatus(status);
    }

    public List<ServiceRecord> getPendingServicesByRoom(String roomId) {
        return serviceRecordRepository.findByRoomIdAndServiceStatus(roomId, "pending");
    }
}
