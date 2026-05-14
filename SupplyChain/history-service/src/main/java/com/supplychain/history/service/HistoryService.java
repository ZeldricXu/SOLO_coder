package com.supplychain.history.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.supplychain.common.entity.HistoryRecord;
import com.supplychain.common.util.IdGenerator;
import com.supplychain.history.mapper.HistoryRecordMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class HistoryService {

    private final HistoryRecordMapper recordMapper;

    @Transactional
    public HistoryRecord record(HistoryRecord record) {
        record.setRecordId(IdGenerator.generateRecordId());
        record.setCreatedAt(LocalDateTime.now());
        recordMapper.insert(record);
        log.info("记录历史: recordId={}, type={}, action={}", 
            record.getRecordId(), record.getRecordType(), record.getAction());
        return record;
    }

    @Transactional
    public HistoryRecord recordPurchase(String relatedId, String action, String operator, String detail) {
        return record(HistoryRecord.builder()
            .recordType("purchase")
            .relatedId(relatedId)
            .action(action)
            .operator(operator != null ? operator : "system")
            .detail(detail)
            .build());
    }

    @Transactional
    public HistoryRecord recordInventory(String relatedId, String action, String operator, String detail) {
        return record(HistoryRecord.builder()
            .recordType("inventory")
            .relatedId(relatedId)
            .action(action)
            .operator(operator != null ? operator : "system")
            .detail(detail)
            .build());
    }

    @Transactional
    public HistoryRecord recordLogistics(String relatedId, String action, String operator, String detail) {
        return record(HistoryRecord.builder()
            .recordType("logistics")
            .relatedId(relatedId)
            .action(action)
            .operator(operator != null ? operator : "system")
            .detail(detail)
            .build());
    }

    public List<HistoryRecord> getRecordsByType(String recordType) {
        LambdaQueryWrapper<HistoryRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HistoryRecord::getRecordType, recordType)
               .orderByDesc(HistoryRecord::getCreatedAt);
        return recordMapper.selectList(wrapper);
    }

    public List<HistoryRecord> getRecordsByRelated(String recordType, String relatedId) {
        LambdaQueryWrapper<HistoryRecord> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(HistoryRecord::getRecordType, recordType)
               .eq(HistoryRecord::getRelatedId, relatedId)
               .orderByDesc(HistoryRecord::getCreatedAt);
        return recordMapper.selectList(wrapper);
    }

    public List<HistoryRecord> getPurchaseHistory(String orderId) {
        return getRecordsByRelated("purchase", orderId);
    }

    public List<HistoryRecord> getInventoryHistory(String itemId) {
        return getRecordsByRelated("inventory", itemId);
    }

    public List<HistoryRecord> getLogisticsHistory(String orderId) {
        return getRecordsByRelated("logistics", orderId);
    }

    public List<HistoryRecord> listRecords(String recordType, String relatedId, String action) {
        LambdaQueryWrapper<HistoryRecord> wrapper = new LambdaQueryWrapper<>();
        if (recordType != null && !recordType.isEmpty()) {
            wrapper.eq(HistoryRecord::getRecordType, recordType);
        }
        if (relatedId != null && !relatedId.isEmpty()) {
            wrapper.eq(HistoryRecord::getRelatedId, relatedId);
        }
        if (action != null && !action.isEmpty()) {
            wrapper.eq(HistoryRecord::getAction, action);
        }
        wrapper.orderByDesc(HistoryRecord::getCreatedAt);
        return recordMapper.selectList(wrapper);
    }

    public HistoryRecord getRecord(String recordId) {
        return recordMapper.selectById(recordId);
    }
}
