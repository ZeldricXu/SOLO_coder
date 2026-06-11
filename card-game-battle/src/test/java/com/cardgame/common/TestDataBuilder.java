package com.cardgame.common;

import com.cardgame.common.entity.*;
import com.cardgame.common.enums.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class TestDataBuilder {

    public static Player createPlayer(String playerId, String name, PlayerClass playerClass) {
        Player player = Player.builder()
                .id(playerId)
                .playerId(playerId)
                .name(name)
                .playerClass(playerClass)
                .maxHp(80)
                .currentHp(80)
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

    public static Player createWarriorPlayer(String playerId) {
        return createPlayer(playerId, "Warrior", PlayerClass.WARRIOR);
    }

    public static Player createMagePlayer(String playerId) {
        return createPlayer(playerId, "Mage", PlayerClass.MAGE);
    }

    public static Player createRoguePlayer(String playerId) {
        return createPlayer(playerId, "Rogue", PlayerClass.ROGUE);
    }

    public static Player createPriestPlayer(String playerId) {
        return createPlayer(playerId, "Priest", PlayerClass.PRIEST);
    }

    public static Enemy createEnemy(String enemyId, String name, EnemyType type, int hp, int speed, int damage) {
        Enemy enemy = Enemy.builder()
                .id(enemyId)
                .name(name)
                .enemyType(type)
                .maxHp(hp)
                .currentHp(hp)
                .block(0)
                .speed(speed)
                .baseSpeed(speed)
                .maxEnergy(0)
                .currentEnergy(0)
                .difficultyModifier(0)
                .floorLevel(1)
                .buffs(new HashMap<>())
                .intents(new ArrayList<>())
                .aiBehaviorTreeId("balanced")
                .experienceReward(50)
                .goldReward(25)
                .rewardCards(new ArrayList<>())
                .build();
        return enemy;
    }

    public static Enemy createSlimeEnemy(String enemyId) {
        return createEnemy(enemyId, "Slime", EnemyType.NORMAL, 30, 5, 6);
    }

    public static Enemy createGoblinEnemy(String enemyId) {
        return createEnemy(enemyId, "Goblin", EnemyType.NORMAL, 40, 8, 8);
    }

    public static Enemy createSkeletonEnemy(String enemyId) {
        return createEnemy(enemyId, "Skeleton", EnemyType.NORMAL, 50, 6, 10);
    }

    public static Enemy createOrcEnemy(String enemyId) {
        return createEnemy(enemyId, "Orc", EnemyType.ELITE, 80, 7, 15);
    }

    public static Enemy createBossEnemy(String enemyId) {
        Enemy boss = createEnemy(enemyId, "Dragon", EnemyType.BOSS, 350, 8, 30);
        boss.setAiBehaviorTreeId("dragon_boss");
        boss.setDifficultyModifier(5);
        return boss;
    }

    public static Card createCard(String templateId, String name, CardType type, int cost, int damage, int block) {
        return Card.builder()
                .id(UUID.randomUUID().toString())
                .cardTemplateId(templateId)
                .name(name)
                .type(type)
                .rarity(CardRarity.COMMON)
                .cost(cost)
                .upgraded(false)
                .description("Test card")
                .effects(new ArrayList<>())
                .build();
    }

    public static Card createStrikeCard() {
        Card card = createCard("strike", "Strike", CardType.ATTACK, 1, 6, 0);
        card.getEffects().add(Effect.builder()
                .effectType(EffectType.DAMAGE)
                .value(6)
                .targetType("SINGLE_ENEMY")
                .build());
        return card;
    }

    public static Card createDefendCard() {
        Card card = createCard("defend", "Defend", CardType.SKILL, 1, 0, 5);
        card.getEffects().add(Effect.builder()
                .effectType(EffectType.BLOCK)
                .value(5)
                .targetType("SELF")
                .build());
        return card;
    }

    public static Card createHealCard() {
        Card card = createCard("heal", "Heal", CardType.SKILL, 1, 0, 0);
        card.getEffects().add(Effect.builder()
                .effectType(EffectType.HEAL)
                .value(8)
                .targetType("SELF")
                .build());
        return card;
    }

    public static Card createBuffCard(BuffType buffType, int stacks, int duration) {
        Card card = createCard("buff_" + buffType.name().toLowerCase(), "Buff Card", CardType.POWER, 1, 0, 0);
        card.getEffects().add(Effect.builder()
                .effectType(EffectType.BUFF)
                .value(stacks)
                .buffType(buffType)
                .duration(duration)
                .targetType("SELF")
                .build());
        return card;
    }

    public static Card createDebuffCard(BuffType debuffType, int stacks, int duration) {
        Card card = createCard("debuff_" + debuffType.name().toLowerCase(), "Debuff Card", CardType.SKILL, 1, 0, 0);
        card.getEffects().add(Effect.builder()
                .effectType(EffectType.DEBUFF)
                .value(stacks)
                .buffType(debuffType)
                .targetType("SINGLE_ENEMY")
                .duration(duration)
                .build());
        return card;
    }

    public static List<Card> createStartingDeck() {
        List<Card> deck = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            deck.add(createStrikeCard());
        }
        for (int i = 0; i < 5; i++) {
            deck.add(createDefendCard());
        }
        return deck;
    }

    public static Buff createBuff(BuffType type, int stacks, int duration) {
        return Buff.builder()
                .type(type)
                .stacks(stacks)
                .duration(duration)
                .sourceId("test")
                .isDebuff(type == BuffType.WEAK || type == BuffType.VULNERABLE ||
                        type == BuffType.POISON || type == BuffType.BURN || type == BuffType.FRAIL)
                .build();
    }

    public static Buff createStrengthBuff(int stacks) {
        return createBuff(BuffType.STRENGTH, stacks, -1);
    }

    public static Buff createWeakDebuff(int duration) {
        return createBuff(BuffType.WEAK, 1, duration);
    }

    public static Buff createVulnerableDebuff(int duration) {
        return createBuff(BuffType.VULNERABLE, 1, duration);
    }

    public static Buff createPoisonDebuff(int stacks, int duration) {
        return createBuff(BuffType.POISON, stacks, duration);
    }

    public static List<Player> createMultiplePlayers(int count) {
        List<Player> players = new ArrayList<>();
        PlayerClass[] classes = PlayerClass.values();
        for (int i = 0; i < count; i++) {
            players.add(createPlayer("player_" + i, "Player" + i, classes[i % classes.length]));
        }
        return players;
    }

    public static List<Enemy> createMultipleEnemies(int count) {
        List<Enemy> enemies = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (i == 0) {
                enemies.add(createSlimeEnemy("enemy_" + i));
            } else if (i == 1) {
                enemies.add(createGoblinEnemy("enemy_" + i));
            } else {
                enemies.add(createSkeletonEnemy("enemy_" + i));
            }
        }
        return enemies;
    }
}
