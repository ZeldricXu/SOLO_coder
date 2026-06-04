package com.battle.platform.leaderboard;

import com.battle.platform.entity.SeasonRanking;
import com.battle.platform.repository.PlayerRepository;
import com.battle.platform.repository.SeasonRankingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class LeaderboardService {

    private final StringRedisTemplate stringRedisTemplate;
    private final SeasonRankingRepository seasonRankingRepository;
    private final PlayerRepository playerRepository;

    private static final String LEADERBOARD_SCORE = "leaderboard:score";
    private static final String LEADERBOARD_KILLS = "leaderboard:kills";
    private static final String LEADERBOARD_GUILD = "leaderboard:guild";

    private static final String SNAPSHOT_SCORE = "leaderboard:snapshot:score";
    private static final String SNAPSHOT_KILLS = "leaderboard:snapshot:kills";
    private static final String SNAPSHOT_GUILD = "leaderboard:snapshot:guild";

    private static final String SNAPSHOT_LOCK_KEY = "leaderboard:snapshot:lock";
    private static final long SNAPSHOT_LOCK_TIMEOUT_SECONDS = 600;

    public List<Map<String, Object>> getTopPlayersByScore(int topN) {
        return LeaderboardQueryBuilder.create(stringRedisTemplate)
                .dimension(LeaderboardQueryBuilder.Dimension.SCORE)
                .scope(LeaderboardQueryBuilder.Scope.ALL)
                .topN(topN)
                .execute();
    }

    public List<Map<String, Object>> getTopPlayersByKills(int topN) {
        return LeaderboardQueryBuilder.create(stringRedisTemplate)
                .dimension(LeaderboardQueryBuilder.Dimension.KILLS)
                .scope(LeaderboardQueryBuilder.Scope.ALL)
                .topN(topN)
                .execute();
    }

    public List<Map<String, Object>> getTopGuildsByScore(int topN) {
        return LeaderboardQueryBuilder.create(stringRedisTemplate)
                .dimension(LeaderboardQueryBuilder.Dimension.GUILD)
                .scope(LeaderboardQueryBuilder.Scope.ALL)
                .topN(topN)
                .execute();
    }

    public LeaderboardQueryBuilder queryBuilder() {
        return LeaderboardQueryBuilder.create(stringRedisTemplate);
    }

    public Long getPlayerRank(Long playerId) {
        return LeaderboardQueryBuilder.create(stringRedisTemplate)
                .dimension(LeaderboardQueryBuilder.Dimension.SCORE)
                .getPlayerRank(playerId);
    }

    public Double getPlayerScore(Long playerId) {
        return LeaderboardQueryBuilder.create(stringRedisTemplate)
                .dimension(LeaderboardQueryBuilder.Dimension.SCORE)
                .getPlayerScore(playerId);
    }

    @Scheduled(cron = "${battle.leaderboard.snapshot-interval-cron:0 0 * * * *}")
    public void takeSnapshot() {
        Boolean lockAcquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(SNAPSHOT_LOCK_KEY,
                        Thread.currentThread().getName() + "-" + System.currentTimeMillis(),
                        SNAPSHOT_LOCK_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.warn("Snapshot task skipped - previous snapshot still running (lock held)");
            return;
        }

        try {
            log.info("Leaderboard snapshot started");

            snapshotZSet(LEADERBOARD_SCORE, SNAPSHOT_SCORE);
            snapshotZSet(LEADERBOARD_KILLS, SNAPSHOT_KILLS);
            snapshotZSet(LEADERBOARD_GUILD, SNAPSHOT_GUILD);

            log.info("Leaderboard snapshot completed at {}", LocalDateTime.now());

        } finally {
            stringRedisTemplate.delete(SNAPSHOT_LOCK_KEY);
        }
    }

    public void archiveSeasonToMySQL(Long seasonId) {
        String lockKey = "leaderboard:archive:" + seasonId + ":lock";
        Boolean lockAcquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(lockKey, "archive", SNAPSHOT_LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        if (Boolean.FALSE.equals(lockAcquired)) {
            log.warn("Archive task skipped for season {} - already running", seasonId);
            return;
        }

        try {
            archiveToMySQL(seasonId, LEADERBOARD_SCORE, SeasonRanking.RankingType.TOTAL_SCORE);
            archiveToMySQL(seasonId, LEADERBOARD_KILLS, SeasonRanking.RankingType.KILLS);
            archiveToMySQL(seasonId, LEADERBOARD_GUILD, SeasonRanking.RankingType.GUILD_SCORE);

            log.info("Season {} rankings archived to MySQL", seasonId);
        } finally {
            stringRedisTemplate.delete(lockKey);
        }
    }

    private List<Map<String, Object>> getTopFromZSet(String key, int topN) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(key, 0, topN - 1);

        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples == null) return result;

        long rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : tuples) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("rank", rank++);
            entry.put("id", tuple.getValue());
            entry.put("score", tuple.getScore());
            result.add(entry);
        }

        return result;
    }

    private void snapshotZSet(String sourceKey, String destKey) {
        stringRedisTemplate.delete(destKey);
        Set<ZSetOperations.TypedTuple<String>> all =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(sourceKey, 0, -1);
        if (all != null && !all.isEmpty()) {
            for (ZSetOperations.TypedTuple<String> tuple : all) {
                if (tuple.getValue() != null && tuple.getScore() != null) {
                    stringRedisTemplate.opsForZSet().add(destKey, tuple.getValue(), tuple.getScore());
                }
            }
        }
    }

    private void archiveToMySQL(Long seasonId, String redisKey, SeasonRanking.RankingType type) {
        Set<ZSetOperations.TypedTuple<String>> all =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(redisKey, 0, -1);

        if (all == null || all.isEmpty()) return;

        List<SeasonRanking> rankings = new ArrayList<>();
        int rank = 1;

        for (ZSetOperations.TypedTuple<String> tuple : all) {
            if (tuple.getValue() == null || tuple.getScore() == null) continue;

            SeasonRanking ranking = SeasonRanking.builder()
                    .seasonId(seasonId)
                    .rankingType(type)
                    .rank(rank++)
                    .score(tuple.getScore().intValue())
                    .snapshotAt(LocalDateTime.now())
                    .build();

            if (type == SeasonRanking.RankingType.GUILD_SCORE) {
                ranking.setGuildId(Long.parseLong(tuple.getValue()));
            } else {
                ranking.setPlayerId(Long.parseLong(tuple.getValue()));
            }

            rankings.add(ranking);
        }

        seasonRankingRepository.saveAll(rankings);
    }
}
