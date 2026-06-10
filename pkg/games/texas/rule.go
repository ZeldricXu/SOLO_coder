package texas

import (
	"sort"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type TexasRule struct {
	game.BaseRule
}

func init() {
	game.RegisterRule(&TexasRule{})
}

func (r *TexasRule) GameType() common.GameType {
	return common.GameTypeTexas
}

func (r *TexasRule) Name() string {
	return "texas_holdem"
}

func (r *TexasRule) InitDeck() []common.Card {
	cards := make([]common.Card, 0, 52)
	idx := 0
	for suit := 1; suit <= 4; suit++ {
		for rank := 2; rank <= 14; rank++ {
			cards = append(cards, common.Card{Suit: suit, Rank: rank, Index: idx})
			idx++
		}
	}
	return cards
}

func (r *TexasRule) GetShuffleStrategy() game.ShuffleStrategy {
	return game.GetShuffleStrategy("random")
}

type TexasPatternValidator struct{}

func (v *TexasPatternValidator) Validate(cards []common.Card) game.PatternResult {
	if len(cards) < 5 {
		return game.PatternResult{Pattern: game.PatternUnknown, Cards: cards, Valid: false}
	}

	best := game.PatternResult{Pattern: game.PatternSingle, Weight: 0, Cards: cards, Valid: true}

	rankCount := make(map[int]int)
	suitCount := make(map[int]int)
	ranks := make([]int, 0, len(cards))

	for _, c := range cards {
		rankCount[c.Rank]++
		suitCount[c.Suit]++
		ranks = append(ranks, c.Rank)
	}
	sort.Sort(sort.Reverse(sort.IntSlice(ranks)))

	isFlush := false
	flushSuit := 0
	for suit, count := range suitCount {
		if count >= 5 {
			isFlush = true
			flushSuit = suit
			break
		}
	}

	sort.Ints(ranks)
	isStraight := false
	straightHigh := 0
	if len(ranks) >= 5 {
		for i := len(ranks) - 1; i >= 4; i-- {
			if ranks[i]-ranks[i-4] == 4 {
				isStraight = true
				straightHigh = ranks[i]
				break
			}
		}
	}

	if isFlush && isStraight {
		best = game.PatternResult{Pattern: game.PatternStraightFlush, Weight: 800 + straightHigh, Cards: cards, Valid: true}
	} else {
		for rank, count := range rankCount {
			if count == 4 {
				best = game.PatternResult{Pattern: game.PatternFourKind, Weight: 700 + rank, Cards: cards, Valid: true}
			}
		}

		hasTriplet := false
		tripletRank := 0
		hasPair := false
		pairRank := 0
		for rank, count := range rankCount {
			if count == 3 && rank > tripletRank {
				hasTriplet = true
				tripletRank = rank
			}
			if count == 2 && rank > pairRank {
				hasPair = true
				pairRank = rank
			}
		}

		if hasTriplet && hasPair {
			best = game.PatternResult{Pattern: game.PatternFullHouse, Weight: 600 + tripletRank*10 + pairRank, Cards: cards, Valid: true}
		} else if isFlush {
			_ = flushSuit
			best = game.PatternResult{Pattern: game.PatternFlush, Weight: 500, Cards: cards, Valid: true}
		} else if isStraight {
			best = game.PatternResult{Pattern: game.PatternStraight, Weight: 400 + straightHigh, Cards: cards, Valid: true}
		} else if hasTriplet {
			best = game.PatternResult{Pattern: game.PatternTriplet, Weight: 300 + tripletRank, Cards: cards, Valid: true}
		} else if hasPair {
			best = game.PatternResult{Pattern: game.PatternPair, Weight: 100 + pairRank, Cards: cards, Valid: true}
		} else if len(ranks) > 0 {
			best = game.PatternResult{Pattern: game.PatternSingle, Weight: ranks[len(ranks)-1], Cards: cards, Valid: true}
		}
	}

	_ = best
	return best
}

func (v *TexasPatternValidator) Compare(a, b game.PatternResult) int {
	if a.Weight > b.Weight {
		return 1
	} else if a.Weight < b.Weight {
		return -1
	}
	return 0
}

func (r *TexasRule) GetPatternValidator() game.CardPatternValidator {
	return &TexasPatternValidator{}
}

type TexasSettlement struct{}

func (s *TexasSettlement) Name() string {
	return "texas_settlement"
}

func (s *TexasSettlement) Calculate(ctx *game.GameContext, config *common.RoomConfig) (*game.Settlement, error) {
	pot := int64(0)
	for _, bet := range ctx.PlayerBets {
		pot += bet
	}
	results := make([]game.SettleResult, 0, len(ctx.Players))
	for i, p := range ctx.Players {
		score := int64(0)
		isWinner := i == 0
		if isWinner {
			score = pot
		} else {
			score = -ctx.PlayerBets[p.UserID]
		}
		results = append(results, game.SettleResult{
			UserID:   p.UserID,
			Score:    score,
			Rank:     i + 1,
			IsWinner: isWinner,
		})
	}
	return &game.Settlement{
		Results:   results,
		Timestamp: common.NowMs(),
		Round:     ctx.Round,
	}, nil
}

func (r *TexasRule) GetSettlementStrategy() game.SettlementStrategy {
	return &TexasSettlement{}
}

func (r *TexasRule) DealCards(ctx *game.GameContext, config *common.RoomConfig) error {
	deckIdx := 0
	for _, p := range ctx.Players {
		hand := make([]common.Card, 2)
		hand[0] = ctx.Deck[deckIdx]
		hand[1] = ctx.Deck[deckIdx+1]
		deckIdx += 2
		ctx.PlayerHands[p.UserID] = hand
	}
	ctx.PublicCards = make([]common.Card, 0, 5)
	ctx.ExtraData["stage"] = "preflop"
	return nil
}

func (r *TexasRule) ValidateAction(ctx *game.GameContext, action *common.GameAction) error {
	switch action.ActionType {
	case common.ActionCall, common.ActionRaise, common.ActionFold, common.ActionCheck:
		return nil
	default:
		return common.ErrInvalidAction
	}
}

func (r *TexasRule) ApplyAction(ctx *game.GameContext, action *common.GameAction) (*common.GameAction, error) {
	switch action.ActionType {
	case common.ActionCall, common.ActionRaise:
		if amount, ok := action.Data["amount"].(float64); ok {
			ctx.PlayerBets[action.UserID] += int64(amount)
		}
	}
	ctx.LastAction = action
	return action, nil
}

func (r *TexasRule) IsRoundOver(ctx *game.GameContext) bool {
	return len(ctx.PublicCards) >= 5
}

func (r *TexasRule) IsGameOver(ctx *game.GameContext) bool {
	return ctx.Round >= 1
}

func (r *TexasRule) GetAutoAction(ctx *game.GameContext, userID common.UserID) (*common.GameAction, error) {
	return &common.GameAction{
		ActionID:   common.GenerateID(),
		UserID:     userID,
		ActionType: common.ActionFold,
		Data:       make(map[string]interface{}),
		Timestamp:  time.Now(),
	}, nil
}

func (r *TexasRule) GetTurnTimeout(ctx *game.GameContext) int {
	return 30
}
