package com.cardgame.battle.engine;

import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.Buff;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.BuffType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Buff System Tests")
class BuffSystemTest {

    private BuffSystem buffSystem;
    private Player player;

    @BeforeEach
    void setUp() {
        buffSystem = new BuffSystem();
        player = TestDataBuilder.createWarriorPlayer("player1");
        player.setMaxHp(80);
        player.setCurrentHp(80);
    }

    @Nested
    @DisplayName("Buff Application Tests")
    class BuffApplicationTests {

        @Test
        @DisplayName("Apply buff - should add new buff to character")
        void applyBuff_ShouldAddNewBuff() {
            Buff strength = TestDataBuilder.createStrengthBuff(3);

            buffSystem.applyBuff(player, strength);

            assertThat(player.hasBuff(BuffType.STRENGTH.name())).isTrue();
            assertThat(player.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(3);
        }

        @Test
        @DisplayName("Apply same buff - should stack correctly")
        void applyBuff_SameBuff_ShouldStack() {
            Buff strength1 = TestDataBuilder.createStrengthBuff(2);
            Buff strength2 = TestDataBuilder.createStrengthBuff(3);

            buffSystem.applyBuff(player, strength1);
            buffSystem.applyBuff(player, strength2);

            assertThat(player.getBuffStacks(BuffType.STRENGTH.name())).isEqualTo(5);
        }

        @Test
        @DisplayName("Apply buff with duration - should use longer duration")
        void applyBuff_DifferentDuration_ShouldUseLonger() {
            Buff weak1 = TestDataBuilder.createWeakDebuff(2);
            Buff weak2 = TestDataBuilder.createWeakDebuff(4);

            buffSystem.applyBuff(player, weak1);
            buffSystem.applyBuff(player, weak2);

            Buff existing = player.getBuffs().get(BuffType.WEAK.name());
            assertThat(existing.getDuration()).isEqualTo(4);
        }

        @Test
        @DisplayName("Apply debuff with Immune - should not apply")
        void applyBuff_WithImmune_ShouldNotApplyDebuff() {
            Buff immune = TestDataBuilder.createBuff(BuffType.IMMUNE, 1, 2);
            Buff weak = TestDataBuilder.createWeakDebuff(2);

            buffSystem.applyBuff(player, immune);
            buffSystem.applyBuff(player, weak);

            assertThat(player.hasBuff(BuffType.WEAK.name())).isFalse();
        }

        @Test
        @DisplayName("Apply Shield buff - should immediately add block")
        void applyBuff_Shield_ShouldAddBlock() {
            Buff shield = TestDataBuilder.createBuff(BuffType.SHIELD, 10, 1);

            buffSystem.applyBuff(player, shield);

            assertThat(player.getBlock()).isEqualTo(10);
        }
    }

    @Nested
    @DisplayName("Buff Removal Tests")
    class BuffRemovalTests {

        @Test
        @DisplayName("Remove buff - should remove specific buff type")
        void removeBuff_ShouldRemoveSpecificBuff() {
            Buff strength = TestDataBuilder.createStrengthBuff(3);
            Buff weak = TestDataBuilder.createWeakDebuff(2);

            buffSystem.applyBuff(player, strength);
            buffSystem.applyBuff(player, weak);

            buffSystem.removeBuff(player, BuffType.STRENGTH);

            assertThat(player.hasBuff(BuffType.STRENGTH.name())).isFalse();
            assertThat(player.hasBuff(BuffType.WEAK.name())).isTrue();
        }

        @Test
        @DisplayName("Remove all debuffs - should only remove debuffs")
        void removeAllDebuffs_ShouldRemoveOnlyDebuffs() {
            Buff strength = TestDataBuilder.createStrengthBuff(3);
            Buff weak = TestDataBuilder.createWeakDebuff(2);
            Buff vulnerable = TestDataBuilder.createVulnerableDebuff(2);

            buffSystem.applyBuff(player, strength);
            buffSystem.applyBuff(player, weak);
            buffSystem.applyBuff(player, vulnerable);

            buffSystem.removeAllDebuffs(player);

            assertThat(player.hasBuff(BuffType.STRENGTH.name())).isTrue();
            assertThat(player.hasBuff(BuffType.WEAK.name())).isFalse();
            assertThat(player.hasBuff(BuffType.VULNERABLE.name())).isFalse();
        }

        @Test
        @DisplayName("Remove all buffs - should clear everything")
        void removeAllBuffs_ShouldClearAll() {
            Buff strength = TestDataBuilder.createStrengthBuff(3);
            Buff weak = TestDataBuilder.createWeakDebuff(2);

            buffSystem.applyBuff(player, strength);
            buffSystem.applyBuff(player, weak);

            buffSystem.removeAllBuffs(player);

            assertThat(player.getBuffs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("Turn Start Buffs Tests")
    class TurnStartBuffsTests {

        @Test
        @DisplayName("Regen buff - should heal on turn start")
        void processTurnStartBuffs_Regen_ShouldHeal() {
            player.setCurrentHp(50);
            Buff regen = TestDataBuilder.createBuff(BuffType.REGEN, 5, 2);
            buffSystem.applyBuff(player, regen);

            buffSystem.processTurnStartBuffs(player);

            assertThat(player.getCurrentHp()).isEqualTo(55);
        }

        @Test
        @DisplayName("Poison debuff - should deal damage on turn start")
        void processTurnStartBuffs_Poison_ShouldDealDamage() {
            Buff poison = TestDataBuilder.createPoisonDebuff(4, 2);
            buffSystem.applyBuff(player, poison);

            buffSystem.processTurnStartBuffs(player);

            assertThat(player.getCurrentHp()).isEqualTo(76);
        }

        @Test
        @DisplayName("Burn debuff - should deal damage on turn start")
        void processTurnStartBuffs_Burn_ShouldDealDamage() {
            Buff burn = TestDataBuilder.createBuff(BuffType.BURN, 6, 2);
            burn.setDebuff(true);
            buffSystem.applyBuff(player, burn);

            buffSystem.processTurnStartBuffs(player);

            assertThat(player.getCurrentHp()).isEqualTo(74);
        }
    }

    @Nested
    @DisplayName("Turn End Buffs Tests")
    class TurnEndBuffsTests {

        @Test
        @DisplayName("Buff with duration - should decrease duration on turn end")
        void processTurnEndBuffs_Duration_ShouldDecrease() {
            Buff weak = TestDataBuilder.createWeakDebuff(3);
            buffSystem.applyBuff(player, weak);

            buffSystem.processTurnEndBuffs(player);

            Buff existing = player.getBuffs().get(BuffType.WEAK.name());
            assertThat(existing.getDuration()).isEqualTo(2);
        }

        @Test
        @DisplayName("Buff with duration 0 - should not decrease (permanent)")
        void processTurnEndBuffs_Permanent_ShouldNotDecrease() {
            Buff strength = TestDataBuilder.createStrengthBuff(3);
            buffSystem.applyBuff(player, strength);

            buffSystem.processTurnEndBuffs(player);

            Buff existing = player.getBuffs().get(BuffType.STRENGTH.name());
            assertThat(existing.getDuration()).isEqualTo(-1);
        }

        @Test
        @DisplayName("Expired buff - should be removed")
        void processTurnEndBuffs_Expired_ShouldBeRemoved() {
            Buff weak = TestDataBuilder.createWeakDebuff(1);
            buffSystem.applyBuff(player, weak);

            buffSystem.processTurnEndBuffs(player);
            buffSystem.processTurnEndBuffs(player);

            assertThat(player.hasBuff(BuffType.WEAK.name())).isFalse();
        }

        @Test
        @DisplayName("Poison - should decrease stacks on turn end")
        void processTurnEndBuffs_Poison_ShouldDecreaseStacks() {
            Buff poison = TestDataBuilder.createPoisonDebuff(5, 3);
            buffSystem.applyBuff(player, poison);

            buffSystem.processTurnEndBuffs(player);

            assertThat(player.getBuffStacks(BuffType.POISON.name())).isEqualTo(4);
        }

        @Test
        @DisplayName("Burn - should decrease stacks on turn end")
        void processTurnEndBuffs_Burn_ShouldDecreaseStacks() {
            Buff burn = TestDataBuilder.createBuff(BuffType.BURN, 5, 3);
            burn.setDebuff(true);
            buffSystem.applyBuff(player, burn);

            buffSystem.processTurnEndBuffs(player);

            assertThat(player.getBuffStacks(BuffType.BURN.name())).isEqualTo(4);
        }
    }

    @Nested
    @DisplayName("Stun Check Tests")
    class StunCheckTests {

        @Test
        @DisplayName("Is stunned - should return true for STUN buff")
        void isStunned_WithStun_ShouldReturnTrue() {
            Buff stun = TestDataBuilder.createBuff(BuffType.STUN, 1, 1);
            buffSystem.applyBuff(player, stun);

            assertThat(buffSystem.isStunned(player)).isTrue();
        }

        @Test
        @DisplayName("Is stunned - should return true for CURSE buff")
        void isStunned_WithCurse_ShouldReturnTrue() {
            Buff curse = TestDataBuilder.createBuff(BuffType.CURSE, 1, 1);
            buffSystem.applyBuff(player, curse);

            assertThat(buffSystem.isStunned(player)).isTrue();
        }

        @Test
        @DisplayName("Is stunned - should return false without stun/curse")
        void isStunned_WithoutStun_ShouldReturnFalse() {
            Buff strength = TestDataBuilder.createStrengthBuff(3);
            buffSystem.applyBuff(player, strength);

            assertThat(buffSystem.isStunned(player)).isFalse();
        }
    }

    @Nested
    @DisplayName("Damage Multiplier Tests")
    class DamageMultiplierTests {

        @Test
        @DisplayName("Get effective attack multiplier - Weak should reduce by 25%")
        void getEffectiveAttackMultiplier_Weak_ShouldReduce() {
            Buff weak = TestDataBuilder.createWeakDebuff(2);
            buffSystem.applyBuff(player, weak);

            assertThat(buffSystem.getEffectiveAttackMultiplier(player)).isEqualTo(75);
        }

        @Test
        @DisplayName("Get effective damage multiplier - Vulnerable should increase by 50%")
        void getEffectiveDamageMultiplier_Vulnerable_ShouldIncrease() {
            Buff vulnerable = TestDataBuilder.createVulnerableDebuff(2);
            buffSystem.applyBuff(player, vulnerable);

            assertThat(buffSystem.getEffectiveDamageMultiplier(player)).isEqualTo(150);
        }

        @Test
        @DisplayName("Get strength bonus - should return correct stacks")
        void getStrengthBonus_ShouldReturnStacks() {
            Buff strength = TestDataBuilder.createStrengthBuff(5);
            buffSystem.applyBuff(player, strength);

            assertThat(buffSystem.getStrengthBonus(player)).isEqualTo(5);
        }

        @Test
        @DisplayName("Get dexterity bonus - should return correct stacks")
        void getDexterityBonus_ShouldReturnStacks() {
            Buff dexterity = TestDataBuilder.createBuff(BuffType.DEXTERITY, 3, -1);
            buffSystem.applyBuff(player, dexterity);

            assertThat(buffSystem.getDexterityBonus(player)).isEqualTo(3);
        }
    }
}
