package com.cardgame.battle.engine;

import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.entity.Enemy;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.EffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Effect Processor Tests")
class EffectProcessorTest {

    @Mock
    private BuffSystem buffSystem;

    @InjectMocks
    private EffectProcessor effectProcessor;

    private BattleContext context;
    private Player player;
    private Enemy enemy;
    private BattleAction action;

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
                .currentTurn(1)
                .currentRound(1)
                .players(List.of(player))
                .enemies(List.of(enemy))
                .build();
        context.getCharacterMap().put(player.getPlayerId(), player);
        context.getCharacterMap().put(enemy.getId(), enemy);

        action = BattleAction.builder()
                .actionId("test-action")
                .actorId(player.getPlayerId())
                .isPlayerAction(true)
                .build();
    }

    @Nested
    @DisplayName("Damage Calculation Tests")
    class DamageCalculationTests {

        @Test
        @DisplayName("Process single damage - should deal correct damage to target")
        void processEffect_SingleDamage_ShouldDealDamage() {
            Effect effect = Effect.builder()
                    .effectType(EffectType.SINGLE_DAMAGE)
                    .value(10)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(20);
            assertThat(action.getDamageDealt()).isEqualTo(10);
        }

        @Test
        @DisplayName("Process damage with Strength buff - should increase damage")
        void processEffect_DamageWithStrength_ShouldIncreaseDamage() {
            when(buffSystem.getStrengthBonus(player)).thenReturn(5);
            player.addBuff(TestDataBuilder.createStrengthBuff(5));

            Effect effect = Effect.builder()
                    .effectType(EffectType.SINGLE_DAMAGE)
                    .value(10)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            int expectedDamage = 10 + 5;
            assertThat(enemy.getCurrentHp()).isEqualTo(30 - expectedDamage);
            assertThat(action.getDamageDealt()).isEqualTo(expectedDamage);
        }

        @Test
        @DisplayName("Process damage with Vulnerable debuff on target - should increase damage by 50%")
        void processEffect_DamageWithVulnerable_ShouldIncreaseDamage() {
            enemy.addBuff(TestDataBuilder.createVulnerableDebuff(2));

            Effect effect = Effect.builder()
                    .effectType(EffectType.SINGLE_DAMAGE)
                    .value(10)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            int expectedDamage = (int) (10 * 1.5);
            assertThat(enemy.getCurrentHp()).isEqualTo(30 - expectedDamage);
            assertThat(action.getDamageDealt()).isEqualTo(expectedDamage);
        }

        @Test
        @DisplayName("Process damage with Weak debuff on attacker - should decrease damage by 25%")
        void processEffect_DamageWithWeak_ShouldDecreaseDamage() {
            player.addBuff(TestDataBuilder.createWeakDebuff(2));

            Effect effect = Effect.builder()
                    .effectType(EffectType.SINGLE_DAMAGE)
                    .value(10)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            int expectedDamage = (int) (10 * 0.75);
            assertThat(enemy.getCurrentHp()).isEqualTo(30 - expectedDamage);
            assertThat(action.getDamageDealt()).isEqualTo(expectedDamage);
        }

        @Test
        @DisplayName("Process damage with Block - should absorb damage")
        void processEffect_DamageWithBlock_ShouldAbsorbDamage() {
            enemy.addBlock(5);

            Effect effect = Effect.builder()
                    .effectType(EffectType.SINGLE_DAMAGE)
                    .value(10)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(25);
            assertThat(enemy.getBlock()).isEqualTo(0);
            assertThat(action.getDamageDealt()).isEqualTo(10);
        }

        @Test
        @DisplayName("Process AOE damage - should damage all enemies")
        void processEffect_AoeDamage_ShouldDamageAllEnemies() {
            Enemy enemy2 = TestDataBuilder.createGoblinEnemy("enemy2");
            enemy2.setMaxHp(40);
            enemy2.setCurrentHp(40);
            context.getEnemies().add(enemy2);
            context.getCharacterMap().put(enemy2.getId(), enemy2);

            Effect effect = Effect.builder()
                    .effectType(EffectType.AOE_DAMAGE)
                    .value(10)
                    .targetType("ALL_ENEMIES")
                    .build();

            effectProcessor.processEffect(context, player, null, effect, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(20);
            assertThat(enemy2.getCurrentHp()).isEqualTo(30);
            assertThat(action.getDamageDealt()).isEqualTo(20);
        }

        @Test
        @DisplayName("Process multi-hit damage - should hit multiple times")
        void processEffect_MultiDamage_ShouldHitMultipleTimes() {
            Effect effect = Effect.builder()
                    .effectType(EffectType.MULTI_DAMAGE)
                    .value(3)
                    .targetType("SINGLE_ENEMY")
                    .params(java.util.Map.of("damagePerHit", 5))
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(30 - 3 * 5);
            assertThat(action.getDamageDealt()).isEqualTo(15);
        }
    }

    @Nested
    @DisplayName("Block and Heal Tests")
    class BlockAndHealTests {

        @Test
        @DisplayName("Process Block - should add block to self")
        void processEffect_Block_ShouldAddBlock() {
            Effect effect = Effect.builder()
                    .effectType(EffectType.BLOCK)
                    .value(8)
                    .targetType("SELF")
                    .build();

            effectProcessor.processEffect(context, player, player, effect, action);

            assertThat(player.getBlock()).isEqualTo(8);
            assertThat(action.getBlockGained()).isEqualTo(8);
        }

        @Test
        @DisplayName("Process Block with Dexterity buff - should increase block")
        void processEffect_BlockWithDexterity_ShouldIncreaseBlock() {
            when(buffSystem.getDexterityBonus(player)).thenReturn(3);
            player.addBuff(TestDataBuilder.createBuff(BuffType.DEXTERITY, 3, -1));

            Effect effect = Effect.builder()
                    .effectType(EffectType.BLOCK)
                    .value(8)
                    .targetType("SELF")
                    .build();

            effectProcessor.processEffect(context, player, player, effect, action);

            assertThat(player.getBlock()).isEqualTo(8 + 3);
            assertThat(action.getBlockGained()).isEqualTo(8 + 3);
        }

        @Test
        @DisplayName("Process Heal - should heal target")
        void processEffect_Heal_ShouldHealTarget() {
            player.setCurrentHp(50);

            Effect effect = Effect.builder()
                    .effectType(EffectType.HEAL)
                    .value(20)
                    .targetType("SELF")
                    .build();

            effectProcessor.processEffect(context, player, player, effect, action);

            assertThat(player.getCurrentHp()).isEqualTo(70);
            assertThat(action.getHealAmount()).isEqualTo(20);
        }

        @Test
        @DisplayName("Process Heal - should not exceed max HP")
        void processEffect_Heal_ShouldNotExceedMaxHp() {
            player.setCurrentHp(70);

            Effect effect = Effect.builder()
                    .effectType(EffectType.HEAL)
                    .value(20)
                    .targetType("SELF")
                    .build();

            effectProcessor.processEffect(context, player, player, effect, action);

            assertThat(player.getCurrentHp()).isEqualTo(80);
            assertThat(action.getHealAmount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Buff and Debuff Tests")
    class BuffAndDebuffTests {

        @Test
        @DisplayName("Process Buff - should apply buff to target")
        void processEffect_Buff_ShouldApplyBuff() {
            Effect effect = Effect.builder()
                    .effectType(EffectType.BUFF)
                    .value(2)
                    .buffType(BuffType.STRENGTH)
                    .duration(-1)
                    .targetType("SELF")
                    .build();

            effectProcessor.processEffect(context, player, player, effect, action);

            assertThat(player.hasBuff(BuffType.STRENGTH.name())).isTrue();
            assertThat(player.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(2);
        }

        @Test
        @DisplayName("Process Debuff - should apply debuff to target")
        void processEffect_Debuff_ShouldApplyDebuff() {
            Effect effect = Effect.builder()
                    .effectType(EffectType.DEBUFF)
                    .value(1)
                    .buffType(BuffType.WEAK)
                    .duration(2)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            assertThat(enemy.hasBuff(BuffType.WEAK.name())).isTrue();
            assertThat(enemy.getBuffStacks(BuffType.WEAK.name())).isEqualTo(1);
        }

        @Test
        @DisplayName("Process Debuff with Immune - should not apply")
        void processEffect_DebuffWithImmune_ShouldNotApply() {
            enemy.addBuff(TestDataBuilder.createBuff(BuffType.IMMUNE, 1, 2));

            Effect effect = Effect.builder()
                    .effectType(EffectType.DEBUFF)
                    .value(1)
                    .buffType(BuffType.WEAK)
                    .duration(2)
                    .targetType("SINGLE_ENEMY")
                    .build();

            effectProcessor.processEffect(context, player, enemy, effect, action);

            assertThat(enemy.hasBuff(BuffType.WEAK.name())).isFalse();
        }
    }

    @Nested
    @DisplayName("Card Effect Tests")
    class CardEffectTests {

        @Test
        @DisplayName("Process card effects - strike card should deal damage")
        void processCardEffects_StrikeCard_ShouldDealDamage() {
            Card strikeCard = TestDataBuilder.createStrikeCard();

            effectProcessor.processCardEffects(context, player, strikeCard, List.of(enemy.getId()), action);

            assertThat(enemy.getCurrentHp()).isEqualTo(24);
            assertThat(action.getDamageDealt()).isEqualTo(6);
        }

        @Test
        @DisplayName("Process card effects - defend card should add block")
        void processCardEffects_DefendCard_ShouldAddBlock() {
            Card defendCard = TestDataBuilder.createDefendCard();

            effectProcessor.processCardEffects(context, player, defendCard, List.of(), action);

            assertThat(player.getBlock()).isEqualTo(5);
            assertThat(action.getBlockGained()).isEqualTo(5);
        }

        @Test
        @DisplayName("Process card with multiple effects")
        void processCardEffects_MultipleEffects_ShouldProcessAll() {
            Card card = TestDataBuilder.createCard("multi", "Multi", com.cardgame.common.enums.CardType.ATTACK, 2, 0, 0);
            card.getEffects().add(Effect.builder()
                    .effectType(EffectType.SINGLE_DAMAGE)
                    .value(5)
                    .targetType("SINGLE_ENEMY")
                    .build());
            card.getEffects().add(Effect.builder()
                    .effectType(EffectType.BLOCK)
                    .value(5)
                    .targetType("SELF")
                    .build());

            effectProcessor.processCardEffects(context, player, card, List.of(enemy.getId()), action);

            assertThat(enemy.getCurrentHp()).isEqualTo(25);
            assertThat(player.getBlock()).isEqualTo(5);
            assertThat(action.getDamageDealt()).isEqualTo(5);
            assertThat(action.getBlockGained()).isEqualTo(5);
        }
    }
}
