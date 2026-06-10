package com.cardgame.battle.pipeline;

import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.battle.engine.EffectProcessor;
import com.cardgame.battle.engine.TimelineEngine;
import com.cardgame.battle.entity.BattleContext;
import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.deck.DeckManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Battle Pipeline Tests")
class BattlePipelineTest {

    @Mock
    private BuffSystem buffSystem;

    @Mock
    private TimelineEngine timelineEngine;

    @Mock
    private DeckManager deckManager;

    @Mock
    private GameConfig gameConfig;

    @Mock
    private EffectProcessor effectProcessor;

    @Mock
    private com.cardgame.replay.service.BattleLogService battleLogService;

    private BattlePipeline pipeline;
    private BattleContext context;
    private Player player;
    private Enemy enemy;

    @BeforeEach
    void setUp() {
        player = TestDataBuilder.createWarriorPlayer("player1");
        enemy = TestDataBuilder.createSlimeEnemy("enemy1");
        player.setMaxHp(80);
        player.setCurrentHp(80);
        player.setCurrentEnergy(3);
        enemy.setMaxHp(30);
        enemy.setCurrentHp(30);

        context = BattleContext.builder()
                .battleId("test-battle")
                .roomId("test-room")
                .floor(1)
                .status(BattleStatus.IN_PROGRESS)
                .currentTurn(0)
                .currentRound(1)
                .players(List.of(player))
                .enemies(List.of(enemy))
                .build();
        context.getCharacterMap().put(player.getPlayerId(), player);
        context.getCharacterMap().put(enemy.getId(), enemy);

        when(gameConfig.getDefaultDrawPerTurn()).thenReturn(5);

        pipeline = BattlePipeline.builder()
                .buffSystem(buffSystem)
                .timelineEngine(timelineEngine)
                .deckManager(deckManager)
                .gameConfig(gameConfig)
                .effectProcessor(effectProcessor)
                .intentGenerator((e, c) -> {})
                .enemyActionExecutor((e, c) -> null)
                .battleLogService(battleLogService)
                .cleanupCallback(() -> {})
                .build();
    }

    @Test
    @DisplayName("Pipeline builder - should create complete chain")
    void pipelineBuilder_ShouldCreateCompleteChain() {
        assertThat(pipeline).isNotNull();
    }

    @Test
    @DisplayName("PreTurnPhase - should process player turn start")
    void preTurnPhase_ShouldProcessPlayerTurnStart() throws Exception {
        var timelineEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(player.getPlayerId())
                .isPlayer(true)
                .speed(10)
                .build();

        when(timelineEngine.getCurrentActor(context)).thenReturn(timelineEntry);
        when(buffSystem.isStunned(player)).thenReturn(false);

        PreTurnPhase phase = new PreTurnPhase(
                buffSystem, timelineEngine, deckManager, gameConfig, (e, c) -> {}
        );

        phase.execute(context);

        verify(buffSystem).processTurnStartBuffs(player);
        verify(deckManager, never()).prepareForBattle(player);
        assertThat(context.getCurrentActor()).isEqualTo(player.getPlayerId());
    }

    @Test
    @DisplayName("PreTurnPhase - stunned player should skip turn")
    void preTurnPhase_StunnedPlayer_ShouldSkipTurn() throws Exception {
        var timelineEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(player.getPlayerId())
                .isPlayer(true)
                .speed(10)
                .build();

        when(timelineEngine.getCurrentActor(context)).thenReturn(timelineEntry);
        when(buffSystem.isStunned(player)).thenReturn(true);

        PreTurnPhase phase = new PreTurnPhase(
                buffSystem, timelineEngine, deckManager, gameConfig, (e, c) -> {}
        );

        phase.execute(context);

        verify(buffSystem).processTurnStartBuffs(player);
        verify(buffSystem).processTurnEndBuffs(player);
        verify(timelineEngine).advanceToNextActor(context);
    }

    @Test
    @DisplayName("PostActionPhase - should process turn end")
    void postActionPhase_ShouldProcessTurnEnd() throws Exception {
        var timelineEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(player.getPlayerId())
                .isPlayer(true)
                .speed(10)
                .build();

        when(timelineEngine.getCurrentActor(context)).thenReturn(timelineEntry);

        PostActionPhase phase = new PostActionPhase(
                buffSystem, timelineEngine, (e, c) -> {}
        );

        phase.execute(context);

        verify(buffSystem).processTurnEndBuffs(player);
        verify(timelineEngine).advanceToNextActor(context);
        assertThat(context.getCurrentActor()).isNull();
    }

    @Test
    @DisplayName("DeathCheckPhase - should detect victory")
    void deathCheckPhase_ShouldDetectVictory() throws Exception {
        enemy.setCurrentHp(0);

        DeathCheckPhase phase = new DeathCheckPhase(battleLogService, () -> {});

        phase.execute(context);

        assertThat(context.getStatus()).isEqualTo(BattleStatus.VICTORY);
        assertThat(context.getEndTime()).isGreaterThan(0);
    }

    @Test
    @DisplayName("DeathCheckPhase - should detect defeat")
    void deathCheckPhase_ShouldDetectDefeat() throws Exception {
        player.setCurrentHp(0);
        enemy.setCurrentHp(30);

        DeathCheckPhase phase = new DeathCheckPhase(battleLogService, () -> {});

        phase.execute(context);

        assertThat(context.getStatus()).isEqualTo(BattleStatus.DEFEAT);
    }

    @Test
    @DisplayName("DeathCheckPhase - should remove dead enemies")
    void deathCheckPhase_ShouldRemoveDeadEnemies() throws Exception {
        Enemy enemy2 = TestDataBuilder.createGoblinEnemy("enemy2");
        enemy2.setCurrentHp(0);
        enemy.setCurrentHp(20);
        context.getEnemies().add(enemy2);
        context.getCharacterMap().put(enemy2.getId(), enemy2);

        DeathCheckPhase phase = new DeathCheckPhase(battleLogService, () -> {});

        phase.execute(context);

        assertThat(context.getEnemies()).hasSize(1);
        assertThat(context.getEnemies().get(0).getId()).isEqualTo(enemy.getId());
        assertThat(context.getCharacterMap()).doesNotContainKey(enemy2.getId());
    }

    @Test
    @DisplayName("PostTurnCleanupPhase - should cleanup after turn")
    void postTurnCleanupPhase_ShouldCleanupAfterTurn() throws Exception {
        var timelineEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(player.getPlayerId())
                .isPlayer(true)
                .speed(10)
                .build();

        when(timelineEngine.getCurrentActor(context)).thenReturn(timelineEntry);

        PostTurnCleanupPhase phase = new PostTurnCleanupPhase(
                buffSystem, timelineEngine, (e, c) -> {}, deckManager
        );

        phase.execute(context);

        verify(buffSystem).processTurnEndBuffs(player);
        verify(timelineEngine).advanceToNextActor(context);
    }

    @Test
    @DisplayName("Full pipeline execution - should execute all phases in order")
    void fullPipeline_ShouldExecuteAllPhases() throws Exception {
        var playerEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(player.getPlayerId())
                .isPlayer(true)
                .speed(10)
                .build();
        var enemyEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(enemy.getId())
                .isPlayer(false)
                .speed(8)
                .build();

        when(timelineEngine.getCurrentActor(context))
                .thenReturn(playerEntry)
                .thenReturn(enemyEntry);
        when(buffSystem.isStunned(any())).thenReturn(false);

        pipeline.execute(context);

        verify(buffSystem, atLeastOnce()).processTurnStartBuffs(any());
        verify(buffSystem, atLeastOnce()).processTurnEndBuffs(any());
        verify(timelineEngine, atLeastOnce()).advanceToNextActor(context);
    }

    @Test
    @DisplayName("Battle ends - should not continue pipeline")
    void battleEnded_ShouldNotContinuePipeline() throws Exception {
        context.setStatus(BattleStatus.VICTORY);

        PreTurnPhase phase = new PreTurnPhase(
                buffSystem, timelineEngine, deckManager, gameConfig, (e, c) -> {}
        );

        phase.execute(context);

        verify(buffSystem, never()).processTurnStartBuffs(any());
        verify(timelineEngine, never()).getCurrentActor(context);
    }

    @Test
    @DisplayName("Chain building - should link phases correctly")
    void chainBuilding_ShouldLinkPhasesCorrectly() {
        PreTurnPhase phase1 = new PreTurnPhase(
                buffSystem, timelineEngine, deckManager, gameConfig, (e, c) -> {}
        );
        ActionPhase phase2 = new ActionPhase(
                timelineEngine, deckManager, effectProcessor, (e, c) -> null, battleLogService
        );
        PostActionPhase phase3 = new PostActionPhase(
                buffSystem, timelineEngine, (e, c) -> {}
        );

        phase1.setNext(phase2).setNext(phase3);

        assertThat(phase1.next).isInstanceOf(ActionPhase.class);
        assertThat(phase2.next).isInstanceOf(PostActionPhase.class);
        assertThat(phase3.next).isNull();
    }

    @Test
    @DisplayName("Death character - should skip in PreTurnPhase")
    void deadCharacter_ShouldSkipInPreTurnPhase() throws Exception {
        player.setCurrentHp(0);
        var timelineEntry = com.cardgame.battle.entity.TimelineEntry.builder()
                .characterId(player.getPlayerId())
                .isPlayer(true)
                .speed(10)
                .build();

        when(timelineEngine.getCurrentActor(context)).thenReturn(timelineEntry);

        PreTurnPhase phase = new PreTurnPhase(
                buffSystem, timelineEngine, deckManager, gameConfig, (e, c) -> {}
        );

        phase.execute(context);

        verify(buffSystem, never()).processTurnStartBuffs(player);
        verify(timelineEngine).advanceToNextActor(context);
    }
}
