package com.cardgame.rank.service;

import com.cardgame.common.utils.IdGenerator;
import com.cardgame.common.utils.SeededRandom;
import com.cardgame.rank.entity.DailyChallenge;
import com.cardgame.rank.mapper.DailyChallengeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Component
public class DailyChallengeService {

    @Autowired
    private DailyChallengeMapper challengeMapper;

    @Autowired
    private RankService rankService;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Transactional
    public DailyChallenge generateTodayChallenge() {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DATE_FORMATTER);

        DailyChallenge existing = getChallengeByDate(dateStr);
        if (existing != null) {
            return existing;
        }

        long seed = generateDailySeed(today);
        SeededRandom random = new SeededRandom(seed);

        String[] difficulties = {"Normal", "Hard", "Expert"};
        String difficulty = difficulties[random.nextInt(difficulties.length)];

        DailyChallenge challenge = DailyChallenge.builder()
                .challengeId(IdGenerator.generateUUID())
                .date(dateStr)
                .seed(seed)
                .description(generateDescription(difficulty, random))
                .difficulty(difficulty)
                .targetFloor(random.nextInt(10, 25))
                .scoreMultiplier(difficulty.equals("Expert") ? 3 : difficulty.equals("Hard") ? 2 : 1)
                .startTime(toStartOfDayMillis(today))
                .endTime(toEndOfDayMillis(today))
                .build();

        String[] modifiers = {"No Heals", "Double Damage", "Half Energy", "Fast Enemies", "Extra Gold"};
        int modifierCount = random.nextInt(1, 3);
        for (int i = 0; i < modifierCount; i++) {
            challenge.getModifiers().put("modifier_" + i, modifiers[random.nextInt(modifiers.length)]);
        }

        challengeMapper.insertChallenge(challenge);
        log.info("Generated daily challenge for {}: {}", dateStr, challenge.getDescription());

        return challenge;
    }

    public DailyChallenge getTodayChallenge() {
        LocalDate today = LocalDate.now();
        String dateStr = today.format(DATE_FORMATTER);
        DailyChallenge challenge = getChallengeByDate(dateStr);
        if (challenge == null) {
            return generateTodayChallenge();
        }
        return challenge;
    }

    public DailyChallenge getChallengeByDate(String date) {
        return challengeMapper.findChallengeByDate(date);
    }

    public DailyChallenge getChallenge(String challengeId) {
        return challengeMapper.findChallengeById(challengeId);
    }

    public List<DailyChallenge> getRecentChallenges(int days) {
        return challengeMapper.findRecentChallenges(days);
    }

    @Transactional
    public void recordDailyScore(String playerId, int baseScore, int floorReached) {
        DailyChallenge challenge = getTodayChallenge();
        int finalScore = challenge.calculateFinalScore(baseScore, floorReached);
        rankService.updateDailyScore(challenge.getDate(), playerId, finalScore);
        log.debug("Recorded daily score for player {}: {}", playerId, finalScore);
    }

    public List<com.cardgame.rank.entity.RankEntry> getTodayLeaderboard(int start, int end) {
        DailyChallenge challenge = getTodayChallenge();
        return rankService.getDailyRank(challenge.getDate(), start, end);
    }

    public int calculateDailyReward(int rank) {
        if (rank <= 1) return 1000;
        if (rank <= 10) return 500;
        if (rank <= 100) return 200;
        if (rank <= 1000) return 100;
        return 50;
    }

    private long generateDailySeed(LocalDate date) {
        return date.getYear() * 10000L + date.getMonthValue() * 100L + date.getDayOfMonth();
    }

    private long toStartOfDayMillis(LocalDate date) {
        return LocalDateTime.of(date, LocalTime.MIN)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private long toEndOfDayMillis(LocalDate date) {
        return LocalDateTime.of(date, LocalTime.MAX)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }

    private String generateDescription(String difficulty, SeededRandom random) {
        String[] templates = {
                "Conquer the dungeon with %s difficulty!",
                "Test your skills in this %s challenge!",
                "Can you survive this %s daily run?",
                "Prove your worth in a %s trial!"
        };
        return String.format(templates[random.nextInt(templates.length)], difficulty);
    }

    public void cleanupOldChallenges(int keepDays) {
        LocalDate cutoff = LocalDate.now().minusDays(keepDays);
        String cutoffDate = cutoff.format(DATE_FORMATTER);
        int deleted = challengeMapper.deleteChallengesBefore(cutoffDate);
        if (deleted > 0) {
            log.info("Cleaned up {} old daily challenges", deleted);
        }
    }
}
