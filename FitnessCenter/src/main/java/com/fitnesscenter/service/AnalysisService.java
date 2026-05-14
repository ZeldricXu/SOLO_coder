package com.fitnesscenter.service;

import com.fitnesscenter.model.Statistic;
import com.fitnesscenter.model.Training;
import com.fitnesscenter.repository.StatisticRepository;
import com.fitnesscenter.repository.TrainingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
public class AnalysisService {

    private final TrainingRepository trainingRepository;
    private final StatisticRepository statisticRepository;
    private final MemberService memberService;
    private final PlanService planService;

    public AnalysisService(TrainingRepository trainingRepository,
                           StatisticRepository statisticRepository,
                           MemberService memberService,
                           PlanService planService) {
        this.trainingRepository = trainingRepository;
        this.statisticRepository = statisticRepository;
        this.memberService = memberService;
        this.planService = planService;
    }

    @Transactional(readOnly = true)
    public Statistic getMonthlyStatistics(String month) {
        return statisticRepository.findByStatMonth(month)
                .orElseGet(() -> {
                    Statistic emptyStat = new Statistic();
                    emptyStat.setStatMonth(month);
                    emptyStat.setMemberCount(0);
                    emptyStat.setBookingCount(0);
                    emptyStat.setTrainingCount(0);
                    emptyStat.setTotalCalories(0);
                    emptyStat.setPlanCount(0);
                    return emptyStat;
                });
    }

    @Transactional(readOnly = true)
    public Statistic getCurrentMonthStatistics() {
        String currentMonth = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        return getMonthlyStatistics(currentMonth);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getMemberAnalysis(String memberId) {
        Map<String, Object> analysis = new HashMap<>();
        analysis.put("memberId", memberId);

        try {
            com.fitnesscenter.model.Member member = memberService.getMemberById(memberId);
            analysis.put("memberName", member.getMemberName());
            analysis.put("memberLevel", member.getMemberLevel());
            analysis.put("memberStatus", member.getMemberStatus());
            analysis.put("totalTrainings", member.getTrainingCount());
            analysis.put("totalCalories", member.getTotalCalories());
            analysis.put("bookingCount", member.getBookingCount());
        } catch (Exception e) {
            // member not found
        }

        try {
            com.fitnesscenter.dto.PlanResponse planResponse = planService.queryPlan(memberId);
            if (planResponse != null && planResponse.getPlan() != null) {
                analysis.put("hasActivePlan", true);
                analysis.put("planProgress", planResponse.getPlan().getProgress());
                analysis.put("planStatus", planResponse.getPlan().getStatus());
            } else {
                analysis.put("hasActivePlan", false);
            }
        } catch (Exception e) {
            analysis.put("hasActivePlan", false);
        }

        List<Training> trainings = trainingRepository.findByMemberId(memberId);
        analysis.put("trainingHistoryCount", trainings.size());

        if (!trainings.isEmpty()) {
            int totalCalories = trainings.stream()
                    .mapToInt(t -> t.getTrainingCalories() != null ? t.getTrainingCalories() : 0)
                    .sum();
            int totalDuration = trainings.stream()
                    .mapToInt(t -> t.getTrainingDuration() != null ? t.getTrainingDuration() : 0)
                    .sum();
            double avgCaloriesPerTraining = (double) totalCalories / trainings.size();
            double avgDurationPerTraining = (double) totalDuration / trainings.size();

            analysis.put("totalCaloriesFromHistory", totalCalories);
            analysis.put("totalDurationFromHistory", totalDuration);
            analysis.put("avgCaloriesPerTraining", String.format("%.2f", avgCaloriesPerTraining));
            analysis.put("avgDurationPerTraining", String.format("%.2f", avgDurationPerTraining));

            Map<String, Integer> intensityCount = new HashMap<>();
            for (Training t : trainings) {
                String intensity = t.getTrainingIntensity();
                if (intensity != null) {
                    intensityCount.put(intensity, intensityCount.getOrDefault(intensity, 0) + 1);
                }
            }
            analysis.put("intensityDistribution", intensityCount);
        }

        return analysis;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getEffectAnalysis(String memberId) {
        Map<String, Object> effect = new HashMap<>();
        effect.put("memberId", memberId);

        List<Training> trainings = trainingRepository.findByMemberId(memberId);

        if (trainings.isEmpty()) {
            effect.put("hasData", false);
            return effect;
        }

        effect.put("hasData", true);

        double totalEffectScore = trainings.stream()
                .mapToDouble(t -> t.getTrainingEffectScore() != null ? t.getTrainingEffectScore() : 0)
                .sum();
        double avgEffectScore = totalEffectScore / trainings.size();

        int totalCalories = trainings.stream()
                .mapToInt(t -> t.getTrainingCalories() != null ? t.getTrainingCalories() : 0)
                .sum();
        int totalDuration = trainings.stream()
                .mapToInt(t -> t.getTrainingDuration() != null ? t.getTrainingDuration() : 0)
                .sum();

        effect.put("totalTrainingCount", trainings.size());
        effect.put("totalEffectScore", String.format("%.2f", totalEffectScore));
        effect.put("averageEffectScore", String.format("%.2f", avgEffectScore));
        effect.put("totalCaloriesBurned", totalCalories);
        effect.put("totalDurationMinutes", totalDuration);
        effect.put("effectLevel", getEffectLevel(avgEffectScore));

        LocalDate now = LocalDate.now();
        LocalDate startOfMonth = now.withDayOfMonth(1);
        Instant startInstant = startOfMonth.atStartOfDay(ZoneId.systemDefault()).toInstant();
        Instant endInstant = now.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant();

        List<Training> thisMonthTrainings = trainingRepository.findByMemberIdAndTrainingTimeBetween(
                memberId, startInstant, endInstant);

        int monthCalories = thisMonthTrainings.stream()
                .mapToInt(t -> t.getTrainingCalories() != null ? t.getTrainingCalories() : 0)
                .sum();
        int monthDuration = thisMonthTrainings.stream()
                .mapToInt(t -> t.getTrainingDuration() != null ? t.getTrainingDuration() : 0)
                .sum();

        Map<String, Object> monthStats = new HashMap<>();
        monthStats.put("trainingCount", thisMonthTrainings.size());
        monthStats.put("caloriesBurned", monthCalories);
        monthStats.put("durationMinutes", monthDuration);
        effect.put("thisMonthStats", monthStats);

        return effect;
    }

    private String getEffectLevel(double avgScore) {
        if (avgScore >= 12) {
            return "excellent";
        } else if (avgScore >= 9) {
            return "good";
        } else if (avgScore >= 6) {
            return "normal";
        } else {
            return "needs_improvement";
        }
    }

    @Transactional(readOnly = true)
    public List<Statistic> getAllStatistics() {
        return statisticRepository.findAll();
    }
}
