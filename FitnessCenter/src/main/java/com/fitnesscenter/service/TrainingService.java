package com.fitnesscenter.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesscenter.dto.TrainingRequest;
import com.fitnesscenter.dto.TrainingResponse;
import com.fitnesscenter.model.*;
import com.fitnesscenter.repository.*;
import com.fitnesscenter.util.IdGenerator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class TrainingService {

    private final TrainingRepository trainingRepository;
    private final MemberService memberService;
    private final CourseService courseService;
    private final BookingService bookingService;
    private final PlanService planService;
    private final HistoryRepository historyRepository;
    private final StatisticRepository statisticRepository;
    private final ObjectMapper objectMapper;

    public TrainingService(TrainingRepository trainingRepository,
                           MemberService memberService,
                           CourseService courseService,
                           BookingService bookingService,
                           PlanService planService,
                           HistoryRepository historyRepository,
                           StatisticRepository statisticRepository,
                           ObjectMapper objectMapper) {
        this.trainingRepository = trainingRepository;
        this.memberService = memberService;
        this.courseService = courseService;
        this.bookingService = bookingService;
        this.planService = planService;
        this.historyRepository = historyRepository;
        this.statisticRepository = statisticRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TrainingResponse recordTraining(TrainingRequest request) {
        memberService.validateMemberStatus(request.getMemberId());
        Member member = memberService.getMemberById(request.getMemberId());

        Course course = courseService.getCourseById(request.getCourseId());

        bookingService.validateMemberHasBooking(request.getMemberId(), request.getCourseId());

        String intensity = request.getTrainingIntensity() != null ? request.getTrainingIntensity() : "medium";
        int duration = request.getTrainingDuration() != null ? request.getTrainingDuration() : 60;
        int calories = calculateCalories(duration, intensity);
        double effectScore = calculateEffectScore(duration, intensity);

        Training training = new Training();
        training.setTrainingId(IdGenerator.generateTrainingId());
        training.setMemberId(request.getMemberId());
        training.setCourseId(request.getCourseId());
        training.setTrainingDuration(duration);
        training.setTrainingIntensity(intensity);
        training.setTrainingCalories(calories);
        training.setTrainingTime(Instant.now());
        training.setTrainingEffectScore(effectScore);

        Training savedTraining = trainingRepository.save(training);

        memberService.updateTrainingStats(request.getMemberId(), calories);

        planService.updatePlanProgress(request.getMemberId(), calories);

        updateMonthlyTrainingStats(calories);

        try {
            History history = new History();
            history.setHistoryId(IdGenerator.generateHistoryId());
            history.setMemberId(request.getMemberId());
            history.setActionType("TRAINING_RECORD");
            history.setActionData(objectMapper.writeValueAsString(savedTraining));
            history.setActionTime(Instant.now());
            history.setRelatedId(savedTraining.getTrainingId());
            historyRepository.save(history);
        } catch (Exception e) {
            // ignore
        }

        return new TrainingResponse(savedTraining.getTrainingId(), savedTraining.getTrainingCalories());
    }

    private int calculateCalories(int duration, String intensity) {
        int basePerMinute = 5;
        int multiplier = 1;
        switch (intensity.toLowerCase()) {
            case "high":
                multiplier = 2;
                break;
            case "medium":
                multiplier = 1;
                break;
            case "low":
                multiplier = 1;
                break;
            default:
                multiplier = 1;
        }
        return duration * basePerMinute * multiplier;
    }

    private double calculateEffectScore(int duration, String intensity) {
        double baseScore = duration / 60.0 * 10;
        switch (intensity.toLowerCase()) {
            case "high":
                return baseScore * 1.5;
            case "medium":
                return baseScore * 1.0;
            case "low":
                return baseScore * 0.7;
            default:
                return baseScore;
        }
    }

    @Transactional(readOnly = true)
    public Training getTrainingById(String trainingId) {
        return trainingRepository.findByTrainingId(trainingId)
                .orElseThrow(() -> new IllegalArgumentException("训练记录不存在"));
    }

    @Transactional(readOnly = true)
    public List<Training> getTrainingsByMemberId(String memberId) {
        return trainingRepository.findByMemberId(memberId);
    }

    @Transactional(readOnly = true)
    public List<Training> getAllTrainings() {
        return trainingRepository.findAll();
    }

    private void updateMonthlyTrainingStats(int calories) {
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

        statistic.setTrainingCount(statistic.getTrainingCount() + 1);
        statistic.setTotalCalories(statistic.getTotalCalories() + calories);
        statisticRepository.save(statistic);
    }
}
