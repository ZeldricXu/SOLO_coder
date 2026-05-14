package com.memberscore.service;

import com.memberscore.dto.MemberCreateRequest;
import com.memberscore.entity.Member;
import com.memberscore.enums.MemberStatus;
import com.memberscore.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class MemberService {
    
    private final MemberRepository memberRepository;
    
    @Transactional
    public Member createMember(MemberCreateRequest request) {
        if (memberRepository.existsByMemberId(request.getMemberId())) {
            throw new RuntimeException("会员ID已存在: " + request.getMemberId());
        }
        if (memberRepository.existsByUserId(request.getUserId())) {
            throw new RuntimeException("用户ID已存在: " + request.getUserId());
        }
        
        Member member = Member.builder()
                .memberId(request.getMemberId())
                .userId(request.getUserId())
                .memberLevel("bronze")
                .totalPoints(0)
                .availablePoints(0)
                .usedPoints(0)
                .memberStatus(MemberStatus.ACTIVE)
                .build();
        
        Member saved = memberRepository.save(member);
        log.info("创建会员成功: memberId={}", saved.getMemberId());
        return saved;
    }
    
    @Transactional(readOnly = true)
    public Optional<Member> getMemberByMemberId(String memberId) {
        return memberRepository.findByMemberId(memberId);
    }
    
    @Transactional(readOnly = true)
    public Optional<Member> getMemberByUserId(String userId) {
        return memberRepository.findByUserId(userId);
    }
    
    @Transactional
    public Member updateMemberPoints(Member member, Integer earnedPoints, Integer consumedPoints) {
        if (earnedPoints != null && earnedPoints > 0) {
            member.setTotalPoints(member.getTotalPoints() + earnedPoints);
            member.setAvailablePoints(member.getAvailablePoints() + earnedPoints);
        }
        if (consumedPoints != null && consumedPoints > 0) {
            if (member.getAvailablePoints() < consumedPoints) {
                throw new RuntimeException("积分余额不足");
            }
            member.setAvailablePoints(member.getAvailablePoints() - consumedPoints);
            member.setUsedPoints(member.getUsedPoints() + consumedPoints);
        }
        return memberRepository.save(member);
    }
    
    @Transactional
    public Member updateMemberLevel(Member member, String newLevel) {
        member.setMemberLevel(newLevel);
        return memberRepository.save(member);
    }
    
    @Transactional
    public Member deactivateMember(String memberId) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RuntimeException("会员不存在: " + memberId));
        member.setMemberStatus(MemberStatus.INACTIVE);
        return memberRepository.save(member);
    }
    
    @Transactional
    public Member activateMember(String memberId) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new RuntimeException("会员不存在: " + memberId));
        member.setMemberStatus(MemberStatus.ACTIVE);
        return memberRepository.save(member);
    }
}
