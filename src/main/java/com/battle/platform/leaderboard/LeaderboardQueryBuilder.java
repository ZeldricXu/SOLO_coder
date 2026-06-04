package com.battle.platform.leaderboard;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.*;

@Slf4j
public class LeaderboardQueryBuilder {

    public enum Dimension {
        SCORE("leaderboard:score"),
        KILLS("leaderboard:kills"),
        GUILD("leaderboard:guild");

        private final String redisKey;

        Dimension(String redisKey) {
            this.redisKey = redisKey;
        }

        public String getRedisKey() {
            return redisKey;
        }
    }

    public enum Scope {
        ALL,
        SERVER,
        FRIENDS
    }

    private final StringRedisTemplate stringRedisTemplate;

    private Dimension dimension = Dimension.SCORE;
    private Scope scope = Scope.ALL;
    private int topN = 100;
    private Integer serverId;
    private Set<Long> friendIds;

    private LeaderboardQueryBuilder(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public static LeaderboardQueryBuilder create(StringRedisTemplate stringRedisTemplate) {
        return new LeaderboardQueryBuilder(stringRedisTemplate);
    }

    public LeaderboardQueryBuilder dimension(Dimension dimension) {
        this.dimension = dimension;
        return this;
    }

    public LeaderboardQueryBuilder scope(Scope scope) {
        this.scope = scope;
        return this;
    }

    public LeaderboardQueryBuilder topN(int topN) {
        this.topN = topN;
        return this;
    }

    public LeaderboardQueryBuilder serverId(Integer serverId) {
        this.serverId = serverId;
        return this;
    }

    public LeaderboardQueryBuilder friendIds(Set<Long> friendIds) {
        this.friendIds = friendIds;
        return this;
    }

    public List<Map<String, Object>> execute() {
        String redisKey = dimension.getRedisKey();

        switch (scope) {
            case ALL:
                return queryAll(redisKey);
            case SERVER:
                return queryByServer(redisKey);
            case FRIENDS:
                return queryByFriends(redisKey);
            default:
                return queryAll(redisKey);
        }
    }

    public Long getPlayerRank(Long playerId) {
        Long rank = stringRedisTemplate.opsForZSet().reverseRank(dimension.getRedisKey(), playerId.toString());
        return rank != null ? rank + 1 : null;
    }

    public Double getPlayerScore(Long playerId) {
        return stringRedisTemplate.opsForZSet().score(dimension.getRedisKey(), playerId.toString());
    }

    private List<Map<String, Object>> queryAll(String redisKey) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(redisKey, 0, topN - 1);
        return buildResult(tuples);
    }

    private List<Map<String, Object>> queryByServer(String redisKey) {
        Set<ZSetOperations.TypedTuple<String>> allTuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(redisKey, 0, -1);

        if (allTuples == null || serverId == null) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        long rank = 1;
        for (ZSetOperations.TypedTuple<String> tuple : allTuples) {
            if (tuple.getValue() != null && rank <= topN) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("rank", rank);
                entry.put("id", tuple.getValue());
                entry.put("score", tuple.getScore());
                entry.put("serverId", serverId);
                result.add(entry);
                rank++;
            }
        }
        return result;
    }

    private List<Map<String, Object>> queryByFriends(String redisKey) {
        if (friendIds == null || friendIds.isEmpty()) return Collections.emptyList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Long friendId : friendIds) {
            Double score = stringRedisTemplate.opsForZSet().score(redisKey, friendId.toString());
            Long rank = stringRedisTemplate.opsForZSet().reverseRank(redisKey, friendId.toString());

            if (score != null) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("rank", rank != null ? rank + 1 : null);
                entry.put("id", friendId.toString());
                entry.put("score", score);
                result.add(entry);
            }
        }

        result.sort((a, b) -> {
            Double sa = (Double) a.get("score");
            Double sb = (Double) b.get("score");
            return sb.compareTo(sa);
        });

        for (int i = 0; i < result.size(); i++) {
            result.get(i).put("rank", i + 1);
        }

        return result.subList(0, Math.min(topN, result.size()));
    }

    private List<Map<String, Object>> buildResult(Set<ZSetOperations.TypedTuple<String>> tuples) {
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
}
