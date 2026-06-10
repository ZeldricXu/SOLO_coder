package game

import (
	"github.com/studio/gameroom/pkg/common"
)

type SettleResult struct {
	UserID   common.UserID
	Score    int64
	Rank     int
	IsWinner bool
	Detail   map[string]interface{}
}

type Settlement struct {
	Results   []SettleResult
	Timestamp int64
	Round     int
}

type SettlementStrategy interface {
	Calculate(ctx *GameContext, config *common.RoomConfig) (*Settlement, error)
	Name() string
}
