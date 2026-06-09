package com.cardgame.common.entity;

import com.cardgame.common.enums.CardRarity;
import com.cardgame.common.enums.CardType;
import com.cardgame.common.enums.PlayerClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CardTemplate {
    private String id;
    private String name;
    private String description;
    private CardType type;
    private CardRarity rarity;
    private int cost;
    private int upgradedCost;
    private String upgradedDescription;
    private PlayerClass playerClass;
    @Builder.Default
    private List<Effect> effects = new ArrayList<>();
    @Builder.Default
    private List<Effect> upgradedEffects = new ArrayList<>();
    private boolean removable;

    public Card createCardInstance() {
        return Card.builder()
                .id(java.util.UUID.randomUUID().toString())
                .cardTemplateId(this.id)
                .name(this.name)
                .description(this.description)
                .type(this.type)
                .rarity(this.rarity)
                .cost(this.cost)
                .upgradedCost(this.upgradedCost)
                .upgraded(false)
                .level(1)
                .upgradedDescription(this.upgradedDescription)
                .effects(new ArrayList<>(this.effects))
                .build();
    }

    public Card createUpgradedCardInstance() {
        Card card = createCardInstance();
        card.upgrade();
        card.setEffects(new ArrayList<>(this.upgradedEffects));
        return card;
    }
}
