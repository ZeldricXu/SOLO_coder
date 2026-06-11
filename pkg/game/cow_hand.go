package game

import (
	"sync"

	"github.com/studio/gameroom/pkg/common"
)

type ImmutableHand struct {
	cards     []common.Card
	ownerID   common.UserID
	version   int
	mu        sync.RWMutex
	shared    bool
}

type CowHandManager struct {
	hands    map[common.UserID]*ImmutableHand
	mu       sync.RWMutex
}

func NewCowHandManager() *CowHandManager {
	return &CowHandManager{
		hands: make(map[common.UserID]*ImmutableHand),
	}
}

func (chm *CowHandManager) InitHand(userID common.UserID, cards []common.Card) {
	chm.mu.Lock()
	defer chm.mu.Unlock()

	chm.hands[userID] = &ImmutableHand{
		cards:   cards,
		ownerID: userID,
		version: 0,
		shared:  false,
	}
}

func (chm *CowHandManager) SetHand(userID common.UserID, cards []common.Card) {
	chm.mu.Lock()
	defer chm.mu.Unlock()

	old, ok := chm.hands[userID]
	nextVersion := 0
	if ok {
		nextVersion = old.version + 1
	}

	chm.hands[userID] = &ImmutableHand{
		cards:   cards,
		ownerID: userID,
		version: nextVersion,
		shared:  false,
	}
}

func (chm *CowHandManager) GetReadOnly(userID common.UserID) []common.Card {
	chm.mu.RLock()
	hand, ok := chm.hands[userID]
	chm.mu.RUnlock()

	if !ok {
		return nil
	}

	hand.mu.RLock()
	defer hand.mu.RUnlock()

	hand.shared = true
	return hand.cards
}

func (chm *CowHandManager) GetSlice(userID common.UserID, start, end int) []common.Card {
	chm.mu.RLock()
	hand, ok := chm.hands[userID]
	chm.mu.RUnlock()

	if !ok {
		return nil
	}

	hand.mu.RLock()
	defer hand.mu.RUnlock()

	if start < 0 {
		start = 0
	}
	if end > len(hand.cards) || end < 0 {
		end = len(hand.cards)
	}
	if start >= end {
		return nil
	}

	return hand.cards[start:end]
}

func (chm *CowHandManager) GetCard(userID common.UserID, idx int) (common.Card, bool) {
	chm.mu.RLock()
	hand, ok := chm.hands[userID]
	chm.mu.RUnlock()

	if !ok {
		return common.Card{}, false
	}

	hand.mu.RLock()
	defer hand.mu.RUnlock()

	if idx < 0 || idx >= len(hand.cards) {
		return common.Card{}, false
	}
	return hand.cards[idx], true
}

func (chm *CowHandManager) GetLen(userID common.UserID) int {
	chm.mu.RLock()
	hand, ok := chm.hands[userID]
	chm.mu.RUnlock()

	if !ok {
		return 0
	}

	hand.mu.RLock()
	defer hand.mu.RUnlock()

	return len(hand.cards)
}

func (chm *CowHandManager) ContainsCard(userID common.UserID, card common.Card) bool {
	handCards := chm.GetReadOnly(userID)
	if handCards == nil {
		return false
	}

	for _, c := range handCards {
		if c.Suit == card.Suit && c.Rank == card.Rank {
			return true
		}
	}
	return false
}

func (chm *CowHandManager) ContainsCards(userID common.UserID, cards []common.Card) bool {
	if len(cards) == 0 {
		return true
	}

	handCards := chm.GetReadOnly(userID)
	if handCards == nil || len(handCards) < len(cards) {
		return false
	}

	countMap := make(map[int]int)
	for _, c := range handCards {
		key := c.Suit*100 + c.Rank
		countMap[key]++
	}

	for _, c := range cards {
		key := c.Suit*100 + c.Rank
		countMap[key]--
		if countMap[key] < 0 {
			return false
		}
	}

	return true
}

func (chm *CowHandManager) MutateForWrite(userID common.UserID) (*ImmutableHand, []common.Card) {
	chm.mu.Lock()
	hand, ok := chm.hands[userID]
	chm.mu.Unlock()

	if !ok {
		return nil, nil
	}

	hand.mu.Lock()
	defer hand.mu.Unlock()

	var newCards []common.Card
	if hand.shared {
		newCards = make([]common.Card, len(hand.cards))
		copy(newCards, hand.cards)
	} else {
		newCards = hand.cards
	}

	newHand := &ImmutableHand{
		cards:   newCards,
		ownerID: userID,
		version: hand.version + 1,
		shared:  false,
	}

	chm.mu.Lock()
	chm.hands[userID] = newHand
	chm.mu.Unlock()

	return newHand, newCards
}

func (chm *CowHandManager) RemoveCards(userID common.UserID, toRemove []common.Card) error {
	if len(toRemove) == 0 {
		return nil
	}

	if !chm.ContainsCards(userID, toRemove) {
		return common.ErrInvalidAction
	}

	_, newCards := chm.MutateForWrite(userID)
	if newCards == nil {
		return common.ErrPlayerNotFound
	}

	removeSet := make(map[int]int)
	for _, c := range toRemove {
		key := c.Suit*100 + c.Rank
		removeSet[key]++
	}

	result := make([]common.Card, 0, len(newCards)-len(toRemove))
	for _, c := range newCards {
		key := c.Suit*100 + c.Rank
		if removeSet[key] > 0 {
			removeSet[key]--
			continue
		}
		result = append(result, c)
	}

	chm.mu.Lock()
	hand, ok := chm.hands[userID]
	if ok {
		hand.mu.Lock()
		hand.cards = result
		hand.version++
		hand.shared = false
		hand.mu.Unlock()
	}
	chm.mu.Unlock()

	return nil
}

func (chm *CowHandManager) AddCard(userID common.UserID, card common.Card) error {
	_, newCards := chm.MutateForWrite(userID)
	if newCards == nil {
		return common.ErrPlayerNotFound
	}

	newCards = append(newCards, card)

	chm.mu.Lock()
	hand, ok := chm.hands[userID]
	if ok {
		hand.mu.Lock()
		hand.cards = newCards
		hand.version++
		hand.shared = false
		hand.mu.Unlock()
	}
	chm.mu.Unlock()

	return nil
}

func (chm *CowHandManager) AddCards(userID common.UserID, cards []common.Card) error {
	if len(cards) == 0 {
		return nil
	}

	_, newCards := chm.MutateForWrite(userID)
	if newCards == nil {
		return common.ErrPlayerNotFound
	}

	newCards = append(newCards, cards...)

	chm.mu.Lock()
	hand, ok := chm.hands[userID]
	if ok {
		hand.mu.Lock()
		hand.cards = newCards
		hand.version++
		hand.shared = false
		hand.mu.Unlock()
	}
	chm.mu.Unlock()

	return nil
}

func (chm *CowHandManager) GetVersion(userID common.UserID) int {
	chm.mu.RLock()
	hand, ok := chm.hands[userID]
	chm.mu.RUnlock()

	if !ok {
		return -1
	}

	hand.mu.RLock()
	defer hand.mu.RUnlock()
	return hand.version
}

func (chm *CowHandManager) RemoveUser(userID common.UserID) {
	chm.mu.Lock()
	defer chm.mu.Unlock()
	delete(chm.hands, userID)
}

func (chm *CowHandManager) Snapshot(userID common.UserID) []common.Card {
	handCards := chm.GetReadOnly(userID)
	if handCards == nil {
		return nil
	}

	snapshot := make([]common.Card, len(handCards))
	copy(snapshot, handCards)
	return snapshot
}

func (chm *CowHandManager) SnapshotAll() map[common.UserID][]common.Card {
	chm.mu.RLock()
	defer chm.mu.RUnlock()

	result := make(map[common.UserID][]common.Card, len(chm.hands))
	for uid, hand := range chm.hands {
		hand.mu.RLock()
		snapshot := make([]common.Card, len(hand.cards))
		copy(snapshot, hand.cards)
		hand.mu.RUnlock()
		result[uid] = snapshot
	}
	return result
}

func (chm *CowHandManager) Count() int {
	chm.mu.RLock()
	defer chm.mu.RUnlock()
	return len(chm.hands)
}
