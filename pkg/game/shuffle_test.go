package game

import (
	"math"
	"testing"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
)

func TestRandomShuffle_Uniformity(t *testing.T) {
	const (
		numRuns   = 100000
		deckSize  = 54
		tolerance = 0.05
	)

	shuffler := &RandomShuffle{}
	baseDeck := makeDeck54()

	positionCounts := make([][]int, deckSize)
	for i := range positionCounts {
		positionCounts[i] = make([]int, deckSize)
	}

	for run := 0; run < numRuns; run++ {
		shuffled := shuffler.Shuffle(baseDeck)
		for pos, card := range shuffled {
			positionCounts[pos][card.Index]++
		}
	}

	expectedCount := float64(numRuns) / float64(deckSize)
	expectedProb := 1.0 / float64(deckSize)

	maxRelDeviation := 0.0
	totalRelDeviation := 0.0
	cellsOutsideTolerance := 0

	chiSquare := 0.0

	for pos := 0; pos < deckSize; pos++ {
		for cardIdx := 0; cardIdx < deckSize; cardIdx++ {
			actual := float64(positionCounts[pos][cardIdx])
			relDev := math.Abs(actual-expectedCount) / expectedCount

			totalRelDeviation += relDev
			if relDev > maxRelDeviation {
				maxRelDeviation = relDev
			}
			if relDev > tolerance {
				cellsOutsideTolerance++
			}

			diff := actual - expectedCount
			chiSquare += diff * diff / expectedCount
		}
	}

	avgRelDeviation := totalRelDeviation / float64(deckSize*deckSize)
	totalCells := deckSize * deckSize

	t.Logf("Expected count per cell: %.1f", expectedCount)
	t.Logf("Expected probability: %.4f (%.2f%%)", expectedProb, expectedProb*100)
	t.Logf("Max relative deviation: %.4f (%.2f%%)", maxRelDeviation, maxRelDeviation*100)
	t.Logf("Avg relative deviation: %.4f (%.2f%%)", avgRelDeviation, avgRelDeviation*100)
	t.Logf("Cells exceeding %.0f%% tolerance: %d / %d (%.1f%%)",
		tolerance*100, cellsOutsideTolerance, totalCells,
		float64(cellsOutsideTolerance)/float64(totalCells)*100)
	t.Logf("Chi-square statistic: %.2f (df=%d)", chiSquare, totalCells-1)

	assert.Less(t, maxRelDeviation, 0.10,
		"max relative deviation should be within 10%% (statistically reasonable for 100k runs)")
	assert.Less(t, avgRelDeviation, 0.03,
		"average relative deviation should be within 3%%")
	assert.Less(t, float64(cellsOutsideTolerance), float64(totalCells)*0.05,
		"fewer than 5%% of cells should exceed 5%% relative deviation")

	df := totalCells - 1
	criticalValue := float64(df) + 4*math.Sqrt(2*float64(df))
	t.Logf("Chi-square critical value (approx 4σ): %.2f", criticalValue)
	assert.Less(t, chiSquare, criticalValue,
		"chi-square test should pass (distribution is uniform)")
}

func TestFisherYatesShuffle_Uniformity(t *testing.T) {
	const (
		numRuns   = 100000
		deckSize  = 54
		tolerance = 0.05
	)

	shuffler := &FisherYatesShuffle{}
	baseDeck := makeDeck54()

	positionCounts := make([][]int, deckSize)
	for i := range positionCounts {
		positionCounts[i] = make([]int, deckSize)
	}

	for run := 0; run < numRuns; run++ {
		shuffled := shuffler.Shuffle(baseDeck)
		for pos, card := range shuffled {
			positionCounts[pos][card.Index]++
		}
	}

	expectedCount := float64(numRuns) / float64(deckSize)
	maxRelDeviation := 0.0
	totalRelDeviation := 0.0
	cellsOutside := 0
	totalCells := deckSize * deckSize
	chiSquare := 0.0

	for pos := 0; pos < deckSize; pos++ {
		for cardIdx := 0; cardIdx < deckSize; cardIdx++ {
			actual := float64(positionCounts[pos][cardIdx])
			relDev := math.Abs(actual-expectedCount) / expectedCount
			totalRelDeviation += relDev
			if relDev > maxRelDeviation {
				maxRelDeviation = relDev
			}
			if relDev > tolerance {
				cellsOutside++
			}
			diff := actual - expectedCount
			chiSquare += diff * diff / expectedCount
		}
	}

	avgRelDeviation := totalRelDeviation / float64(totalCells)
	t.Logf("FisherYates: max dev=%.4f (%.2f%%), avg dev=%.4f (%.2f%%)",
		maxRelDeviation, maxRelDeviation*100,
		avgRelDeviation, avgRelDeviation*100)
	t.Logf("FisherYates: chi-square=%.2f", chiSquare)

	assert.Less(t, maxRelDeviation, 0.10, "FisherYates max deviation < 10%")
	assert.Less(t, float64(cellsOutside), float64(totalCells)*0.05,
		"fewer than 5%% cells outside 5%% tolerance")
}

func TestShuffle_PreservesAllCards(t *testing.T) {
	shuffler := &RandomShuffle{}
	original := makeDeck54()
	shuffled := shuffler.Shuffle(original)

	assert.Equal(t, len(original), len(shuffled), "shuffle should preserve deck size")

	originalSet := make(map[int]bool)
	for _, c := range original {
		originalSet[c.Index] = true
	}
	shuffledSet := make(map[int]bool)
	for _, c := range shuffled {
		shuffledSet[c.Index] = true
	}

	assert.Equal(t, originalSet, shuffledSet, "no cards lost or duplicated")
}

func TestShuffle_NonTrivialPermutation(t *testing.T) {
	shuffler := &RandomShuffle{}
	original := makeDeck54()

	differentCount := 0
	runs := 1000

	for i := 0; i < runs; i++ {
		shuffled := shuffler.Shuffle(original)
		same := true
		for j, c := range shuffled {
			if c.Index != original[j].Index {
				same = false
				break
			}
		}
		if !same {
			differentCount++
		}
	}

	assert.Greater(t, float64(differentCount)/float64(runs), 0.99,
		"at least 99%% of shuffles produce different order")
}

func TestShuffle_StrategyConsistency(t *testing.T) {
	strategies := []ShuffleStrategy{
		&RandomShuffle{},
		&FisherYatesShuffle{},
	}

	original := makeDeck54()
	origSum := 0
	origMin := original[0].Index
	origMax := original[0].Index
	for _, c := range original {
		origSum += c.Index
		if c.Index < origMin {
			origMin = c.Index
		}
		if c.Index > origMax {
			origMax = c.Index
		}
	}

	for _, s := range strategies {
		result := s.Shuffle(original)
		assert.Equal(t, len(original), len(result), "%s: same length", s.Name())

		sum := 0
		for _, c := range result {
			sum += c.Index
		}
		assert.Equal(t, origSum, sum, "%s: same sum of indices", s.Name())

		minIdx := result[0].Index
		maxIdx := result[0].Index
		for _, c := range result {
			if c.Index < minIdx {
				minIdx = c.Index
			}
			if c.Index > maxIdx {
				maxIdx = c.Index
			}
		}
		assert.Equal(t, origMin, minIdx, "%s: same min index", s.Name())
		assert.Equal(t, origMax, maxIdx, "%s: same max index", s.Name())
	}
}

func TestGetShuffleStrategy(t *testing.T) {
	assert.NotNil(t, GetShuffleStrategy("random"))
	assert.NotNil(t, GetShuffleStrategy("fisher_yates"))
	assert.NotNil(t, GetShuffleStrategy("unknown"), "unknown returns default strategy")

	custom := &RandomShuffle{}
	RegisterShuffleStrategy("custom_test", custom)
	assert.Equal(t, custom, GetShuffleStrategy("custom_test"))
}

func BenchmarkRandomShuffle(b *testing.B) {
	shuffler := &RandomShuffle{}
	deck := makeDeck54()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = shuffler.Shuffle(deck)
	}
}

func BenchmarkFisherYatesShuffle(b *testing.B) {
	shuffler := &FisherYatesShuffle{}
	deck := makeDeck54()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = shuffler.Shuffle(deck)
	}
}

func makeDeck54() []common.Card {
	cards := make([]common.Card, 54)
	idx := 0
	for suit := 1; suit <= 4; suit++ {
		for rank := 1; rank <= 13; rank++ {
			cards[idx] = common.Card{Suit: suit, Rank: rank, Index: idx}
			idx++
		}
	}
	cards[52] = common.Card{Suit: 5, Rank: 14, Index: 52}
	cards[53] = common.Card{Suit: 5, Rank: 15, Index: 53}
	return cards
}
