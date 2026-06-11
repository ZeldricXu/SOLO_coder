package com.cardgame.battle.effects;

import com.cardgame.battle.engine.BuffSystem;
import com.cardgame.common.entity.BattleAction;
import com.cardgame.common.entity.BattleContext;
import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.*;
import com.cardgame.common.enums.BattleStatus;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.EffectType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Effect System Tests - Composition Pattern")
class EffectSystemTest {

    @Mock
    private BuffSystem buffSystem;

    private EffectFactory effectFactory;
    private BattleContext context;
    private Player player;
    private Enemy enemy;
    private BattleAction action;

    @BeforeEach
    void setUp() {
        effectFactory = new EffectFactory(buffSystem);

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
    @DisplayName("EffectFactory Tests")
    class EffectFactoryTests {

        @Test
        @DisplayName("Factory should initialize with all effect types")
        void factory_ShouldInitializeAllEffects() {
            assertThat(effectFactory.getValidatorCount()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Get SINGLE_DAMAGE effect")
        void getEffect_SingleDamage_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.SINGLE_DAMAGE);
            assertThat(effect).isInstanceOf(DamageEffect.class);
            assertThat(effect.requiresTarget()).isTrue();
        }

        @Test
        @DisplayName("Get AOE_DAMAGE effect")
        void getEffect_AoeDamage_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.AOE_DAMAGE);
            assertThat(effect).isInstanceOf(DamageEffect.class);
            assertThat(effect.requiresTarget()).isFalse();
        }

        @Test
        @DisplayName("Get BLOCK effect")
        void getEffect_Block_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.BLOCK);
            assertThat(effect).isInstanceOf(BlockEffect.class);
            assertThat(effect.requiresTarget()).isFalse();
        }

        @Test
        @DisplayName("Get HEAL effect")
        void getEffect_Heal_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.HEAL);
            assertThat(effect).isInstanceOf(HealEffect.class);
            assertThat(effect.requiresTarget()).isFalse();
        }

        @Test
        @DisplayName("Get DRAW effect")
        void getEffect_Draw_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.DRAW);
            assertThat(effect).isInstanceOf(DrawCardEffect.class);
            assertThat(effect.requiresTarget()).isFalse();
        }

        @Test
        @DisplayName("Get BUFF effect")
        void getEffect_Buff_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.BUFF);
            assertThat(effect).isInstanceOf(ApplyBuffEffect.class);
            assertThat(effect.requiresTarget()).isFalse();
        }

        @Test
        @DisplayName("Get DEBUFF effect")
        void getEffect_Debuff_ShouldReturnCorrectEffect() {
            IEffect effect = effectFactory.getEffect(EffectType.DEBUFF);
            assertThat(effect).isInstanceOf(ApplyBuffEffect.class);
            assertThat(effect.requiresTarget()).isTrue();
        }

        @Test
        @DisplayName("Unknown effect type should return default")
        void getEffect_UnknownType_ShouldReturnDefault() {
            IEffect effect = effectFactory.getEffect(EffectType.SUMMON);
            assertThat(effect).isNotNull();
        }
    }

    @Nested
    @DisplayName("DamageEffect Tests")
    class DamageEffectTests {

        @Test
        @DisplayName("Single damage - should deal correct damage")
        void singleDamage_ShouldDealDamage() {
            IEffect effect = effectFactory.getEffect(EffectType.SINGLE_DAMAGE);
            Effect config = Effect.builder().type(EffectType.SINGLE_DAMAGE).value(10).build();

            effect.apply(context, player, enemy, config, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(20);
            assertThat(action.getDamageDealt()).isEqualTo(10);
        }

        @Test
        @DisplayName("AOE damage - should damage all enemies")
        void aoeDamage_ShouldDamageAllEnemies() {
            Enemy enemy2 = TestDataBuilder.createGoblinEnemy("enemy2");
            enemy2.setCurrentHp(40);
            context.getEnemies().add(enemy2);
            context.getCharacterMap().put(enemy2.getId(), enemy2);

            IEffect effect = effectFactory.getEffect(EffectType.AOE_DAMAGE);
            Effect config = Effect.builder().type(EffectType.AOE_DAMAGE).value(10).build();

            effect.apply(context, player, null, config, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(20);
            assertThat(enemy2.getCurrentHp()).isEqualTo(30);
            assertThat(action.getDamageDealt()).isEqualTo(20);
        }

        @Test
        @DisplayName("Multi damage - should hit multiple times")
        void multiDamage_ShouldHitMultipleTimes() {
            IEffect effect = effectFactory.getEffect(EffectType.MULTI_DAMAGE);
            Effect config = Effect.builder()
                    .type(EffectType.MULTI_DAMAGE)
                    .value(3)
                    .params(Map.of("damagePerHit", 5))
                    .build();

            effect.apply(context, player, enemy, config, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(15);
            assertThat(action.getDamageDealt()).isEqualTo(15);
        }

        @Test
        @DisplayName("Damage with thorns - should reflect damage")
        void damageWithThorns_ShouldReflectDamage() {
            enemy.addBuff(TestDataBuilder.createBuff(BuffType.THORNS, 3, -1));

            IEffect effect = effectFactory.getEffect(EffectType.SINGLE_DAMAGE);
            Effect config = Effect.builder().type(EffectType.SINGLE_DAMAGE).value(10).build();

            effect.apply(context, player, enemy, config, action);

            assertThat(enemy.getCurrentHp()).isEqualTo(20);
            assertThat(player.getCurrentHp()).isEqualTo(77);
        }
    }

    @Nested
    @DisplayName("Block and Heal Tests")
    class BlockAndHealTests {

        @Test
        @DisplayName("Block - should add block")
        void block_ShouldAddBlock() {
            IEffect effect = effectFactory.getEffect(EffectType.BLOCK);
            Effect config = Effect.builder().type(EffectType.BLOCK).value(8).build();

            effect.apply(context, player, player, config, action);

            assertThat(player.getBlock()).isEqualTo(8);
            assertThat(action.getBlockGained()).isEqualTo(8);
        }

        @Test
        @DisplayName("Block with Dexterity - should increase block")
        void blockWithDexterity_ShouldIncreaseBlock() {
            player.addBuff(TestDataBuilder.createBuff(BuffType.DEXTERITY, 3, -1));

            IEffect effect = effectFactory.getEffect(EffectType.BLOCK);
            Effect config = Effect.builder().type(EffectType.BLOCK).value(8).build();

            effect.apply(context, player, player, config, action);

            assertThat(player.getBlock()).isEqualTo(11);
            assertThat(action.getBlockGained()).isEqualTo(11);
        }

        @Test
        @DisplayName("Heal - should heal target")
        void heal_ShouldHealTarget() {
            player.setCurrentHp(50);

            IEffect effect = effectFactory.getEffect(EffectType.HEAL);
            Effect config = Effect.builder().type(EffectType.HEAL).value(20).build();

            effect.apply(context, player, player, config, action);

            assertThat(player.getCurrentHp()).isEqualTo(70);
            assertThat(action.getHealAmount()).isEqualTo(20);
        }

        @Test
        @DisplayName("Heal - should not exceed max HP")
        void heal_ShouldNotExceedMaxHp() {
            player.setCurrentHp(70);

            IEffect effect = effectFactory.getEffect(EffectType.HEAL);
            Effect config = Effect.builder().type(EffectType.HEAL).value(20).build();

            effect.apply(context, player, player, config, action);

            assertThat(player.getCurrentHp()).isEqualTo(80);
            assertThat(action.getHealAmount()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Buff Effect Tests")
    class BuffEffectTests {

        @Test
        @DisplayName("Apply buff - should add buff to target")
        void applyBuff_ShouldAddBuff() {
            Buff buff = TestDataBuilder.createStrengthBuff(2);
            Effect config = Effect.builder()
                    .type(EffectType.BUFF)
                    .buff(buff)
                    .build();

            IEffect effect = effectFactory.getEffect(EffectType.BUFF);
            effect.apply(context, player, player, config, action);

            verify(buffSystem).applyBuff(player, buff);
            assertThat(action.getBuffsApplied()).containsKey(BuffType.STRENGTH.name());
        }

        @Test
        @DisplayName("Apply debuff - should add debuff to enemy")
        void applyDebuff_ShouldAddDebuff() {
            Buff debuff = TestDataBuilder.createWeakDebuff(2);
            Effect config = Effect.builder()
                    .type(EffectType.DEBUFF)
                    .buff(debuff)
                    .build();

            IEffect effect = effectFactory.getEffect(EffectType.DEBUFF);
            effect.apply(context, player, enemy, config, action);

            verify(buffSystem).applyBuff(enemy, debuff);
            assertThat(action.getBuffsApplied()).containsKey(BuffType.WEAK.name());
        }

        @Test
        @DisplayName("Immune target - should not apply debuff")
        void immuneTarget_ShouldNotApplyDebuff() {
            enemy.addBuff(TestDataBuilder.createBuff(BuffType.IMMUNE, 1, 2));

            Buff debuff = TestDataBuilder.createWeakDebuff(2);
            Effect config = Effect.builder()
                    .type(EffectType.DEBUFF)
                    .buff(debuff)
                    .build();

            IEffect effect = effectFactory.getEffect(EffectType.DEBUFF);
            effect.apply(context, player, enemy, config, action);

            verify(buffSystem, never()).applyBuff(eq(enemy), any());
            assertThat(action.getBuffsApplied()).doesNotContainKey(BuffType.WEAK.name());
        }

        @Test
        @DisplayName("Remove debuff - should clear all debuffs")
        void removeDebuff_ShouldClearDebuffs() {
            enemy.addBuff(TestDataBuilder.createWeakDebuff(2));
            enemy.addBuff(TestDataBuilder.createVulnerableDebuff(2));
            enemy.addBuff(TestDataBuilder.createStrengthBuff(3));

            IEffect effect = effectFactory.getEffect(EffectType.REMOVE_DEBUFF);
            Effect config = Effect.builder().type(EffectType.REMOVE_DEBUFF).build();

            effect.apply(context, enemy, enemy, config, action);

            assertThat(enemy.getBuffStacks(BuffType.WEAK.name())).isEqualTo(0);
            assertThat(enemy.getBuffStacks(BuffType.VULNERABLE.name())).isEqualTo(0);
            assertThat(enemy.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(3);
            assertThat(action.getBuffsRemoved()).containsEntry("debuffs", 2);
        }
    }

    @Nested
    @DisplayName("Card Effect Composition Tests")
    class CardEffectCompositionTests {

        @Test
        @DisplayName("Card with multiple effects - should apply all in order")
        void cardWithMultipleEffects_ShouldApplyAll() {
            Card card = Card.builder()
                    .cardId("test-card")
                    .name("Combo Card")
                    .cost(2)
                    .effects(List.of(
                            Effect.builder().type(EffectType.SINGLE_DAMAGE).value(5).build(),
                            Effect.builder().type(EffectType.BLOCK).value(5).build(),
                            Effect.builder().type(EffectType.DRAW).value(1).build()
                    ))
                    .build();

            int initialHandSize = player.getCurrentHand().size();

            for (Effect effect : card.getEffects()) {
                IEffect effectExecutor = effectFactory.getEffect(effect.getType());
                GameCharacter target = effectExecutor.requiresTarget() ? enemy : null;
                effectExecutor.apply(context, player, target, effect, action);
            }

            assertThat(enemy.getCurrentHp()).isEqualTo(25);
            assertThat(player.getBlock()).isEqualTo(5);
            assertThat(action.getDamageDealt()).isEqualTo(5);
            assertThat(action.getBlockGained()).isEqualTo(5);
        }

        @Test
        @DisplayName("New card - create by combining existing effects")
        void createNewCard_ByCombiningEffects() {
            Card fireball = Card.builder()
                    .cardId("fireball")
                    .name("Fireball")
                    .cost(2)
                    .effects(List.of(
                            Effect.builder().type(EffectType.SINGLE_DAMAGE).value(8).build(),
                            Effect.builder()
                                    .type(EffectType.DEBUFF)
                                    .buff(TestDataBuilder.createVulnerableDebuff(2))
                                    .build()
                    ))
                    .build();

            for (Effect effect : fireball.getEffects()) {
                IEffect effectExecutor = effectFactory.getEffect(effect.getType());
                GameCharacter target = effectExecutor.requiresTarget() ? enemy : null;
                effectExecutor.apply(context, player, target, effect, action);
            }

            assertThat(enemy.getCurrentHp()).isEqualTo(22);
            assertThat(action.getBuffsApplied()).containsKey(BuffType.VULNERABLE.name());
        }
    }
}
