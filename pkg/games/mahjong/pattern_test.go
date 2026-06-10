package mahjong

import (
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

func TestMahjongPatternValidator_ValidPatterns(t *testing.T) {
	v := &MahjongPatternValidator{}

	tests := []struct {
		name     string
		cards    []common.Card
		expected game.CardPattern
	}{
		{
			name:     "single card",
			cards:    mj(1, 5),
			expected: game.PatternSingle,
		},
		{
			name:     "pair",
			cards:    mjPair(1, 5),
			expected: game.PatternPair,
		},
		{
			name:     "pung (three of a kind)",
			cards:    mjPung(2, 8),
			expected: game.PatternPung,
		},
		{
			name:     "kong (four of a kind)",
			cards:    mjKong(3, 4),
			expected: game.PatternKong,
		},
		{
			name:     "chow (consecutive in same suit)",
			cards:    mjChow(1, 5),
			expected: game.PatternChow,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := v.Validate(tt.cards)
			assert.True(t, result.Valid, "should be valid: %s", tt.name)
			assert.Equal(t, tt.expected, result.Pattern, "pattern mismatch: %s", tt.name)
		})
	}
}

func TestMahjongPatternValidator_InvalidPatterns(t *testing.T) {
	v := &MahjongPatternValidator{}

	tests := []struct {
		name  string
		cards []common.Card
	}{
		{name: "two different cards", cards: []common.Card{mjCard(1, 3), mjCard(2, 3)}},
		{name: "three unrelated", cards: []common.Card{mjCard(1, 2), mjCard(2, 5), mjCard(3, 8)}},
		{name: "chow with honors", cards: []common.Card{
			{Suit: suitFeng, Rank: 1}, {Suit: suitFeng, Rank: 2}, {Suit: suitFeng, Rank: 3},
		}},
		{name: "chow across suits", cards: []common.Card{
			{Suit: 1, Rank: 5}, {Suit: 2, Rank: 6}, {Suit: 3, Rank: 7},
		}},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := v.Validate(tt.cards)
			assert.False(t, result.Valid, "should be invalid: %s", tt.name)
			assert.Equal(t, game.PatternUnknown, result.Pattern)
		})
	}
}

func TestCheckWin_SevenPairs(t *testing.T) {
	hand := makeSevenPairs()
	picked := mjCard(2, 9)

	result := CheckWin(hand, picked)
	assert.True(t, result.IsWin, "seven pairs should win")
	assert.Equal(t, WinSevenPairs, result.Pattern)
	assert.Equal(t, 4, result.Score)
	assert.Contains(t, result.Fans, "七小对")
}

func TestCheckWin_ThirteenOrphans(t *testing.T) {
	hand := makeThirteenOrphans()
	picked := mjCard(suitWan, 1)

	result := CheckWin(hand, picked)
	assert.True(t, result.IsWin, "thirteen orphans should win")
	assert.Equal(t, WinThirteenOrphans, result.Pattern)
	assert.Equal(t, 13, result.Score)
	assert.Contains(t, result.Fans, "十三幺")
}

func TestCheckWin_NormalWin(t *testing.T) {
	hand := makeNormalHand()
	picked := mjCard(1, 5)

	result := CheckWin(hand, picked)
	assert.True(t, result.IsWin, "normal hand should win")
	assert.Equal(t, WinNormal, result.Pattern)
	assert.GreaterOrEqual(t, result.Score, 1)
}

func TestCheckWin_InvalidHands(t *testing.T) {
	tests := []struct {
		name   string
		hand   []common.Card
		picked common.Card
	}{
		{
			name:   "13 tiles no win",
			hand:   mjRandomHand(13),
			picked: mjCard(1, 1),
		},
		{
			name:   "only 2 tiles",
			hand:   []common.Card{mjCard(1, 1), mjCard(1, 1)},
			picked: mjCard(1, 1),
		},
		{
			name:   "incomplete hand",
			hand:   mjRandomHand(10),
			picked: mjCard(2, 5),
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := CheckWin(tt.hand, tt.picked)
			assert.False(t, result.IsWin, "should not win: %s", tt.name)
		})
	}
}

func TestCheckKongBloom(t *testing.T) {
	hand := makeHandForKongBloom()
	winTile := mjCard(1, 5)

	result := CheckKongBloom(hand, winTile, KongMing)
	assert.True(t, result.IsKongBloom, "kong bloom should be valid")
	assert.Equal(t, 1, result.ScoreBonus, "ming kong gives +1 fan")

	resultAn := CheckKongBloom(hand, winTile, KongAn)
	assert.True(t, resultAn.IsKongBloom)
	assert.Equal(t, 2, resultAn.ScoreBonus, "an kong gives +2 fan")
}

func TestCheckRobKong(t *testing.T) {
	hand := makeHandForRobKong()
	kongTile := mjCard(1, 5)

	result := CheckRobKong(hand, kongTile)
	assert.True(t, result.CanRob, "should be able to rob kong")
	assert.True(t, result.IsWin)
	assert.Equal(t, 1, result.ScoreBonus)
}

func TestCheckRobKong_NotWinningTile(t *testing.T) {
	hand := mjRandomHand(13)
	kongTile := mjCard(3, 5)

	result := CheckRobKong(hand, kongTile)
	assert.False(t, result.CanRob, "should not be able to rob non-winning tile")
}

func TestMahjongScorer_FanCalculation(t *testing.T) {
	scorer := &MahjongScorer{}

	t.Run("base score", func(t *testing.T) {
		hand := makeNormalHand()
		picked := mjCard(1, 5)
		extras := map[string]bool{}
		fan := scorer.CalculateFan(hand, picked, extras)
		assert.GreaterOrEqual(t, fan, 1, "basic win has at least 1 fan")
	})

	t.Run("kong bloom adds fan", func(t *testing.T) {
		hand := makeNormalHand()
		picked := mjCard(1, 5)
		basic := scorer.CalculateFan(hand, picked, map[string]bool{})
		withKongBloom := scorer.CalculateFan(hand, picked, map[string]bool{"kong_bloom": true})
		assert.Equal(t, basic+1, withKongBloom, "kong bloom should add 1 fan")
	})

	t.Run("rob kong adds fan", func(t *testing.T) {
		hand := makeNormalHand()
		picked := mjCard(1, 5)
		basic := scorer.CalculateFan(hand, picked, map[string]bool{})
		withRob := scorer.CalculateFan(hand, picked, map[string]bool{"rob_kong": true})
		assert.Equal(t, basic+1, withRob, "rob kong should add 1 fan")
	})

	t.Run("self draw adds fan", func(t *testing.T) {
		hand := makeNormalHand()
		picked := mjCard(1, 5)
		basic := scorer.CalculateFan(hand, picked, map[string]bool{})
		withSelf := scorer.CalculateFan(hand, picked, map[string]bool{"self_draw": true})
		assert.Equal(t, basic+1, withSelf, "self draw should add 1 fan")
	})

	t.Run("all pungs adds fans", func(t *testing.T) {
		hand := makeNormalHand()
		picked := mjCard(1, 5)
		basic := scorer.CalculateFan(hand, picked, map[string]bool{})
		withPungs := scorer.CalculateFan(hand, picked, map[string]bool{"all_pungs": true})
		assert.Equal(t, basic+2, withPungs, "all pungs adds 2 fans")
	})

	t.Run("multiple bonuses stack", func(t *testing.T) {
		hand := makeNormalHand()
		picked := mjCard(1, 5)
		basic := scorer.CalculateFan(hand, picked, map[string]bool{})
		stacked := scorer.CalculateFan(hand, picked, map[string]bool{
			"kong_bloom": true,
			"self_draw":  true,
			"all_pungs":  true,
		})
		assert.Equal(t, basic+1+1+2, stacked, "all bonuses should stack")
	})
}

func TestMahjongScorer_ScoreToPoints(t *testing.T) {
	scorer := &MahjongScorer{}
	base := int64(100)

	t.Run("1 fan = base", func(t *testing.T) {
		assert.Equal(t, int64(100), scorer.ScoreToPoints(1, base))
	})
	t.Run("2 fans = 2x base", func(t *testing.T) {
		assert.Equal(t, int64(200), scorer.ScoreToPoints(2, base))
	})
	t.Run("3 fans = 4x base", func(t *testing.T) {
		assert.Equal(t, int64(400), scorer.ScoreToPoints(3, base))
	})
	t.Run("4 fans = 8x base", func(t *testing.T) {
		assert.Equal(t, int64(800), scorer.ScoreToPoints(4, base))
	})
	t.Run("0 fan = 0", func(t *testing.T) {
		assert.Equal(t, int64(0), scorer.ScoreToPoints(0, base))
	})
}

func mjCard(suit, rank int) common.Card {
	return common.Card{Suit: suit, Rank: rank, Index: suit*100 + rank}
}

func mj(suitsAndRanks ...int) []common.Card {
	cards := make([]common.Card, 0, len(suitsAndRanks)/2)
	for i := 0; i < len(suitsAndRanks)-1; i += 2 {
		cards = append(cards, mjCard(suitsAndRanks[i], suitsAndRanks[i+1]))
	}
	return cards
}

func mjPair(suit, rank int) []common.Card {
	return []common.Card{
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 1},
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 2},
	}
}

func mjPung(suit, rank int) []common.Card {
	return []common.Card{
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 1},
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 2},
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 3},
	}
}

func mjKong(suit, rank int) []common.Card {
	return []common.Card{
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 1},
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 2},
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 3},
		{Suit: suit, Rank: rank, Index: suit*100 + rank*10 + 4},
	}
}

func mjChow(suit, start int) []common.Card {
	return []common.Card{
		{Suit: suit, Rank: start, Index: suit*100 + start},
		{Suit: suit, Rank: start + 1, Index: suit*100 + start + 1},
		{Suit: suit, Rank: start + 2, Index: suit*100 + start + 2},
	}
}

func makeSevenPairs() []common.Card {
	return []common.Card{
		{Suit: 1, Rank: 1, Index: 101}, {Suit: 1, Rank: 1, Index: 102},
		{Suit: 1, Rank: 3, Index: 103}, {Suit: 1, Rank: 3, Index: 104},
		{Suit: 2, Rank: 5, Index: 201}, {Suit: 2, Rank: 5, Index: 202},
		{Suit: 2, Rank: 7, Index: 203}, {Suit: 2, Rank: 7, Index: 204},
		{Suit: 3, Rank: 2, Index: 301}, {Suit: 3, Rank: 2, Index: 302},
		{Suit: 3, Rank: 6, Index: 303}, {Suit: 3, Rank: 6, Index: 304},
		{Suit: 2, Rank: 9, Index: 205},
	}
}

func makeThirteenOrphans() []common.Card {
	return []common.Card{
		{Suit: suitWan, Rank: 1, Index: 101},
		{Suit: suitWan, Rank: 9, Index: 199},
		{Suit: suitTiao, Rank: 1, Index: 201},
		{Suit: suitTiao, Rank: 9, Index: 299},
		{Suit: suitTong, Rank: 1, Index: 301},
		{Suit: suitTong, Rank: 9, Index: 399},
		{Suit: suitFeng, Rank: 1, Index: 401},
		{Suit: suitFeng, Rank: 2, Index: 402},
		{Suit: suitFeng, Rank: 3, Index: 403},
		{Suit: suitFeng, Rank: 4, Index: 404},
		{Suit: suitJian, Rank: 1, Index: 501},
		{Suit: suitJian, Rank: 2, Index: 502},
		{Suit: suitJian, Rank: 3, Index: 503},
	}
}

func makeNormalHand() []common.Card {
	return []common.Card{
		{Suit: 1, Rank: 1, Index: 101}, {Suit: 1, Rank: 2, Index: 102}, {Suit: 1, Rank: 3, Index: 103},
		{Suit: 1, Rank: 5, Index: 104},
		{Suit: 1, Rank: 7, Index: 105}, {Suit: 1, Rank: 8, Index: 106}, {Suit: 1, Rank: 9, Index: 107},
		{Suit: 2, Rank: 7, Index: 201}, {Suit: 2, Rank: 8, Index: 202}, {Suit: 2, Rank: 9, Index: 203},
		{Suit: 3, Rank: 2, Index: 301}, {Suit: 3, Rank: 2, Index: 302}, {Suit: 3, Rank: 2, Index: 303},
	}
}

func makeHandForKongBloom() []common.Card {
	return makeNormalHand()
}

func makeHandForRobKong() []common.Card {
	return makeNormalHand()
}

func mjRandomHand(count int) []common.Card {
	cards := make([]common.Card, count)
	for i := 0; i < count; i++ {
		cards[i] = mjCard((i%3)+1, (i%9)+1)
	}
	return cards
}
