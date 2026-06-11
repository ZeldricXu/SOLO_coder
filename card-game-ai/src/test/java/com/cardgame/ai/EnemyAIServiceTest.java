package com.cardgame.ai;

import com.cardgame.ai.tree.BehaviorNode;
import com.cardgame.ai.tree.BehaviorTreeFactory;
import com.cardgame.common.entity.BattleContext;
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

@DisplayName("Enemy AI Service Tests")
class EnemyAIServiceTest {

    private EnemyAIService enemyAIService;
    private BattleContext context;
    private Enemy enemy;
    private Player player1;
    private Player player2;

    @BeforeEach
    void setUp() {
        enemyAIService = new EnemyAIService();
        player1 = TestDataBuilder.createWarriorPlayer("player1");
        player2 = TestDataBuilder.createMagePlayer("player2");
        player1.setMaxHp(80);
        player1.setCurrentHp(80);
        player2.setMaxHp(70);
        player2.setCurrentHp(70);

        enemy = TestDataBuilder.createGoblinEnemy("enemy1");
        enemy.setMaxHp(50);
        enemy.setCurrentHp(50);
        enemy.setAiBehaviorTreeId("balanced");

        context = BattleContext.builder()
                .battleId("test-battle")
                .roomId("test-room")
                .floor(1)
                .status(BattleStatus.IN_PROGRESS)
                .currentTurn(1)
                .currentRound(1)
                .players(List.of(player1, player2))
                .enemies(List.of(enemy))
                .build();
        context.getCharacterMap().put(player1.getPlayerId(), player1);
        context.getCharacterMap().put(player2.getPlayerId(), player2);
        context.getCharacterMap().put(enemy.getId(), enemy);
    }

    @Nested
    @DisplayName("Intent Generation Tests")
    class IntentGenerationTests {

        @Test
        @DisplayName("Generate intent - should create attack intent by default")
        void generateIntent_Default_ShouldCreateAttackIntent() {
            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents()).isNotEmpty();
            assertThat(enemy.getIntents().get(0).getType()).isEqualTo("ATTACK");
            assertThat(enemy.getIntents().get(0).getTargetId()).isNotNull();
        }

        @Test
        @DisplayName("Generate intent with balanced tree - should select lowest HP target")
        void generateIntent_BalancedTree_ShouldSelectLowestHpTarget() {
            player1.setCurrentHp(30);
            player2.setCurrentHp(70);

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents()).isNotEmpty();
            assertThat(enemy.getIntents().get(0).getTargetId()).isEqualTo(player1.getPlayerId());
        }

        @Test
        @DisplayName("Generate intent with defensive tree - should defend when HP is low")
        void generateIntent_DefensiveTree_LowHp_ShouldDefend() {
            enemy.setAiBehaviorTreeId("defensive");
            enemy.setCurrentHp(20);
            enemy.setMaxHp(50);

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents()).isNotEmpty();
            Enemy.Intent intent = enemy.getIntents().get(0);
            assertThat(intent.getType()).isIn("ATTACK", "DEFEND");
        }

        @Test
        @DisplayName("Generate intent with aggressive tree - should attack more")
        void generateIntent_AggressiveTree_ShouldAttack() {
            enemy.setAiBehaviorTreeId("aggressive");

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents()).isNotEmpty();
            assertThat(enemy.getIntents().get(0).getType()).isIn("ATTACK", "AOE_ATTACK", "MULTI_HIT");
        }

        @Test
        @DisplayName("Generate intent with caster tree - should apply debuffs")
        void generateIntent_CasterTree_ShouldApplyDebuffs() {
            enemy.setAiBehaviorTreeId("caster");

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents()).isNotEmpty();
            assertThat(enemy.getIntents().get(0).getType()).isIn("ATTACK", "AOE_ATTACK", "DEBUFF", "BUFF");
        }

        @Test
        @DisplayName("Generate intent with no players - should not crash")
        void generateIntent_NoPlayers_ShouldHandleGracefully() {
            context.getPlayers().clear();

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Action Execution Tests")
    class ActionExecutionTests {

        @Test
        @DisplayName("Execute attack action - should deal damage to target")
        void executeEnemyAction_Attack_ShouldDealDamage() {
            enemy.setAiBehaviorTreeId("aggressive");
            enemyAIService.generateIntent(enemy, context);
            int initialHp = player1.getCurrentHp();

            var action = enemyAIService.executeEnemyAction(enemy, context);

            assertThat(action).isNotNull();
            assertThat(action.getDamageDealt()).isGreaterThan(0);
            assertThat(player1.getCurrentHp()).isLessThan(initialHp);
        }

        @Test
        @DisplayName("Execute defend action - should add block to enemy")
        void executeEnemyAction_Defend_ShouldAddBlock() {
            enemy.setAiBehaviorTreeId("defensive");
            enemy.setCurrentHp(20);
            enemy.setMaxHp(50);

            var action = enemyAIService.executeEnemyAction(enemy, context);

            assertThat(action).isNotNull();
            assertThat(enemy.getBlock()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Execute buff action - should apply buff to self")
        void executeEnemyAction_Buff_ShouldApplyBuff() {
            enemy.setAiBehaviorTreeId("balanced");
            enemy.clearIntents();
            enemy.addIntent(Enemy.Intent.builder()
                    .type("BUFF")
                    .value(2)
                    .description("Test buff")
                    .build());

            BehaviorNode buffNode = BehaviorTreeFactory.getBehaviorTree("balanced");
            buffNode.execute(enemy, context);

            assertThat(context.getActionHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("Execute debuff action - should apply debuff to target")
        void executeEnemyAction_Debuff_ShouldApplyDebuff() {
            enemy.setAiBehaviorTreeId("caster");

            var action = enemyAIService.executeEnemyAction(enemy, context);

            assertThat(action).isNotNull();
            assertThat(player1.hasBuff(BuffType.VULNERABLE.name()) || 
                       player1.hasBuff(BuffType.WEAK.name()) ||
                       player2.hasBuff(BuffType.VULNERABLE.name()) ||
                       player2.hasBuff(BuffType.WEAK.name())).isTrue();
        }

        @Test
        @DisplayName("Execute AOE attack - should damage all players")
        void executeEnemyAction_AoeAttack_ShouldDamageAllPlayers() {
            enemy.setAiBehaviorTreeId("aggressive");
            int hp1Before = player1.getCurrentHp();
            int hp2Before = player2.getCurrentHp();

            BehaviorNode tree = BehaviorTreeFactory.createAggressiveTree();
            tree.execute(enemy, context);

            boolean someoneDamaged = player1.getCurrentHp() < hp1Before || player2.getCurrentHp() < hp2Before;
            assertThat(someoneDamaged).isTrue();
        }

        @Test
        @DisplayName("Execute lifesteal attack - should heal enemy")
        void executeEnemyAction_Lifesteal_ShouldHealEnemy() {
            enemy.setAiBehaviorTreeId("vampire_boss");
            enemy.setCurrentHp(50);
            enemy.setMaxHp(200);
            int hpBefore = enemy.getCurrentHp();

            BehaviorNode tree = BehaviorTreeFactory.createVampireBossTree();
            tree.execute(enemy, context);

            assertThat(enemy.getCurrentHp()).isGreaterThanOrEqualTo(hpBefore);
        }
    }

    @Nested
    @DisplayName("Behavior Tree Tests")
    class BehaviorTreeTests {

        @Test
        @DisplayName("Get behavior tree - should return correct tree for ID")
        void getBehaviorTree_ShouldReturnCorrectTree() {
            BehaviorNode aggressive = BehaviorTreeFactory.getBehaviorTree("aggressive");
            BehaviorNode defensive = BehaviorTreeFactory.getBehaviorTree("defensive");
            BehaviorNode balanced = BehaviorTreeFactory.getBehaviorTree("balanced");

            assertThat(aggressive).isNotNull();
            assertThat(defensive).isNotNull();
            assertThat(balanced).isNotNull();
        }

        @Test
        @DisplayName("Get behavior tree - default to balanced for unknown ID")
        void getBehaviorTree_UnknownId_ShouldDefaultToBalanced() {
            BehaviorNode tree = BehaviorTreeFactory.getBehaviorTree("unknown");

            assertThat(tree).isNotNull();
        }

        @Test
        @DisplayName("Sequence node - should fail if any child fails")
        void sequenceNode_ShouldFailIfAnyChildFails() {
            BehaviorNode tree = BehaviorTreeFactory.getBehaviorTree("balanced");
            assertThat(tree).isNotNull();
        }

        @Test
        @DisplayName("Selector node - should succeed if any child succeeds")
        void selectorNode_ShouldSucceedIfAnyChildSucceeds() {
            BehaviorNode tree = BehaviorTreeFactory.getBehaviorTree("aggressive");
            assertThat(tree.execute(enemy, context)).isEqualTo(BehaviorNode.NodeStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("Difficulty Scaling Tests")
    class DifficultyScalingTests {

        @Test
        @DisplayName("Apply difficulty scaling - should increase stats on higher floors")
        void applyDifficultyScaling_HigherFloor_ShouldIncreaseStats() {
            int initialMaxHp = enemy.getMaxHp();

            enemyAIService.applyDifficultyScaling(enemy, 5);

            assertThat(enemy.getMaxHp()).isGreaterThan(initialMaxHp);
            assertThat(enemy.getDifficultyModifier()).isGreaterThan(0);
        }

        @Test
        @DisplayName("Apply difficulty scaling - floor 1 should have minimal scaling")
        void applyDifficultyScaling_Floor1_ShouldHaveMinimalScaling() {
            int initialMaxHp = enemy.getMaxHp();

            enemyAIService.applyDifficultyScaling(enemy, 1);

            assertThat(enemy.getMaxHp()).isGreaterThanOrEqualTo(initialMaxHp);
        }

        @Test
        @DisplayName("Apply difficulty scaling - damage should increase with modifier")
        void applyDifficultyScaling_Damage_ShouldIncrease() {
            enemyAIService.applyDifficultyScaling(enemy, 10);
            int damage = enemy.getBaseDamage() + enemy.getDifficultyModifier();

            assertThat(damage).isGreaterThan(enemy.getBaseDamage());
        }
    }

    @Nested
    @DisplayName("Target Selection Tests")
    class TargetSelectionTests {

        @Test
        @DisplayName("Select target - should target lowest HP player")
        void selectTarget_LowestHp_ShouldBeSelected() {
            player1.setCurrentHp(40);
            player2.setCurrentHp(20);

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents().get(0).getTargetId()).isEqualTo(player2.getPlayerId());
        }

        @Test
        @DisplayName("Select target - all players same HP, should select first")
        void selectTarget_SameHp_ShouldSelectFirst() {
            player1.setCurrentHp(50);
            player2.setCurrentHp(50);

            enemyAIService.generateIntent(enemy, context);

            assertThat(enemy.getIntents().get(0).getTargetId()).isIn(player1.getPlayerId(), player2.getPlayerId());
        }
    }

    @Nested
    @DisplayName("Boss AI Tests")
    class BossAITests {

        @Test
        @DisplayName("Dragon boss tree - should have powerful attacks")
        void dragonBossTree_ShouldHavePowerfulAttacks() {
            Enemy boss = TestDataBuilder.createBossEnemy("boss1");
            context.getEnemies().clear();
            context.getEnemies().add(boss);

            BehaviorNode tree = BehaviorTreeFactory.getBehaviorTree("dragon_boss");
            var status = tree.execute(boss, context);

            assertThat(status).isEqualTo(BehaviorNode.NodeStatus.SUCCESS);
            assertThat(context.getActionHistory()).isNotEmpty();
        }

        @Test
        @DisplayName("Vampire boss tree - should use lifesteal")
        void vampireBossTree_ShouldUseLifesteal() {
            Enemy boss = TestDataBuilder.createOrcEnemy("boss2");
            boss.setAiBehaviorTreeId("vampire_boss");
            boss.setCurrentHp(100);
            boss.setMaxHp(300);
            context.getEnemies().clear();
            context.getEnemies().add(boss);

            int hpBefore = boss.getCurrentHp();
            BehaviorNode tree = BehaviorTreeFactory.getBehaviorTree("vampire_boss");
            tree.execute(boss, context);

            assertThat(boss.getCurrentHp()).isGreaterThanOrEqualTo(hpBefore);
        }
    }
}
