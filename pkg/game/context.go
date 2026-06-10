package game

import (
	"github.com/studio/gameroom/pkg/common"
)

type GameContext struct {
	RoomID       common.RoomID
	Players      []*common.Player
	CurrentTurn  common.UserID
	State        common.GameState
	Round        int
	Deck         []common.Card
	DiscardPile  []common.Card
	PublicCards  []common.Card
	PlayerHands  map[common.UserID][]common.Card
	PlayerBets   map[common.UserID]int64
	LastAction   *common.GameAction
	ExtraData    map[string]interface{}
	Seq          int64
}

func NewGameContext(roomID common.RoomID) *GameContext {
	return &GameContext{
		RoomID:      roomID,
		Players:     make([]*common.Player, 0),
		State:       common.StateWaiting,
		Deck:        make([]common.Card, 0),
		DiscardPile: make([]common.Card, 0),
		PublicCards: make([]common.Card, 0),
		PlayerHands: make(map[common.UserID][]common.Card),
		PlayerBets:  make(map[common.UserID]int64),
		ExtraData:   make(map[string]interface{}),
		Seq:         0,
	}
}

func (ctx *GameContext) NextSeq() int64 {
	ctx.Seq++
	return ctx.Seq
}

func (ctx *GameContext) GetPlayer(userID common.UserID) *common.Player {
	for _, p := range ctx.Players {
		if p.UserID == userID {
			return p
		}
	}
	return nil
}

func (ctx *GameContext) GetNextPlayer(current common.UserID) *common.Player {
	if len(ctx.Players) == 0 {
		return nil
	}
	foundIdx := -1
	for i, p := range ctx.Players {
		if p.UserID == current {
			foundIdx = i
			break
		}
	}
	if foundIdx == -1 {
		return ctx.Players[0]
	}
	for i := 1; i <= len(ctx.Players); i++ {
		idx := (foundIdx + i) % len(ctx.Players)
		if ctx.Players[idx].IsOnline {
			return ctx.Players[idx]
		}
	}
	return nil
}
