package game

import (
	"github.com/studio/gameroom/pkg/common"
)

type CardPattern string

const (
	PatternSingle      CardPattern = "single"
	PatternPair        CardPattern = "pair"
	PatternTriplet     CardPattern = "triplet"
	PatternTripletOne  CardPattern = "triplet_one"
	PatternTripletTwo  CardPattern = "triplet_two"
	PatternStraight    CardPattern = "straight"
	PatternFlush       CardPattern = "flush"
	PatternFullHouse   CardPattern = "full_house"
	PatternFourKind    CardPattern = "four_kind"
	PatternStraightFlush CardPattern = "straight_flush"
	PatternRoyalFlush  CardPattern = "royal_flush"
	PatternSequence    CardPattern = "sequence"
	PatternBomb        CardPattern = "bomb"
	PatternUnknown     CardPattern = "unknown"
)

type PatternResult struct {
	Pattern CardPattern
	Weight  int
	Cards   []common.Card
	Valid   bool
}

type CardPatternValidator interface {
	Validate(cards []common.Card) PatternResult
	Compare(a, b PatternResult) int
}
