package com.configcenter.audit.service;

import com.configcenter.common.dto.*;
import com.configcenter.common.entity.*;
import com.configcenter.common.enums.*;
import com.configcenter.common.util.EntityConverter;
import com.configcenter.audit.repository.AuditRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRecordRepository auditRecordRepository;

    @Transactional
    public AuditRecordDTO recordCreate(String configId, String newValue, String operator, String ipAddress) {
        log.info("Recording create audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.CREATE, null, newValue, operator, "创建配置", ipAddress);
    }

    @Transactional
    public AuditRecordDTO recordUpdate(String configId, String oldValue, String newValue, String operator, String remark, String ipAddress) {
        log.info("Recording update audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.UPDATE, oldValue, newValue, operator, remark, ipAddress);
    }

    @Transactional
    public AuditRecordDTO recordDelete(String configId, String oldValue, String operator, String ipAddress) {
        log.info("Recording delete audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.DELETE, oldValue, null, operator, "删除配置", ipAddress);
    }

    @Transactional
    public AuditRecordDTO recordRollback(String configId, String oldValue, String newValue, String operator, String ipAddress) {
        log.info("Recording rollback audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.ROLLBACK, oldValue, newValue, operator, "回滚配置", ipAddress);
    }

    @Transactional
    public AuditRecordDTO recordPush(String configId, String operator, String ipAddress) {
        log.info("Recording push audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.PUSH, null, null, operator, "推送配置", ipAddress);
    }

    @Transactional
    public AuditRecordDTO recordImport(String configId, String newValue, String operator, String ipAddress) {
        log.info("Recording import audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.IMPORT, null, newValue, operator, "导入配置", ipAddress);
    }

    @Transactional
    public AuditRecordDTO recordExport(String configId, String oldValue, String operator, String ipAddress) {
        log.info("Recording export audit: configId={}, operator={}", configId, operator);
        return record(configId, AuditOperation.EXPORT, oldValue, null, operator, "导出配置", ipAddress);
    }

    private AuditRecordDTO record(String configId, AuditOperation operation, String oldValue, 
            String newValue, String operator, String remark, String ipAddress) {
        AuditRecord record = new AuditRecord();
        record.setConfigId(configId);
        record.setOperation(operation);
        record.setOldValue(oldValue);
        record.setNewValue(newValue);
        record.setOperator(operator);
        record.setRemark(remark);
        record.setIpAddress(ipAddress);

        AuditRecord saved = auditRecordRepository.save(record);
        log.info("Audit record saved: auditId={}, operation={}", saved.getAuditId(), operation);
        return EntityConverter.toAuditRecordDTO(saved);
    }

    public List<AuditRecordDTO> getAuditRecordsByConfig(String configId) {
        List<AuditRecord> records = auditRecordRepository.findByConfigIdOrderByOperatedAtDesc(configId);
        List<AuditRecordDTO> result = new ArrayList<>();
        for (AuditRecord r : records) {
            result.add(EntityConverter.toAuditRecordDTO(r));
        }
        return result;
    }

    public List<AuditRecordDTO> getAuditRecordsByOperator(String operator) {
        List<AuditRecord> records = auditRecordRepository.findByOperatorOrderByOperatedAtDesc(operator);
        List<AuditRecordDTO> result = new ArrayList<>();
        for (AuditRecord r : records) {
            result.add(EntityConverter.toAuditRecordDTO(r));
        }
        return result;
    }

    public List<AuditRecordDTO> getAuditRecordsByOperation(AuditOperation operation) {
        List<AuditRecord> records = auditRecordRepository.findByOperationOrderByOperatedAtDesc(operation);
        List<AuditRecordDTO> result = new ArrayList<>();
        for (AuditRecord r : records) {
            result.add(EntityConverter.toAuditRecordDTO(r));
        }
        return result;
    }

    public List<AuditRecordDTO> getLatestAuditRecords(String configId, int limit) {
        List<AuditRecord> records = auditRecordRepository.findLatestByConfigId(configId, PageRequest.of(0, limit));
        List<AuditRecordDTO> result = new ArrayList<>();
        for (AuditRecord r : records) {
            result.add(EntityConverter.toAuditRecordDTO(r));
        }
        return result;
    }

    public List<AuditRecordDTO> getAuditRecordsByTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
        List<AuditRecord> records = auditRecordRepository.findByTimeRange(startTime, endTime);
        List<AuditRecordDTO> result = new ArrayList<>();
        for (AuditRecord r : records) {
            result.add(EntityConverter.toAuditRecordDTO(r));
        }
        return result;
    }

    public List<AuditRecordDTO> getAuditRecordsByConfigAndOperation(String configId, AuditOperation operation) {
        List<AuditRecord> records = auditRecordRepository.findByConfigIdAndOperation(configId, operation);
        List<AuditRecordDTO> result = new ArrayList<>();
        for (AuditRecord r : records) {
            result.add(EntityConverter.toAuditRecordDTO(r));
        }
        return result;
    }

    public Map<String, Object> getAuditStatistics(String configId) {
        List<AuditRecord> records = auditRecordRepository.findByConfigIdOrderByOperatedAtDesc(configId);
        
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalCount", records.size());
        
        Map<AuditOperation, Long> operationCounts = new EnumMap<>(AuditOperation.class);
        for (AuditOperation op : AuditOperation.values()) {
            operationCounts.put(op, 0L);
        }
        
        for (AuditRecord record : records) {
            operationCounts.merge(record.getOperation(), 1L);
        }
        
        stats.put("operationCounts", operationCounts);
        
        if (!records.isEmpty()) {
            stats.put("firstOperatedAt", records.get(records.size() - 1).getOperatedAt());
            stats.put("lastOperatedAt", records.get(0).getOperatedAt());
        }
        
        return stats;
    }
}
