package landlord

import (
	"sort"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type LandlordPatternValidator struct{}

type rankCount struct {
	rank  int
	count int
}

func countRanks(cards []common.Card) []rankCount {
	counts := make(map[int]int)
	for _, c := range cards {
		counts[c.Rank]++
	}
	result := make([]rankCount, 0, len(counts))
	for rank, cnt := range counts {
		result = append(result, rankCount{rank: rank, count: cnt})
	}
	sort.Slice(result, func(i, j int) bool {
		if result[i].count == result[j].count {
			return result[i].rank < result[j].rank
		}
		return result[i].count > result[j].count
	})
	return result
}

func findConsecutive(counts []rankCount, countPerRank, minLength int) (int, int, bool) {
	singles := make([]int, 0)
	for _, rc := range counts {
		if rc.count >= countPerRank {
			singles = append(singles, rc.rank)
		}
	}
	sort.Ints(singles)

	if len(singles) < minLength {
		return 0, 0, false
	}

	bestStart := 0
	bestEnd := 0
	currentStart := 0

	for i := 1; i < len(singles); i++ {
		if singles[i] == singles[i-1]+1 && singles[i] < 15 {
			if i-currentStart+1 >= minLength {
				if i-currentStart > bestEnd-bestStart {
					bestStart = currentStart
					bestEnd = i
				}
			}
		} else {
			currentStart = i
		}
	}

	if bestEnd-bestStart+1 >= minLength {
		return singles[bestStart], singles[bestEnd], true
	}
	return 0, 0, false
}

func (v *LandlordPatternValidator) Validate(cards []common.Card) game.PatternResult {
	n := len(cards)
	if n == 0 {
		return game.PatternResult{Pattern: game.PatternUnknown, Valid: false}
	}

	counts := countRanks(cards)
	if n == 1 {
		return game.PatternResult{
			Pattern: game.PatternSingle,
			Weight:  cards[0].Rank,
			Cards:   cards,
			Valid:   true,
		}
	}

	if n == 2 {
		if counts[0].count == 2 {
			return game.PatternResult{
				Pattern: game.PatternPair,
				Weight:  counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		hasSmall := false
		hasBig := false
		for _, c := range cards {
			if c.Rank == 14 && c.Suit == 5 {
				hasSmall = true
			}
			if c.Rank == 15 && c.Suit == 5 {
				hasBig = true
			}
		}
		if hasSmall && hasBig {
			return game.PatternResult{
				Pattern: game.PatternRocket,
				Weight:  1000,
				Cards:   cards,
				Valid:   true,
			}
		}
		return game.PatternResult{Pattern: game.PatternUnknown, Cards: cards, Valid: false}
	}

	if n == 3 {
		if counts[0].count == 3 {
			return game.PatternResult{
				Pattern: game.PatternTriplet,
				Weight:  counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		return game.PatternResult{Pattern: game.PatternUnknown, Cards: cards, Valid: false}
	}

	if n == 4 {
		if counts[0].count == 4 {
			return game.PatternResult{
				Pattern: game.PatternBomb,
				Weight:  100 + counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		if counts[0].count == 3 && counts[1].count == 1 {
			return game.PatternResult{
				Pattern: game.PatternTripletOne,
				Weight:  counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}

	if n == 5 {
		if counts[0].count == 3 && counts[1].count == 2 {
			return game.PatternResult{
				Pattern: game.PatternTripletTwo,
				Weight:  counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}

	if counts[0].count == 4 {
		if n == 5 {
			return game.PatternResult{
				Pattern: game.PatternFourTwo,
				Weight:  80 + counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		if n == 6 && len(counts) == 3 && counts[1].count == 1 && counts[2].count == 1 {
			return game.PatternResult{
				Pattern: game.PatternFourTwo,
				Weight:  80 + counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
		if n == 6 && len(counts) == 2 && counts[1].count == 2 {
			return game.PatternResult{
				Pattern: game.PatternFourTwo,
				Weight:  80 + counts[0].rank,
				Cards:   cards,
				Valid:   true,
			}
		}
	}

	if start, end, ok := findConsecutive(counts, 1, 5); ok && n == end-start+1 {
		return game.PatternResult{
			Pattern: game.PatternStraight,
			Weight:  end,
			Cards:   cards,
			Valid:   true,
		}
	}

	if start, end, ok := findConsecutive(counts, 2, 3); ok && n == (end-start+1)*2 {
		return game.PatternResult{
			Pattern: game.PatternDoubleStraight,
			Weight:  end,
			Cards:   cards,
			Valid:   true,
		}
	}

	if start, end, ok := findConsecutive(counts, 3, 2); ok {
		planeLen := end - start + 1
		if n == planeLen*3 {
			return game.PatternResult{
				Pattern: game.PatternAirplane,
				Weight:  end,
				Cards:   cards,
				Valid:   true,
			}
		}
		if n == planeLen*4 {
			return game.PatternResult{
				Pattern: game.PatternAirplaneOne,
				Weight:  end,
				Cards:   cards,
				Valid:   true,
			}
		}
		if n == planeLen*5 {
			return game.PatternResult{
				Pattern: game.PatternAirplaneTwo,
				Weight:  end,
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

func (v *LandlordPatternValidator) Compare(a, b game.PatternResult) int {
	if a.Pattern == game.PatternRocket {
		return 1
	}
	if b.Pattern == game.PatternRocket {
		return -1
	}
	if a.Pattern == game.PatternBomb && b.Pattern != game.PatternBomb && b.Pattern != game.PatternRocket {
		return 1
	}
	if b.Pattern == game.PatternBomb && a.Pattern != game.PatternBomb && a.Pattern != game.PatternRocket {
		return -1
	}
	if a.Pattern != b.Pattern {
		return 0
	}
	if a.Pattern == game.PatternAirplane || a.Pattern == game.PatternAirplaneOne || a.Pattern == game.PatternAirplaneTwo {
		lenA := len(a.Cards)
		lenB := len(b.Cards)
		if lenA != lenB {
			return 0
		}
	}
	if a.Weight > b.Weight {
		return 1
	} else if a.Weight < b.Weight {
		return -1
	}
	return 0
}
