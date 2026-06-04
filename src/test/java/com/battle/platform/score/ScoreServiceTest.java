package com.battle.platform.score;

import com.battle.platform.config.ScoreProperties;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ScoreService统一积分单元测试")
class ScoreServiceTest {

    private static final String BATTLE_ID = "BF-score-001";

    private ScoreService scoreService;
    private ScoreProperties scoreProperties;
    private StringRedisTemplate stringRedisTemplate;
    private ZSetOperations<String, String> zSetOperations;

    @BeforeEach
    void setUp() {
        scoreProperties = new ScoreProperties();
        scoreProperties.setKillBase(100);
        scoreProperties.setAssistBase(50);
        scoreProperties.setCaptureBase(200);
        scoreProperties.setStreakBonusThreshold(3);
        scoreProperties.setStreakMultiplier(0.5);

        stringRedisTemplate = mock(StringRedisTemplate.class);
        zSetOperations = mock(ZSetOperations.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        scoreService = new ScoreService(scoreProperties, stringRedisTemplate);
    }

    private void initPlayer(Long playerId) {
        scoreService.initPlayerScore(BATTLE_ID, playerId);
    }

    @Nested
    @DisplayName("addScore统一入口")
    class AddScoreTest {

        @Test
        @DisplayName("addScore正确增加积分")
        void addScoreCorrectlyIncreases() {
            initPlayer(1L);

            ScoreService.AddScoreResult result = scoreService.addScore(BATTLE_ID, 1L, 100, "kill", null);

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getTotalScore()).isEqualTo(100);
            assertThat(scoreService.getPlayerScore(BATTLE_ID, 1L).getTotalScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("多次addScore累加")
        void multipleAddScoreAccumulates() {
            initPlayer(1L);

            scoreService.addScore(BATTLE_ID, 1L, 100, "kill_1", null);
            scoreService.addScore(BATTLE_ID, 1L, 200, "capture", null);

            assertThat(scoreService.getPlayerScore(BATTLE_ID, 1L).getTotalScore()).isEqualTo(300);
        }

        @Test
        @DisplayName("幂等key相同不重复加积分")
        void idempotencyKeyPreventsDuplicate() {
            initPlayer(1L);

            ScoreService.AddScoreResult r1 = scoreService.addScore(BATTLE_ID, 1L, 100, "kill", "kill_1_2_1234");
            ScoreService.AddScoreResult r2 = scoreService.addScore(BATTLE_ID, 1L, 100, "kill", "kill_1_2_1234");

            assertThat(r1.isSuccess()).isTrue();
            assertThat(r2.isDuplicate()).isTrue();
            assertThat(scoreService.getPlayerScore(BATTLE_ID, 1L).getTotalScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("幂等key不同正常加积分")
        void differentIdempotencyKeysAddScore() {
            initPlayer(1L);

            scoreService.addScore(BATTLE_ID, 1L, 100, "kill", "kill_1_2_1234");
            scoreService.addScore(BATTLE_ID, 1L, 100, "kill", "kill_1_2_5678");

            assertThat(scoreService.getPlayerScore(BATTLE_ID, 1L).getTotalScore()).isEqualTo(200);
        }

        @Test
        @DisplayName("addScore更新Redis排行榜")
        void addScoreUpdatesRedis() {
            initPlayer(1L);

            scoreService.addScore(BATTLE_ID, 1L, 100, "kill", null);

            verify(zSetOperations).add("leaderboard:score", "1", 100.0);
            verify(zSetOperations).add("leaderboard:kills", "1", 0.0);
        }

        @Test
        @DisplayName("不存在的battleId返回失败")
        void nonExistentBattleReturnsFailure() {
            ScoreService.AddScoreResult result = scoreService.addScore("nonexistent", 1L, 100, "kill", null);
            assertThat(result.getErrorMessage()).isNotNull();
        }

        @Test
        @DisplayName("不存在的playerId返回失败")
        void nonExistentPlayerReturnsFailure() {
            initPlayer(1L);
            ScoreService.AddScoreResult result = scoreService.addScore(BATTLE_ID, 99L, 100, "kill", null);
            assertThat(result.getErrorMessage()).isNotNull();
        }
    }

    @Nested
    @DisplayName("并发addScore安全性")
    class ConcurrentAddScoreTest {

        @Test
        @DisplayName("并发addScore不丢积分")
        void concurrentAddScoreNoLoss() throws Exception {
            initPlayer(1L);

            int threadCount = 10;
            int scorePerThread = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < scorePerThread; j++) {
                            ScoreService.AddScoreResult r = scoreService.addScore(
                                    BATTLE_ID, 1L, 1, "concurrent_" + idx + "_" + j, null);
                            if (r.isSuccess()) successCount.incrementAndGet();
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(5, TimeUnit.SECONDS);
            executor.shutdown();

            assertThat(successCount.get()).isEqualTo(threadCount * scorePerThread);
            assertThat(scoreService.getPlayerScore(BATTLE_ID, 1L).getTotalScore())
                    .isEqualTo(threadCount * scorePerThread);
        }
    }

    @Nested
    @DisplayName("击杀积分计算")
    class KillScoreTest {

        @Test
        @DisplayName("基础击杀100分")
        void baseKillScore100() {
            initPlayer(1L);
            initPlayer(2L);

            scoreService.onKill(BATTLE_ID, 1L, 2L, 100);

            PlayerScore killerScore = scoreService.getPlayerScore(BATTLE_ID, 1L);
            assertThat(killerScore.getKills()).isEqualTo(1);
            assertThat(killerScore.getTotalScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("死亡不扣分只重置连杀")
        void deathNoScorePenalty() {
            initPlayer(1L);
            initPlayer(2L);

            scoreService.addScore(BATTLE_ID, 2L, 500, "prior", null);
            scoreService.onKill(BATTLE_ID, 1L, 2L, 100);

            PlayerScore victimScore = scoreService.getPlayerScore(BATTLE_ID, 2L);
            assertThat(victimScore.getDeaths()).isEqualTo(1);
            assertThat(victimScore.getTotalScore()).isEqualTo(500);
        }

        @Test
        @DisplayName("3连杀开始加成")
        void streakBonusStartsAt3() {
            initPlayer(1L);
            for (long i = 2L; i <= 4L; i++) initPlayer(i);

            scoreService.onKill(BATTLE_ID, 1L, 2L, 100);
            scoreService.onKill(BATTLE_ID, 1L, 3L, 100);
            scoreService.onKill(BATTLE_ID, 1L, 4L, 100);

            PlayerScore killerScore = scoreService.getPlayerScore(BATTLE_ID, 1L);
            assertThat(killerScore.getCurrentStreak()).isEqualTo(3);
            assertThat(killerScore.getTotalScore()).isGreaterThan(300);
        }
    }

    @Nested
    @DisplayName("助攻积分分配")
    class AssistScoreTest {

        @Test
        @DisplayName("助攻按伤害比例分配50分基础")
        void assistScoreByDamageRatio() {
            initPlayer(1L);
            initPlayer(2L);
            initPlayer(3L);

            scoreService.onDamage(BATTLE_ID, 2L, 3L, 60.0, 100, false);
            scoreService.onDamage(BATTLE_ID, 1L, 3L, 40.0, 100, false);

            scoreService.onKill(BATTLE_ID, 1L, 3L, 100);

            PlayerScore assisterScore = scoreService.getPlayerScore(BATTLE_ID, 2L);
            assertThat(assisterScore).isNotNull();
            assertThat(assisterScore.getAssists()).isEqualTo(1);
            assertThat(assisterScore.getTotalScore()).isGreaterThan(0);
        }
    }

    @Nested
    @DisplayName("占点积分")
    class CaptureScoreTest {

        @Test
        @DisplayName("占点200分")
        void captureScore200() {
            initPlayer(1L);

            scoreService.onCapture(BATTLE_ID, 1L, 5);

            PlayerScore score = scoreService.getPlayerScore(BATTLE_ID, 1L);
            assertThat(score.getCaptures()).isEqualTo(1);
            assertThat(score.getTotalScore()).isEqualTo(200);
        }
    }

    @Nested
    @DisplayName("finalizeBattleScores清理")
    class FinalizeTest {

        @Test
        @DisplayName("finalize后获取分数返回null")
        void finalizeRemovesBattleData() {
            initPlayer(1L);

            scoreService.finalizeBattleScores(BATTLE_ID);

            assertThat(scoreService.getPlayerScore(BATTLE_ID, 1L)).isNull();
        }
    }
}
