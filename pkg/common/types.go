package common

import "time"

type UserID string
type RoomID string
type GameType string
type SeatID int

const (
	GameTypeMahjong  GameType = "mahjong"
	GameTypeLandlord GameType = "landlord"
	GameTypeTexas    GameType = "texas"
)

type Player struct {
	UserID     UserID     `json:"user_id" bson:"user_id"`
	Nickname   string     `json:"nickname" bson:"nickname"`
	Avatar     string     `json:"avatar" bson:"avatar"`
	Level      int        `json:"level" bson:"level"`
	Elo        float64    `json:"elo" bson:"elo"`
	SeatID     SeatID     `json:"seat_id" bson:"seat_id"`
	IsReady    bool       `json:"is_ready" bson:"is_ready"`
	IsHost     bool       `json:"is_host" bson:"is_host"`
	IsOnline   bool       `json:"is_online" bson:"is_online"`
	IsRobot    bool       `json:"is_robot" bson:"is_robot"`
	JoinedAt   time.Time  `json:"joined_at" bson:"joined_at"`
	Score      int64      `json:"score" bson:"score"`
	Connection string     `json:"-" bson:"-"`
}

type Card struct {
	Suit  int `json:"suit" bson:"suit"`
	Rank  int `json:"rank" bson:"rank"`
	Index int `json:"index" bson:"index"`
}

type GameState string

const (
	StateWaiting   GameState = "waiting"
	StatePlaying   GameState = "playing"
	StatePaused    GameState = "paused"
	StateSettling  GameState = "settling"
	StateFinished  GameState = "finished"
	StateDisbanded GameState = "disbanded"
)

type ActionType string

const (
	ActionPlayCard    ActionType = "play_card"
	ActionDiscard     ActionType = "discard"
	ActionDraw        ActionType = "draw"
	ActionPass        ActionType = "pass"
	ActionCall        ActionType = "call"
	ActionRaise       ActionType = "raise"
	ActionFold        ActionType = "fold"
	ActionCheck       ActionType = "check"
	ActionReady       ActionType = "ready"
	ActionCancelReady ActionType = "cancel_ready"
	ActionChat        ActionType = "chat"
)

type GameAction struct {
	ActionID   string                 `json:"action_id" bson:"action_id"`
	RoomID     RoomID                 `json:"room_id" bson:"room_id"`
	UserID     UserID                 `json:"user_id" bson:"user_id"`
	ActionType ActionType             `json:"action_type" bson:"action_type"`
	Data       map[string]interface{} `json:"data" bson:"data"`
	Timestamp  time.Time              `json:"timestamp" bson:"timestamp"`
	Seq        int64                  `json:"seq" bson:"seq"`
}

type RoomConfig struct {
	GameType        GameType `json:"game_type" bson:"game_type"`
	MaxPlayers      int      `json:"max_players" bson:"max_players"`
	MinPlayers      int      `json:"min_players" bson:"min_players"`
	IsFriendRoom    bool     `json:"is_friend_room" bson:"is_friend_room"`
	InviteCode      string   `json:"invite_code,omitempty" bson:"invite_code,omitempty"`
	BaseScore       int64    `json:"base_score" bson:"base_score"`
	TurnTimeoutSec  int      `json:"turn_timeout_sec" bson:"turn_timeout_sec"`
	ReadyTimeoutSec int      `json:"ready_timeout_sec" bson:"ready_timeout_sec"`
	AllowObserver   bool     `json:"allow_observer" bson:"allow_observer"`
	PlaybackEnabled bool     `json:"playback_enabled" bson:"playback_enabled"`
}

type MatchRequest struct {
	UserID     UserID   `json:"user_id" bson:"user_id"`
	GameType   GameType `json:"game_type" bson:"game_type"`
	Elo        float64  `json:"elo" bson:"elo"`
	Level      int      `json:"level" bson:"level"`
	Rank       RankTier `json:"rank" bson:"rank"`
	RequestedAt time.Time `json:"requested_at" bson:"requested_at"`
	Priority   int      `json:"priority" bson:"priority"`
}

type RankTier int

const (
	RankBronze   RankTier = 1
	RankSilver   RankTier = 2
	RankGold     RankTier = 3
	RankDiamond  RankTier = 4
	RankMaster   RankTier = 5
)

func (r RankTier) String() string {
	switch r {
	case RankBronze:
		return "bronze"
	case RankSilver:
		return "silver"
	case RankGold:
		return "gold"
	case RankDiamond:
		return "diamond"
	case RankMaster:
		return "master"
	default:
		return "unknown"
	}
}

func EloToRank(elo float64) RankTier {
	switch {
	case elo < 1200:
		return RankBronze
	case elo < 1400:
		return RankSilver
	case elo < 1600:
		return RankGold
	case elo < 1800:
		return RankDiamond
	default:
		return RankMaster
	}
}
