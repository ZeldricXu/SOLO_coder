package game

import (
	"github.com/studio/gameroom/pkg/common"
)

type ShuffleStrategy interface {
	Shuffle(cards []common.Card) []common.Card
	Name() string
}

type RandomShuffle struct{}

func (s *RandomShuffle) Shuffle(cards []common.Card) []common.Card {
	return common.ShuffleCards(cards)
}

func (s *RandomShuffle) Name() string {
	return "random"
}

type FisherYatesShuffle struct{}

func (s *FisherYatesShuffle) Shuffle(cards []common.Card) []common.Card {
	return common.ShuffleCards(cards)
}

func (s *FisherYatesShuffle) Name() string {
	return "fisher_yates"
}

var shuffleStrategies = map[string]ShuffleStrategy{
	"random":      &RandomShuffle{},
	"fisher_yates": &FisherYatesShuffle{},
}

func GetShuffleStrategy(name string) ShuffleStrategy {
	if s, ok := shuffleStrategies[name]; ok {
		return s
	}
	return &RandomShuffle{}
}

func RegisterShuffleStrategy(name string, strategy ShuffleStrategy) {
	shuffleStrategies[name] = strategy
}
