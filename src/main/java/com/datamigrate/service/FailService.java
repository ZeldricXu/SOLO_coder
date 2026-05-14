package com.datamigrate.service;

import com.datamigrate.common.FailStatus;
import com.datamigrate.entity.FailRecord;
import com.datamigrate.repository.FailRecordRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class FailService {

    private final FailRecordRepository failRecordRepository;
    private final LogService logService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public FailRecord recordFail(String taskId, String recordKey, Map<String, Object> recordData, 
                                 String failReason, int maxRetryTimes) {
        FailRecord failRecord = new FailRecord();
        failRecord.setFailId("fail_" + UUID.randomUUID().toString().substring(0, 8));
        failRecord.setTaskId(taskId);
        failRecord.setRecordKey(recordKey);
        try {
            failRecord.setRecordData(objectMapper.writeValueAsString(recordData));
        } catch (JsonProcessingException e) {
            failRecord.setRecordData(recordData != null ? recordData.toString() : null);
        }
        failRecord.setFailReason(failReason);
        failRecord.setRetryCount(0);
        failRecord.setMaxRetryTimes(maxRetryTimes);
        failRecord.setStatus(FailStatus.PENDING_RETRY);
        failRecord.setNextRetryAt(LocalDateTime.now().plusSeconds(5));

        logService.logRetry(taskId, "记录失败数据: key=" + recordKey + ", reason=" + failReason);
        
        return failRecordRepository.save(failRecord);
    }

    @Transactional
    public List<FailRecord> getPendingRetryRecords() {
        return failRecordRepository.findPendingRetryRecords(LocalDateTime.now());
    }

    @Transactional
    public boolean updateRetryResult(FailRecord record, boolean success) {
        record.setRetryCount(record.getRetryCount() + 1);
        record.setLastRetryAt(LocalDateTime.now());

        if (success) {
            record.setStatus(FailStatus.SUCCESS);
            logService.logRetry(record.getTaskId(), 
                "重试成功: key=" + record.getRecordKey() + ", 重试次数=" + record.getRetryCount());
            failRecordRepository.save(record);
            return true;
        } else {
            if (record.getRetryCount() >= record.getMaxRetryTimes()) {
                record.setStatus(FailStatus.FINAL_FAILED);
                logService.logRetry(record.getTaskId(), 
                    "重试失败已达上限: key=" + record.getRecordKey() + ", 最终失败");
            } else {
                record.setStatus(FailStatus.PENDING_RETRY);
                int delaySeconds = 5 * (record.getRetryCount() + 1);
                record.setNextRetryAt(LocalDateTime.now().plusSeconds(delaySeconds));
                logService.logRetry(record.getTaskId(), 
                    "重试失败: key=" + record.getRecordKey() + ", 第" + record.getRetryCount() + 
                    "次失败, 下次重试在" + delaySeconds + "秒后");
            }
            failRecordRepository.save(record);
            return false;
        }
    }

    public List<FailRecord> getFailsByTaskId(String taskId) {
        return failRecordRepository.findByTaskId(taskId);
    }

    public long countPendingRetryByTaskId(String taskId) {
        return failRecordRepository.countByTaskIdAndStatus(taskId, FailStatus.PENDING_RETRY);
    }

    public long countFinalFailedByTaskId(String taskId) {
        return failRecordRepository.countByTaskIdAndStatus(taskId, FailStatus.FINAL_FAILED);
    }

    @Transactional
    public void resetAllPendingFails(String taskId) {
        List<FailRecord> records = failRecordRepository.findByTaskIdAndStatus(taskId, FailStatus.PENDING_RETRY);
        for (FailRecord record : records) {
            record.setStatus(FailStatus.PENDING_RETRY);
            record.setRetryCount(0);
            record.setNextRetryAt(LocalDateTime.now());
            failRecordRepository.save(record);
        }
    }
}
