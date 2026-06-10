package landlord

import (
	"sort"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type LandlordRule struct {
	game.BaseRule
}

func init() {
	game.RegisterRule(&LandlordRule{})
}

func (r *LandlordRule) GameType() common.GameType {
	return common.GameTypeLandlord
}

func (r *LandlordRule) Name() string {
	return "standard_landlord"
}

func (r *LandlordRule) InitDeck() []common.Card {
	cards := make([]common.Card, 0, 54)
	idx := 0
	for suit := 1; suit <= 4; suit++ {
		for rank := 1; rank <= 13; rank++ {
			cards = append(cards, common.Card{Suit: suit, Rank: rank, Index: idx})
			idx++
		}
	}
	cards = append(cards, common.Card{Suit: 5, Rank: 14, Index: idx})
	idx++
	cards = append(cards, common.Card{Suit: 5, Rank: 15, Index: idx})
	return cards
}

func (r *LandlordRule) GetShuffleStrategy() game.ShuffleStrategy {
	return game.GetShuffleStrategy("random")
}

type LandlordPatternValidator struct{}

func (v *LandlordPatternValidator) Validate(cards []common.Card) game.PatternResult {
	if len(cards) == 0 {
		return game.PatternResult{Valid: false}
	}
	if len(cards) == 1 {
		return game.PatternResult{
			Pattern: game.PatternSingle,
			Weight:  cards[0].Rank,
			Cards:   cards,
			Valid:   true,
		}
	}
	if len(cards) == 2 {
		if cards[0].Rank == cards[1].Rank {
			return game.PatternResult{
				Pattern: game.PatternPair,
				Weight:  cards[0].Rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		if (cards[0].Rank == 14 && cards[1].Rank == 15) ||
			(cards[0].Rank == 15 && cards[1].Rank == 14) {
			return game.PatternResult{
				Pattern: game.PatternBomb,
				Weight:  100,
				Cards:   cards,
				Valid:   true,
			}
		}
	}
	if len(cards) == 3 {
		if cards[0].Rank == cards[1].Rank && cards[1].Rank == cards[2].Rank {
			return game.PatternResult{
				Pattern: game.PatternTriplet,
				Weight:  cards[0].Rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}
	if len(cards) == 4 {
		if cards[0].Rank == cards[1].Rank && cards[1].Rank == cards[2].Rank &&
			cards[2].Rank == cards[3].Rank {
			return game.PatternResult{
				Pattern: game.PatternBomb,
				Weight:  50 + cards[0].Rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}

	sorted := make([]common.Card, len(cards))
	copy(sorted, cards)
	sort.Slice(sorted, func(i, j int) bool {
		return sorted[i].Rank < sorted[j].Rank
	})
	isStraight := true
	for i := 1; i < len(sorted); i++ {
		if sorted[i].Rank != sorted[i-1].Rank+1 || sorted[i].Rank >= 15 {
			isStraight = false
			break
		}
	}
	if isStraight && len(sorted) >= 5 {
		return game.PatternResult{
			Pattern: game.PatternStraight,
			Weight:  sorted[len(sorted)-1].Rank,
			Cards:   sorted,
			Valid:   true,
		}
	}

	return game.PatternResult{
		Pattern: game.PatternUnknown,
		Cards:   cards,
		Valid:   false,
	}
}

func (v *LandlordPatternValidator) Compare(a, b game.PatternResult) int {
	if a.Pattern != b.Pattern {
		if a.Pattern == game.PatternBomb {
			return 1
		}
		if b.Pattern == game.PatternBomb {
			return -1
		}
		return 0
	}
	if len(a.Cards) != len(b.Cards) && a.Pattern != game.PatternBomb {
		return 0
	}
	if a.Weight > b.Weight {
		return 1
	} else if a.Weight < b.Weight {
		return -1
	}
	return 0
}

func (r *LandlordRule) GetPatternValidator() game.CardPatternValidator {
	return &LandlordPatternValidator{}
}

type LandlordSettlement struct{}

func (s *LandlordSettlement) Name() string {
	return "landlord_settlement"
}

func (s *LandlordSettlement) Calculate(ctx *game.GameContext, config *common.RoomConfig) (*game.Settlement, error) {
	results := make([]game.SettleResult, 0, len(ctx.Players))
	landlordWin := false
	for i, p := range ctx.Players {
		score := config.BaseScore
		isWinner := (i == 0 && landlordWin) || (i != 0 && !landlordWin)
		if i == 0 {
			if landlordWin {
				score = config.BaseScore * 2
			} else {
				score = -config.BaseScore * 2
			}
		} else {
			if landlordWin {
				score = -config.BaseScore
			} else {
				score = config.BaseScore
			}
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

func (r *LandlordRule) GetSettlementStrategy() game.SettlementStrategy {
	return &LandlordSettlement{}
}

func (r *LandlordRule) DealCards(ctx *game.GameContext, config *common.RoomConfig) error {
	cardsPerPlayer := 17
	for i, p := range ctx.Players {
		start := i * cardsPerPlayer
		end := start + cardsPerPlayer
		hand := make([]common.Card, cardsPerPlayer)
		copy(hand, ctx.Deck[start:end])
		ctx.PlayerHands[p.UserID] = hand
	}
	ctx.ExtraData["bottom_cards"] = ctx.Deck[51:54]
	ctx.Deck = ctx.Deck[54:]
	return nil
}

func (r *LandlordRule) ValidateAction(ctx *game.GameContext, action *common.GameAction) error {
	switch action.ActionType {
	case common.ActionPlayCard, common.ActionPass, common.ActionCall:
		return nil
	default:
		return common.ErrInvalidAction
	}
}

func (r *LandlordRule) ApplyAction(ctx *game.GameContext, action *common.GameAction) (*common.GameAction, error) {
	if action.ActionType == common.ActionPlayCard {
		if cardsData, ok := action.Data["cards"]; ok {
			if cards, ok := cardsData.([]common.Card); ok {
				hand := ctx.PlayerHands[action.UserID]
				for _, c := range cards {
					for i, h := range hand {
						if h.Index == c.Index {
							hand = append(hand[:i], hand[i+1:]...)
							break
						}
					}
				}
				ctx.PlayerHands[action.UserID] = hand
				ctx.DiscardPile = append(ctx.DiscardPile, cards...)
			}
		}
	}
	ctx.LastAction = action
	return action, nil
}

func (r *LandlordRule) IsRoundOver(ctx *game.GameContext) bool {
	for uid, hand := range ctx.PlayerHands {
		_ = uid
		if len(hand) == 0 {
			return true
		}
	}
	return false
}

func (r *LandlordRule) IsGameOver(ctx *game.GameContext) bool {
	return ctx.Round >= 1
}

func (r *LandlordRule) GetAutoAction(ctx *game.GameContext, userID common.UserID) (*common.GameAction, error) {
	return &common.GameAction{
		ActionID:   common.GenerateID(),
		UserID:     userID,
		ActionType: common.ActionPass,
		Data:       make(map[string]interface{}),
		Timestamp:  time.Now(),
	}, nil
}
