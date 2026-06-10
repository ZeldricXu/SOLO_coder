package search

import "math"

type BM25Config struct {
	K1 float64
	B  float64
}

func DefaultBM25Config() BM25Config {
	return BM25Config{
		K1: 1.5,
		B:  0.75,
	}
}

type BM25Scorer struct {
	config        BM25Config
	totalDocs     int
	avgDocLength  float64
	docLengths    map[uint]int
	docFrequencies map[string]int
}

func NewBM25Scorer(config BM25Config) *BM25Scorer {
	return &BM25Scorer{
		config:         config,
		docLengths:     make(map[uint]int),
		docFrequencies: make(map[string]int),
	}
}

func (s *BM25Scorer) SetTotalDocs(n int) {
	s.totalDocs = n
}

func (s *BM25Scorer) SetAvgDocLength(avg float64) {
	s.avgDocLength = avg
}

func (s *BM25Scorer) SetDocLengths(lengths map[uint]int) {
	s.docLengths = lengths
	total := 0
	for _, l := range lengths {
		total += l
	}
	if len(lengths) > 0 {
		s.avgDocLength = float64(total) / float64(len(lengths))
	}
}

func (s *BM25Scorer) SetDocFrequencies(df map[string]int) {
	s.docFrequencies = df
}

func (s *BM25Scorer) Score(docID uint, termFreq map[string]int) float64 {
	if s.avgDocLength == 0 || s.totalDocs == 0 {
		return 0
	}

	docLen, ok := s.docLengths[docID]
	if !ok {
		return 0
	}

	score := 0.0
	k1 := s.config.K1
	b := s.config.B

	for term, tf := range termFreq {
		df, ok := s.docFrequencies[term]
		if !ok || df == 0 {
			continue
		}

		idf := math.Log(1 + (float64(s.totalDocs)-float64(df)+0.5)/(float64(df)+0.5))

		numerator := float64(tf) * (k1 + 1)
		denominator := float64(tf) + k1*(1-b+b*float64(docLen)/s.avgDocLength)

		score += idf * numerator / denominator
	}

	return score
}

func (s *BM25Scorer) ScoreWithPositions(docID uint, terms []string, termFreq, positionFreq map[string]int) float64 {
	baseScore := s.Score(docID, termFreq)

	if len(terms) <= 1 {
		return baseScore
	}

	proximityBonus := 0.0
	for i := 0; i < len(terms)-1; i++ {
		for j := i + 1; j < len(terms); j++ {
			freq1 := positionFreq[terms[i]]
			freq2 := positionFreq[terms[j]]
			if freq1 > 0 && freq2 > 0 {
				proximityBonus += math.Min(float64(freq1), float64(freq2)) * 0.1
			}
		}
	}

	return baseScore * (1 + proximityBonus)
}
