package com.memberscore.service;

import com.memberscore.entity.PointRecord;
import com.memberscore.enums.PointType;
import com.memberscore.repository.PointRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class HistoryService {
    
    private final PointRecordRepository pointRecordRepository;
    
    @Transactional(readOnly = true)
    public List<PointRecord> getMemberHistory(String memberId) {
        return pointRecordRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
    }
    
    @Transactional(readOnly = true)
    public List<PointRecord> getMemberEarnHistory(String memberId) {
        return pointRecordRepository.findByMemberIdAndPointTypeOrderByCreatedAtDesc(
                memberId, PointType.EARN);
    }
    
    @Transactional(readOnly = true)
    public List<PointRecord> getMemberConsumeHistory(String memberId) {
        return pointRecordRepository.findByMemberIdAndPointTypeOrderByCreatedAtDesc(
                memberId, PointType.CONSUME);
    }
    
    @Transactional(readOnly = true)
    public List<PointRecord> getMemberHistoryByDateRange(String memberId, 
                                                         LocalDateTime start, 
                                                         LocalDateTime end) {
        return pointRecordRepository.findByMemberIdAndCreatedAtBetweenOrderByCreatedAtDesc(
                memberId, start, end);
    }
    
    @Transactional(readOnly = true)
    public List<PointRecord> getMemberRecentHistory(String memberId, int limit) {
        List<PointRecord> all = pointRecordRepository.findByMemberIdOrderByCreatedAtDesc(memberId);
        return all.stream().limit(limit).toList();
    }
}
