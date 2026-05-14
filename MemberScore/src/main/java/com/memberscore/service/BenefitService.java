package com.memberscore.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.memberscore.entity.BenefitRecord;
import com.memberscore.entity.LevelConfig;
import com.memberscore.enums.BenefitStatus;
import com.memberscore.queue.BenefitTaskQueue;
import com.memberscore.repository.BenefitRecordRepository;
import com.memberscore.repository.LevelConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class BenefitService {
    
    private final BenefitRecordRepository benefitRecordRepository;
    private final LevelConfigRepository levelConfigRepository;
    private final BenefitTaskQueue benefitTaskQueue;
    private final ObjectMapper objectMapper;
    
    @Value("${benefit.async.enabled:true}")
    private boolean asyncEnabled;
    
    public String issueLevelBenefits(String memberId, String levelId) {
        if (asyncEnabled) {
            String taskId = benefitTaskQueue.submitTask(memberId, levelId, "level_upgrade");
            log.info("权益发放任务已提交到队列: memberId={}, levelId={}, taskId={}", 
                    memberId, levelId, taskId);
            return taskId;
        } else {
            issueLevelBenefitsSync(memberId, levelId);
            return null;
        }
    }
    
    @Transactional
    public void issueLevelBenefitsSync(String memberId, String levelId) {
        log.info("同步发放会员权益: memberId={}, levelId={}", memberId, levelId);
        
        Optional<LevelConfig> levelOpt = levelConfigRepository.findByLevelId(levelId);
        if (levelOpt.isEmpty()) {
            log.warn("等级配置不存在，无法发放权益: levelId={}", levelId);
            return;
        }
        
        LevelConfig level = levelOpt.get();
        String benefitsJson = level.getLevelBenefits();
        
        if (benefitsJson == null || benefitsJson.isEmpty()) {
            log.info("等级 {} 没有配置权益", level.getLevelName());
            return;
        }
        
        try {
            List<Map<String, String>> benefits = objectMapper.readValue(
                    benefitsJson, new TypeReference<List<Map<String, String>>>() {});
            
            int issuedCount = 0;
            for (Map<String, String> benefit : benefits) {
                String benefitType = benefit.getOrDefault("type", "general");
                String benefitContent = benefit.getOrDefault("content", "");
                
                if (!benefitRecordRepository.existsByMemberIdAndLevelIdAndBenefitTypeAndBenefitStatus(
                        memberId, levelId, benefitType, BenefitStatus.ACTIVE)) {
                    
                    BenefitRecord record = BenefitRecord.builder()
                            .benefitId("benefit_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8))
                            .memberId(memberId)
                            .levelId(levelId)
                            .benefitType(benefitType)
                            .benefitContent(benefitContent)
                            .benefitStatus(BenefitStatus.ACTIVE)
                            .expireAt(LocalDateTime.now().plusYears(1))
                            .build();
                    
                    benefitRecordRepository.save(record);
                    issuedCount++;
                    log.info("发放会员权益: memberId={}, levelId={}, benefitType={}", 
                            memberId, levelId, benefitType);
                }
            }
            
            log.info("权益发放完成: memberId={}, levelId={}, issuedCount={}", 
                    memberId, levelId, issuedCount);
            
        } catch (Exception e) {
            log.error("解析等级权益配置失败: levelId={}", levelId, e);
            throw new RuntimeException("权益发放失败: " + e.getMessage(), e);
        }
    }
    
    @Transactional(readOnly = true)
    public List<BenefitRecord> getMemberBenefits(String memberId) {
        return benefitRecordRepository.findByMemberIdOrderByIssuedAtDesc(memberId);
    }
    
    @Transactional(readOnly = true)
    public List<BenefitRecord> getMemberActiveBenefits(String memberId) {
        return benefitRecordRepository.findByMemberIdAndBenefitStatusOrderByIssuedAtDesc(
                memberId, BenefitStatus.ACTIVE);
    }
    
    @Transactional
    public BenefitRecord useBenefit(String benefitId) {
        BenefitRecord benefit = benefitRecordRepository.findByBenefitId(benefitId)
                .orElseThrow(() -> new RuntimeException("权益不存在: " + benefitId));
        
        if (benefit.getBenefitStatus() != BenefitStatus.ACTIVE) {
            throw new RuntimeException("权益状态不可用: " + benefit.getBenefitStatus());
        }
        
        benefit.setBenefitStatus(BenefitStatus.USED);
        BenefitRecord saved = benefitRecordRepository.save(benefit);
        log.info("使用权益: benefitId={}", benefitId);
        return saved;
    }
    
    @Transactional
    public void expireBenefits() {
        LocalDateTime now = LocalDateTime.now();
        List<BenefitRecord> benefits = benefitRecordRepository.findAll().stream()
                .filter(b -> b.getBenefitStatus() == BenefitStatus.ACTIVE 
                        && b.getExpireAt() != null 
                        && b.getExpireAt().isBefore(now))
                .toList();
        
        for (BenefitRecord benefit : benefits) {
            benefit.setBenefitStatus(BenefitStatus.EXPIRED);
            benefitRecordRepository.save(benefit);
            log.info("权益过期: benefitId={}", benefit.getBenefitId());
        }
    }
}
