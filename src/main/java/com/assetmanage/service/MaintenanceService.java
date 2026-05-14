package com.assetmanage.service;

import com.assetmanage.common.IdGenerator;
import com.assetmanage.dto.MaintenancePlanRequest;
import com.assetmanage.entity.MaintenanceRecord;
import com.assetmanage.exception.BusinessException;
import com.assetmanage.repository.MaintenanceRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MaintenanceService {

    private final MaintenanceRecordRepository maintenanceRepository;

    @Transactional
    public String createMaintenance(MaintenancePlanRequest request) {
        MaintenanceRecord record = new MaintenanceRecord();
        record.setMaintenanceId(IdGenerator.generateMaintenanceId());
        record.setAssetId(request.getAssetId());
        record.setMaintenanceType(request.getMaintenanceType());
        record.setMaintenanceDate(request.getMaintenanceDate());
        record.setMaintenanceContent(request.getMaintenanceContent());
        record.setMaintenanceCost(request.getMaintenanceCost());
        record.setNextMaintenance(request.getNextMaintenance());
        maintenanceRepository.save(record);
        log.info("维护记录创建成功: maintId={}, assetId={}", record.getMaintenanceId(), request.getAssetId());
        return record.getMaintenanceId();
    }

    @Transactional
    public void adjustMaintenancePlan(String assetId) {
        List<MaintenanceRecord> records = maintenanceRepository.findByAssetIdOrderByDateDesc(assetId);
        for (MaintenanceRecord record : records) {
            if (record.getNextMaintenance() != null && record.getNextMaintenance().isBefore(LocalDate.now().plusDays(7))) {
                record.setNextMaintenance(LocalDate.now().plusMonths(1));
                maintenanceRepository.save(record);
                log.info("维护计划已调整: maintId={}, newDate={}", record.getMaintenanceId(), record.getNextMaintenance());
            }
        }
    }

    public List<MaintenanceRecord> getMaintenanceByAsset(String assetId) {
        return maintenanceRepository.findByAssetIdOrderByDateDesc(assetId);
    }

    public List<MaintenanceRecord> getMaintenanceByType(String type) {
        return maintenanceRepository.findByMaintenanceType(type);
    }

    public List<MaintenanceRecord> getUpcomingMaintenance(LocalDate start, LocalDate end) {
        return maintenanceRepository.findByNextMaintenanceBetween(start, end);
    }

    public MaintenanceRecord getMaintenanceById(String maintId) {
        return maintenanceRepository.findById(maintId)
                .orElseThrow(() -> new BusinessException("维护记录不存在: " + maintId));
    }

    public List<MaintenanceRecord> getAllMaintenance() {
        return maintenanceRepository.findAll();
    }
}
