package com.cardgame.deck;

import com.cardgame.common.config.GameConfig;
import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.CardTemplate;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.CardRarity;
import com.cardgame.common.enums.PlayerClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Component
public class DeckManager {

    @Autowired
    private CardTemplateLibrary cardTemplateLibrary;

    @Autowired
    private GameConfig gameConfig;

    public void initializeStartingDeck(Player player) {
        PlayerClass playerClass = player.getPlayerClass();
        List<CardTemplate> templates = cardTemplateLibrary.getStartingDeck(playerClass);

        player.getMasterDeck().clear();
        for (CardTemplate template : templates) {
            Card card = template.createCardInstance();
            card.setOwnerId(player.getPlayerId());
            player.getMasterDeck().add(card);
        }

        player.setHandLimit(gameConfig.getMaxHandSize());
        log.info("Initialized starting deck for player {} with {} cards",
                player.getPlayerId(), player.getMasterDeck().size());
    }

    public void prepareForBattle(Player player) {
        player.resetForBattle();
        player.drawCards(gameConfig.getDefaultDrawPerTurn());
    }

    public void startTurn(Player player) {
        player.resetBlock();
        player.resetEnergy();
        player.processTurnStartBuffs();
        player.discardHand();
        player.drawCards(gameConfig.getDefaultDrawPerTurn());
    }

    public void endTurn(Player player) {
        player.processTurnEndBuffs();
        player.discardHand();
    }

    public Card drawCard(Player player) {
        if (player.getDrawPile().isEmpty()) {
            if (player.getDiscardPile().isEmpty()) {
                return null;
            }
            player.reshuffleDiscardPile();
        }
        if (!player.getDrawPile().isEmpty()) {
            Card card = player.getDrawPile().remove(player.getDrawPile().size() - 1);
            if (player.getCurrentHand().size() >= player.getHandLimit()) {
                player.getDiscardPile().add(card);
                return null;
            }
            player.getCurrentHand().add(card);
            return card;
        }
        return null;
    }

    public List<Card> drawCards(Player player, int count) {
        List<Card> drawn = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Card card = drawCard(player);
            if (card != null) {
                drawn.add(card);
            } else {
                break;
            }
        }
        return drawn;
    }

    public boolean playCard(Player player, String cardId, List<String> targetIds) {
        Card card = findCardInHand(player, cardId);
        if (card == null) {
            return false;
        }

        if (!player.useEnergy(card.getCurrentCost())) {
            return false;
        }

        player.getCurrentHand().remove(card);

        if (card.getType() == com.cardgame.common.enums.CardType.POWER) {
            player.getMasterDeck().add(card);
        } else {
            player.getDiscardPile().add(card);
        }

        return true;
    }

    public Card findCardInHand(Player player, String cardId) {
        for (Card card : player.getCurrentHand()) {
            if (card.getId().equals(cardId)) {
                return card;
            }
        }
        return null;
    }

    public void discardCard(Player player, String cardId) {
        Card card = findCardInHand(player, cardId);
        if (card != null) {
            player.discardCard(card);
        }
    }

    public void exhaustCard(Player player, String cardId) {
        Card card = findCardInHand(player, cardId);
        if (card != null) {
            player.exhaustCard(card);
        }
    }

    public Card upgradeCard(Player player, String cardId) {
        Card card = player.upgradeCard(cardId);
        if (card != null) {
            log.info("Upgraded card {} for player {}", cardId, player.getPlayerId());
        }
        return card;
    }

    public boolean removeCard(Player player, String cardId) {
        for (Card card : player.getMasterDeck()) {
            if (card.getId().equals(cardId)) {
                CardTemplate template = cardTemplateLibrary.getTemplate(card.getCardTemplateId());
                if (template == null || template.isRemovable()) {
                    boolean removed = player.removeCardFromDeck(cardId);
                    if (removed) {
                        log.info("Removed card {} from player {}'s deck", cardId, player.getPlayerId());
                    }
                    return removed;
                }
                return false;
            }
        }
        return false;
    }

    public void addCardToDeck(Player player, Card card) {
        player.addCardToDeck(card);
        log.info("Added card {} to player {}'s deck", card.getName(), player.getPlayerId());
    }

    public Card createCardFromTemplate(String templateId, boolean upgraded) {
        CardTemplate template = cardTemplateLibrary.getTemplate(templateId);
        if (template == null) {
            return null;
        }
        return upgraded ? template.createUpgradedCardInstance() : template.createCardInstance();
    }

    public List<Card> getCardRewardChoices(Player player, int count) {
        List<CardTemplate> templates = cardTemplateLibrary.getRandomCards(
                player.getPlayerClass(), count, false);
        List<Card> cards = new ArrayList<>();
        for (CardTemplate template : templates) {
            cards.add(template.createCardInstance());
        }
        return cards;
    }

    public List<Card> getShopCards(Player player, int count) {
        List<Card> cards = new ArrayList<>();
        List<CardTemplate> commons = cardTemplateLibrary.getRarityCards(CardRarity.COMMON);
        List<CardTemplate> uncommons = cardTemplateLibrary.getRarityCards(CardRarity.UNCOMMON);
        List<CardTemplate> rares = cardTemplateLibrary.getRarityCards(CardRarity.RARE);

        java.util.Collections.shuffle(commons);
        java.util.Collections.shuffle(uncommons);
        java.util.Collections.shuffle(rares);

        int commonCount = (int) (count * 0.5);
        int uncommonCount = (int) (count * 0.35);
        int rareCount = count - commonCount - uncommonCount;

        for (int i = 0; i < Math.min(commonCount, commons.size()); i++) {
            cards.add(commons.get(i).createCardInstance());
        }
        for (int i = 0; i < Math.min(uncommonCount, uncommons.size()); i++) {
            cards.add(uncommons.get(i).createCardInstance());
        }
        for (int i = 0; i < Math.min(rareCount, rares.size()); i++) {
            cards.add(rares.get(i).createCardInstance());
        }

        return cards;
    }

    public int getCardPrice(Card card) {
        CardRarity rarity = card.getRarity();
        int basePrice = switch (rarity) {
            case BASIC -> 30;
            case COMMON -> 50;
            case UNCOMMON -> 75;
            case RARE -> 150;
            case LEGENDARY -> 300;
        };
        if (card.isUpgraded()) {
            basePrice = (int) (basePrice * 1.5);
        }
        return basePrice;
    }

    public int getRemoveCardPrice() {
        return 75;
    }

    public int getUpgradeCardPrice() {
        return 50;
    }

    public List<Card> getUpgradableCards(Player player) {
        return player.getMasterDeck().stream()
                .filter(card -> !card.isUpgraded())
                .collect(Collectors.toList());
    }

    public List<Card> getRemovableCards(Player player) {
        return player.getMasterDeck().stream()
                .filter(card -> {
                    CardTemplate template = cardTemplateLibrary.getTemplate(card.getCardTemplateId());
                    return template == null || template.isRemovable();
                })
                .collect(Collectors.toList());
    }

    public void shuffleDeck(Player player) {
        java.util.Collections.shuffle(player.getDrawPile());
    }
}
