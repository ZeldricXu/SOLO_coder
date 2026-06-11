package game

import (
	"testing"

	"github.com/studio/gameroom/pkg/common"
	"github.com/stretchr/testify/assert"
)

func makeCards(count int) []common.Card {
	cards := make([]common.Card, count)
	for i := 0; i < count; i++ {
		cards[i] = common.Card{Index: i, Suit: 1, Rank: i + 1}
	}
	return cards
}

func TestCOWHand_BasicReadWrite(t *testing.T) {
	chm := NewCowHandManager()
	user1 := common.UserID("u1")
	hand := makeCards(5)

	chm.InitHand(user1, hand)
	assert.Equal(t, 5, chm.GetLen(user1))

	read := chm.GetReadOnly(user1)
	assert.Len(t, read, 5)
	assert.Equal(t, 0, read[0].Index)
}

func TestCOWHand_SharedCopyOnWrite(t *testing.T) {
	chm := NewCowHandManager()
	user1 := common.UserID("u1")
	hand := makeCards(5)

	chm.InitHand(user1, hand)

	origCards := chm.GetReadOnly(user1)
	assert.Len(t, origCards, 5)

	ih, writeCards := chm.MutateForWrite(user1)
	assert.NotNil(t, ih)
	assert.Len(t, writeCards, 5)

	writeCards[0].Rank = 999
	assert.Equal(t, 1, origCards[0].Rank, "COW read copy must not be mutated")

	readAfter := chm.GetReadOnly(user1)
	assert.Equal(t, 999, readAfter[0].Rank, "written data must be reflected")
}

func TestCOWHand_NoSharedNoCopy(t *testing.T) {
	chm := NewCowHandManager()
	user1 := common.UserID("u1")
	hand := makeCards(5)

	chm.InitHand(user1, hand)

	ver1 := chm.GetVersion(user1)
	_, cards1 := chm.MutateForWrite(user1)
	ver2 := chm.GetVersion(user1)

	assert.Equal(t, 1, ver2-ver1)
	cards1[0].Rank = 888

	after := chm.GetReadOnly(user1)
	assert.Equal(t, 888, after[0].Rank)
}

func TestCOWHand_RemoveCards(t *testing.T) {
	chm := NewCowHandManager()
	user1 := common.UserID("u1")
	hand := makeCards(5)

	chm.InitHand(user1, hand)

	_ = chm.GetReadOnly(user1)

	err := chm.RemoveCards(user1, []common.Card{
		{Index: 1, Suit: 1, Rank: 2},
		{Index: 3, Suit: 1, Rank: 4},
	})
	assert.NoError(t, err)
	assert.Equal(t, 3, chm.GetLen(user1))

	after := chm.GetReadOnly(user1)
	idxSet := map[int]bool{}
	for _, c := range after {
		idxSet[c.Index] = true
	}
	assert.False(t, idxSet[1])
	assert.False(t, idxSet[3])
	assert.True(t, idxSet[0])
}

func TestCOWHand_AddCards(t *testing.T) {
	chm := NewCowHandManager()
	user1 := common.UserID("u1")
	hand := makeCards(3)
	chm.InitHand(user1, hand)

	err := chm.AddCard(user1, common.Card{Index: 99, Suit: 1, Rank: 9})
	assert.NoError(t, err)
	assert.Equal(t, 4, chm.GetLen(user1))
}

func TestCOWHand_ContainsCards(t *testing.T) {
	chm := NewCowHandManager()
	user1 := common.UserID("u1")
	hand := makeCards(5)
	chm.InitHand(user1, hand)

	assert.True(t, chm.ContainsCards(user1, []common.Card{
		{Index: 0, Suit: 1, Rank: 1},
		{Index: 4, Suit: 1, Rank: 5},
	}))
	assert.False(t, chm.ContainsCards(user1, []common.Card{
		{Index: 99, Suit: 1, Rank: 99},
	}))
}

func TestCOWHand_UtilsFallback(t *testing.T) {
	ctx := NewGameContext("room1")
	ctx.Players = append(ctx.Players, &common.Player{UserID: "u1"})
	ctx.PlayerHands["u1"] = makeCards(13)

	read := GetPlayerHand(ctx, "u1")
	assert.Len(t, read, 13, "should fallback to PlayerHands when CowHands empty")

	write := GetPlayerHandForWrite(ctx, "u1")
	assert.Len(t, write, 13)
	write[0].Rank = 777
	orig := ctx.PlayerHands["u1"]
	assert.Equal(t, 1, orig[0].Rank, "write copy must not affect original")
}

func TestCOWHand_UtilsWriteThroughCow(t *testing.T) {
	ctx := NewGameContext("room1")
	SetPlayerHand(ctx, "u1", makeCards(10))

	read := GetPlayerHand(ctx, "u1")
	assert.Len(t, read, 10)
	assert.Len(t, ctx.PlayerHands["u1"], 10, "PlayerHands should be synced")
}
