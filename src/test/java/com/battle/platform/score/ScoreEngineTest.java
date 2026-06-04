package com.battle.platform.score;

import com.battle.platform.config.ScoreProperties;
import com.battle.platform.netty.GameServerHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("实时积分计算引擎单元测试")
class ScoreEngineTest {

    @Mock
    private StringRedisTemplate stringRedisTemplate;
    @Mock
    private ZSetOperations<String, String> zSetOperations;
    @Mock
    private GameServerHandler gameServerHandler;

    private ScoreEngine scoreEngine;
    private ScoreProperties scoreProperties;

    private static final String BATTLE_ID = "BF-test-001";

    @BeforeEach
    void setUp() {
        scoreProperties = new ScoreProperties();
        scoreProperties.setKillBase(100);
        scoreProperties.setAssistBase(50);
        scoreProperties.setCaptureBase(200);
        scoreProperties.setStreakBonusThreshold(3);
        scoreProperties.setStreakMultiplier(0.5);

        lenient().when(stringRedisTemplate.opsForZSet()).thenReturn(zSetOperations);

        scoreEngine = new ScoreEngine(scoreProperties, stringRedisTemplate, gameServerHandler);
    }

    private void initPlayers(Long... playerIds) {
        for (Long pid : playerIds) {
            scoreEngine.initPlayerScore(BATTLE_ID, pid);
        }
    }

    @Nested
    @DisplayName("单一击杀事件积分")
    class SingleKillScoreTest {

        @Test
        @DisplayName("击杀获得基础100分")
        void killAwardsBaseScore() {
            initPlayers(1L, 2L);
            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);

            PlayerScore killerScore = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(killerScore.getKills()).isEqualTo(1);
            assertThat(killerScore.getTotalScore()).isEqualTo(100);
        }

        @Test
        @DisplayName("被击杀方死亡数+1但总分不减少")
        void victimDeathNoScoreDeduction() {
            initPlayers(1L, 2L);

            scoreEngine.onCapture(BATTLE_ID, 2L, 1);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 2L).getTotalScore()).isEqualTo(200);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);

            PlayerScore victimScore = scoreEngine.getPlayerScore(BATTLE_ID, 2L);
            assertThat(victimScore.getDeaths()).isEqualTo(1);
            assertThat(victimScore.getTotalScore()).isEqualTo(200);
            assertThat(victimScore.getCurrentStreak()).isEqualTo(0);
        }

        @Test
        @DisplayName("击杀者连杀数+1，被击杀者连杀归零")
        void killUpdatesStreak() {
            initPlayers(1L, 2L, 3L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L).getCurrentStreak()).isEqualTo(1);

            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L).getCurrentStreak()).isEqualTo(2);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L).getMaxStreak()).isEqualTo(2);
        }

        @Test
        @DisplayName("不存在的battleId不做任何处理")
        void killWithInvalidBattleIdDoesNothing() {
            scoreEngine.onKill("nonexistent", 1L, 2L, 100);
        }
    }

    @Nested
    @DisplayName("连杀加成正确叠加")
    class StreakBonusTest {

        @Test
        @DisplayName("2连杀无加成（阈值3）")
        void doubleKillNoBonus() {
            initPlayers(1L, 2L, 3L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);

            PlayerScore score = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(score.getTotalScore()).isEqualTo(200);
            assertThat(score.getCurrentStreak()).isEqualTo(2);
        }

        @Test
        @DisplayName("3连杀(Triple Kill)获得额外加成: 100 + 100*3*0.5 = 250")
        void tripleKillBonus() {
            initPlayers(1L, 2L, 3L, 4L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);

            scoreEngine.onKill(BATTLE_ID, 1L, 4L, 100);

            PlayerScore score = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            int thirdKillScore = 100 + (int) (100 * 3 * 0.5);
            assertThat(score.getTotalScore()).isEqualTo(200 + thirdKillScore);
        }

        @Test
        @DisplayName("4连杀(Quadra Kill)加成: 100 + 100*4*0.5 = 300")
        void quadraKillBonus() {
            initPlayers(1L, 2L, 3L, 4L, 5L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 4L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 5L, 100);

            PlayerScore score = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            int fourthKillScore = 100 + (int) (100 * 4 * 0.5);
            assertThat(score.getTotalScore()).isEqualTo(200 + 250 + fourthKillScore);
        }

        @Test
        @DisplayName("5连杀(Penta Kill)加成: 100 + 100*5*0.5 = 350")
        void pentaKillBonus() {
            initPlayers(1L, 2L, 3L, 4L, 5L, 6L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 4L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 5L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 6L, 100);

            PlayerScore score = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            int fifthKillScore = 100 + (int) (100 * 5 * 0.5);
            assertThat(score.getTotalScore()).isEqualTo(200 + 250 + 300 + fifthKillScore);
        }

        @Test
        @DisplayName("连杀中断后重新计数")
        void streakResetsAfterDeath() {
            initPlayers(1L, 2L, 3L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);
            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L).getCurrentStreak()).isEqualTo(2);

            scoreEngine.onKill(BATTLE_ID, 3L, 1L, 100);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L).getCurrentStreak()).isEqualTo(0);

            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);
            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L).getCurrentStreak()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("多人助攻积分按伤害比例分配")
    class AssistDistributionTest {

        @Test
        @DisplayName("两个助攻者按伤害比例分配助攻积分")
        void twoAssistersProportionalDistribution() {
            initPlayers(1L, 2L, 3L, 4L);

            scoreEngine.onDamage(BATTLE_ID, 2L, 4L, 300, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 3L, 4L, 700, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 1L, 4L, 500, 100, false);

            scoreEngine.onKill(BATTLE_ID, 1L, 4L, 100);

            PlayerScore assister2 = scoreEngine.getPlayerScore(BATTLE_ID, 2L);
            PlayerScore assister3 = scoreEngine.getPlayerScore(BATTLE_ID, 3L);

            assertThat(assister2.getAssists()).isEqualTo(1);
            assertThat(assister3.getAssists()).isEqualTo(1);

            int totalAssistScore = assister2.getTotalScore() + assister3.getTotalScore();
            assertThat(totalAssistScore).isLessThanOrEqualTo(50);

            double ratio2 = 300.0 / 1500.0;
            double ratio3 = 700.0 / 1500.0;
            int expected2 = (int) (50 * ratio2);
            int expected3 = (int) (50 * ratio3);
            assertThat(assister2.getTotalScore()).isEqualTo(expected2);
            assertThat(assister3.getTotalScore()).isEqualTo(expected3);
        }

        @Test
        @DisplayName("无助攻伤害时击杀者独享积分")
        void noAssistKillerGetsFullScore() {
            initPlayers(1L, 2L);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);

            PlayerScore killer = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(killer.getTotalScore()).isEqualTo(100);
            assertThat(killer.getAssists()).isEqualTo(0);
        }

        @Test
        @DisplayName("助攻积分总和不超过助攻基础分50")
        void assistScoreSumNotExceedBase() {
            initPlayers(1L, 2L, 3L, 4L, 5L);

            scoreEngine.onDamage(BATTLE_ID, 2L, 5L, 100, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 3L, 5L, 200, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 4L, 5L, 300, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 1L, 5L, 400, 100, false);

            scoreEngine.onKill(BATTLE_ID, 1L, 5L, 100);

            PlayerScore a2 = scoreEngine.getPlayerScore(BATTLE_ID, 2L);
            PlayerScore a3 = scoreEngine.getPlayerScore(BATTLE_ID, 3L);
            PlayerScore a4 = scoreEngine.getPlayerScore(BATTLE_ID, 4L);

            int totalAssist = a2.getTotalScore() + a3.getTotalScore() + a4.getTotalScore();
            assertThat(totalAssist).isLessThanOrEqualTo(50);
        }

        @Test
        @DisplayName("伤害贡献为0的助攻者得0分")
        void zeroDamageAssistGetsZeroScore() {
            initPlayers(1L, 2L, 3L);

            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);

            PlayerScore noDamage = scoreEngine.getPlayerScore(BATTLE_ID, 2L);
            assertThat(noDamage.getAssists()).isEqualTo(0);
            assertThat(noDamage.getTotalScore()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("占点积分")
    class CaptureScoreTest {

        @Test
        @DisplayName("占点获得200分")
        void captureAwards200Score() {
            initPlayers(1L);

            scoreEngine.onCapture(BATTLE_ID, 1L, 1);

            PlayerScore score = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(score.getCaptures()).isEqualTo(1);
            assertThat(score.getTotalScore()).isEqualTo(200);
        }

        @Test
        @DisplayName("多次占点积分叠加")
        void multipleCapturesStack() {
            initPlayers(1L);

            scoreEngine.onCapture(BATTLE_ID, 1L, 1);
            scoreEngine.onCapture(BATTLE_ID, 1L, 2);
            scoreEngine.onCapture(BATTLE_ID, 1L, 3);

            PlayerScore score = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(score.getCaptures()).isEqualTo(3);
            assertThat(score.getTotalScore()).isEqualTo(600);
        }
    }

    @Nested
    @DisplayName("综合积分场景")
    class CompositeScoreTest {

        @Test
        @DisplayName("击杀+助攻+占点+连杀混合计算")
        void mixedScoreCalculation() {
            initPlayers(1L, 2L, 3L, 4L, 5L);

            scoreEngine.onDamage(BATTLE_ID, 2L, 4L, 500, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 3L, 4L, 500, 100, false);
            scoreEngine.onKill(BATTLE_ID, 1L, 4L, 100);

            scoreEngine.onCapture(BATTLE_ID, 1L, 1);

            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);

            scoreEngine.onKill(BATTLE_ID, 1L, 3L, 100);

            PlayerScore p1 = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(p1.getKills()).isEqualTo(3);
            assertThat(p1.getCaptures()).isEqualTo(1);
            assertThat(p1.getCurrentStreak()).isEqualTo(3);

            int kill1 = 100;
            int kill2 = 100;
            int kill3 = 100 + (int) (100 * 3 * 0.5);
            int capture = 200;
            assertThat(p1.getTotalScore()).isEqualTo(kill1 + kill2 + kill3 + capture);
        }
    }

    @Nested
    @DisplayName("战斗结束积分清理")
    class FinalizeScoreTest {

        @Test
        @DisplayName("战斗结束后清理所有分数数据")
        void finalizeClearsBattleData() {
            initPlayers(1L, 2L);
            scoreEngine.onKill(BATTLE_ID, 1L, 2L, 100);

            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L)).isNotNull();

            scoreEngine.finalizeBattleScores(BATTLE_ID);

            assertThat(scoreEngine.getPlayerScore(BATTLE_ID, 1L)).isNull();
        }
    }

    @Nested
    @DisplayName("伤害记录")
    class DamageRecordTest {

        @Test
        @DisplayName("伤害记录正确累加")
        void damageAccumulates() {
            initPlayers(1L, 2L);

            scoreEngine.onDamage(BATTLE_ID, 1L, 2L, 100, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 1L, 2L, 200, 101, false);

            PlayerScore attacker = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(attacker.getDamageDealt()).isCloseTo(300.0, within(0.01));

            PlayerScore victim = scoreEngine.getPlayerScore(BATTLE_ID, 2L);
            assertThat(victim.getDamageTaken()).isCloseTo(300.0, within(0.01));
        }

        @Test
        @DisplayName("爆头计数正确")
        void headshotCount() {
            initPlayers(1L, 2L);

            scoreEngine.onDamage(BATTLE_ID, 1L, 2L, 100, 100, true);
            scoreEngine.onDamage(BATTLE_ID, 1L, 2L, 100, 100, false);
            scoreEngine.onDamage(BATTLE_ID, 1L, 2L, 100, 100, true);

            PlayerScore attacker = scoreEngine.getPlayerScore(BATTLE_ID, 1L);
            assertThat(attacker.getHeadshots()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("并发积分计算")
    class ConcurrentScoreTest {

        @Test
        @DisplayName("多线程同时击杀不同目标积分正确")
        void concurrentKillsDifferentTargets() throws InterruptedException {
            int playerCount = 10;
            for (long i = 1; i <= playerCount; i++) {
                scoreEngine.initPlayerScore(BATTLE_ID, i);
            }

            ExecutorService executor = Executors.newFixedThreadPool(5);
            CountDownLatch latch = new CountDownLatch(5);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < 5; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        long killer = idx * 2 + 1;
                        long victim = idx * 2 + 2;
                        scoreEngine.onKill(BATTLE_ID, killer, victim, 100);
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(5, TimeUnit.SECONDS);
            assertThat(completed).isTrue();
            assertThat(errors.get()).isEqualTo(0);

            for (int i = 0; i < 5; i++) {
                long killer = i * 2 + 1;
                PlayerScore ks = scoreEngine.getPlayerScore(BATTLE_ID, killer);
                assertThat(ks.getKills()).isEqualTo(1);
                assertThat(ks.getTotalScore()).isEqualTo(100);
            }

            executor.shutdown();
        }
    }
}
