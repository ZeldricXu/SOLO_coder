package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.dto.PlanRequest;
import com.fitnesscenter.dto.PlanResponse;
import com.fitnesscenter.model.History;
import com.fitnesscenter.model.Member;
import com.fitnesscenter.model.Plan;
import com.fitnesscenter.model.Statistic;
import com.fitnesscenter.repository.HistoryRepository;
import com.fitnesscenter.repository.PlanRepository;
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
public class PlanService {

    private final PlanRepository planRepository;
    private final MemberService memberService;
    private final HistoryRepository historyRepository;
    private final StatisticRepository statisticRepository;
    private final ObjectMapper objectMapper;

    public PlanService(PlanRepository planRepository,
                       MemberService memberService,
                       HistoryRepository historyRepository,
                       StatisticRepository statisticRepository,
                       ObjectMapper objectMapper) {
        this.planRepository = planRepository;
        this.memberService = memberService;
        this.historyRepository = historyRepository;
        this.statisticRepository = statisticRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public Plan createPlan(PlanRequest request) {
        Member member = memberService.getMemberById(request.getMemberId());

        Optional<Plan> existingPlan = planRepository.findByMemberId(request.getMemberId());
        if (existingPlan.isPresent() && "in_progress".equals(existingPlan.get().getPlanStatus())) {
            throw new IllegalStateException("该会员已有进行中的健身计划");
        }

        Plan plan = new Plan();
        plan.setPlanId(IdGenerator.generatePlanId());
        plan.setMemberId(request.getMemberId());
        plan.setPlanType(request.getPlanType() != null ? request.getPlanType() : "general");
        plan.setPlanDuration(request.getPlanDuration() != null ? request.getPlanDuration() : 30);
        plan.setPlanTarget(request.getPlanTarget() != null ? request.getPlanTarget() : "保持健康");
        plan.setPlanProgress(0);
        plan.setPlanStatus("in_progress");
        plan.setCreatedAt(Instant.now());
        plan.setPlanContent(generatePlanContent(plan.getPlanType(), plan.getPlanDuration()));

        Plan savedPlan = planRepository.save(plan);

        updateMonthlyPlanCount();

        try {
            History history = new History();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setMemberId(request.getMemberId());
            history.setActionType("PLAN_CREATE");
            history.setActionData(objectMapper.writeValueAsString(savedPlan));
            history.setActionTime(Instant.now());
            history.setRelatedId(savedPlan.getPlanId());
            historyRepository.save(history);
        } catch (Exception e) {
            // ignore
        }

        return savedPlan;
    }

    private String generatePlanContent(String planType, int duration) {
        StringBuilder content = new StringBuilder();
        content.append("健身计划类型: ").append(planType).append("\n");
        content.append("计划周期: ").append(duration).append("天\n");
        content.append("训练频率: 每周5-6次\n");
        content.append("每次训练时长: 45-60分钟\n");
        content.append("包含内容: 有氧运动、力量训练、柔韧性练习\n");
        return content.toString();
    }

    @Transactional(readOnly = true)
    public Plan getPlanByMemberId(String memberId) {
        return planRepository.findByMemberId(memberId)
                .orElse(null);
    }

    @Transactional(readOnly = true)
    public Plan getPlanById(String planId) {
        return planRepository.findByPlanId(planId)
                .orElseThrow(() -> new IllegalArgumentException("健身计划不存在"));
    }

    @Transactional(readOnly = true)
    public PlanResponse queryPlan(String memberId) {
        Plan plan = planRepository.findByMemberId(memberId).orElse(null);
        if (plan == null) {
            return new PlanResponse(null);
        }
        return new PlanResponse(new PlanResponse.PlanInfo(
                plan.getPlanProgress(),
                plan.getPlanStatus(),
                plan.getPlanId(),
                plan.getPlanType(),
                plan.getPlanTarget()
        ));
    }

    @Transactional(readOnly = true)
    public List<Plan> getAllPlans() {
        return planRepository.findAll();
    }

    @Transactional
    public void updatePlanProgress(String memberId, int calories) {
        Optional<Plan> planOpt = planRepository.findByMemberId(memberId);
        if (planOpt.isEmpty()) {
            return;
        }

        Plan plan = planOpt.get();
        if (!"in_progress".equals(plan.getPlanStatus())) {
            return;
        }

        int progressIncrement = Math.max(1, calories / 100);
        int newProgress = Math.min(100, plan.getPlanProgress() + progressIncrement);
        plan.setPlanProgress(newProgress);

        if (newProgress >= 100) {
            plan.setPlanStatus("completed");
            try {
                History history = new History();
                history.setHistoryId(IdGenerator.generateHistoryId());
                history.setMemberId(memberId);
                history.setActionType("PLAN_COMPLETE");
                history.setActionData("Plan completed: " + plan.getPlanId());
                history.setActionTime(Instant.now());
                history.setRelatedId(plan.getPlanId());
                historyRepository.save(history);
            } catch (Exception e) {
                // ignore
            }
        }

        planRepository.save(plan);
    }

    @Transactional
    public Plan updatePlanStatus(String planId, String status) {
        Plan plan = planRepository.findByPlanId(planId)
                .orElseThrow(() -> new IllegalArgumentException("健身计划不存在"));

        plan.setPlanStatus(status);
        return planRepository.save(plan);
    }

    private void updateMonthlyPlanCount() {
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

        statistic.setPlanCount(statistic.getPlanCount() + 1);
        statisticRepository.save(statistic);
    }
}
