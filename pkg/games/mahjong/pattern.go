package mahjong

import (
	"fmt"
	"sort"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type MahjongPatternValidator struct{}

const (
	suitWan   = 1
	suitTiao  = 2
	suitTong  = 3
	suitFeng  = 4
	suitJian  = 5
)

func (v *MahjongPatternValidator) Validate(cards []common.Card) game.PatternResult {
	if len(cards) == 0 {
		return game.PatternResult{Valid: false}
	}
	if len(cards) == 1 {
		return game.PatternResult{
			Pattern: game.PatternSingle,
			Weight:  cards[0].Suit*100 + cards[0].Rank,
			Cards:   cards,
			Valid:   true,
		}
	}
	if len(cards) == 2 {
		if cards[0].Suit == cards[1].Suit && cards[0].Rank == cards[1].Rank {
			return game.PatternResult{
				Pattern: game.PatternPair,
				Weight:  cards[0].Suit*100 + cards[0].Rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}
	if len(cards) == 3 {
		if cards[0].Suit == cards[1].Suit && cards[1].Suit == cards[2].Suit &&
			cards[0].Rank == cards[1].Rank && cards[1].Rank == cards[2].Rank {
			return game.PatternResult{
				Pattern: game.PatternPung,
				Weight:  cards[0].Suit*100 + cards[0].Rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		if isChow(cards) {
			return game.PatternResult{
				Pattern: game.PatternChow,
				Weight:  cards[0].Suit*100 + middleRank(cards),
				Cards:   cards,
				Valid:   true,
			}
		}
	}
	if len(cards) == 4 {
		if allSameRank(cards) {
			return game.PatternResult{
				Pattern: game.PatternKong,
				Weight:  cards[0].Suit*100 + cards[0].Rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}
	return game.PatternResult{
		Pattern: game.PatternUnknown,
		Cards:   cards,
		Valid:   false,
	}
}

func isChow(cards []common.Card) bool {
	if len(cards) != 3 {
		return false
	}
	suit := cards[0].Suit
	if cards[1].Suit != suit || cards[2].Suit != suit {
		return false
	}
	if suit == suitFeng || suit == suitJian {
		return false
	}
	ranks := []int{cards[0].Rank, cards[1].Rank, cards[2].Rank}
	sort.Ints(ranks)
	return ranks[0]+1 == ranks[1] && ranks[1]+1 == ranks[2]
}

func middleRank(cards []common.Card) int {
	ranks := []int{cards[0].Rank, cards[1].Rank, cards[2].Rank}
	sort.Ints(ranks)
	return ranks[1]
}

func allSameRank(cards []common.Card) bool {
	if len(cards) == 0 {
		return false
	}
	suit := cards[0].Suit
	rank := cards[0].Rank
	for _, c := range cards[1:] {
		if c.Suit != suit || c.Rank != rank {
			return false
		}
	}
	return true
}

func (v *MahjongPatternValidator) Compare(a, b game.PatternResult) int {
	if a.Weight > b.Weight {
		return 1
	} else if a.Weight < b.Weight {
		return -1
	}
	return 0
}

type WinPattern int

const (
	WinNormal WinPattern = iota
	WinSevenPairs
	WinThirteenOrphans
	WinAllPungs
	WinAllHonors
)

type WinResult struct {
	IsWin   bool
	Pattern WinPattern
	Score   int
	Fans    []string
}

func CheckWin(hand []common.Card, picked common.Card) WinResult {
	fullHand := append(hand, picked)
	if len(fullHand)%3 != 2 {
		return WinResult{IsWin: false}
	}

	if isSevenPairs(fullHand) {
		return WinResult{
			IsWin:   true,
			Pattern: WinSevenPairs,
			Score:   4,
			Fans:    []string{"七小对"},
		}
	}

	if isThirteenOrphans(fullHand) {
		return WinResult{
			IsWin:   true,
			Pattern: WinThirteenOrphans,
			Score:   13,
			Fans:    []string{"十三幺"},
		}
	}

	if isNormalWin(fullHand) {
		fans := []string{"平胡"}
		score := 1
		if isAllPungs(fullHand) {
			fans = append(fans, "对对胡")
			score += 2
		}
		return WinResult{
			IsWin:   true,
			Pattern: WinNormal,
			Score:   score,
			Fans:    fans,
		}
	}

	return WinResult{IsWin: false}
}

func isSevenPairs(hand []common.Card) bool {
	if len(hand) != 14 {
		return false
	}
	counts := make(map[string]int)
	for _, c := range hand {
		key := fmt.Sprintf("%d_%d", c.Suit, c.Rank)
		counts[key]++
	}
	pairCount := 0
	for _, cnt := range counts {
		if cnt == 2 {
			pairCount++
		} else if cnt == 4 {
			pairCount += 2
		} else {
			return false
		}
	}
	return pairCount == 7
}

func isThirteenOrphans(hand []common.Card) bool {
	if len(hand) != 14 {
		return false
	}

	orphans := map[[2]int]int{
		{suitWan, 1}:  0,
		{suitWan, 9}:  0,
		{suitTiao, 1}: 0,
		{suitTiao, 9}: 0,
		{suitTong, 1}: 0,
		{suitTong, 9}: 0,
		{suitFeng, 1}: 0,
		{suitFeng, 2}: 0,
		{suitFeng, 3}: 0,
		{suitFeng, 4}: 0,
		{suitJian, 1}: 0,
		{suitJian, 2}: 0,
		{suitJian, 3}: 0,
	}

	for _, c := range hand {
		key := [2]int{c.Suit, c.Rank}
		if _, ok := orphans[key]; ok {
			orphans[key]++
		} else {
			return false
		}
	}

	pairCount := 0
	singleCount := 0
	for _, cnt := range orphans {
		if cnt == 2 {
			pairCount++
		} else if cnt == 1 {
			singleCount++
		}
	}

	return pairCount == 1 && singleCount == 12
}

func isNormalWin(hand []common.Card) bool {
	if len(hand)%3 != 2 {
		return false
	}
	sorted := sortHand(hand)
	return checkMelds(sorted, true)
}

func checkMelds(hand []common.Card, needPair bool) bool {
	if len(hand) == 0 {
		return !needPair
	}

	if needPair {
		for i := 0; i < len(hand)-1; i++ {
			if hand[i].Suit == hand[i+1].Suit && hand[i].Rank == hand[i+1].Rank {
				remaining := removePairs(hand, i, 2)
				if checkMelds(remaining, false) {
					return true
				}
			}
		}
		return false
	}

	if len(hand) < 3 {
		return false
	}

	if hand[0].Suit == hand[1].Suit && hand[1].Suit == hand[2].Suit &&
		hand[0].Rank == hand[1].Rank && hand[1].Rank == hand[2].Rank {
		if checkMelds(hand[3:], false) {
			return true
		}
	}

	if hand[0].Suit != suitFeng && hand[0].Suit != suitJian {
		rank1 := hand[0].Rank
		suit := hand[0].Suit
		idx1 := -1
		idx2 := -1
		for i := 1; i < len(hand); i++ {
			if hand[i].Suit == suit && hand[i].Rank == rank1+1 && idx1 == -1 {
				idx1 = i
			}
			if hand[i].Suit == suit && hand[i].Rank == rank1+2 && idx2 == -1 {
				idx2 = i
			}
		}
		if idx1 != -1 && idx2 != -1 {
			remaining := removeIndices(hand, 0, idx1, idx2)
			if checkMelds(remaining, false) {
				return true
			}
		}
	}

	return false
}

func removePairs(hand []common.Card, start, count int) []common.Card {
	result := make([]common.Card, 0, len(hand)-count)
	result = append(result, hand[:start]...)
	result = append(result, hand[start+count:]...)
	return result
}

func removeIndices(hand []common.Card, indices ...int) []common.Card {
	sort.Ints(indices)
	result := make([]common.Card, 0, len(hand)-len(indices))
	skipIdx := 0
	for i, c := range hand {
		if skipIdx < len(indices) && i == indices[skipIdx] {
			skipIdx++
			continue
		}
		result = append(result, c)
	}
	return result
}

func sortHand(hand []common.Card) []common.Card {
	result := make([]common.Card, len(hand))
	copy(result, hand)
	sort.Slice(result, func(i, j int) bool {
		if result[i].Suit != result[j].Suit {
			return result[i].Suit < result[j].Suit
		}
		return result[i].Rank < result[j].Rank
	})
	return result
}

func isAllPungs(hand []common.Card) bool {
	counts := make(map[string]int)
	for _, c := range hand {
		key := fmt.Sprintf("%d_%d", c.Suit, c.Rank)
		counts[key]++
	}
	pairs := 0
	pungs := 0
	for _, cnt := range counts {
		if cnt == 2 {
			pairs++
		} else if cnt == 3 || cnt == 4 {
			pungs++
		} else if cnt == 1 {
			return false
		}
	}
	return pairs == 1 && pungs >= 4
}

type KongType int

const (
	KongMing KongType = iota
	KongAn
	KongJia
)

type KongBloomResult struct {
	IsKongBloom bool
	ScoreBonus  int
}

func CheckKongBloom(handAfterKong []common.Card, winTile common.Card, kongType KongType) KongBloomResult {
	result := CheckWin(handAfterKong, winTile)
	if !result.IsWin {
		return KongBloomResult{IsKongBloom: false}
	}
	bonus := 1
	if kongType == KongAn {
		bonus = 2
	}
	return KongBloomResult{
		IsKongBloom: true,
		ScoreBonus:  bonus,
	}
}

type RobKongResult struct {
	CanRob   bool
	IsWin    bool
	ScoreBonus int
}

func CheckRobKong(hand []common.Card, kongTile common.Card) RobKongResult {
	result := CheckWin(hand, kongTile)
	if !result.IsWin {
		return RobKongResult{CanRob: false}
	}
	return RobKongResult{
		CanRob:     true,
		IsWin:      true,
		ScoreBonus: 1,
	}
}

type MahjongScorer struct{}

func (s *MahjongScorer) CalculateFan(hand []common.Card, picked common.Card, extras map[string]bool) int {
	result := CheckWin(hand, picked)
	if !result.IsWin {
		return 0
	}

	fan := result.Score
	if extras["kong_bloom"] {
		fan++
	}
	if extras["rob_kong"] {
		fan++
	}
	if extras["self_draw"] {
		fan++
	}
	if extras["all_pungs"] {
		fan += 2
	}
	return fan
}

func (s *MahjongScorer) ScoreToPoints(fan int, baseScore int64) int64 {
	if fan <= 0 {
		return 0
	}
	points := baseScore
	for i := 1; i < fan && i < 8; i++ {
		points *= 2
	}
	return points
}
