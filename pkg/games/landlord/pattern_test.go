package landlord

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

func TestLandlordPatternValidator_ValidPatterns(t *testing.T) {
	v := &LandlordPatternValidator{}

	tests := []struct {
		name     string
		cards    []common.Card
		expected game.CardPattern
		weight   int
	}{
		{
			name:     "single card",
			cards:    mkCards(1, 3),
			expected: game.PatternSingle,
			weight:   3,
		},
		{
			name:     "pair",
			cards:    mkCardsSameRank(3, 2),
			expected: game.PatternPair,
			weight:   3,
		},
		{
			name:     "triplet",
			cards:    mkCardsSameRank(5, 3),
			expected: game.PatternTriplet,
			weight:   5,
		},
		{
			name:     "triplet with one",
			cards:    append(mkCardsSameRank(7, 3), mkCards(2, 8)...),
			expected: game.PatternTripletOne,
			weight:   7,
		},
		{
			name:     "triplet with two",
			cards:    append(mkCardsSameRank(9, 3), mkCardsSameRank(4, 2)...),
			expected: game.PatternTripletTwo,
			weight:   9,
		},
		{
			name:     "bomb (four of a kind)",
			cards:    mkCardsSameRank(8, 4),
			expected: game.PatternBomb,
			weight:   108,
		},
		{
			name:     "rocket (two jokers)",
			cards:    []common.Card{{Suit: 5, Rank: 14, Index: 100}, {Suit: 5, Rank: 15, Index: 101}},
			expected: game.PatternRocket,
			weight:   1000,
		},
		{
			name:     "straight (5+ consecutive singles)",
			cards:    mkSequence(3, 7, 1),
			expected: game.PatternStraight,
			weight:   7,
		},
		{
			name:     "straight 7 cards",
			cards:    mkSequence(4, 10, 1),
			expected: game.PatternStraight,
			weight:   10,
		},
		{
			name:     "double straight (3+ consecutive pairs)",
			cards:    mkDoubleSequence(4, 6),
			expected: game.PatternDoubleStraight,
			weight:   6,
		},
		{
			name:     "airplane (2+ consecutive triplets)",
			cards:    mkTripleSequence(5, 6),
			expected: game.PatternAirplane,
			weight:   6,
		},
		{
			name:     "airplane with single wings",
			cards:    append(mkTripleSequence(7, 8), mkCards(1, 3, 2, 4)...),
			expected: game.PatternAirplaneOne,
			weight:   8,
		},
		{
			name:     "four with two singles",
			cards:    append(mkCardsSameRank(10, 4), mkCards(1, 2, 2, 3)...),
			expected: game.PatternFourTwo,
			weight:   90,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := v.Validate(tt.cards)
			assert.True(t, result.Valid, "pattern should be valid")
			assert.Equal(t, tt.expected, result.Pattern, "pattern type mismatch")
			assert.Equal(t, tt.weight, result.Weight, "weight mismatch")
		})
	}
}

func TestLandlordPatternValidator_InvalidPatterns(t *testing.T) {
	v := &LandlordPatternValidator{}

	tests := []struct {
		name  string
		cards []common.Card
	}{
		{name: "empty", cards: []common.Card{}},
		{name: "two unmatched cards", cards: mkCards(1, 3, 2, 5)},
		{name: "three unmatched cards", cards: mkCards(1, 2, 2, 5, 3, 9)},
		{name: "straight too short (4 cards)", cards: mkSequence(3, 6, 1)},
		{name: "double straight too short (2 pairs)", cards: mkDoubleSequence(3, 4)},
		{name: "straight with 2 or joker", cards: append(mkSequence(10, 13, 1), mkCards(1, 2)...)},
		{name: "three with three (6 cards same rank)", cards: mkCardsSameRank(5, 5)},
		{name: "random mix", cards: mkCards(2, 5, 7, 8, 10)},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := v.Validate(tt.cards)
			assert.False(t, result.Valid, "pattern should be invalid: %s", tt.name)
			assert.Equal(t, game.PatternUnknown, result.Pattern, "should be unknown pattern")
		})
	}
}

func TestLandlordPatternValidator_Compare(t *testing.T) {
	v := &LandlordPatternValidator{}

	tests := []struct {
		name     string
		aPattern game.CardPattern
		aCards   []common.Card
		bPattern game.CardPattern
		bCards   []common.Card
		expected int
	}{
		{
			name:     "bomb beats single",
			aPattern: game.PatternBomb,
			aCards:   mkCardsSameRank(5, 4),
			bPattern: game.PatternSingle,
			bCards:   mkCards(1, 10),
			expected: 1,
		},
		{
			name:     "rocket beats bomb",
			aPattern: game.PatternRocket,
			aCards:   []common.Card{{Suit: 5, Rank: 14}, {Suit: 5, Rank: 15}},
			bPattern: game.PatternBomb,
			bCards:   mkCardsSameRank(13, 4),
			expected: 1,
		},
		{
			name:     "higher single beats lower single",
			aPattern: game.PatternSingle,
			aCards:   mkCards(1, 10),
			bPattern: game.PatternSingle,
			bCards:   mkCards(1, 5),
			expected: 1,
		},
		{
			name:     "pair same rank is equal",
			aPattern: game.PatternPair,
			aCards:   mkCardsSameRank(7, 2),
			bPattern: game.PatternPair,
			bCards:   mkCardsSameRank(7, 2),
			expected: 0,
		},
		{
			name:     "different patterns (non-bomb) can't compare",
			aPattern: game.PatternSingle,
			aCards:   mkCards(1, 5),
			bPattern: game.PatternPair,
			bCards:   mkCardsSameRank(3, 2),
			expected: 0,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			a := v.Validate(tt.aCards)
			b := v.Validate(tt.bCards)
			result := v.Compare(a, b)
			assert.Equal(t, tt.expected, result, "compare result mismatch")
		})
	}
}

func TestLandlordSettlement_BaseCalculation(t *testing.T) {
	settler := &LandlordSettlement{}
	config := &common.RoomConfig{BaseScore: 10, MaxPlayers: 3}

	ctx := game.NewGameContext("test_room")
	ctx.Players = []*common.Player{
		{UserID: "landlord"},
		{UserID: "farmer1"},
		{UserID: "farmer2"},
	}
	ctx.PlayerHands = map[common.UserID][]common.Card{
		"landlord": {},
		"farmer1":  {{}, {}, {}},
		"farmer2":  {{}, {}, {}},
	}
	ctx.Round = 1

	settlement, err := settler.Calculate(ctx, config)
	assert.NoError(t, err)
	assert.Len(t, settlement.Results, 3)

	landlordResult := findResult(settlement.Results, "landlord")
	farmerResult := findResult(settlement.Results, "farmer1")

	assert.Equal(t, int64(20), landlordResult.Score, "landlord should win 2x base score")
	assert.Equal(t, int64(-10), farmerResult.Score, "farmer should lose base score")
	assert.True(t, landlordResult.IsWinner)
	assert.False(t, farmerResult.IsWinner)
}

func TestLandlordSettlement_SpringMultiplier(t *testing.T) {
	settler := &LandlordSettlement{}
	config := &common.RoomConfig{BaseScore: 10, MaxPlayers: 3}

	ctx := game.NewGameContext("test_room")
	ctx.Players = []*common.Player{
		{UserID: "landlord"},
		{UserID: "farmer1"},
		{UserID: "farmer2"},
	}
	ctx.PlayerHands = map[common.UserID][]common.Card{
		"landlord": {},
		"farmer1":  {{}, {}, {}, {}},
		"farmer2":  {{}, {}, {}},
	}
	ctx.ExtraData = map[string]interface{}{"spring": true}
	ctx.Round = 1

	settlement, err := settler.Calculate(ctx, config)
	assert.NoError(t, err)

	landlordResult := findResult(settlement.Results, "landlord")
	assert.Equal(t, int64(40), landlordResult.Score,
		"spring doubles: base 10 * 2 (landlord) * 2 (spring) = 40")

	farmerResult := findResult(settlement.Results, "farmer1")
	assert.Equal(t, int64(-20), farmerResult.Score,
		"farmer loses doubled: 10 * 2 = 20")
}

func TestLandlordSettlement_AntiSpringMultiplier(t *testing.T) {
	settler := &LandlordSettlement{}
	config := &common.RoomConfig{BaseScore: 10, MaxPlayers: 3}

	ctx := game.NewGameContext("test_room")
	ctx.Players = []*common.Player{
		{UserID: "landlord"},
		{UserID: "farmer1"},
		{UserID: "farmer2"},
	}
	ctx.PlayerHands = map[common.UserID][]common.Card{
		"landlord": {{}, {}, {}, {}},
		"farmer1":  {},
		"farmer2":  {{}, {}, {}},
	}
	ctx.ExtraData = map[string]interface{}{"anti_spring": true}
	ctx.Round = 1

	settlement, err := settler.Calculate(ctx, config)
	assert.NoError(t, err)

	landlordResult := findResult(settlement.Results, "landlord")
	assert.Equal(t, int64(-40), landlordResult.Score,
		"anti-spring landlord loss: 10 * 2 * 2 = -40")

	farmerResult := findResult(settlement.Results, "farmer1")
	assert.Equal(t, int64(20), farmerResult.Score,
		"farmer wins doubled: 10 * 2 = 20")
}

func TestLandlordSettlement_BothSpringAndAntiSpring(t *testing.T) {
	settler := &LandlordSettlement{}
	config := &common.RoomConfig{BaseScore: 10, MaxPlayers: 3}

	ctx := game.NewGameContext("test_room")
	ctx.Players = []*common.Player{
		{UserID: "landlord"},
		{UserID: "farmer1"},
		{UserID: "farmer2"},
	}
	ctx.PlayerHands = map[common.UserID][]common.Card{
		"landlord": {},
		"farmer1":  {{}, {}, {}},
		"farmer2":  {{}, {}, {}},
	}
	ctx.ExtraData = map[string]interface{}{
		"spring":     true,
		"anti_spring": true,
	}
	ctx.Round = 1

	settlement, err := settler.Calculate(ctx, config)
	assert.NoError(t, err)

	landlordResult := findResult(settlement.Results, "landlord")
	assert.Equal(t, int64(80), landlordResult.Score,
		"both multipliers stack: 10 * 2 (landlord) * 2 (spring) * 2 (anti_spring?) = 80")
}

func findResult(results []game.SettleResult, userID string) game.SettleResult {
	for _, r := range results {
		if string(r.UserID) == userID {
			return r
		}
	}
	return game.SettleResult{}
}

func mkCards(suitsAndRanks ...int) []common.Card {
	cards := make([]common.Card, 0, len(suitsAndRanks)/2)
	for i := 0; i < len(suitsAndRanks)-1; i += 2 {
		cards = append(cards, common.Card{
			Suit:  suitsAndRanks[i],
			Rank:  suitsAndRanks[i+1],
			Index: suitsAndRanks[i]*100 + suitsAndRanks[i+1] + i,
		})
	}
	return cards
}

func mkCardsSameRank(rank, count int) []common.Card {
	cards := make([]common.Card, count)
	for i := 0; i < count; i++ {
		cards[i] = common.Card{
			Suit:  (i % 4) + 1,
			Rank:  rank,
			Index: rank*100 + i,
		}
	}
	return cards
}

func mkSequence(startRank, endRank int, suit int) []common.Card {
	cards := make([]common.Card, 0, endRank-startRank+1)
	for r := startRank; r <= endRank; r++ {
		cards = append(cards, common.Card{
			Suit:  suit,
			Rank:  r,
			Index: r*100 + suit,
		})
	}
	return cards
}

func mkDoubleSequence(startRank, endRank int) []common.Card {
	cards := make([]common.Card, 0, (endRank-startRank+1)*2)
	for r := startRank; r <= endRank; r++ {
		cards = append(cards,
			common.Card{Suit: 1, Rank: r, Index: r*100 + 1},
			common.Card{Suit: 2, Rank: r, Index: r*100 + 2},
		)
	}
	return cards
}

func mkTripleSequence(startRank, endRank int) []common.Card {
	cards := make([]common.Card, 0, (endRank-startRank+1)*3)
	for r := startRank; r <= endRank; r++ {
		cards = append(cards,
			common.Card{Suit: 1, Rank: r, Index: r*100 + 1},
			common.Card{Suit: 2, Rank: r, Index: r*100 + 2},
			common.Card{Suit: 3, Rank: r, Index: r*100 + 3},
		)
	}
	return cards
}
