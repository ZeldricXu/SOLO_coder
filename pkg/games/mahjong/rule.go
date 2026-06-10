package mahjong

import (
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type MahjongRule struct {
	game.BaseRule
}

func init() {
	game.RegisterRule(&MahjongRule{})
}

func (r *MahjongRule) GameType() common.GameType {
	return common.GameTypeMahjong
}

func (r *MahjongRule) Name() string {
	return "standard_mahjong"
}

func (r *MahjongRule) InitDeck() []common.Card {
	cards := make([]common.Card, 0, 144)
	suits := []int{1, 2, 3}
	ranks := []int{1, 2, 3, 4, 5, 6, 7, 8, 9}

	idx := 0
	for _, suit := range suits {
		for _, rank := range ranks {
			for i := 0; i < 4; i++ {
				cards = append(cards, common.Card{Suit: suit, Rank: rank, Index: idx})
				idx++
			}
		}
	}
	for wind := 1; wind <= 4; wind++ {
		for i := 0; i < 4; i++ {
			cards = append(cards, common.Card{Suit: 4, Rank: wind, Index: idx})
			idx++
		}
	}
	for dragon := 1; dragon <= 3; dragon++ {
		for i := 0; i < 4; i++ {
			cards = append(cards, common.Card{Suit: 5, Rank: dragon, Index: idx})
			idx++
		}
	}
	for flower := 1; flower <= 8; flower++ {
		cards = append(cards, common.Card{Suit: 6, Rank: flower, Index: idx})
		idx++
	}
	return cards
}

func (r *MahjongRule) GetShuffleStrategy() game.ShuffleStrategy {
	return game.GetShuffleStrategy("fisher_yates")
}

type MahjongPatternValidator struct{}

func (v *MahjongPatternValidator) Validate(cards []common.Card) game.PatternResult {
	return game.PatternResult{
		Pattern: game.PatternSingle,
		Weight:  0,
		Cards:   cards,
		Valid:   len(cards) > 0,
	}
}

func (v *MahjongPatternValidator) Compare(a, b game.PatternResult) int {
	if a.Weight > b.Weight {
		return 1
	} else if a.Weight < b.Weight {
		return -1
	}
	return 0
}

func (r *MahjongRule) GetPatternValidator() game.CardPatternValidator {
	return &MahjongPatternValidator{}
}

type MahjongSettlement struct{}

func (s *MahjongSettlement) Name() string {
	return "mahjong_settlement"
}

func (s *MahjongSettlement) Calculate(ctx *game.GameContext, config *common.RoomConfig) (*game.Settlement, error) {
	results := make([]game.SettleResult, 0, len(ctx.Players))
	for i, p := range ctx.Players {
		score := int64(0)
		isWinner := i == 0
		if isWinner {
			score = config.BaseScore * 2
		} else {
			score = -config.BaseScore
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

func (r *MahjongRule) GetSettlementStrategy() game.SettlementStrategy {
	return &MahjongSettlement{}
}

func (r *MahjongRule) DealCards(ctx *game.GameContext, config *common.RoomConfig) error {
	cardsPerPlayer := 13
	for i, p := range ctx.Players {
		start := i * cardsPerPlayer
		end := start + cardsPerPlayer
		if end > len(ctx.Deck) {
			end = len(ctx.Deck)
		}
		hand := make([]common.Card, end-start)
		copy(hand, ctx.Deck[start:end])
		ctx.PlayerHands[p.UserID] = hand
	}
	ctx.Deck = ctx.Deck[len(ctx.Players)*cardsPerPlayer:]
	return nil
}

func (r *MahjongRule) ValidateAction(ctx *game.GameContext, action *common.GameAction) error {
	switch action.ActionType {
	case common.ActionDiscard:
		return nil
	case common.ActionDraw:
		return nil
	case common.ActionPass:
		return nil
	default:
		return common.ErrInvalidAction
	}
}

func (r *MahjongRule) ApplyAction(ctx *game.GameContext, action *common.GameAction) (*common.GameAction, error) {
	ctx.LastAction = action
	return action, nil
}

func (r *MahjongRule) IsRoundOver(ctx *game.GameContext) bool {
	if ctx.LastAction != nil && ctx.LastAction.ActionType == "win" {
		return true
	}
	return len(ctx.Deck) == 0
}

func (r *MahjongRule) IsGameOver(ctx *game.GameContext) bool {
	return ctx.Round >= 4
}

func (r *MahjongRule) GetAutoAction(ctx *game.GameContext, userID common.UserID) (*common.GameAction, error) {
	return &common.GameAction{
		ActionID:   common.GenerateID(),
		UserID:     userID,
		ActionType: common.ActionPass,
		Data:       make(map[string]interface{}),
		Timestamp:  time.Now(),
	}, nil
}
