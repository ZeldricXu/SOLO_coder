package game

import (
	"github.com/studio/gameroom/pkg/common"
)

func GetPlayerHand(ctx *GameContext, userID common.UserID) []common.Card {
	if ctx.CowHands != nil {
		if cards := ctx.CowHands.GetReadOnly(userID); cards != nil {
			return cards
		}
	}
	if ctx.PlayerHands != nil {
		return ctx.PlayerHands[userID]
	}
	return nil
}

func GetPlayerHandForWrite(ctx *GameContext, userID common.UserID) []common.Card {
	if ctx.CowHands != nil {
		if _, cards := ctx.CowHands.MutateForWrite(userID); cards != nil {
			return cards
		}
	}
	hand := ctx.PlayerHands[userID]
	newHand := make([]common.Card, len(hand))
	copy(newHand, hand)
	return newHand
}

func SetPlayerHand(ctx *GameContext, userID common.UserID, hand []common.Card) {
	if ctx.CowHands != nil {
		ctx.CowHands.SetHand(userID, hand)
	}
	if ctx.PlayerHands == nil {
		ctx.PlayerHands = make(map[common.UserID][]common.Card)
	}
	ctx.PlayerHands[userID] = hand
}
