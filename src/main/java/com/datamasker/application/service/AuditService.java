package com.datamasker.application.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.datamasker.domain.audit.chain.HashChain;
import com.datamasker.domain.audit.model.AuditLogEntry;
import com.datamasker.domain.audit.model.TamperDetectionResult;
import com.datamasker.infrastructure.config.AuditConfig;
import com.datamasker.infrastructure.persistence.entity.AuditLogEntity;
import com.datamasker.infrastructure.persistence.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final HashChain hashChain;
    private final AuditConfig auditConfig;
    private final AuditLogMapper auditLogMapper;

    public AuditLogEntry recordLog(String operation, String operator, String module, String detail) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setOperation(operation);
        entry.setOperator(operator);
        entry.setModule(module);
        entry.setDetail(detail);
        entry.setTimestamp(LocalDateTime.now());

        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByDesc(AuditLogEntity::getTimestamp).last("LIMIT 1");
        AuditLogEntity lastEntity = auditLogMapper.selectOne(wrapper);

        String prevHash = (lastEntity != null) ? lastEntity.getLogHash() : hashChain.getGenesisHash();
        String logHash = hashChain.computeHashWithPrev(entry, prevHash);
        entry.setLogHash(logHash);

        AuditLogEntity entity = new AuditLogEntity();
        entity.setLogHash(entry.getLogHash());
        entity.setPrevHash(entry.getPrevHash());
        entity.setOperation(entry.getOperation());
        entity.setOperator(entry.getOperator());
        entity.setModule(entry.getModule());
        entity.setDetail(entry.getDetail());
        entity.setTimestamp(entry.getTimestamp());
        auditLogMapper.insert(entity);

        entry.setLogId(entity.getId());
        return entry;
    }

    public TamperDetectionResult verifyIntegrity() {
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(AuditLogEntity::getTimestamp);
        List<AuditLogEntity> entities = auditLogMapper.selectList(wrapper);

        List<AuditLogEntry> entries = entities.stream().map(entity -> {
            AuditLogEntry entry = new AuditLogEntry();
            entry.setLogId(entity.getId());
            entry.setLogHash(entity.getLogHash());
            entry.setPrevHash(entity.getPrevHash());
            entry.setOperation(entity.getOperation());
            entry.setOperator(entity.getOperator());
            entry.setModule(entity.getModule());
            entry.setDetail(entity.getDetail());
            entry.setTimestamp(entity.getTimestamp());
            return entry;
        }).collect(Collectors.toList());

        return hashChain.verifyChain(entries);
    }

    public List<AuditLogEntry> getLogs(String module, int page, int size) {
        LambdaQueryWrapper<AuditLogEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(AuditLogEntity::getModule, module)
                .orderByDesc(AuditLogEntity::getTimestamp);

        Page<AuditLogEntity> pageParam = new Page<>(page, size);
        Page<AuditLogEntity> resultPage = auditLogMapper.selectPage(pageParam, wrapper);

        return resultPage.getRecords().stream().map(entity -> {
            AuditLogEntry entry = new AuditLogEntry();
            entry.setLogId(entity.getId());
            entry.setLogHash(entity.getLogHash());
            entry.setPrevHash(entity.getPrevHash());
            entry.setOperation(entity.getOperation());
            entry.setOperator(entity.getOperator());
            entry.setModule(entity.getModule());
            entry.setDetail(entity.getDetail());
            entry.setTimestamp(entity.getTimestamp());
            return entry;
        }).collect(Collectors.toList());
    }

    public AuditLogEntry getLogById(String logId) {
        AuditLogEntity entity = auditLogMapper.selectById(logId);
        if (entity == null) {
            return null;
        }
        AuditLogEntry entry = new AuditLogEntry();
        entry.setLogId(entity.getId());
        entry.setLogHash(entity.getLogHash());
        entry.setPrevHash(entity.getPrevHash());
        entry.setOperation(entity.getOperation());
        entry.setOperator(entity.getOperator());
        entry.setModule(entity.getModule());
        entry.setDetail(entity.getDetail());
        entry.setTimestamp(entity.getTimestamp());
        return entry;
    }
}
