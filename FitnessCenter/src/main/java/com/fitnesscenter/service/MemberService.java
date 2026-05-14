package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.dto.MemberRequest;
import com.fitnesscenter.model.History;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.model.Statistic;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.repository.MemberRepository;
import com.fitnesscenter.repository.StatisticRepository;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class MemberService {

    private final MemberRepository memberRepository;
    private final HistoryRepository historyRepository;
    private final StatisticRepository statisticRepository;
    private final ObjectMapper objectMapper;

    public MemberService(MemberRepository memberRepository,
                         HistoryRepository historyRepository,
                         StatisticRepository statisticRepository,
                         ObjectMapper objectMapper) {
        this.memberRepository = memberRepository;
        this.historyRepository = historyRepository;
        this.statisticRepository = statisticRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Member registerMember(MemberRequest request) {
        if (memberRepository.existsByMemberPhone(request.getMemberPhone())) {
            throw new IllegalArgumentException("该手机号已被注册");
        }

        Member member = new Member();
        member.setMemberId(IdGenerator.generateMemberId());
        member.setMemberName(request.getMemberName());
        member.setMemberPhone(request.getMemberPhone());
        member.setMemberType(request.getMemberType() != null ? request.getMemberType() : "annual");
        member.setMemberStatus("active");
        member.setMemberLevel(request.getMemberLevel() != null ? request.getMemberLevel() : "regular");
        member.setRegisteredAt(Instant.now());
        member.setExpireAt(Instant.now().plusSeconds(365L * 24 * 60 * 60));
        member.setBookingCount(0);
        member.setTrainingCount(0);
        member.setTotalCalories(0);

        Member savedMember = memberRepository.save(member);

        try {
            History history = new History();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setMemberId(savedMember.getMemberId());
            history.setActionType("MEMBER_REGISTER");
            history.setActionData(objectMapper.writeValueAsString(savedMember));
            history.setActionTime(Instant.now());
            history.setRelatedId(savedMember.getMemberId());
            historyRepository.save(history);
        } catch (Exception e) {
            // ignore
        }

        updateMonthlyMemberCount();

        return savedMember;
    }

    @Transactional(readOnly = true)
    public Member getMemberById(String memberId) {
        return memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));
    }

    @Transactional(readOnly = true)
    public List<Member> getAllMembers() {
        return memberRepository.findAll();
    }

    @Transactional
    public Member updateMember(String memberId, MemberRequest request) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));

        if (request.getMemberName() != null) {
            member.setMemberName(request.getMemberName());
        }
        if (request.getMemberPhone() != null && !request.getMemberPhone().equals(member.getMemberPhone())) {
            if (memberRepository.existsByMemberPhone(request.getMemberPhone())) {
                throw new IllegalArgumentException("该手机号已被其他会员使用");
            }
            member.setMemberPhone(request.getMemberPhone());
        }
        if (request.getMemberType() != null) {
            member.setMemberType(request.getMemberType());
        }
        if (request.getMemberLevel() != null) {
            member.setMemberLevel(request.getMemberLevel());
        }

        Member updatedMember = memberRepository.save(member);

        try {
            History history = new History();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setMemberId(memberId);
            history.setActionType("MEMBER_UPDATE");
            history.setActionData(objectMapper.writeValueAsString(updatedMember));
            history.setActionTime(Instant.now());
            history.setRelatedId(memberId);
            historyRepository.save(history);
        } catch (Exception e) {
            // ignore
        }

        return updatedMember;
    }

    @Transactional
    public void updateMemberStatus(String memberId, String status) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));

        member.setMemberStatus(status);
        memberRepository.save(member);

        try {
            History history = new History();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setMemberId(memberId);
            history.setActionType("MEMBER_STATUS_UPDATE");
            history.setActionData("status=" + status);
            history.setActionTime(Instant.now());
            history.setRelatedId(memberId);
            historyRepository.save(history);
        } catch (Exception e) {
            // ignore
        }
    }

    @Transactional
    public void incrementBookingCount(String memberId) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));

        member.setBookingCount(member.getBookingCount() + 1);
        memberRepository.save(member);
    }

    @Transactional
    public void updateTrainingStats(String memberId, int calories) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));

        member.setTrainingCount(member.getTrainingCount() + 1);
        member.setTotalCalories(member.getTotalCalories() + calories);
        memberRepository.save(member);
    }

    @Transactional(readOnly = true)
    public void validateMemberStatus(String memberId) {
        Member member = memberRepository.findByMemberId(memberId)
                .orElseThrow(() -> new IllegalArgumentException("会员不存在"));

        if ("expired".equals(member.getMemberStatus())) {
            throw new IllegalStateException("会员已过期");
        }
        if ("frozen".equals(member.getMemberStatus())) {
            throw new IllegalStateException("会员已冻结，不可用");
        }
        if (member.getExpireAt() != null && member.getExpireAt().isBefore(Instant.now())) {
            throw new IllegalStateException("会员已过期");
        }
    }

    private void updateMonthlyMemberCount() {
        String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        Statistic statistic = statisticRepository.findByStatMonth(month).orElseGet(() -> {
            Statistic newStat = new Statistic();
            newStat.setStatId(IdGenerator.generateStatId());
            newStat.setStatMonth(month);
            newStat.setMemberCount(0);
            newStat.setBookingCount(0);
            newStat.setTrainingCount(0);
            newStat.setTotalCalories(0);
            newStat.setPlanCount(0);
            return newStat;
        });

        statistic.setMemberCount(statistic.getMemberCount() + 1);
        statisticRepository.save(statistic);
    }
}
