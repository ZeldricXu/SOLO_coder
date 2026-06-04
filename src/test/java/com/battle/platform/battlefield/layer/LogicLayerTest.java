package com.battle.platform.battlefield.layer;

import com.battle.platform.battlefield.event.*;
import com.battle.platform.config.BattlefieldProperties;
import com.battle.platform.config.ScoreProperties;
import com.battle.platform.score.PlayerScore;
import com.battle.platform.score.ScoreService;
import com.battle.platform.replay.ReplayRecorder;
import com.google.common.eventbus.Subscribe;
import io.netty.channel.Channel;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LogicLayer单元测试")
class LogicLayerTest {

    private static final String BATTLE_ID = "BF-logic-001";

    private BattlefieldEventBus eventBus;
    private ScoreService scoreService;
    private ConnectionLayer connectionLayer;
    private LogicLayer logicLayer;
    private ReplayRecorder replayRecorder;

    @BeforeEach
    void setUp() {
        eventBus = new BattlefieldEventBus();

        ScoreProperties scoreProperties = new ScoreProperties();
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        when(stringRedisTemplate.opsForZSet()).thenReturn(mock(org.springframework.data.redis.core.ZSetOperations.class));
        scoreService = new ScoreService(scoreProperties, stringRedisTemplate);

        BattlefieldProperties properties = new BattlefieldProperties();
        connectionLayer = new ConnectionLayer(BATTLE_ID, eventBus, properties);

        replayRecorder = mock(ReplayRecorder.class);
        logicLayer = new LogicLayer(BATTLE_ID, eventBus, scoreService, connectionLayer, replayRecorder);

        eventBus.register(connectionLayer);
        eventBus.register(logicLayer);
    }

    private void connectPlayer(Long playerId) {
        Channel ch = mock(Channel.class);
        when(ch.isActive()).thenReturn(true);
        connectionLayer.onPlayerConnect(playerId, ch);
    }

    @Nested
    @DisplayName("击杀与死亡")
    class KillDeathTest {

        @Test
        @DisplayName("击杀后死者不再存活")
        void victimNotAliveAfterDeath() {
            connectPlayer(1L);
            connectPlayer(2L);

            logicLayer.onDeath(1L, 2L, 100);

            assertThat(logicLayer.isAlive(2L)).isFalse();
        }

        @Test
        @DisplayName("击杀后击杀者仍存活")
        void killerStillAliveAfterDeath() {
            connectPlayer(1L);
            connectPlayer(2L);

            logicLayer.onDeath(1L, 2L, 100);

            assertThat(logicLayer.isAlive(1L)).isTrue();
        }

        @Test
        @DisplayName("击杀触发积分计算")
        void killTriggersScoreCalculation() {
            connectPlayer(1L);
            connectPlayer(2L);

            logicLayer.onDeath(1L, 2L, 100);

            PlayerScore killerScore = scoreService.getPlayerScore(BATTLE_ID, 1L);
            PlayerScore victimScore = scoreService.getPlayerScore(BATTLE_ID, 2L);

            assertThat(killerScore).isNotNull();
            assertThat(killerScore.getKills()).isEqualTo(1);
            assertThat(killerScore.getTotalScore()).isGreaterThan(0);

            assertThat(victimScore).isNotNull();
            assertThat(victimScore.getDeaths()).isEqualTo(1);
        }

        @Test
        @DisplayName("击杀事件触发PlayerDeathEvent")
        void killTriggersDeathEvent() throws InterruptedException {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<PlayerDeathEvent> captured = new java.util.concurrent.atomic.AtomicReference<>();

            Object listener = new Object() {
                @Subscribe
                public void onEvent(PlayerDeathEvent event) {
                    captured.set(event);
                    latch.countDown();
                }
            };
            eventBus.register(listener);

            connectPlayer(1L);
            connectPlayer(2L);
            logicLayer.onDeath(1L, 2L, 100);

            assertThat(latch.await(1, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(captured.get().getKillerId()).isEqualTo(1L);
            assertThat(captured.get().getVictimId()).isEqualTo(2L);

            eventBus.unregister(listener);
        }

        @Test
        @DisplayName("击杀录制到回放")
        void killRecordedForReplay() {
            connectPlayer(1L);
            connectPlayer(2L);

            logicLayer.onDeath(1L, 2L, 100);

            verify(replayRecorder).recordDeathEvent(BATTLE_ID, 1L, 2L, 100);
        }
    }

    @Nested
    @DisplayName("占点积分")
    class CaptureTest {

        @Test
        @DisplayName("占点触发积分计算")
        void captureTriggersScore() {
            connectPlayer(1L);

            logicLayer.onCapture(1L, 5);

            PlayerScore score = scoreService.getPlayerScore(BATTLE_ID, 1L);
            assertThat(score).isNotNull();
            assertThat(score.getCaptures()).isEqualTo(1);
            assertThat(score.getTotalScore()).isGreaterThan(0);
        }

        @Test
        @DisplayName("占点录制到回放")
        void captureRecordedForReplay() {
            connectPlayer(1L);

            logicLayer.onCapture(1L, 5);

            verify(replayRecorder).recordCaptureEvent(BATTLE_ID, 1L, 5);
        }
    }

    @Nested
    @DisplayName("技能释放")
    class SkillCastTest {

        @Test
        @DisplayName("存活玩家可以释放技能")
        void alivePlayerCanCastSkill() {
            connectPlayer(1L);

            logicLayer.onSkillCast(1L, 1001, 10.0, 20.0);

            verify(replayRecorder).recordSkillEvent(BATTLE_ID, 1L, 1001, 10.0, 20.0);
        }

        @Test
        @DisplayName("死亡玩家不能释放技能")
        void deadPlayerCannotCastSkill() {
            connectPlayer(1L);
            connectPlayer(2L);

            logicLayer.onDeath(2L, 1L, 100);
            logicLayer.onSkillCast(1L, 1001, 10.0, 20.0);
        }
    }

    @Nested
    @DisplayName("战场结束")
    class EndBattleTest {

        @Test
        @DisplayName("结束战场后active为false")
        void battleNotActiveAfterEnd() {
            logicLayer.endBattle();
            assertThat(logicLayer.isActive()).isFalse();
        }

        @Test
        @DisplayName("结束战场刷新回放")
        void endBattleFlushesReplay() {
            logicLayer.endBattle();
            verify(replayRecorder).flushBattleReplay(BATTLE_ID);
        }
    }

    @Nested
    @DisplayName("玩家连接事件")
    class ConnectEventTest {

        @Test
        @DisplayName("玩家连接后初始化积分")
        void playerScoreInitializedOnConnect() {
            connectPlayer(1L);

            PlayerScore score = scoreService.getPlayerScore(BATTLE_ID, 1L);
            assertThat(score).isNotNull();
            assertThat(score.getTotalScore()).isEqualTo(0);
            assertThat(score.getKills()).isEqualTo(0);
        }
    }
}
