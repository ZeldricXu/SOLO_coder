package com.battle.platform.score;

import com.battle.platform.config.ScoreProperties;
import com.battle.platform.protocol.GameMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoreService {

    private final ScoreProperties scoreProperties;
    private final StringRedisTemplate stringRedisTemplate;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, PlayerScore>> battleScores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, List<DamageRecord>>> battleDamageRecords = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, ReentrantLock>> playerScoreLocks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> processedEvents = new ConcurrentHashMap<>();

    private static final String LEADERBOARD_SCORE = "leaderboard:score";
    private static final String LEADERBOARD_KILLS = "leaderboard:kills";
    private static final String LEADERBOARD_GUILD = "leaderboard:guild";

    public void initPlayerScore(String battleId, Long playerId) {
        battleScores.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>())
                .put(playerId, PlayerScore.builder()
                        .playerId(playerId)
                        .totalScore(0)
                        .kills(0)
                        .deaths(0)
                        .assists(0)
                        .captures(0)
                        .currentStreak(0)
                        .maxStreak(0)
                        .headshots(0)
                        .damageDealt(0)
                        .damageTaken(0)
                        .build());

        battleDamageRecords.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>());
        playerScoreLocks.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>())
                .put(playerId, new ReentrantLock());
        processedEvents.computeIfAbsent(battleId, k -> ConcurrentHashMap.newKeySet());
    }

    public AddScoreResult addScore(String battleId, Long playerId, int scoreDelta, String reason, String idempotencyKey) {
        if (idempotencyKey != null) {
            Set<String> events = processedEvents.get(battleId);
            if (events != null && !events.add(battleId + ":" + playerId + ":" + idempotencyKey)) {
                log.warn("Duplicate score event rejected: battleId={}, playerId={}, key={}", battleId, playerId, idempotencyKey);
                return AddScoreResult.duplicate();
            }
        }

        Map<Long, PlayerScore> scores = battleScores.get(battleId);
        if (scores == null) return AddScoreResult.failure("Battle not found");

        PlayerScore score = scores.get(playerId);
        if (score == null) return AddScoreResult.failure("Player not found");

        ReentrantLock lock = null;
        ConcurrentHashMap<Long, ReentrantLock> locks = playerScoreLocks.get(battleId);
        if (locks != null) {
            lock = locks.get(playerId);
        }

        if (lock != null) {
            lock.lock();
            try {
                score.setTotalScore(score.getTotalScore() + scoreDelta);
            } finally {
                lock.unlock();
            }
        } else {
            score.setTotalScore(score.getTotalScore() + scoreDelta);
        }

        updateRedisLeaderboard(score);

        log.info("Score added: battleId={}, playerId={}, delta={}, reason={}, total={}",
                battleId, playerId, scoreDelta, reason, score.getTotalScore());

        return AddScoreResult.success(score.getTotalScore());
    }

    public void onDamage(String battleId, Long attackerId, Long victimId,
                         double damage, int skillId, boolean isHeadshot) {
        Map<Long, PlayerScore> scores = battleScores.get(battleId);
        if (scores == null) return;

        DamageRecord record = new DamageRecord(attackerId, victimId, damage, skillId, isHeadshot, System.currentTimeMillis());
        battleDamageRecords.get(battleId).computeIfAbsent(victimId, k -> new ArrayList<>()).add(record);

        PlayerScore attackerScore = scores.get(attackerId);
        if (attackerScore != null) {
            attackerScore.setDamageDealt(attackerScore.getDamageDealt() + damage);
            if (isHeadshot) {
                attackerScore.setHeadshots(attackerScore.getHeadshots() + 1);
            }
        }

        PlayerScore victimScore = scores.get(victimId);
        if (victimScore != null) {
            victimScore.setDamageTaken(victimScore.getDamageTaken() + damage);
        }
    }

    public void onKill(String battleId, Long killerId, Long victimId, int skillId) {
        Map<Long, PlayerScore> scores = battleScores.get(battleId);
        if (scores == null) return;

        PlayerScore killerScore = scores.get(killerId);
        PlayerScore victimScore = scores.get(victimId);
        if (killerScore == null || victimScore == null) return;

        int killScore = scoreProperties.getKillBase();
        killerScore.setKills(killerScore.getKills() + 1);
        killerScore.setCurrentStreak(killerScore.getCurrentStreak() + 1);
        if (killerScore.getCurrentStreak() > killerScore.getMaxStreak()) {
            killerScore.setMaxStreak(killerScore.getCurrentStreak());
        }

        if (killerScore.getCurrentStreak() >= scoreProperties.getStreakBonusThreshold()) {
            int streakBonus = (int) (killScore * killerScore.getCurrentStreak() * scoreProperties.getStreakMultiplier());
            killScore += streakBonus;
        }

        addScore(battleId, killerId, killScore, "kill_" + victimId, "kill_" + killerId + "_" + victimId + "_" + System.currentTimeMillis());

        victimScore.setDeaths(victimScore.getDeaths() + 1);
        victimScore.setCurrentStreak(0);

        distributeAssistScore(battleId, victimId, killerId);

        updateRedisLeaderboard(killerScore);
    }

    private void distributeAssistScore(String battleId, Long victimId, Long killerId) {
        Map<Long, List<DamageRecord>> damageMap = battleDamageRecords.get(battleId);
        if (damageMap == null) return;

        List<DamageRecord> victimDamage = damageMap.get(victimId);
        if (victimDamage == null || victimDamage.isEmpty()) return;

        Map<Long, Double> attackerDamage = new HashMap<>();
        double totalDamage = 0;

        for (DamageRecord dr : victimDamage) {
            if (!dr.getAttackerId().equals(killerId)) {
                attackerDamage.merge(dr.getAttackerId(), dr.getDamage(), Double::sum);
            }
            totalDamage += dr.getDamage();
        }

        if (totalDamage <= 0) return;

        Map<Long, PlayerScore> scores = battleScores.get(battleId);

        for (Map.Entry<Long, Double> entry : attackerDamage.entrySet()) {
            Long assisterId = entry.getKey();
            double contribution = entry.getValue() / totalDamage;
            int assistScore = (int) (scoreProperties.getAssistBase() * contribution);

            PlayerScore assisterScore = scores.get(assisterId);
            if (assisterScore != null) {
                assisterScore.setAssists(assisterScore.getAssists() + 1);
                addScore(battleId, assisterId, assistScore, "assist_" + victimId,
                        "assist_" + assisterId + "_" + victimId + "_" + System.currentTimeMillis());
                updateRedisLeaderboard(assisterScore);
            }
        }

        damageMap.remove(victimId);
    }

    public void onCapture(String battleId, Long playerId, int pointId) {
        Map<Long, PlayerScore> scores = battleScores.get(battleId);
        if (scores == null) return;

        PlayerScore score = scores.get(playerId);
        if (score == null) return;

        score.setCaptures(score.getCaptures() + 1);
        addScore(battleId, playerId, scoreProperties.getCaptureBase(), "capture_" + pointId,
                "capture_" + playerId + "_" + pointId + "_" + System.currentTimeMillis());

        log.info("Player {} captured point {} in battle {}, +{} score",
                playerId, pointId, battleId, scoreProperties.getCaptureBase());
    }

    public void finalizeBattleScores(String battleId) {
        Map<Long, PlayerScore> scores = battleScores.remove(battleId);
        battleDamageRecords.remove(battleId);
        playerScoreLocks.remove(battleId);
        processedEvents.remove(battleId);

        if (scores != null) {
            log.info("Finalized scores for battle {}: {} players", battleId, scores.size());
        }
    }

    public PlayerScore getPlayerScore(String battleId, Long playerId) {
        Map<Long, PlayerScore> scores = battleScores.get(battleId);
        return scores != null ? scores.get(playerId) : null;
    }

    private void updateRedisLeaderboard(PlayerScore score) {
        stringRedisTemplate.opsForZSet().add(LEADERBOARD_SCORE, score.getPlayerId().toString(), score.getTotalScore());
        stringRedisTemplate.opsForZSet().add(LEADERBOARD_KILLS, score.getPlayerId().toString(), score.getKills());
        if (score.getGuildId() != null) {
            Double currentGuildScore = stringRedisTemplate.opsForZSet().score(LEADERBOARD_GUILD, score.getGuildId().toString());
            double newScore = (currentGuildScore != null ? currentGuildScore : 0) + score.getTotalScore();
            stringRedisTemplate.opsForZSet().add(LEADERBOARD_GUILD, score.getGuildId().toString(), newScore);
        }
    }

    @lombok.Data
    @lombok.AllArgsConstructor(staticName = "of")
    @lombok.NoArgsConstructor
    public static class AddScoreResult {
        private boolean success;
        private boolean duplicate;
        private int totalScore;
        private String errorMessage;

        public static AddScoreResult success(int totalScore) {
            AddScoreResult r = new AddScoreResult();
            r.success = true;
            r.totalScore = totalScore;
            return r;
        }

        public static AddScoreResult duplicate() {
            AddScoreResult r = new AddScoreResult();
            r.duplicate = true;
            return r;
        }

        public static AddScoreResult failure(String message) {
            AddScoreResult r = new AddScoreResult();
            r.errorMessage = message;
            return r;
        }
    }
}
