package com.cardgame.common.entity;

import com.cardgame.common.enums.PlayerClass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.ArrayList;
import java.util.List;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Player extends GameCharacter {
    private String playerId;
    private String accountId;
    private PlayerClass playerClass;
    private int gold;
    private int floor;
    @Builder.Default
    private List<Card> masterDeck = new ArrayList<>();
    @Builder.Default
    private List<Card> drawPile = new ArrayList<>();
    @Builder.Default
    private List<Card> discardPile = new ArrayList<>();
    @Builder.Default
    private List<Card> exhaustPile = new ArrayList<>();
    private int handLimit;
    private String roomId;
    private int positionIndex;
    private boolean ready;
    private boolean online;
    private long lastHeartbeat;

    public int getTotalDeckSize() {
        return masterDeck.size();
    }

    public void addCardToDeck(Card card) {
        card.setOwnerId(this.id);
        masterDeck.add(card);
    }

    public boolean removeCardFromDeck(String cardId) {
        return masterDeck.removeIf(card -> card.getId().equals(cardId));
    }

    public Card upgradeCard(String cardId) {
        for (Card card : masterDeck) {
            if (card.getId().equals(cardId) && !card.isUpgraded()) {
                card.upgrade();
                return card;
            }
        }
        return null;
    }

    public void drawCards(int count) {
        for (int i = 0; i < count; i++) {
            if (drawPile.isEmpty()) {
                if (discardPile.isEmpty()) {
                    break;
                }
                reshuffleDiscardPile();
            }
            if (!drawPile.isEmpty()) {
                Card card = drawPile.remove(drawPile.size() - 1);
                if (currentHand.size() >= handLimit) {
                    discardPile.add(card);
                } else {
                    currentHand.add(card);
                }
            }
        }
    }

    public void reshuffleDiscardPile() {
        java.util.Collections.shuffle(discardPile);
        drawPile.addAll(discardPile);
        discardPile.clear();
    }

    public void discardHand() {
        discardPile.addAll(currentHand);
        currentHand.clear();
    }

    public void discardCard(Card card) {
        currentHand.remove(card);
        discardPile.add(card);
    }

    public void exhaustCard(Card card) {
        currentHand.remove(card);
        exhaustPile.add(card);
    }

    public void resetForBattle() {
        drawPile = new ArrayList<>(masterDeck);
        java.util.Collections.shuffle(drawPile);
        discardPile.clear();
        currentHand.clear();
        exhaustPile.clear();
        block = 0;
        currentEnergy = maxEnergy;
        currentHp = maxHp;
    }
}
