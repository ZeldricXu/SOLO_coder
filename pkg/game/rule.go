package game

import (
	"github.com/studio/gameroom/pkg/common"
)

type GameRule interface {
	GameType() common.GameType
	Name() string

	InitDeck() []common.Card
	GetShuffleStrategy() ShuffleStrategy
	GetPatternValidator() CardPatternValidator
	GetSettlementStrategy() SettlementStrategy

	DealCards(ctx *GameContext, config *common.RoomConfig) error
	ValidateAction(ctx *GameContext, action *common.GameAction) error
	ApplyAction(ctx *GameContext, action *common.GameAction) (*common.GameAction, error)
	IsRoundOver(ctx *GameContext) bool
	IsGameOver(ctx *GameContext) bool

	GetTurnTimeout(ctx *GameContext) int
	GetAutoAction(ctx *GameContext, userID common.UserID) (*common.GameAction, error)

	CanJoinMidGame(ctx *GameContext) bool
	GetInitialState() common.GameState
}

type BaseRule struct{}

func (r *BaseRule) GetTurnTimeout(ctx *GameContext) int {
	return 15
}

func (r *BaseRule) CanJoinMidGame(ctx *GameContext) bool {
	return ctx.State == common.StateWaiting
}

func (r *BaseRule) GetInitialState() common.GameState {
	return common.StateWaiting
}
