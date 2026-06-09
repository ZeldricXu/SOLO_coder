package com.cardgame.common.entity;

import com.cardgame.common.enums.CardRarity;
import com.cardgame.common.enums.CardType;
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
public class Card {
    private String id;
    private String cardTemplateId;
    private String name;
    private String description;
    private CardType type;
    private CardRarity rarity;
    private int cost;
    private int upgradedCost;
    private boolean upgraded;
    private int level;
    private String ownerId;
    @Builder.Default
    private List<Effect> effects = new ArrayList<>();
    private String upgradedDescription;

    public int getCurrentCost() {
        return upgraded ? upgradedCost : cost;
    }

    public String getCurrentDescription() {
        return upgraded ? upgradedDescription : description;
    }

    public void upgrade() {
        if (!upgraded) {
            this.upgraded = true;
            this.level++;
        }
    }

    public Card copy() {
        Card copy = Card.builder()
                .id(java.util.UUID.randomUUID().toString())
                .cardTemplateId(this.cardTemplateId)
                .name(this.name)
                .description(this.description)
                .type(this.type)
                .rarity(this.rarity)
                .cost(this.cost)
                .upgradedCost(this.upgradedCost)
                .upgraded(this.upgraded)
                .level(this.level)
                .upgradedDescription(this.upgradedDescription)
                .build();
        copy.setEffects(new ArrayList<>(this.effects));
        return copy;
    }
}
