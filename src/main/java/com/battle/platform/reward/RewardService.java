package com.battle.platform.reward;

import com.battle.platform.entity.RewardRecord;
import com.battle.platform.entity.SeasonRanking;
import com.battle.platform.repository.RewardRecordRepository;
import com.battle.platform.repository.SeasonRankingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class RewardService {

    private final RewardRecordRepository rewardRecordRepository;
    private final SeasonRankingRepository seasonRankingRepository;
    private final StringRedisTemplate stringRedisTemplate;

    private static final String LEADERBOARD_SCORE = "leaderboard:score";
    private static final String LEADERBOARD_GUILD = "leaderboard:guild";

    private static final String IDEMPOTENT_KEY_PREFIX = "reward:idempotent:";
    private static final long IDEMPOTENT_KEY_TTL_HOURS = 24;

    private final Map<String, RewardDeliveryResult> processedRequests = new ConcurrentHashMap<>();

    @Transactional
    public void calculateAndCreateRewards(Long seasonId, String rewardConfigJson) {
        List<SeasonRanking> personalRankings = seasonRankingRepository
                .findBySeasonIdAndRankingTypeOrderByRankAsc(seasonId, SeasonRanking.RankingType.TOTAL_SCORE);

        for (SeasonRanking ranking : personalRankings) {
            String rewardContent = calculatePersonalReward(ranking.getRank(), rewardConfigJson);

            try {
                RewardRecord record = RewardRecord.builder()
                        .seasonId(seasonId)
                        .playerId(ranking.getPlayerId())
                        .rewardType(RewardRecord.RewardType.PERSONAL)
                        .rank(ranking.getRank())
                        .rewardContentJson(rewardContent)
                        .status(RewardRecord.RewardStatus.PENDING)
                        .retryCount(0)
                        .build();

                rewardRecordRepository.save(record);
            } catch (DuplicateKeyException e) {
                log.debug("Reward record already exists for player {} season {} type PERSONAL",
                        ranking.getPlayerId(), seasonId);
            }
        }

        List<SeasonRanking> guildRankings = seasonRankingRepository
                .findBySeasonIdAndRankingTypeOrderByRankAsc(seasonId, SeasonRanking.RankingType.GUILD_SCORE);

        for (SeasonRanking ranking : guildRankings) {
            String rewardContent = calculateGuildReward(ranking.getRank(), rewardConfigJson);

            try {
                RewardRecord record = RewardRecord.builder()
                        .seasonId(seasonId)
                        .playerId(ranking.getPlayerId() != null ? ranking.getPlayerId() : 0L)
                        .rewardType(RewardRecord.RewardType.GUILD)
                        .rank(ranking.getRank())
                        .rewardContentJson(rewardContent)
                        .status(RewardRecord.RewardStatus.PENDING)
                        .retryCount(0)
                        .build();
                record.setGuildId(ranking.getGuildId());

                rewardRecordRepository.save(record);
            } catch (DuplicateKeyException e) {
                log.debug("Reward record already exists for guild {} season {} type GUILD",
                        ranking.getGuildId(), seasonId);
            }
        }

        log.info("Created reward records for season {}: {} personal, {} guild",
                seasonId, personalRankings.size(), guildRankings.size());
    }

    @Transactional
    public RewardDeliveryResult deliverReward(Long rewardId, String requestId) {
        if (requestId != null) {
            RewardDeliveryResult cachedResult = getCachedResult(requestId);
            if (cachedResult != null) {
                log.info("Idempotent request {} - returning cached result", requestId);
                return cachedResult;
            }
        }

        Optional<RewardRecord> opt = rewardRecordRepository.findById(rewardId);
        if (opt.isEmpty()) {
            RewardDeliveryResult result = new RewardDeliveryResult(false, "Reward not found");
            if (requestId != null) cacheResult(requestId, result);
            return result;
        }

        RewardRecord record = opt.get();

        if (record.getStatus() == RewardRecord.RewardStatus.DELIVERED) {
            RewardDeliveryResult result = new RewardDeliveryResult(true, "Already delivered");
            if (requestId != null) cacheResult(requestId, result);
            return result;
        }

        try {
            sendToGameMailbox(record.getPlayerId(), record.getRewardContentJson());

            record.setStatus(RewardRecord.RewardStatus.DELIVERED);
            record.setDeliveredAt(LocalDateTime.now());
            if (requestId != null) record.setRequestId(requestId);
            rewardRecordRepository.save(record);

            log.info("Reward {} delivered to player {}", rewardId, record.getPlayerId());

            RewardDeliveryResult result = new RewardDeliveryResult(true, "Success");
            if (requestId != null) cacheResult(requestId, result);
            return result;

        } catch (Exception e) {
            log.error("Failed to deliver reward {} to player {}", rewardId, record.getPlayerId(), e);

            record.setRetryCount(record.getRetryCount() + 1);
            if (record.getRetryCount() >= 3) {
                record.setStatus(RewardRecord.RewardStatus.FAILED);
                record.setFailReason(e.getMessage());
            } else {
                record.setFailReason("Retry " + record.getRetryCount() + ": " + e.getMessage());
            }
            rewardRecordRepository.save(record);

            RewardDeliveryResult result = new RewardDeliveryResult(false, e.getMessage());
            if (requestId != null) cacheResult(requestId, result);
            return result;
        }
    }

    public RewardDeliveryResult deliverReward(Long rewardId) {
        return deliverReward(rewardId, null);
    }

    @Transactional
    public void retryFailedRewards() {
        List<RewardRecord> failedRecords = rewardRecordRepository
                .findByStatusAndRetryCountLessThan(RewardRecord.RewardStatus.FAILED, 3);

        for (RewardRecord record : failedRecords) {
            record.setStatus(RewardRecord.RewardStatus.PENDING);
            rewardRecordRepository.save(record);
            deliverReward(record.getId());
        }

        log.info("Retried {} failed reward deliveries", failedRecords.size());
    }

    private RewardDeliveryResult getCachedResult(String requestId) {
        String redisKey = IDEMPOTENT_KEY_PREFIX + requestId;
        String cached = stringRedisTemplate.opsForValue().get(redisKey);
        if (cached != null) {
            return RewardDeliveryResult.fromString(cached);
        }
        return processedRequests.get(requestId);
    }

    private void cacheResult(String requestId, RewardDeliveryResult result) {
        processedRequests.put(requestId, result);
        String redisKey = IDEMPOTENT_KEY_PREFIX + requestId;
        stringRedisTemplate.opsForValue().set(redisKey, result.toString(),
                IDEMPOTENT_KEY_TTL_HOURS, TimeUnit.HOURS);
    }

    void sendToGameMailbox(Long playerId, String rewardContentJson) {
        log.info("Sending reward to player {} mailbox: {}", playerId, rewardContentJson);
    }

    private String calculatePersonalReward(int rank, String configJson) {
        if (rank <= 3) return "{\"diamond\":5000,\"hero_shard\":100,\"legendary_equipment\":1}";
        if (rank <= 10) return "{\"diamond\":3000,\"hero_shard\":50,\"epic_equipment\":1}";
        if (rank <= 50) return "{\"diamond\":1000,\"hero_shard\":20}";
        if (rank <= 100) return "{\"diamond\":500,\"hero_shard\":10}";
        return "{\"diamond\":100}";
    }

    private String calculateGuildReward(int rank, String configJson) {
        if (rank <= 3) return "{\"guild_diamond\":10000,\"guild_exp\":5000}";
        if (rank <= 10) return "{\"guild_diamond\":5000,\"guild_exp\":3000}";
        return "{\"guild_diamond\":1000,\"guild_exp\":1000}";
    }

    public static class RewardDeliveryResult {
        private final boolean success;
        private final String message;

        public RewardDeliveryResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public boolean isSuccess() {
            return success;
        }

        public String getMessage() {
            return message;
        }

        public static RewardDeliveryResult fromString(String str) {
            String[] parts = str.split("\\|", 2);
            return new RewardDeliveryResult(Boolean.parseBoolean(parts[0]), parts.length > 1 ? parts[1] : "");
        }

        @Override
        public String toString() {
            return success + "|" + message;
        }
    }
}
