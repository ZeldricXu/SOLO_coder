package landlord

import (
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

func (r *LandlordRule) GetPatternValidator() game.CardPatternValidator {
	return &LandlordPatternValidator{}
}

type LandlordSettlement struct{}

type LandlordSettleConfig struct {
	BaseScore int64
	Spring    bool
	AntiSpring bool
}

func (s *LandlordSettlement) Name() string {
	return "landlord_settlement"
}

func (s *LandlordSettlement) Calculate(ctx *game.GameContext, config *common.RoomConfig) (*game.Settlement, error) {
	results := make([]game.SettleResult, 0, len(ctx.Players))

	landlordWin := false
	for i, p := range ctx.Players {
		hand := game.GetPlayerHand(ctx, p.UserID)
		if i == 0 && len(hand) == 0 {
			landlordWin = true
			break
		}
		if i != 0 && len(hand) == 0 {
			landlordWin = false
			break
		}
	}

	multiplier := int64(1)
	if ctx.ExtraData["spring"] == true {
		multiplier *= 2
	}
	if ctx.ExtraData["anti_spring"] == true {
		multiplier *= 2
	}

	for i, p := range ctx.Players {
		var score int64
		isWinner := (i == 0 && landlordWin) || (i != 0 && !landlordWin)

		if i == 0 {
			if landlordWin {
				score = config.BaseScore * 2 * multiplier
			} else {
				score = -config.BaseScore * 2 * multiplier
			}
		} else {
			if landlordWin {
				score = -config.BaseScore * multiplier
			} else {
				score = config.BaseScore * multiplier
			}
		}

		rank := 1
		if !isWinner {
			rank = i + 1
		}

		results = append(results, game.SettleResult{
			UserID:   p.UserID,
			Score:    score,
			Rank:     rank,
			IsWinner: isWinner,
			Detail: map[string]interface{}{
				"base_score":   config.BaseScore,
				"multiplier":   multiplier,
				"landlord_win": landlordWin,
				"spring":       ctx.ExtraData["spring"],
				"anti_spring":  ctx.ExtraData["anti_spring"],
			},
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
		game.SetPlayerHand(ctx, p.UserID, hand)
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
				hand := game.GetPlayerHandForWrite(ctx, action.UserID)
				for _, c := range cards {
					for i, h := range hand {
						if h.Index == c.Index {
							hand = append(hand[:i], hand[i+1:]...)
							break
						}
					}
				}
				game.SetPlayerHand(ctx, action.UserID, hand)
		ctx.DiscardPile = append(ctx.DiscardPile, cards...)
			}
		}
	}
	ctx.LastAction = action
	return action, nil
}

func (r *LandlordRule) IsRoundOver(ctx *game.GameContext) bool {
	for _, p := range ctx.Players {
		hand := game.GetPlayerHand(ctx, p.UserID)
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
