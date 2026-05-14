package com.memberscore.service;

import com.memberscore.entity.Member;
import com.memberscore.entity.PointRecord;
import com.memberscore.enums.PointType;
import com.memberscore.repository.MemberRepository;
import com.memberscore.repository.PointRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ExpireService {
    
    private final PointRecordRepository pointRecordRepository;
    private final MemberRepository memberRepository;
    
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional
    public void processExpiredPoints() {
        log.info("开始处理过期积分: {}", LocalDateTime.now());
        
        LocalDate today = LocalDate.now();
        List<PointRecord> expiredRecords = pointRecordRepository.findExpiredPoints(today);
        
        if (expiredRecords.isEmpty()) {
            log.info("没有需要处理的过期积分");
            return;
        }
        
        Map<String, Integer> expireAmountMap = new HashMap<>();
        
        for (PointRecord record : expiredRecords) {
            record.setIsExpired(true);
            pointRecordRepository.save(record);
            
            String memberId = record.getMemberId();
            int amount = record.getPointAmount();
            expireAmountMap.merge(memberId, amount, Integer::sum);
            
            log.info("积分记录过期: pointId={}, memberId={}, amount={}", 
                    record.getPointId(), memberId, amount);
        }
        
        for (Map.Entry<String, Integer> entry : expireAmountMap.entrySet()) {
            String memberId = entry.getKey();
            int expireAmount = entry.getValue();
            
            Optional<Member> memberOpt = memberRepository.findByMemberId(memberId);
            if (memberOpt.isPresent()) {
                Member member = memberOpt.get();
                int newAvailable = Math.max(0, member.getAvailablePoints() - expireAmount);
                member.setAvailablePoints(newAvailable);
                memberRepository.save(member);
                
                log.info("更新会员可用积分: memberId={}, expiredAmount={}, newAvailable={}", 
                        memberId, expireAmount, newAvailable);
                
                String expirePointId = "point_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                PointRecord expireRecord = PointRecord.builder()
                        .pointId(expirePointId)
                        .memberId(memberId)
                        .pointType(PointType.CONSUME)
                        .pointAmount(expireAmount)
                        .consumeType("expire")
                        .pointBalance(newAvailable)
                        .isExpired(false)
                        .build();
                pointRecordRepository.save(expireRecord);
            }
        }
        
        log.info("过期积分处理完成: 处理记录数={}, 影响会员数={}", 
                expiredRecords.size(), expireAmountMap.size());
    }
    
    @Transactional
    public void manualExpireCheck() {
        processExpiredPoints();
    }
}
