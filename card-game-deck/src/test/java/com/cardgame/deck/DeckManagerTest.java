package com.cardgame.deck;

import com.cardgame.common.TestDataBuilder;
import com.cardgame.common.entity.Card;
import com.cardgame.common.entity.Player;
import com.cardgame.common.enums.PlayerClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("Deck Manager Tests")
class DeckManagerTest {

    @Mock
    private CardTemplateLibrary cardTemplateLibrary;

    @Mock
    private com.cardgame.common.config.GameConfig gameConfig;

    @InjectMocks
    private DeckManager deckManager;

    private Player player;

    @BeforeEach
    void setUp() {
        player = TestDataBuilder.createPlayer("player1", "TestPlayer", PlayerClass.WARRIOR);
        player.setHandLimit(10);
    }

    @Nested
    @DisplayName("Normal Path Tests")
    class NormalPathTests {

        @Test
        @DisplayName("Draw cards from draw pile to hand - should move cards correctly")
        void drawCards_ShouldMoveCardsFromDrawPileToHand() {
            List<Card> startingDeck = TestDataBuilder.createStartingDeck();
            player.getMasterDeck().addAll(startingDeck);
            player.resetForBattle();

            int initialHandSize = player.getCurrentHand().size();
            int initialDrawPileSize = player.getDrawPile().size();

            List<Card> drawnCards = deckManager.drawCards(player, 5);

            assertThat(drawnCards).hasSize(5);
            assertThat(player.getCurrentHand()).hasSize(initialHandSize + 5);
            assertThat(player.getDrawPile()).hasSize(initialDrawPileSize - 5);
            assertThat(player.getCurrentHand()).containsAll(drawnCards);
        }

        @Test
        @DisplayName("Draw cards - should not exceed hand limit")
        void drawCards_ShouldNotExceedHandLimit() {
            List<Card> startingDeck = TestDataBuilder.createStartingDeck();
            player.getMasterDeck().addAll(startingDeck);
            player.resetForBattle();
            player.setHandLimit(5);

            List<Card> drawnCards = deckManager.drawCards(player, 10);

            assertThat(drawnCards).hasSize(5);
            assertThat(player.getCurrentHand()).hasSize(5);
        }

        @Test
        @DisplayName("Discard hand - should move all cards to discard pile")
        void discardHand_ShouldMoveAllCardsToDiscardPile() {
            List<Card> startingDeck = TestDataBuilder.createStartingDeck();
            player.getMasterDeck().addAll(startingDeck);
            player.resetForBattle();
            deckManager.drawCards(player, 5);

            int handSizeBefore = player.getCurrentHand().size();
            int discardSizeBefore = player.getDiscardPile().size();

            player.discardHand();

            assertThat(player.getCurrentHand()).isEmpty();
            assertThat(player.getDiscardPile()).hasSize(discardSizeBefore + handSizeBefore);
        }

        @Test
        @DisplayName("Reshuffle discard pile when draw pile is empty")
        void drawCards_ShouldReshuffleDiscardPileWhenDrawPileIsEmpty() {
            List<Card> startingDeck = TestDataBuilder.createStartingDeck();
            player.getMasterDeck().addAll(startingDeck);
            player.resetForBattle();

            int initialDrawPileSize = player.getDrawPile().size();
            deckManager.drawCards(player, initialDrawPileSize);
            assertThat(player.getDrawPile()).isEmpty();

            player.discardHand();
            assertThat(player.getDiscardPile()).isNotEmpty();
            assertThat(player.getCurrentHand()).isEmpty();

            List<Card> drawnCards = deckManager.drawCards(player, 5);

            assertThat(drawnCards).hasSize(5);
            assertThat(player.getCurrentHand()).hasSize(5);
            assertThat(player.getDiscardPile()).isEmpty();
        }

        @Test
        @DisplayName("Play attack card - should consume energy and move to discard")
        void playCard_AttackCard_ShouldConsumeEnergyAndDiscard() {
            Card strikeCard = TestDataBuilder.createStrikeCard();
            player.getCurrentHand().add(strikeCard);
            player.setCurrentEnergy(3);

            boolean played = deckManager.playCard(player, strikeCard.getCardId(), List.of("enemy1"));

            assertThat(played).isTrue();
            assertThat(player.getCurrentEnergy()).isEqualTo(2);
            assertThat(player.getCurrentHand()).doesNotContain(strikeCard);
            assertThat(player.getDiscardPile()).contains(strikeCard);
        }

        @Test
        @DisplayName("Play power card - should consume energy and move to master deck")
        void playCard_PowerCard_ShouldConsumeEnergyAndAddToMasterDeck() {
            Card powerCard = TestDataBuilder.createBuffCard(com.cardgame.common.enums.BuffType.STRENGTH, 2, -1);
            player.getCurrentHand().add(powerCard);
            player.setCurrentEnergy(3);
            int initialMasterDeckSize = player.getMasterDeck().size();

            boolean played = deckManager.playCard(player, powerCard.getCardId(), List.of());

            assertThat(played).isTrue();
            assertThat(player.getCurrentEnergy()).isEqualTo(2);
            assertThat(player.getCurrentHand()).doesNotContain(powerCard);
            assertThat(player.getMasterDeck()).hasSize(initialMasterDeckSize + 1);
            assertThat(player.getMasterDeck()).contains(powerCard);
        }

        @Test
        @DisplayName("Play card with insufficient energy - should fail")
        void playCard_InsufficientEnergy_ShouldFail() {
            Card strikeCard = TestDataBuilder.createStrikeCard();
            player.getCurrentHand().add(strikeCard);
            player.setCurrentEnergy(0);

            boolean played = deckManager.playCard(player, strikeCard.getCardId(), List.of("enemy1"));

            assertThat(played).isFalse();
            assertThat(player.getCurrentEnergy()).isEqualTo(0);
            assertThat(player.getCurrentHand()).contains(strikeCard);
        }
    }

    @Nested
    @DisplayName("Exception Path Tests")
    class ExceptionPathTests {

        @Test
        @DisplayName("Draw cards when hand is full - overflow cards should go to discard pile")
        void drawCards_HandFull_OverflowGoesToDiscard() {
            List<Card> startingDeck = TestDataBuilder.createStartingDeck();
            player.getMasterDeck().addAll(startingDeck);
            player.resetForBattle();
            player.setHandLimit(5);

            deckManager.drawCards(player, 5);
            assertThat(player.getCurrentHand()).hasSize(5);
            assertThat(player.getDiscardPile()).isEmpty();

            int drawPileSizeBefore = player.getDrawPile().size();
            int discardSizeBefore = player.getDiscardPile().size();

            List<Card> drawnCards = deckManager.drawCards(player, 5);

            assertThat(drawnCards).isEmpty();
            assertThat(player.getCurrentHand()).hasSize(5);
            assertThat(player.getDrawPile()).hasSize(drawPileSizeBefore - 5);
            assertThat(player.getDiscardPile()).hasSize(discardSizeBefore + 5);
        }

        @Test
        @DisplayName("Draw single card when hand is full - should return null and card goes to discard")
        void drawCard_HandFull_ShouldReturnNullAndDiscard() {
            List<Card> startingDeck = TestDataBuilder.createStartingDeck();
            player.getMasterDeck().addAll(startingDeck);
            player.resetForBattle();
            player.setHandLimit(3);

            deckManager.drawCards(player, 3);
            assertThat(player.getCurrentHand()).hasSize(3);

            int discardSizeBefore = player.getDiscardPile().size();

            Card drawnCard = deckManager.drawCard(player);

            assertThat(drawnCard).isNull();
            assertThat(player.getCurrentHand()).hasSize(3);
            assertThat(player.getDiscardPile()).hasSize(discardSizeBefore + 1);
        }

        @Test
        @DisplayName("Play card not in hand - should fail")
        void playCard_CardNotInHand_ShouldFail() {
            Card strikeCard = TestDataBuilder.createStrikeCard();
            player.setCurrentEnergy(3);

            boolean played = deckManager.playCard(player, strikeCard.getCardId(), List.of("enemy1"));

            assertThat(played).isFalse();
            assertThat(player.getCurrentEnergy()).isEqualTo(3);
        }

        @Test
        @DisplayName("Draw from empty piles - should return empty")
        void drawCards_EmptyPiles_ShouldReturnEmpty() {
            player.getDrawPile().clear();
            player.getDiscardPile().clear();

            List<Card> drawnCards = deckManager.drawCards(player, 5);

            assertThat(drawnCards).isEmpty();
            assertThat(player.getCurrentHand()).isEmpty();
        }

        @Test
        @DisplayName("Discard specific card - should move only that card")
        void discardCard_SpecificCard_ShouldMoveOnlyThatCard() {
            Card card1 = TestDataBuilder.createStrikeCard();
            Card card2 = TestDataBuilder.createDefendCard();
            Card card3 = TestDataBuilder.createHealCard();
            player.getCurrentHand().addAll(List.of(card1, card2, card3));

            deckManager.discardCard(player, card2.getCardId());

            assertThat(player.getCurrentHand()).containsExactly(card1, card3);
            assertThat(player.getDiscardPile()).containsExactly(card2);
        }

        @Test
        @DisplayName("Exhaust card - should move to exhaust pile")
        void exhaustCard_ShouldMoveToExhaustPile() {
            Card card = TestDataBuilder.createStrikeCard();
            player.getCurrentHand().add(card);

            deckManager.exhaustCard(player, card.getCardId());

            assertThat(player.getCurrentHand()).doesNotContain(card);
            assertThat(player.getExhaustPile()).contains(card);
            assertThat(player.getDiscardPile()).doesNotContain(card);
        }
    }

    @Nested
    @DisplayName("Card Management Tests")
    class CardManagementTests {

        @Test
        @DisplayName("Add card to deck - should add to master deck")
        void addCardToDeck_ShouldAddToMasterDeck() {
            Card newCard = TestDataBuilder.createStrikeCard();
            int initialSize = player.getMasterDeck().size();

            deckManager.addCardToDeck(player, newCard);

            assertThat(player.getMasterDeck()).hasSize(initialSize + 1);
            assertThat(player.getMasterDeck()).contains(newCard);
            assertThat(newCard.getOwnerId()).isEqualTo(player.getId());
        }

        @Test
        @DisplayName("Upgrade card - should mark as upgraded")
        void upgradeCard_ShouldMarkAsUpgraded() {
            Card card = TestDataBuilder.createStrikeCard();
            player.getMasterDeck().add(card);

            Card upgraded = deckManager.upgradeCard(player, card.getCardId());

            assertThat(upgraded).isNotNull();
            assertThat(upgraded.isUpgraded()).isTrue();
        }

        @Test
        @DisplayName("Upgrade already upgraded card - should return null")
        void upgradeCard_AlreadyUpgraded_ShouldReturnNull() {
            Card card = TestDataBuilder.createStrikeCard();
            card.upgrade();
            player.getMasterDeck().add(card);

            Card upgraded = deckManager.upgradeCard(player, card.getCardId());

            assertThat(upgraded).isNull();
        }

        @Test
        @DisplayName("Find card in hand - should return correct card")
        void findCardInHand_ShouldReturnCard() {
            Card card = TestDataBuilder.createStrikeCard();
            player.getCurrentHand().add(card);

            Card found = deckManager.findCardInHand(player, card.getCardId());

            assertThat(found).isEqualTo(card);
        }

        @Test
        @DisplayName("Find card not in hand - should return null")
        void findCardInHand_NotFound_ShouldReturnNull() {
            Card found = deckManager.findCardInHand(player, "non-existent-id");

            assertThat(found).isNull();
        }
    }
}
