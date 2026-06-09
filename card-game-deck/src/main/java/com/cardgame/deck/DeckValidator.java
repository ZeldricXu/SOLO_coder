package com.cardgame.deck;

import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Player;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
public class DeckValidator {

    public boolean validateDeck(Player player) {
        List<Card> deck = player.getMasterDeck();
        if (deck.isEmpty()) {
            log.warn("Player {} has empty deck", player.getPlayerId());
            return false;
        }

        if (deck.size() < 5) {
            log.warn("Player {}'s deck is too small: {} cards", player.getPlayerId(), deck.size());
            return false;
        }

        Set<String> cardIds = new HashSet<>();
        for (Card card : deck) {
            if (cardIds.contains(card.getId())) {
                log.warn("Duplicate card id {} in player {}'s deck", card.getId(), player.getPlayerId());
                return false;
            }
            cardIds.add(card.getId());

            if (card.getOwnerId() != null && !card.getOwnerId().equals(player.getPlayerId())) {
                log.warn("Card {} has wrong owner in player {}'s deck", card.getId(), player.getPlayerId());
                return false;
            }
        }

        return true;
    }

    public boolean canPlayCard(Player player, Card card) {
        if (card == null) {
            return false;
        }

        if (!player.getCurrentHand().contains(card)) {
            return false;
        }

        return player.getCurrentEnergy() >= card.getCurrentCost();
    }

    public boolean canDrawCard(Player player) {
        if (player.getCurrentHand().size() >= player.getHandLimit()) {
            return false;
        }
        return !player.getDrawPile().isEmpty() || !player.getDiscardPile().isEmpty();
    }

    public boolean hasCardInHand(Player player, String cardId) {
        for (Card card : player.getCurrentHand()) {
            if (card.getId().equals(cardId)) {
                return true;
            }
        }
        return false;
    }

    public int getCardCount(Player player) {
        return player.getMasterDeck().size();
    }

    public int getUpgradedCardCount(Player player) {
        return (int) player.getMasterDeck().stream().filter(Card::isUpgraded).count();
    }

    public int getAttackCardCount(Player player) {
        return (int) player.getMasterDeck().stream()
                .filter(c -> c.getType() == com.cardgame.common.enums.CardType.ATTACK)
                .count();
    }

    public int getSkillCardCount(Player player) {
        return (int) player.getMasterDeck().stream()
                .filter(c -> c.getType() == com.cardgame.common.enums.CardType.SKILL)
                .count();
    }

    public int getPowerCardCount(Player player) {
        return (int) player.getMasterDeck().stream()
                .filter(c -> c.getType() == com.cardgame.common.enums.CardType.POWER)
                .count();
    }

    public double getAverageCardCost(Player player) {
        if (player.getMasterDeck().isEmpty()) {
            return 0;
        }
        int totalCost = player.getMasterDeck().stream()
                .mapToInt(Card::getCurrentCost)
                .sum();
        return (double) totalCost / player.getMasterDeck().size();
    }
}
