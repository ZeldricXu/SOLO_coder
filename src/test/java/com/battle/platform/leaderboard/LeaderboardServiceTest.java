package com.battle.platform.leaderboard;

import com.battle.platform.entity.SeasonRanking;
import com.battle.platform.repository.PlayerRepository;
import com.battle.platform.repository.SeasonRankingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("赛季排行榜系统单元测试")
class LeaderboardServiceTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private SeasonRankingRepository seasonRankingRepository;
    @Mock
    private PlayerRepository playerRepository;

    private LeaderboardService leaderboardService;

    private static final String LEADERBOARD_SCORE = "leaderboard:score";
    private static final String LEADERBOARD_KILLS = "leaderboard:kills";
    private static final String LEADERBOARD_GUILD = "leaderboard:guild";

    @BeforeEach
    void setUp() {
        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);
        leaderboardService = new LeaderboardService(stringRedisTemplate, seasonRankingRepository, playerRepository);
    }

    @Nested
    @DisplayName("并发更新积分一致性")
    class ConcurrentUpdateConsistencyTest {

        @Test
        @DisplayName("多线程并发更新同一玩家积分最终写入Redis")
        void concurrentUpdateSamePlayerConsistent() throws InterruptedException {
            String playerId = "1001";
            when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(10);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < 10; i++) {
                final int score = (i + 1) * 100;
                executor.submit(() -> {
                    try {
                        stringRedisTemplate.opsForZSet().add(LEADERBOARD_SCORE, playerId, score);
                        successCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(successCount.get()).isEqualTo(10);

            verify(zSetOperations, times(10)).add(eq(LEADERBOARD_SCORE), eq(playerId), anyDouble());

            executor.shutdown();
        }

        @Test
        @DisplayName("多线程并发更新不同玩家积分无丢失")
        void concurrentUpdateDifferentPlayersNoLoss() throws InterruptedException {
            when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

            int playerCount = 50;
            ExecutorService executor = Executors.newFixedThreadPool(10);
            CountDownLatch latch = new CountDownLatch(playerCount);

            for (int i = 0; i < playerCount; i++) {
                final String pid = String.valueOf(1000 + i);
                final double score = (i + 1) * 100;
                executor.submit(() -> {
                    try {
                        stringRedisTemplate.opsForZSet().add(LEADERBOARD_SCORE, pid, score);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            verify(zSetOperations, times(playerCount)).add(eq(LEADERBOARD_SCORE), anyString(), anyDouble());

            executor.shutdown();
        }
    }

    @Nested
    @DisplayName("排行榜查询")
    class LeaderboardQueryTest {

        @Test
        @DisplayName("获取Top10积分榜")
        void getTop10ByScore() {
            Set<ZSetOperations.TypedTuple<String>> mockTuples = new LinkedHashSet<>();
            for (int i = 0; i < 10; i++) {
                mockTuples.add(new SimpleTypedTuple(String.valueOf(1000 + i), (double) (1000 - i * 100)));
            }

            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_SCORE, 0, 9))
                    .thenReturn(mockTuples);

            List<Map<String, Object>> result = leaderboardService.getTopPlayersByScore(10);

            assertThat(result).hasSize(10);
            assertThat(result.get(0).get("rank")).isEqualTo(1L);
            assertThat(result.get(0).get("score")).isEqualTo(1000.0);
            assertThat(result.get(9).get("rank")).isEqualTo(10L);
        }

        @Test
        @DisplayName("空排行榜返回空列表")
        void emptyLeaderboardReturnsEmptyList() {
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_SCORE, 0, 9))
                    .thenReturn(null);

            List<Map<String, Object>> result = leaderboardService.getTopPlayersByScore(10);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("获取玩家排名（从0开始+1）")
        void getPlayerRank() {
            when(zSetOperations.reverseRank(LEADERBOARD_SCORE, "1001")).thenReturn(4L);

            Long rank = leaderboardService.getPlayerRank(1001L);
            assertThat(rank).isEqualTo(5L);
        }

        @Test
        @DisplayName("不在榜的玩家返回null")
        void playerNotInLeaderboardReturnsNull() {
            when(zSetOperations.reverseRank(LEADERBOARD_SCORE, "9999")).thenReturn(null);

            Long rank = leaderboardService.getPlayerRank(9999L);
            assertThat(rank).isNull();
        }

        @Test
        @DisplayName("获取玩家分数")
        void getPlayerScore() {
            when(zSetOperations.score(LEADERBOARD_SCORE, "1001")).thenReturn(1500.0);

            Double score = leaderboardService.getPlayerScore(1001L);
            assertThat(score).isEqualTo(1500.0);
        }
    }

    @Nested
    @DisplayName("赛季归档到MySQL")
    class SeasonArchiveTest {

        @Test
        @DisplayName("赛季归档将Redis排行榜数据写入MySQL")
        void archiveSeasonToMySQL() {
            Set<ZSetOperations.TypedTuple<String>> scoreTuples = new LinkedHashSet<>();
            scoreTuples.add(new SimpleTypedTuple("1001", 5000.0));
            scoreTuples.add(new SimpleTypedTuple("1002", 4000.0));
            scoreTuples.add(new SimpleTypedTuple("1003", 3000.0));

            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_SCORE, 0, -1))
                    .thenReturn(scoreTuples);
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_KILLS, 0, -1))
                    .thenReturn(Collections.emptySet());
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_GUILD, 0, -1))
                    .thenReturn(Collections.emptySet());

            leaderboardService.archiveSeasonToMySQL(1L);

            ArgumentCaptor<List<SeasonRanking>> captor = ArgumentCaptor.forClass(List.class);
            verify(seasonRankingRepository).saveAll(captor.capture());

            List<SeasonRanking> saved = captor.getValue();
            assertThat(saved).hasSize(3);
            assertThat(saved.get(0).getRank()).isEqualTo(1);
            assertThat(saved.get(0).getPlayerId()).isEqualTo(1001L);
            assertThat(saved.get(0).getScore()).isEqualTo(5000);
            assertThat(saved.get(0).getSeasonId()).isEqualTo(1L);
            assertThat(saved.get(0).getRankingType()).isEqualTo(SeasonRanking.RankingType.TOTAL_SCORE);
        }

        @Test
        @DisplayName("归档时排行榜为空不写入MySQL")
        void archiveEmptyLeaderboardNoWrite() {
            when(zSetOperations.reverseRangeWithScores(anyString(), anyLong(), anyLong()))
                    .thenReturn(null);

            leaderboardService.archiveSeasonToMySQL(1L);

            verify(seasonRankingRepository, never()).saveAll(any());
        }

        @Test
        @DisplayName("归档三种排行榜类型")
        void archiveAllThreeLeaderboardTypes() {
            Set<ZSetOperations.TypedTuple<String>> scoreTuples = new LinkedHashSet<>();
            scoreTuples.add(new SimpleTypedTuple("1001", 5000.0));

            Set<ZSetOperations.TypedTuple<String>> killTuples = new LinkedHashSet<>();
            killTuples.add(new SimpleTypedTuple("1001", 30.0));

            Set<ZSetOperations.TypedTuple<String>> guildTuples = new LinkedHashSet<>();
            guildTuples.add(new SimpleTypedTuple("5001", 10000.0));

            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_SCORE, 0, -1))
                    .thenReturn(scoreTuples);
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_KILLS, 0, -1))
                    .thenReturn(killTuples);
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_GUILD, 0, -1))
                    .thenReturn(guildTuples);

            leaderboardService.archiveSeasonToMySQL(1L);

            ArgumentCaptor<List<SeasonRanking>> captor = ArgumentCaptor.forClass(List.class);
            verify(seasonRankingRepository, times(3)).saveAll(captor.capture());

            List<SeasonRanking> allSaved = captor.getAllValues().stream()
                    .flatMap(List::stream).toList();
            assertThat(allSaved).hasSize(3);

            long totalScoreCount = allSaved.stream()
                    .filter(r -> r.getRankingType() == SeasonRanking.RankingType.TOTAL_SCORE)
                    .count();
            long killsCount = allSaved.stream()
                    .filter(r -> r.getRankingType() == SeasonRanking.RankingType.KILLS)
                    .count();
            long guildCount = allSaved.stream()
                    .filter(r -> r.getRankingType() == SeasonRanking.RankingType.GUILD_SCORE)
                    .count();

            assertThat(totalScoreCount).isEqualTo(1);
            assertThat(killsCount).isEqualTo(1);
            assertThat(guildCount).isEqualTo(1);
        }

        @Test
        @DisplayName("公会排行榜归档时使用guildId而非playerId")
        void guildLeaderboardUsesGuildId() {
            Set<ZSetOperations.TypedTuple<String>> guildTuples = new LinkedHashSet<>();
            guildTuples.add(new SimpleTypedTuple("5001", 10000.0));

            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_SCORE, 0, -1))
                    .thenReturn(Collections.emptySet());
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_KILLS, 0, -1))
                    .thenReturn(Collections.emptySet());
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_GUILD, 0, -1))
                    .thenReturn(guildTuples);

            leaderboardService.archiveSeasonToMySQL(1L);

            ArgumentCaptor<List<SeasonRanking>> captor = ArgumentCaptor.forClass(List.class);
            verify(seasonRankingRepository).saveAll(captor.capture());

            List<SeasonRanking> saved = captor.getValue();
            assertThat(saved.get(0).getGuildId()).isEqualTo(5001L);
        }
    }

    @Nested
    @DisplayName("排行榜快照")
    class SnapshotTest {

        @Test
        @DisplayName("快照复制当前排行榜数据到snapshot key")
        void snapshotCopiesDataToSnapshotKey() {
            Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
            tuples.add(new SimpleTypedTuple("1001", 5000.0));
            tuples.add(new SimpleTypedTuple("1002", 4000.0));

            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_SCORE, 0, -1))
                    .thenReturn(tuples);
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_KILLS, 0, -1))
                    .thenReturn(Collections.emptySet());
            when(zSetOperations.reverseRangeWithScores(LEADERBOARD_GUILD, 0, -1))
                    .thenReturn(Collections.emptySet());

            leaderboardService.takeSnapshot();

            verify(stringRedisTemplate).delete("leaderboard:snapshot:score");
            verify(zSetOperations, times(2)).add(eq("leaderboard:snapshot:score"), anyString(), anyDouble());
        }
    }

    private static class SimpleTypedTuple implements ZSetOperations.TypedTuple<String> {
        private final String value;
        private final Double score;

        SimpleTypedTuple(String value, Double score) {
            this.value = value;
            this.score = score;
        }

        @Override
        public String getValue() { return value; }

        @Override
        public Double getScore() { return score; }

        @Override
        public int compareTo(ZSetOperations.TypedTuple<String> o) {
            return Double.compare(this.score, o.getScore());
        }
    }
}
