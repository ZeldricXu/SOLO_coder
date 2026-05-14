package com.memberscore.service;

import com.memberscore.dto.LevelQueryResponse;
import com.memberscore.entity.LevelConfig;
import com.memberscore.entity.Member;
import com.memberscore.repository.LevelConfigRepository;
import com.memberscore.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LevelService {
    
    private final LevelConfigRepository levelConfigRepository;
    private final MemberRepository memberRepository;
    private final BenefitService benefitService;
    
    @Transactional(readOnly = true)
    public List<LevelConfig> getAllEnabledLevels() {
        return levelConfigRepository.findByIsEnabledTrueOrderByLevelOrderAsc();
    }
    
    @Transactional(readOnly = true)
    public Optional<LevelConfig> getLevelByLevelId(String levelId) {
        return levelConfigRepository.findByLevelId(levelId);
    }
    
    @Transactional(readOnly = true)
    public double getLevelMultiplier(String levelId) {
        return levelConfigRepository.findByLevelId(levelId)
                .map(LevelConfig::getPointMultiplier)
                .orElse(1.0);
    }
    
    @Transactional
    public LevelConfig createLevel(LevelConfig level) {
        LevelConfig saved = levelConfigRepository.save(level);
        log.info("创建等级配置成功: levelId={}", saved.getLevelId());
        return saved;
    }
    
    @Transactional
    public boolean checkAndUpgradeLevel(Member member) {
        List<LevelConfig> levels = levelConfigRepository.findHighestLevelForPoints(member.getTotalPoints());
        
        if (levels.isEmpty()) {
            return false;
        }
        
        LevelConfig highestLevel = levels.get(0);
        
        if (!highestLevel.getLevelId().equals(member.getMemberLevel())) {
            String oldLevel = member.getMemberLevel();
            member.setMemberLevel(highestLevel.getLevelId());
            member.setLevelUpdatedAt(LocalDateTime.now());
            memberRepository.save(member);
            
            log.info("会员等级升级: memberId={}, 从 {} 升级到 {}", 
                    member.getMemberId(), oldLevel, highestLevel.getLevelId());
            
            benefitService.issueLevelBenefits(member.getMemberId(), highestLevel.getLevelId());
            
            return true;
        }
        
        return false;
    }
    
    @Transactional(readOnly = true)
    public LevelQueryResponse queryMemberLevel(Member member) {
        LevelConfig currentLevel = levelConfigRepository.findByLevelId(member.getMemberLevel())
                .orElse(null);
        
        Integer pointsToNext = calculatePointsToNextLevel(member.getTotalPoints());
        
        return LevelQueryResponse.builder()
                .level(member.getMemberLevel())
                .levelName(currentLevel != null ? currentLevel.getLevelName() : "未知等级")
                .totalPoints(member.getTotalPoints())
                .availablePoints(member.getAvailablePoints())
                .pointsToNextLevel(pointsToNext)
                .build();
    }
    
    @Transactional(readOnly = true)
    public Integer calculatePointsToNextLevel(Integer currentPoints) {
        List<LevelConfig> nextLevels = levelConfigRepository.findNextLevels(currentPoints);
        if (nextLevels.isEmpty()) {
            return 0;
        }
        return nextLevels.get(0).getLevelPointsRequired() - currentPoints;
    }
}
