package game

import (
	"github.com/studio/gameroom/pkg/common"
)

type CardPattern string

const (
	PatternSingle        CardPattern = "single"
	PatternPair          CardPattern = "pair"
	PatternTriplet       CardPattern = "triplet"
	PatternTripletOne    CardPattern = "triplet_one"
	PatternTripletTwo    CardPattern = "triplet_two"
	PatternStraight      CardPattern = "straight"
	PatternDoubleStraight CardPattern = "double_straight"
	PatternTripleStraight CardPattern = "triple_straight"
	PatternAirplane      CardPattern = "airplane"
	PatternAirplaneOne   CardPattern = "airplane_one"
	PatternAirplaneTwo   CardPattern = "airplane_two"
	PatternFlush         CardPattern = "flush"
	PatternFullHouse     CardPattern = "full_house"
	PatternFourKind      CardPattern = "four_kind"
	PatternFourTwo       CardPattern = "four_two"
	PatternStraightFlush CardPattern = "straight_flush"
	PatternRoyalFlush    CardPattern = "royal_flush"
	PatternSequence      CardPattern = "sequence"
	PatternBomb          CardPattern = "bomb"
	PatternRocket        CardPattern = "rocket"
	PatternChow          CardPattern = "chow"
	PatternPung          CardPattern = "pung"
	PatternKong          CardPattern = "kong"
	PatternWin           CardPattern = "win"
	PatternThirteenOrphans CardPattern = "thirteen_orphans"
	PatternSevenPairs    CardPattern = "seven_pairs"
	PatternKongBloom     CardPattern = "kong_bloom"
	PatternRobKong       CardPattern = "rob_kong"
	PatternUnknown       CardPattern = "unknown"
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
