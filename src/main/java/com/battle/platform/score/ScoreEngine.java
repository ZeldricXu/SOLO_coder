package com.battle.platform.score;

import com.battle.platform.config.ScoreProperties;
import com.battle.platform.dto.ScoreUpdateNotification;
import com.battle.platform.netty.GameServerHandler;
import com.battle.platform.protocol.GameMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ScoreEngine {

    private final ScoreProperties scoreProperties;
    private final StringRedisTemplate stringRedisTemplate;
    private final GameServerHandler gameServerHandler;

    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, PlayerScore>> battleScores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, List<DamageRecord>>> battleDamageRecords = new ConcurrentHashMap<>();

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

        killerScore.setTotalScore(killerScore.getTotalScore() + killScore);

        victimScore.setDeaths(victimScore.getDeaths() + 1);
        victimScore.setCurrentStreak(0);

        distributeAssistScore(battleId, victimId, killerId);

        updateRedisLeaderboard(killerScore);
        notifyScoreUpdate(battleId, killerId, killerScore.getTotalScore());
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
                assisterScore.setTotalScore(assisterScore.getTotalScore() + assistScore);
                updateRedisLeaderboard(assisterScore);
                notifyScoreUpdate(battleId, assisterId, assisterScore.getTotalScore());
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
        score.setTotalScore(score.getTotalScore() + scoreProperties.getCaptureBase());

        updateRedisLeaderboard(score);
        notifyScoreUpdate(battleId, playerId, score.getTotalScore());

        log.info("Player {} captured point {} in battle {}, +{} score",
                playerId, pointId, battleId, scoreProperties.getCaptureBase());
    }

    public void finalizeBattleScores(String battleId) {
        Map<Long, PlayerScore> scores = battleScores.remove(battleId);
        battleDamageRecords.remove(battleId);

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

    private void notifyScoreUpdate(String battleId, Long playerId, int totalScore) {
        byte[] payload = (playerId + ":" + totalScore).getBytes();
        GameMessage msg = GameMessage.builder()
                .msgId(GameMessage.MSG_SCORE_UPDATE)
                .msgType(GameMessage.TYPE_PUSH)
                .playerId(playerId)
                .timestamp(System.currentTimeMillis())
                .payload(payload)
                .build();
        gameServerHandler.sendToPlayer(playerId, msg);
    }
}
