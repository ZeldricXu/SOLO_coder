package com.cardgame.battle.engine;

import com.cardgame.battle.entity.BattleContext;
import com.cardgame.battle.entity.TimelineEntry;
import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.enums.BuffType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Timeline Engine Tests")
class TimelineEngineTest {

    private TimelineEngine timelineEngine;
    private BattleContext context;
    private List<Player> players;
    private List<Enemy> enemies;

    @BeforeEach
    void setUp() {
        timelineEngine = new TimelineEngine();
        players = TestDataBuilder.createMultiplePlayers(2);
        enemies = TestDataBuilder.createMultipleEnemies(2);

        players.get(0).setBaseSpeed(10);
        players.get(1).setBaseSpeed(15);
        enemies.get(0).setBaseSpeed(8);
        enemies.get(1).setBaseSpeed(12);

        context = BattleContext.builder()
                .battleId("test-battle")
                .roomId("test-room")
                .floor(1)
                .status(BattleStatus.IN_PROGRESS)
                .currentTurn(1)
                .currentRound(1)
                .players(players)
                .enemies(enemies)
                .build();
    }

    @Nested
    @DisplayName("Timeline Building Tests")
    class TimelineBuildingTests {

        @Test
        @DisplayName("Build timeline - should sort by speed descending")
        void buildTimeline_ShouldSortBySpeedDescending() {
            timelineEngine.buildTimeline(context);

            List<TimelineEntry> timeline = context.getTimeline();

            assertThat(timeline).hasSize(4);
            assertThat(timeline.get(0).getCharacterId()).isEqualTo(players.get(1).getPlayerId());
            assertThat(timeline.get(0).getSpeed()).isEqualTo(15);

            assertThat(timeline.get(1).getCharacterId()).isEqualTo(enemies.get(1).getId());
            assertThat(timeline.get(1).getSpeed()).isEqualTo(12);

            assertThat(timeline.get(2).getCharacterId()).isEqualTo(players.get(0).getPlayerId());
            assertThat(timeline.get(2).getSpeed()).isEqualTo(10);

            assertThat(timeline.get(3).getCharacterId()).isEqualTo(enemies.get(0).getId());
            assertThat(timeline.get(3).getSpeed()).isEqualTo(8);
        }

        @Test
        @DisplayName("Build timeline - should mark player entries correctly")
        void buildTimeline_ShouldMarkPlayerEntries() {
            timelineEngine.buildTimeline(context);

            List<TimelineEntry> timeline = context.getTimeline();

            long playerCount = timeline.stream().filter(TimelineEntry::isPlayer).count();
            long enemyCount = timeline.stream().filter(e -> !e.isPlayer()).count();

            assertThat(playerCount).isEqualTo(2);
            assertThat(enemyCount).isEqualTo(2);
        }

        @Test
        @DisplayName("Build timeline - should exclude dead characters")
        void buildTimeline_ShouldExcludeDeadCharacters() {
            enemies.get(0).setCurrentHp(0);
            assertThat(enemies.get(0).isAlive()).isFalse();

            timelineEngine.buildTimeline(context);

            List<TimelineEntry> timeline = context.getTimeline();
            assertThat(timeline).hasSize(3);
            assertThat(timeline).extracting(TimelineEntry::getCharacterId)
                    .doesNotContain(enemies.get(0).getId());
        }

        @Test
        @DisplayName("Build timeline - should include Haste buff in speed calculation")
        void buildTimeline_ShouldIncludeHasteBuff() {
            players.get(0).addBuff(TestDataBuilder.createBuff(BuffType.HASTE, 3, 2));

            timelineEngine.buildTimeline(context);

            TimelineEntry player0Entry = context.getTimeline().stream()
                    .filter(e -> e.getCharacterId().equals(players.get(0).getPlayerId()))
                    .findFirst()
                    .orElseThrow();

            assertThat(player0Entry.getSpeed()).isEqualTo(10 + 3 * 2);
        }
    }

    @Nested
    @DisplayName("Timeline Navigation Tests")
    class TimelineNavigationTests {

        @BeforeEach
        void setUpTimeline() {
            timelineEngine.buildTimeline(context);
        }

        @Test
        @DisplayName("Get current actor - should return first unacted alive entry")
        void getCurrentActor_ShouldReturnFirstUnactedAlive() {
            TimelineEntry current = timelineEngine.getCurrentActor(context);

            assertThat(current).isNotNull();
            assertThat(current.getCharacterId()).isEqualTo(players.get(1).getPlayerId());
            assertThat(current.isHasActed()).isFalse();
        }

        @Test
        @DisplayName("Advance to next actor - should mark current as acted and move to next")
        void advanceToNextActor_ShouldMarkAndAdvance() {
            TimelineEntry first = timelineEngine.getCurrentActor(context);
            assertThat(first.getCharacterId()).isEqualTo(players.get(1).getPlayerId());
            assertThat(first.isHasActed()).isFalse();

            timelineEngine.advanceToNextActor(context);

            assertThat(first.isHasActed()).isTrue();

            TimelineEntry second = timelineEngine.getCurrentActor(context);
            assertThat(second).isNotNull();
            assertThat(second.getCharacterId()).isEqualTo(enemies.get(1).getId());
            assertThat(second.isHasActed()).isFalse();
        }

        @Test
        @DisplayName("All acted - should return true when all alive entries have acted")
        void allActed_ShouldReturnTrueWhenAllActed() {
            assertThat(timelineEngine.allActed(context)).isFalse();

            for (int i = 0; i < 4; i++) {
                timelineEngine.advanceToNextActor(context);
            }

            assertThat(timelineEngine.allActed(context)).isTrue();
        }

        @Test
        @DisplayName("Get next actor - should return next unacted entry")
        void getNextActor_ShouldReturnNextUnacted() {
            TimelineEntry first = timelineEngine.getCurrentActor(context);
            timelineEngine.markActed(context, first.getCharacterId());

            TimelineEntry next = timelineEngine.getNextActor(context);

            assertThat(next).isNotNull();
            assertThat(next.getCharacterId()).isEqualTo(enemies.get(1).getId());
        }
    }

    @Nested
    @DisplayName("Round Management Tests")
    class RoundManagementTests {

        @BeforeEach
        void setUpTimeline() {
            timelineEngine.buildTimeline(context);
        }

        @Test
        @DisplayName("Start new round - should increment round and reset all acted flags")
        void startNewRound_ShouldResetTimeline() {
            for (int i = 0; i < 4; i++) {
                timelineEngine.advanceToNextActor(context);
            }
            assertThat(timelineEngine.allActed(context)).isTrue();

            int oldRound = context.getCurrentRound();
            timelineEngine.startNewRound(context);

            assertThat(context.getCurrentRound()).isEqualTo(oldRound + 1);
            assertThat(timelineEngine.allActed(context)).isFalse();
            assertThat(timelineEngine.getCurrentActor()).isNotNull();
        }

        @Test
        @DisplayName("Start new round - should exclude dead characters")
        void startNewRound_ShouldExcludeDeadCharacters() {
            enemies.get(1).setCurrentHp(0);
            timelineEngine.refreshTimeline(context);

            assertThat(context.getTimeline()).hasSize(3);
            assertThat(context.getTimeline()).extracting(TimelineEntry::getCharacterId)
                    .doesNotContain(enemies.get(1).getId());
        }

        @Test
        @DisplayName("Refresh timeline - should recalculate speeds")
        void refreshTimeline_ShouldRecalculateSpeeds() {
            players.get(0).addBuff(TestDataBuilder.createBuff(BuffType.HASTE, 2, 2));
            int oldSpeed = players.get(0).getSpeed();

            timelineEngine.refreshTimeline(context);

            TimelineEntry entry = context.getTimeline().stream()
                    .filter(e -> e.getCharacterId().equals(players.get(0).getPlayerId()))
                    .findFirst()
                    .orElseThrow();

            assertThat(entry.getSpeed()).isEqualTo(oldSpeed + 2 * 2);
        }
    }

    @Nested
    @DisplayName("Turn Type Tests")
    class TurnTypeTests {

        @BeforeEach
        void setUpTimeline() {
            timelineEngine.buildTimeline(context);
        }

        @Test
        @DisplayName("Is player turn - should return true when current actor is player")
        void isPlayerTurn_ShouldReturnTrueForPlayerTurn() {
            TimelineEntry current = timelineEngine.getCurrentActor(context);
            assertThat(current.isPlayer()).isTrue();
            assertThat(timelineEngine.isPlayerTurn(context)).isTrue();
        }

        @Test
        @DisplayName("Is player turn - should return false when current actor is enemy")
        void isPlayerTurn_ShouldReturnFalseForEnemyTurn() {
            timelineEngine.advanceToNextActor(context);
            timelineEngine.advanceToNextActor(context);

            assertThat(timelineEngine.getCurrentActor().isPlayer()).isFalse();
            assertThat(timelineEngine.isPlayerTurn(context)).isFalse();
        }
    }
}
