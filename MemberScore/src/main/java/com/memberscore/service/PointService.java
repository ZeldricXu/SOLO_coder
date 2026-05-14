package com.memberscore.service;

import com.memberscore.dto.ConsumePointRequest;
import com.memberscore.dto.EarnPointRequest;
import com.memberscore.dto.PointOperationResponse;
import com.memberscore.entity.Member;
import com.memberscore.entity.PointRecord;
import com.memberscore.entity.PointRule;
import com.memberscore.enums.PointType;
import com.memberscore.repository.MemberRepository;
import com.memberscore.repository.PointRecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PointService {
    
    private final MemberRepository memberRepository;
    private final PointRecordRepository pointRecordRepository;
    private final PointRuleService pointRuleService;
    private final LevelService levelService;
    private final PointStatService pointStatService;
    private final ValidationRuleService validationRuleService;
    private final ExpirePolicyService expirePolicyService;
    
    @Value("${points.expire.days:365}")
    private int expireDays;
    
    @Transactional
    public PointOperationResponse earnPoints(EarnPointRequest request) {
        Member member = memberRepository.findByMemberId(request.getMemberId())
                .orElseThrow(() -> new RuntimeException("会员不存在: " + request.getMemberId()));
        
        Optional<PointRule> ruleOpt = pointRuleService.getActiveRule(request.getPointSource());
        
        if (ruleOpt.isEmpty()) {
            log.warn("未找到有效的积分规则: pointSource={}", request.getPointSource());
            throw new RuntimeException("无效的积分来源: " + request.getPointSource());
        }
        
        PointRule rule = ruleOpt.get();
        
        ValidationRuleService.ValidationResult validationResult = 
                validationRuleService.validateAndCalculatePoints(
                        request.getPointSource(), 
                        request.getPointAmount(), 
                        rule);
        
        if (!validationResult.isValid()) {
            log.warn("积分校验失败: memberId={}, sourceType={}, errorCode={}, message={}", 
                    request.getMemberId(), request.getPointSource(), 
                    validationResult.getErrorCode(), validationResult.getMessage());
            throw new RuntimeException("积分校验失败: " + validationResult.getMessage());
        }
        
        double levelMultiplier = levelService.getLevelMultiplier(member.getMemberLevel());
        int basePoints = validationResult.getCalculatedPoints();
        int actualPoints = (int) Math.round(basePoints * levelMultiplier);
        
        log.info("积分计算: sourceType={}, baseAmount={}, basePoints={}, levelMultiplier={}, actualPoints={}, validationRule={}", 
                request.getPointSource(), request.getPointAmount(), basePoints, 
                levelMultiplier, actualPoints, validationResult.getValidationRuleId());
        
        if (actualPoints <= 0) {
            log.warn("计算得到的积分数为0: memberId={}", request.getMemberId());
            throw new RuntimeException("计算得到的积分数为0");
        }
        
        int originalPoints = member.getAvailablePoints();
        int originalTotal = member.getTotalPoints();
        
        member.setTotalPoints(originalTotal + actualPoints);
        member.setAvailablePoints(originalPoints + actualPoints);
        memberRepository.save(member);
        
        String pointId = "point_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        
        LocalDate expireDate = expirePolicyService.calculateExpireDate(rule, member, actualPoints);
        
        PointRecord record = PointRecord.builder()
                .pointId(pointId)
                .memberId(member.getMemberId())
                .pointType(PointType.EARN)
                .pointAmount(actualPoints)
                .pointSource(request.getPointSource())
                .pointBalance(member.getAvailablePoints())
                .expireAt(expireDate)
                .isExpired(expireDate == null ? false : false)
                .build();
        
        pointRecordRepository.save(record);
        
        pointStatService.recordEarnStat(actualPoints);
        
        levelService.checkAndUpgradeLevel(member);
        
        log.info("积分获取成功: memberId={}, earnedPoints={}, balance={}, expireDate={}", 
                member.getMemberId(), actualPoints, member.getAvailablePoints(), expireDate);
        
        return PointOperationResponse.builder()
                .pointId(pointId)
                .balance(member.getAvailablePoints())
                .earnedPoints(actualPoints)
                .build();
    }
    
    @Transactional
    public PointOperationResponse consumePoints(ConsumePointRequest request) {
        Member member = memberRepository.findByMemberId(request.getMemberId())
                .orElseThrow(() -> new RuntimeException("会员不存在: " + request.getMemberId()));
        
        int consumeAmount = request.getConsumeAmount();
        
        if (member.getAvailablePoints() < consumeAmount) {
            throw new RuntimeException("积分余额不足: 可用=" + member.getAvailablePoints() 
                    + ", 消费=" + consumeAmount);
        }
        
        int originalPoints = member.getAvailablePoints();
        int originalUsed = member.getUsedPoints();
        
        member.setAvailablePoints(originalPoints - consumeAmount);
        member.setUsedPoints(originalUsed + consumeAmount);
        memberRepository.save(member);
        
        String pointId = "point_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        
        PointRecord record = PointRecord.builder()
                .pointId(pointId)
                .memberId(member.getMemberId())
                .pointType(PointType.CONSUME)
                .pointAmount(consumeAmount)
                .consumeType(request.getConsumeType())
                .pointBalance(member.getAvailablePoints())
                .isExpired(false)
                .build();
        
        pointRecordRepository.save(record);
        
        pointStatService.recordConsumeStat(consumeAmount);
        
        log.info("积分消费成功: memberId={}, consumedPoints={}, balance={}", 
                member.getMemberId(), consumeAmount, member.getAvailablePoints());
        
        return PointOperationResponse.builder()
                .pointId(pointId)
                .balance(member.getAvailablePoints())
                .consumedPoints(consumeAmount)
                .build();
    }
    
    private LocalDate calculateExpireDate() {
        return LocalDate.now().plusDays(expireDays);
    }
    
    @Transactional(readOnly = true)
    public int getAvailablePoints(String memberId) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RuntimeException("会员不存在: " + memberId));
        return member.getAvailablePoints();
    }
    
    @Transactional(readOnly = true)
    public int getTotalPoints(String memberId) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RuntimeException("会员不存在: " + memberId));
        return member.getTotalPoints();
    }
}
