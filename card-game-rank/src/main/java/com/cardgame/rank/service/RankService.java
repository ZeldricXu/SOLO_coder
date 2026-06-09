package com.cardgame.rank.service;

import com.cardgame.rank.entity.RankEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Tuple;

import java.util.*;

@Slf4j
@Component
public class RankService {

    private static final String GLOBAL_RANK_KEY = "cardgame:rank:global";
    private static final String SEASON_RANK_PREFIX = "cardgame:rank:season:";
    private static final String DAILY_RANK_PREFIX = "cardgame:rank:daily:";
    private static final String PLAYER_DATA_PREFIX = "cardgame:player:rank:";

    @Autowired
    private JedisPool jedisPool;

    public void updateScore(String playerId, String playerName, int score, int highestFloor) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.zadd(GLOBAL_RANK_KEY, score, playerId);

            String playerDataKey = PLAYER_DATA_PREFIX + playerId;
            Map<String, String> playerData = new HashMap<>();
            playerData.put("playerName", playerName);
            playerData.put("highestFloor", String.valueOf(highestFloor));
            playerData.put("lastUpdate", String.valueOf(System.currentTimeMillis()));
            jedis.hset(playerDataKey, playerData);

            log.debug("Updated rank for player {}: score={}", playerId, score);
        } catch (Exception e) {
            log.error("Failed to update rank for player {}: {}", playerId, e.getMessage());
        }
    }

    public void updateSeasonScore(String seasonId, String playerId, int score) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = SEASON_RANK_PREFIX + seasonId;
            jedis.zadd(key, score, playerId);
            log.debug("Updated season {} rank for player {}: score={}", seasonId, playerId, score);
        } catch (Exception e) {
            log.error("Failed to update season rank: {}", e.getMessage());
        }
    }

    public void updateDailyScore(String date, String playerId, int score) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = DAILY_RANK_PREFIX + date;
            jedis.zadd(key, score, playerId);
        } catch (Exception e) {
            log.error("Failed to update daily rank: {}", e.getMessage());
        }
    }

    public List<RankEntry> getGlobalRank(int start, int end) {
        try (Jedis jedis = jedisPool.getResource()) {
            Set<Tuple> tuples = jedis.zrevrangeWithScores(GLOBAL_RANK_KEY, start, end);
            return convertToRankEntries(tuples, jedis, start);
        } catch (Exception e) {
            log.error("Failed to get global rank: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<RankEntry> getSeasonRank(String seasonId, int start, int end) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = SEASON_RANK_PREFIX + seasonId;
            Set<Tuple> tuples = jedis.zrevrangeWithScores(key, start, end);
            return convertToRankEntries(tuples, jedis, start);
        } catch (Exception e) {
            log.error("Failed to get season rank: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public List<RankEntry> getDailyRank(String date, int start, int end) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = DAILY_RANK_PREFIX + date;
            Set<Tuple> tuples = jedis.zrevrangeWithScores(key, start, end);
            return convertToRankEntries(tuples, jedis, start);
        } catch (Exception e) {
            log.error("Failed to get daily rank: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    public RankEntry getPlayerRank(String playerId) {
        try (Jedis jedis = jedisPool.getResource()) {
            Long rank = jedis.zrevrank(GLOBAL_RANK_KEY, playerId);
            Double score = jedis.zscore(GLOBAL_RANK_KEY, playerId);

            if (rank == null || score == null) {
                return null;
            }

            String playerDataKey = PLAYER_DATA_PREFIX + playerId;
            Map<String, String> playerData = jedis.hgetAll(playerDataKey);

            return RankEntry.builder()
                    .playerId(playerId)
                    .playerName(playerData.getOrDefault("playerName", "Unknown"))
                    .score(score.intValue())
                    .rank(rank.intValue() + 1)
                    .highestFloor(Integer.parseInt(playerData.getOrDefault("highestFloor", "0")))
                    .timestamp(Long.parseLong(playerData.getOrDefault("lastUpdate", "0")))
                    .build();
        } catch (Exception e) {
            log.error("Failed to get player rank: {}", e.getMessage());
            return null;
        }
    }

    public RankEntry getPlayerSeasonRank(String seasonId, String playerId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = SEASON_RANK_PREFIX + seasonId;
            Long rank = jedis.zrevrank(key, playerId);
            Double score = jedis.zscore(key, playerId);

            if (rank == null || score == null) {
                return null;
            }

            String playerDataKey = PLAYER_DATA_PREFIX + playerId;
            Map<String, String> playerData = jedis.hgetAll(playerDataKey);

            return RankEntry.builder()
                    .playerId(playerId)
                    .playerName(playerData.getOrDefault("playerName", "Unknown"))
                    .score(score.intValue())
                    .rank(rank.intValue() + 1)
                    .seasonId(seasonId)
                    .build();
        } catch (Exception e) {
            log.error("Failed to get player season rank: {}", e.getMessage());
            return null;
        }
    }

    public long getGlobalRankTotal() {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.zcard(GLOBAL_RANK_KEY);
        } catch (Exception e) {
            log.error("Failed to get global rank total: {}", e.getMessage());
            return 0;
        }
    }

    public int calculateScore(int floor, int totalTurns, boolean victory, int playersCount) {
        int score = 0;
        score += floor * 100;
        if (victory) {
            score += 5000;
            score += Math.max(0, 1000 - totalTurns * 5);
        }
        score += playersCount * 200;
        return score;
    }

    private List<RankEntry> convertToRankEntries(Set<Tuple> tuples, Jedis jedis, int startOffset) {
        List<RankEntry> entries = new ArrayList<>();
        int rank = startOffset + 1;

        for (Tuple tuple : tuples) {
            String playerId = tuple.getElement();
            String playerDataKey = PLAYER_DATA_PREFIX + playerId;
            Map<String, String> playerData = jedis.hgetAll(playerDataKey);

            RankEntry entry = RankEntry.builder()
                    .playerId(playerId)
                    .playerName(playerData.getOrDefault("playerName", "Unknown"))
                    .score((int) tuple.getScore())
                    .rank(rank)
                    .highestFloor(Integer.parseInt(playerData.getOrDefault("highestFloor", "0")))
                    .timestamp(Long.parseLong(playerData.getOrDefault("lastUpdate", "0")))
                    .build();

            entries.add(entry);
            rank++;
        }

        return entries;
    }

    public void resetDailyRank(String date) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = DAILY_RANK_PREFIX + date;
            jedis.del(key);
            log.info("Reset daily rank for {}", date);
        } catch (Exception e) {
            log.error("Failed to reset daily rank: {}", e.getMessage());
        }
    }

    public void resetSeasonRank(String seasonId) {
        try (Jedis jedis = jedisPool.getResource()) {
            String key = SEASON_RANK_PREFIX + seasonId;
            jedis.del(key);
            log.info("Reset season rank for {}", seasonId);
        } catch (Exception e) {
            log.error("Failed to reset season rank: {}", e.getMessage());
        }
    }
}
