package com.cardgame.battle.engine;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.entity.BuffTriggerContext;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.PlayerClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Buff Chain Reaction Tests")
class BuffChainReactionTest {

    private BuffSystem buffSystem;
    private Player player1;
    private Player player2;
    private BattleContext battleContext;

    private Player createTestPlayer(String id, String name, PlayerClass playerClass, int maxHp) {
        Player player = Player.builder()
                .id(id)
                .playerId(id)
                .name(name)
                .playerClass(playerClass)
                .maxHp(maxHp)
                .currentHp(maxHp)
                .block(0)
                .speed(10)
                .baseSpeed(10)
                .maxEnergy(3)
                .currentEnergy(3)
                .handLimit(10)
                .gold(100)
                .floor(1)
                .online(true)
                .buffs(new HashMap<>())
                .currentHand(new ArrayList<>())
                .drawPile(new ArrayList<>())
                .discardPile(new ArrayList<>())
                .exhaustPile(new ArrayList<>())
                .masterDeck(new ArrayList<>())
                .build();
        return player;
    }

    @BeforeEach
    void setUp() {
        buffSystem = new BuffSystem();
        player1 = createTestPlayer("player1", "Warrior", PlayerClass.WARRIOR, 80);
        player2 = createTestPlayer("player2", "Mage", PlayerClass.MAGE, 60);

        battleContext = BattleContext.builder()
                .battleId("test-battle")
                .players(new ArrayList<>(java.util.Arrays.asList(player1, player2)))
                .enemies(new ArrayList<>())
                .characterMap(new HashMap<>())
                .build();
        battleContext.getCharacterMap().put(player1.getId(), player1);
        battleContext.getCharacterMap().put(player2.getId(), player2);
    }

    @Nested
    @DisplayName("Buff Trigger Context Tests")
    class BuffTriggerContextTests {

        @Test
        @DisplayName("Max depth should be 5")
        void maxDepth_ShouldBe5() {
            assertThat(BuffTriggerContext.getMaxDepth()).isEqualTo(5);
        }

        @Test
        @DisplayName("New context should have depth 0")
        void newContext_ShouldHaveDepth0() {
            BuffTriggerContext context = new BuffTriggerContext("buff-1", "player1");
            assertThat(context.getDepth()).isEqualTo(0);
            assertThat(context.getRootBuffId()).isEqualTo("buff-1");
            assertThat(context.getRootSourceId()).isEqualTo("player1");
        }

        @Test
        @DisplayName("Next level should increment depth and add to triggered set")
        void nextLevel_ShouldIncrementDepth() {
            BuffTriggerContext context = new BuffTriggerContext("buff-1", "player1");
            BuffTriggerContext next = context.nextLevel("buff-1");

            assertThat(next.getDepth()).isEqualTo(1);
            assertThat(next.canTrigger("buff-1")).isFalse();
            assertThat(next.canTrigger("buff-2")).isTrue();
        }

        @Test
        @DisplayName("Should not trigger when max depth reached")
        void canTrigger_AtMaxDepth_ShouldReturnFalse() {
            BuffTriggerContext context = new BuffTriggerContext("buff-1", "player1");
            BuffTriggerContext next = context;
            for (int i = 0; i < 5; i++) {
                next = next.nextLevel("buff-" + i);
            }

            assertThat(next.isMaxDepthReached()).isTrue();
            assertThat(next.canTrigger("buff-new")).isFalse();
        }

        @Test
        @DisplayName("Should not trigger same buff twice")
        void canTrigger_SameBuffTwice_ShouldReturnFalse() {
            BuffTriggerContext context = new BuffTriggerContext("buff-1", "player1");
            BuffTriggerContext next = context.nextLevel("buff-1");

            assertThat(next.canTrigger("buff-1")).isFalse();
            assertThat(next.canTrigger("buff-2")).isTrue();
        }
    }

    @Nested
    @DisplayName("Chain Reaction Prevention Tests")
    class ChainReactionPreventionTests {

        @Test
        @DisplayName("Mutual buff triggers should not cause infinite loop")
        void mutualBuffTriggers_ShouldNotInfiniteLoop() {
            Buff rage = Buff.builder()
                    .type(BuffType.RAGE)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player1.getId())
                    .isDebuff(false)
                    .build();

            Buff weak = Buff.builder()
                    .type(BuffType.WEAK)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player1.getId())
                    .isDebuff(true)
                    .build();

            player1.addBuff(weak);

            BuffTriggerContext triggerContext = new BuffTriggerContext(
                    "trigger-test",
                    player1.getId()
            );
            BattleAction action = BattleAction.builder().build();

            int initialStackCount = player1.getBuffStacks(BuffType.STRENGTH.name());

            buffSystem.applyBuff(player1, rage, battleContext, action, triggerContext);

            assertThat(player1.getBuffStacks(BuffType.RAGE.name())).isEqualTo(1);
            assertThat(player1.getBuffStacks(BuffType.STRENGTH.name())).isGreaterThan(initialStackCount);
        }

        @Test
        @DisplayName("Curse retaliation should not cause infinite loop")
        void curseRetaliation_ShouldNotInfiniteLoop() {
            Buff curse = Buff.builder()
                    .type(BuffType.CURSE)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player1.getId())
                    .isDebuff(true)
                    .build();

            BuffTriggerContext triggerContext = new BuffTriggerContext(
                    "curse-test",
                    player1.getId()
            );
            BattleAction action = BattleAction.builder().build();

            int player1WeakBefore = player1.getBuffStacks(BuffType.WEAK.name());

            buffSystem.applyBuff(player2, curse, battleContext, action, triggerContext);

            assertThat(player2.hasBuff(BuffType.CURSE.name())).isTrue();
            assertThat(player1.getBuffStacks(BuffType.WEAK.name())).isGreaterThan(player1WeakBefore);
        }

        @Test
        @DisplayName("Apply buff without context should not trigger chain reactions")
        void applyBuff_WithoutContext_ShouldNotTriggerChain() {
            Buff rage = Buff.builder()
                    .type(BuffType.RAGE)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player1.getId())
                    .isDebuff(false)
                    .build();

            player1.addBuff(Buff.builder()
                    .type(BuffType.WEAK)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player2.getId())
                    .isDebuff(true)
                    .build());

            int strengthBefore = player1.getBuffStacks(BuffType.STRENGTH.name());
            buffSystem.applyBuff(player1, rage);

            assertThat(player1.hasBuff(BuffType.RAGE.name())).isTrue();
            assertThat(player1.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(strengthBefore);
        }

        @Test
        @DisplayName("Deep chain should stop at max depth")
        void deepChain_ShouldStopAtMaxDepth() {
            BuffTriggerContext context = new BuffTriggerContext("root", "player1");

            Buff currentBuff = Buff.builder()
                    .type(BuffType.RAGE)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player1.getId())
                    .isDebuff(false)
                    .build();

            player1.addBuff(Buff.builder()
                    .type(BuffType.WEAK)
                    .stacks(1)
                    .duration(2)
                    .sourceId(player2.getId())
                    .isDebuff(true)
                    .build());

            BuffTriggerContext deepContext = context;
            for (int i = 0; i < 4; i++) {
                deepContext = deepContext.nextLevel("buff-" + i);
            }

            BattleAction action = BattleAction.builder().build();
            int strengthBefore = player1.getBuffStacks(BuffType.STRENGTH.name());

            buffSystem.applyBuff(player1, currentBuff, battleContext, action, deepContext);

            assertThat(player1.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(strengthBefore);
        }
    }

    @Nested
    @DisplayName("Buff Instance ID Tests")
    class BuffInstanceIdTests {

        @Test
        @DisplayName("New buff should get instance ID when applied")
        void applyBuff_ShouldSetInstanceId() {
            Buff buff = Buff.builder()
                    .type(BuffType.STRENGTH)
                    .stacks(3)
                    .duration(-1)
                    .sourceId("test")
                    .isDebuff(false)
                    .build();
            assertThat(buff.getInstanceId()).isNull();

            buffSystem.applyBuff(player1, buff);

            assertThat(buff.getInstanceId()).isNotNull();
            assertThat(buff.getInstanceId()).isNotEmpty();
        }

        @Test
        @DisplayName("Buff with existing instance ID should not be overwritten")
        void applyBuff_WithInstanceId_ShouldPreserveIt() {
            String customId = "custom-instance-id-123";
            Buff buff = Buff.builder()
                    .type(BuffType.STRENGTH)
                    .stacks(3)
                    .duration(-1)
                    .sourceId("test")
                    .isDebuff(false)
                    .build();
            buff.setInstanceId(customId);

            buffSystem.applyBuff(player1, buff);

            assertThat(buff.getInstanceId()).isEqualTo(customId);
        }
    }
}
