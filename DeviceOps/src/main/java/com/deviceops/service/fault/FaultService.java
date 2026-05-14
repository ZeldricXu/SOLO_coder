package com.deviceops.service.fault;

import com.deviceops.dto.FaultReportRequest;
import com.deviceops.entity.FaultRecord;
import com.deviceops.exception.DeviceOpsException;
import com.deviceops.queue.FaultQueueService;
import com.deviceops.queue.FaultTaskDTO;
import com.deviceops.repository.FaultRecordRepository;
import com.deviceops.service.analysis.AnalysisService;
import com.deviceops.service.device.DeviceService;
import com.deviceops.service.history.HistoryService;
import com.deviceops.service.task.TaskService;
import com.deviceops.util.IdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class FaultService {

    @Autowired
    private FaultRecordRepository faultRecordRepository;

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private HistoryService historyService;

    @Autowired
    private AnalysisService analysisService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private FaultQueueService faultQueueService;

    @Transactional
    public FaultRecord reportFault(FaultReportRequest request) {
        if (!deviceService.exists(request.getDeviceId())) {
            throw DeviceOpsException.deviceNotFound(request.getDeviceId());
        }

        FaultRecord fault = new FaultRecord();
        fault.setFaultId(IdGenerator.generateFaultId());
        fault.setDeviceId(request.getDeviceId());
        fault.setFaultType(normalizeFaultType(request.getFaultType()));
        fault.setFaultLevel(determineFaultLevel(request.getFaultLevel()));
        fault.setFaultDesc(request.getFaultDesc());
        fault.setFaultStatus("pending");
        fault.setReportedBy(request.getReportedBy() != null ? request.getReportedBy() : "system");

        FaultRecord saved = faultRecordRepository.save(fault);

        deviceService.updateDeviceStatus(request.getDeviceId(), "abnormal");

        historyService.recordFaultReport(request.getDeviceId(), saved.getFaultId(), request.getFaultDesc());

        analysisService.incrementFaultCount();

        enqueueFaultProcessing(saved);

        return saved;
    }

    private void enqueueFaultProcessing(FaultRecord fault) {
        FaultTaskDTO task = new FaultTaskDTO();
        task.setFaultId(fault.getFaultId());
        task.setDeviceId(fault.getDeviceId());
        task.setFaultType(fault.getFaultType());
        task.setFaultLevel(fault.getFaultLevel());
        task.setFaultDesc(fault.getFaultDesc());
        task.setReportedBy(fault.getReportedBy());
        task.setReportedAt(fault.getReportedAt());
        task.setTaskStatus(fault.getFaultStatus());

        faultQueueService.enqueueFaultTask(task);
    }

    private String normalizeFaultType(String type) {
        if ("hardware".equalsIgnoreCase(type) || "hw".equalsIgnoreCase(type)) {
            return "hardware";
        } else if ("software".equalsIgnoreCase(type) || "sw".equalsIgnoreCase(type)) {
            return "software";
        } else if ("network".equalsIgnoreCase(type)) {
            return "network";
        }
        return type != null ? type.toLowerCase() : "other";
    }

    private String determineFaultLevel(String level) {
        if (level == null) {
            return "medium";
        }
        if ("high".equalsIgnoreCase(level) || "urgent".equalsIgnoreCase(level)) {
            return "high";
        } else if ("low".equalsIgnoreCase(level)) {
            return "low";
        }
        return "medium";
    }

    public FaultRecord getFault(String faultId) {
        return faultRecordRepository.findById(faultId)
                .orElseThrow(() -> DeviceOpsException.faultNotFound(faultId));
    }

    public List<FaultRecord> getAllFaults() {
        return faultRecordRepository.findAll();
    }

    public List<FaultRecord> getFaultsByDevice(String deviceId) {
        if (!deviceService.exists(deviceId)) {
            throw DeviceOpsException.deviceNotFound(deviceId);
        }
        return faultRecordRepository.findByDeviceIdOrderByReportedAtDesc(deviceId);
    }

    public List<FaultRecord> getFaultsByStatus(String status) {
        return faultRecordRepository.findByFaultStatus(status);
    }

    @Transactional
    public FaultRecord updateFaultStatus(String faultId, String status) {
        FaultRecord fault = getFault(faultId);
        fault.setFaultStatus(status);
        
        if ("resolved".equals(status)) {
            fault.setRepairedAt(LocalDateTime.now());
        }
        
        return faultRecordRepository.save(fault);
    }

    @Transactional
    public FaultRecord processFault(String faultId) {
        return updateFaultStatus(faultId, "processing");
    }

    @Transactional
    public FaultRecord resolveFault(String faultId, String operatorId) {
        FaultRecord fault = updateFaultStatus(faultId, "resolved");
        
        deviceService.updateDeviceStatus(fault.getDeviceId(), "normal");
        
        historyService.recordFaultRepair(fault.getDeviceId(), faultId, operatorId);
        
        return fault;
    }

    public long countByStatus(String status) {
        return faultRecordRepository.countByFaultStatus(status);
    }

    public long count() {
        return faultRecordRepository.count();
    }
}
