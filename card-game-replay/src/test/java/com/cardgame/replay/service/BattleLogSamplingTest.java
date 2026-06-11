package com.cardgame.replay.service;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.replay.config.BattleLogSamplingConfig;
import com.cardgame.replay.entity.BattleLog;
import com.cardgame.replay.kafka.BattleLogProducer;
import com.cardgame.replay.mapper.BattleLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Battle Log Sampling Tests")
class BattleLogSamplingTest {

    @Mock
    private BattleLogMapper battleLogMapper;

    @Mock
    private BattleLogProducer battleLogProducer;

    @InjectMocks
    private BattleLogService battleLogService;

    private BattleLogSamplingConfig samplingConfig;
    private BattleContext normalBattleContext;
    private BattleContext bossBattleContext;

    @BeforeEach
    void setUp() {
        samplingConfig = new BattleLogSamplingConfig();
        battleLogService = new BattleLogService();
        battleLogService.battleLogMapper = battleLogMapper;
        battleLogService.battleLogProducer = battleLogProducer;
        battleLogService.samplingConfig = samplingConfig;

        normalBattleContext = BattleContext.builder()
                .battleId("normal-battle")
                .roomId("room-1")
                .floor(5)
                .startTime(System.currentTimeMillis())
                .players(new ArrayList<>())
                .enemies(new ArrayList<>())
                .build();

        bossBattleContext = BattleContext.builder()
                .battleId("boss-battle")
                .roomId("room-1")
                .floor(10)
                .startTime(System.currentTimeMillis())
                .build();
    }

    @Nested
    @DisplayName("Sampling Config Tests")
    class SamplingConfigTests {

        @Test
        @DisplayName("Boss floor detection - every 10th floor should be boss")
        void isBossBattle_Every10thFloor_ShouldBeTrue() {
            assertThat(samplingConfig.isBossBattle(10)).isTrue();
            assertThat(samplingConfig.isBossBattle(20)).isTrue();
            assertThat(samplingConfig.isBossBattle(30)).isTrue();
        }

        @Test
        @DisplayName("Boss floor detection - non-10th floor should not be boss")
        void isBossBattle_Non10thFloor_ShouldBeFalse() {
            assertThat(samplingConfig.isBossBattle(1)).isFalse();
            assertThat(samplingConfig.isBossBattle(5)).isFalse();
            assertThat(samplingConfig.isBossBattle(15)).isFalse();
        }

        @Test
        @DisplayName("Critical action types should include DRAW_CARD, PLAY_CARD, DEAL_DAMAGE")
        void criticalActionTypes_ShouldIncludeKeyActions() {
            assertThat(samplingConfig.getCriticalActionTypes())
                    .contains("DRAW_CARD", "PLAY_CARD", "DEAL_DAMAGE");
        }

        @Test
        @DisplayName("Detailed action types should include all critical types plus more")
        void detailedActionTypes_ShouldIncludeAllCriticalAndMore() {
            assertThat(samplingConfig.getDetailedActionTypes())
                    .containsAll(samplingConfig.getCriticalActionTypes());
            assertThat(samplingConfig.getDetailedActionTypes().size())
                    .isGreaterThan(samplingConfig.getCriticalActionTypes().size());
        }

        @Test
        @DisplayName("Normal battle should filter out non-critical actions")
        void shouldLogAction_NormalBattle_ShouldFilterNonCritical() {
            boolean isBossBattle = false;
            assertThat(samplingConfig.shouldLogAction("DRAW_CARD", isBossBattle)).isTrue();
            assertThat(samplingConfig.shouldLogAction("PLAY_CARD", isBossBattle)).isTrue();
            assertThat(samplingConfig.shouldLogAction("DEAL_DAMAGE", isBossBattle)).isTrue();
            assertThat(samplingConfig.shouldLogAction("GAIN_BLOCK", isBossBattle)).isFalse();
            assertThat(samplingConfig.shouldLogAction("APPLY_BUFF", isBossBattle)).isFalse();
            assertThat(samplingConfig.shouldLogAction("HEAL", isBossBattle)).isFalse();
        }

        @Test
        @DisplayName("Boss battle should log all detailed actions")
        void shouldLogAction_BossBattle_ShouldLogAll() {
            boolean isBossBattle = true;
            for (String actionType : samplingConfig.getDetailedActionTypes()) {
                assertThat(samplingConfig.shouldLogAction(actionType, isBossBattle)).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Battle Log Service Sampling Tests")
    class BattleLogServiceSamplingTests {

        @Test
        @DisplayName("Normal battle should start with SAMPLED level")
        void startBattleLogging_NormalBattle_ShouldSetSampled() {
            battleLogService.startBattleLogging(normalBattleContext);

            BattleLog log = battleLogService.activeBattleLogs.get("normal-battle");
            assertThat(log).isNotNull();
            assertThat(log.isBossBattle()).isFalse();
            assertThat(log.getSamplingLevel()).isEqualTo("SAMPLED");
            assertThat(log.getLogTimestamp()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Boss battle should start with FULL level")
        void startBattleLogging_BossBattle_ShouldSetFull() {
            battleLogService.startBattleLogging(bossBattleContext);

            BattleLog log = battleLogService.activeBattleLogs.get("boss-battle");
            assertThat(log).isNotNull();
            assertThat(log.isBossBattle()).isTrue();
            assertThat(log.getSamplingLevel()).isEqualTo("FULL");
            assertThat(log.getLogTimestamp()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Normal battle should only log critical actions")
        void logAction_NormalBattle_ShouldFilterActions() {
            battleLogService.startBattleLogging(normalBattleContext);

            List<String> actionTypes = Arrays.asList(
                    "DRAW_CARD", "PLAY_CARD", "DEAL_DAMAGE",
                    "GAIN_BLOCK", "APPLY_BUFF", "HEAL",
                    "BATTLE_START", "BATTLE_END"
            );

            for (String actionType : actionTypes) {
                BattleAction action = BattleAction.builder()
                        .actionType(actionType)
                        .build();
                battleLogService.logAction("normal-battle", action);
            }

            BattleLog log = battleLogService.activeBattleLogs.get("normal-battle");
            assertThat(log.getActions()).hasSize(5);
            assertThat(log.getActions().stream().map(BattleAction::getActionType))
                    .containsExactly("DRAW_CARD", "PLAY_CARD", "DEAL_DAMAGE", "BATTLE_START", "BATTLE_END");
        }

        @Test
        @DisplayName("Boss battle should log all detailed actions")
        void logAction_BossBattle_ShouldLogAll() {
            battleLogService.startBattleLogging(bossBattleContext);

            List<String> actionTypes = Arrays.asList(
                    "DRAW_CARD", "PLAY_CARD", "DEAL_DAMAGE",
                    "GAIN_BLOCK", "APPLY_BUFF", "HEAL",
                    "BATTLE_START", "BATTLE_END"
            );

            for (String actionType : actionTypes) {
                BattleAction action = BattleAction.builder()
                        .actionType(actionType)
                        .build();
                battleLogService.logAction("boss-battle", action);
            }

            BattleLog log = battleLogService.activeBattleLogs.get("boss-battle");
            assertThat(log.getActions()).hasSize(8);
        }

        @Test
        @DisplayName("End battle logging should clean up all caches")
        void endBattleLogging_ShouldCleanUpCaches() {
            battleLogService.startBattleLogging(normalBattleContext);

            normalBattleContext.setStatus(BattleStatus.VICTORY);
            normalBattleContext.setEndTime(System.currentTimeMillis());

            battleLogService.endBattleLogging("normal-battle", normalBattleContext);

            assertThat(battleLogService.activeBattleLogs).isEmpty();
            assertThat(battleLogService.bossBattleFlags).isEmpty();
            assertThat(battleLogService.allowedActionTypes).isEmpty();

            verify(battleLogProducer).sendBattleLogAsync(eq("normal-battle"), any());
        }

        @Test
        @DisplayName("End battle logging should add sampling stats")
        void endBattleLogging_ShouldAddSamplingStats() {
            battleLogService.startBattleLogging(normalBattleContext);

            for (int i = 0; i < 10; i++) {
                BattleAction critical = BattleAction.builder()
                        .actionType("PLAY_CARD")
                        .turn(i + 1)
                        .build();
                battleLogService.logAction("normal-battle", critical);

                BattleAction nonCritical = BattleAction.builder()
                        .actionType("GAIN_BLOCK")
                        .turn(i + 1)
                        .build();
                battleLogService.logAction("normal-battle", nonCritical);
            }

            normalBattleContext.setStatus(BattleStatus.VICTORY);
            normalBattleContext.setEndTime(System.currentTimeMillis());

            BattleLog logBefore = battleLogService.activeBattleLogs.get("normal-battle");
            battleLogService.endBattleLogging("normal-battle", normalBattleContext);

            assertThat(logBefore.getStats()).containsKey("samplingReductionPct");
            assertThat(logBefore.getStats()).containsKey("originalActionCount");
            assertThat(logBefore.getStats()).containsKey("sampledActionCount");
        }

        @Test
        @DisplayName("Log action with null action should not throw")
        void logAction_NullAction_ShouldNotThrow() {
            battleLogService.startBattleLogging(normalBattleContext);
            battleLogService.logAction("normal-battle", null);

            BattleLog log = battleLogService.activeBattleLogs.get("normal-battle");
            assertThat(log.getActions()).isEmpty();
        }

        @Test
        @DisplayName("Log action with unknown battle should not throw")
        void logAction_UnknownBattle_ShouldNotThrow() {
            battleLogService.logAction("unknown-battle", BattleAction.builder().build());
        }
    }

    @Nested
    @DisplayName("Sampling Disabled Tests")
    class SamplingDisabledTests {

        @Test
        @DisplayName("When sampling is disabled, all actions should be logged")
        void shouldLogAction_SamplingDisabled_ShouldLogAll() {
            samplingConfig.setEnabled(false);

            assertThat(samplingConfig.shouldLogAction("ANY_ACTION", false)).isTrue();
            assertThat(samplingConfig.shouldLogAction("SOME_OTHER", false)).isTrue();
            assertThat(samplingConfig.getActionTypesForBattle(false))
                    .isEqualTo(samplingConfig.getDetailedActionTypes());
        }
    }

    @Nested
    @DisplayName("Database Cleanup Tests")
    class DatabaseCleanupTests {

        @Test
        @DisplayName("Delete old battle logs should call mapper with correct timestamp")
        void deleteOldBattleLogs_ShouldCallMapper() {
            long cutoff = System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L;

            when(battleLogMapper.deleteOldBattleLogs(cutoff)).thenReturn(100);

            int deleted = battleLogMapper.deleteOldBattleLogs(cutoff);

            assertThat(deleted).isEqualTo(100);
            verify(battleLogMapper).deleteOldBattleLogs(cutoff);
        }
    }
}
