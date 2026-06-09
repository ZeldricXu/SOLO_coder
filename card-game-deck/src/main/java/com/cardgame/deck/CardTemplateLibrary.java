package com.cardgame.deck;

import com.cardgame.common.entity.CardTemplate;
import com.cardgame.common.entity.Effect;
import com.cardgame.common.enums.BuffType;
import com.cardgame.common.enums.CardRarity;
import com.cardgame.common.enums.CardType;
import com.cardgame.common.enums.EffectType;
import com.cardgame.common.enums.PlayerClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class CardTemplateLibrary {
    private final Map<String, CardTemplate> templateMap = new HashMap<>();
    private final Map<PlayerClass, List<CardTemplate>> classCards = new HashMap<>();
    private final Map<CardRarity, List<CardTemplate>> rarityCards = new HashMap<>();

    @PostConstruct
    public void init() {
        registerCard(createStrikeCard());
        registerCard(createDefendCard());
        registerCard(createHeavyStrikeCard());
        registerCard(createHealCard());
        registerCard(createFireballCard());
        registerCard(createShieldBashCard());
        registerCard(createPoisonStrikeCard());
        registerCard(createDoubleStrikeCard());
        registerCard(createWeakenCard());
        registerCard(createRegenerationCard());
        registerCard(createBerserkCard());
        registerCard(createLightningBoltCard());
        log.info("Card template library initialized with {} cards", templateMap.size());
    }

    private void registerCard(CardTemplate template) {
        templateMap.put(template.getId(), template);
        classCards.computeIfAbsent(template.getPlayerClass(), k -> new ArrayList<>()).add(template);
        rarityCards.computeIfAbsent(template.getRarity(), k -> new ArrayList<>()).add(template);
    }

    public CardTemplate getTemplate(String templateId) {
        return templateMap.get(templateId);
    }

    public List<CardTemplate> getClassCards(PlayerClass playerClass) {
        return new ArrayList<>(classCards.getOrDefault(playerClass, new ArrayList<>()));
    }

    public List<CardTemplate> getRarityCards(CardRarity rarity) {
        return new ArrayList<>(rarityCards.getOrDefault(rarity, new ArrayList<>()));
    }

    public List<CardTemplate> getStartingDeck(PlayerClass playerClass) {
        List<CardTemplate> deck = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            deck.add(getTemplate("STRIKE_" + playerClass.name()));
        }
        for (int i = 0; i < 4; i++) {
            deck.add(getTemplate("DEFEND_" + playerClass.name()));
        }
        deck.add(getTemplate("HEAVY_STRIKE_" + playerClass.name()));
        return deck;
    }

    public List<CardTemplate> getRandomCards(PlayerClass playerClass, int count, boolean includeBasic) {
        List<CardTemplate> pool = new ArrayList<>();
        for (CardTemplate template : classCards.getOrDefault(playerClass, new ArrayList<>())) {
            if (!includeBasic && template.getRarity() == CardRarity.BASIC) {
                continue;
            }
            pool.add(template);
        }
        java.util.Collections.shuffle(pool);
        return pool.subList(0, Math.min(count, pool.size()));
    }

    private CardTemplate createStrikeCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(6).build());

        List<Effect> upgradedEffects = new ArrayList<>();
        upgradedEffects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(9).build());

        return CardTemplate.builder()
                .id("STRIKE_WARRIOR")
                .name("Strike")
                .description("Deal 6 damage")
                .type(CardType.ATTACK)
                .rarity(CardRarity.BASIC)
                .cost(1)
                .upgradedCost(1)
                .upgradedDescription("Deal 9 damage")
                .playerClass(PlayerClass.WARRIOR)
                .effects(effects)
                .upgradedEffects(upgradedEffects)
                .removable(true)
                .build();
    }

    private CardTemplate createDefendCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.BLOCK).value(5).build());

        List<Effect> upgradedEffects = new ArrayList<>();
        upgradedEffects.add(Effect.builder().type(EffectType.BLOCK).value(8).build());

        return CardTemplate.builder()
                .id("DEFEND_WARRIOR")
                .name("Defend")
                .description("Gain 5 Block")
                .type(CardType.SKILL)
                .rarity(CardRarity.BASIC)
                .cost(1)
                .upgradedCost(1)
                .upgradedDescription("Gain 8 Block")
                .playerClass(PlayerClass.WARRIOR)
                .effects(effects)
                .upgradedEffects(upgradedEffects)
                .removable(true)
                .build();
    }

    private CardTemplate createHeavyStrikeCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(14).build());

        List<Effect> upgradedEffects = new ArrayList<>();
        upgradedEffects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(18).build());

        return CardTemplate.builder()
                .id("HEAVY_STRIKE_WARRIOR")
                .name("Heavy Strike")
                .description("Deal 14 damage")
                .type(CardType.ATTACK)
                .rarity(CardRarity.COMMON)
                .cost(2)
                .upgradedCost(2)
                .upgradedDescription("Deal 18 damage")
                .playerClass(PlayerClass.WARRIOR)
                .effects(effects)
                .upgradedEffects(upgradedEffects)
                .removable(true)
                .build();
    }

    private CardTemplate createHealCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.HEAL).value(8).build());

        List<Effect> upgradedEffects = new ArrayList<>();
        upgradedEffects.add(Effect.builder().type(EffectType.HEAL).value(12).build());

        return CardTemplate.builder()
                .id("HEAL_PRIEST")
                .name("Heal")
                .description("Heal 8 HP")
                .type(CardType.SKILL)
                .rarity(CardRarity.COMMON)
                .cost(1)
                .upgradedCost(1)
                .upgradedDescription("Heal 12 HP")
                .playerClass(PlayerClass.PRIEST)
                .effects(effects)
                .upgradedEffects(upgradedEffects)
                .removable(true)
                .build();
    }

    private CardTemplate createFireballCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(10).build());
        effects.add(Effect.builder().type(EffectType.DEBUFF)
                .buff(com.cardgame.common.entity.Buff.builder()
                        .type(BuffType.BURN).stacks(3).duration(2).isDebuff(true).build())
                .build());

        return CardTemplate.builder()
                .id("FIREBALL_MAGE")
                .name("Fireball")
                .description("Deal 10 damage. Apply 3 Burn.")
                .type(CardType.ATTACK)
                .rarity(CardRarity.COMMON)
                .cost(2)
                .upgradedCost(2)
                .upgradedDescription("Deal 14 damage. Apply 4 Burn.")
                .playerClass(PlayerClass.MAGE)
                .effects(effects)
                .removable(true)
                .build();
    }

    private CardTemplate createShieldBashCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(8).build());
        effects.add(Effect.builder().type(EffectType.BLOCK).value(8).build());

        return CardTemplate.builder()
                .id("SHIELD_BASH_WARRIOR")
                .name("Shield Bash")
                .description("Deal 8 damage. Gain 8 Block.")
                .type(CardType.ATTACK)
                .rarity(CardRarity.UNCOMMON)
                .cost(2)
                .upgradedCost(2)
                .upgradedDescription("Deal 10 damage. Gain 10 Block.")
                .playerClass(PlayerClass.WARRIOR)
                .effects(effects)
                .removable(true)
                .build();
    }

    private CardTemplate createPoisonStrikeCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.SINGLE_DAMAGE).value(5).build());
        effects.add(Effect.builder().type(EffectType.DEBUFF)
                .buff(com.cardgame.common.entity.Buff.builder()
                        .type(BuffType.POISON).stacks(4).duration(3).isDebuff(true).build())
                .build());

        return CardTemplate.builder()
                .id("POISON_STRIKE_ROGUE")
                .name("Poison Strike")
                .description("Deal 5 damage. Apply 4 Poison.")
                .type(CardType.ATTACK)
                .rarity(CardRarity.COMMON)
                .cost(1)
                .upgradedCost(1)
                .upgradedDescription("Deal 7 damage. Apply 6 Poison.")
                .playerClass(PlayerClass.ROGUE)
                .effects(effects)
                .removable(true)
                .build();
    }

    private CardTemplate createDoubleStrikeCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.MULTI_DAMAGE).value(2).params(Map.of("damagePerHit", 5)).build());

        return CardTemplate.builder()
                .id("DOUBLE_STRIKE_ROGUE")
                .name("Double Strike")
                .description("Deal 5 damage twice")
                .type(CardType.ATTACK)
                .rarity(CardRarity.COMMON)
                .cost(1)
                .upgradedCost(1)
                .upgradedDescription("Deal 7 damage twice")
                .playerClass(PlayerClass.ROGUE)
                .effects(effects)
                .removable(true)
                .build();
    }

    private CardTemplate createWeakenCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.DEBUFF)
                .buff(com.cardgame.common.entity.Buff.builder()
                        .type(BuffType.WEAK).stacks(1).duration(2).isDebuff(true).build())
                .build());

        return CardTemplate.builder()
                .id("WEAKEN_MAGE")
                .name("Weaken")
                .description("Apply 2 Weak to target")
                .type(CardType.SKILL)
                .rarity(CardRarity.COMMON)
                .cost(1)
                .upgradedCost(1)
                .upgradedDescription("Apply 3 Weak to target")
                .playerClass(PlayerClass.MAGE)
                .effects(effects)
                .removable(true)
                .build();
    }

    private CardTemplate createRegenerationCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.BUFF)
                .buff(com.cardgame.common.entity.Buff.builder()
                        .type(BuffType.REGEN).stacks(5).duration(3).isDebuff(false).build())
                .build());

        return CardTemplate.builder()
                .id("REGENERATION_PRIEST")
                .name("Regeneration")
                .description("Gain 5 Regen (3 turns)")
                .type(CardType.POWER)
                .rarity(CardRarity.UNCOMMON)
                .cost(1)
                .upgradedCost(0)
                .upgradedDescription("Gain 6 Regen (3 turns)")
                .playerClass(PlayerClass.PRIEST)
                .effects(effects)
                .removable(true)
                .build();
    }

    private CardTemplate createBerserkCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.BUFF)
                .buff(com.cardgame.common.entity.Buff.builder()
                        .type(BuffType.STRENGTH).stacks(2).duration(-1).isDebuff(false).build())
                .build());
        effects.add(Effect.builder().type(EffectType.DEBUFF)
                .buff(com.cardgame.common.entity.Buff.builder()
                        .type(BuffType.VULNERABLE).stacks(1).duration(2).isDebuff(true).build())
                .build());

        return CardTemplate.builder()
                .id("BERSERK_WARRIOR")
                .name("Berserk")
                .description("Gain 2 Strength. Gain 2 Vulnerable.")
                .type(CardType.POWER)
                .rarity(CardRarity.RARE)
                .cost(0)
                .upgradedCost(0)
                .upgradedDescription("Gain 3 Strength. Gain 1 Vulnerable.")
                .playerClass(PlayerClass.WARRIOR)
                .effects(effects)
                .removable(false)
                .build();
    }

    private CardTemplate createLightningBoltCard() {
        List<Effect> effects = new ArrayList<>();
        effects.add(Effect.builder().type(EffectType.AOE_DAMAGE).value(8).build());

        return CardTemplate.builder()
                .id("LIGHTNING_BOLT_MAGE")
                .name("Lightning Bolt")
                .description("Deal 8 damage to all enemies")
                .type(CardType.ATTACK)
                .rarity(CardRarity.UNCOMMON)
                .cost(2)
                .upgradedCost(2)
                .upgradedDescription("Deal 12 damage to all enemies")
                .playerClass(PlayerClass.MAGE)
                .effects(effects)
                .removable(true)
                .build();
    }

    public List<CardTemplate> getAllTemplates() {
        return new ArrayList<>(templateMap.values());
    }
}
