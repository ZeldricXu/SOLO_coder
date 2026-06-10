package testutil

import (
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/protocol"
)

type PlayerFactory struct{}

func NewPlayerFactory() *PlayerFactory {
	return &PlayerFactory{}
}

func (f *PlayerFactory) Human(userID string, nickname string, opts ...PlayerOpt) *common.Player {
	p := &common.Player{
		UserID:   common.UserID(userID),
		Nickname: nickname,
		Avatar:   "default.png",
		Level:    10,
		Elo:      1500.0,
		IsOnline: true,
		IsRobot:  false,
		JoinedAt: time.Now(),
	}
	for _, opt := range opts {
		opt(p)
	}
	return p
}

func (f *PlayerFactory) Robot(userID string, opts ...PlayerOpt) *common.Player {
	p := f.Human(userID, "AI-Player", opts...)
	p.IsRobot = true
	p.IsReady = true
	return p
}

type PlayerOpt func(*common.Player)

func WithElo(elo float64) PlayerOpt {
	return func(p *common.Player) { p.Elo = elo }
}

func WithLevel(level int) PlayerOpt {
	return func(p *common.Player) { p.Level = level }
}

func WithSeat(seat common.SeatID) PlayerOpt {
	return func(p *common.Player) { p.SeatID = seat }
}

func WithReady(ready bool) PlayerOpt {
	return func(p *common.Player) { p.IsReady = ready }
}

func WithHost(isHost bool) PlayerOpt {
	return func(p *common.Player) { p.IsHost = isHost }
}

func WithOnline(online bool) PlayerOpt {
	return func(p *common.Player) { p.IsOnline = online }
}

func CardFactory() *CardF {
	return &CardF{}
}

type CardF struct{}

func (f *CardF) Single(suit, rank int) common.Card {
	return common.Card{Suit: suit, Rank: rank, Index: rank*10 + suit}
}

func (f *CardF) OfSuit(suit int, ranks ...int) []common.Card {
	cards := make([]common.Card, len(ranks))
	for i, r := range ranks {
		cards[i] = common.Card{Suit: suit, Rank: r, Index: r*10 + suit + i*100}
	}
	return cards
}

func (f *CardF) Sequence(suit int, start, count int) []common.Card {
	cards := make([]common.Card, count)
	for i := 0; i < count; i++ {
		cards[i] = common.Card{Suit: suit, Rank: start + i, Index: (start+i)*10 + suit + i*100}
	}
	return cards
}

func (f *CardF) Pairs(rank int, count int) []common.Card {
	cards := make([]common.Card, 0, count)
	for i := 0; i < count; i++ {
		suit := (i % 4) + 1
		cards = append(cards, common.Card{Suit: suit, Rank: rank, Index: rank*10 + suit + i*100})
	}
	return cards
}

func (f *CardF) Triplet(rank int) []common.Card {
	return f.Pairs(rank, 3)
}

func (f *CardF) Quad(rank int) []common.Card {
	return f.Pairs(rank, 4)
}

func (f *CardF) Jokers() []common.Card {
	return []common.Card{
		{Suit: 5, Rank: 14, Index: 1000},
		{Suit: 5, Rank: 15, Index: 1001},
	}
}

func (f *CardF) FullDeck54() []common.Card {
	cards := make([]common.Card, 0, 54)
	idx := 0
	for suit := 1; suit <= 4; suit++ {
		for rank := 1; rank <= 13; rank++ {
			cards = append(cards, common.Card{Suit: suit, Rank: rank, Index: idx})
			idx++
		}
	}
	cards = append(cards, common.Card{Suit: 5, Rank: 14, Index: idx})
	idx++
	cards = append(cards, common.Card{Suit: 5, Rank: 15, Index: idx})
	return cards
}

func (f *CardF) FullDeck52() []common.Card {
	cards := make([]common.Card, 0, 52)
	idx := 0
	for suit := 1; suit <= 4; suit++ {
		for rank := 2; rank <= 14; rank++ {
			cards = append(cards, common.Card{Suit: suit, Rank: rank, Index: idx})
			idx++
		}
	}
	return cards
}

type MessageBuilder struct{}

func NewMessageBuilder() *MessageBuilder {
	return &MessageBuilder{}
}

func (b *MessageBuilder) Action(roomID, userID, actionType string, payload map[string]interface{}) *protocol.Message {
	msg := protocol.NewMessage(protocol.MsgAction, roomID, userID, payload)
	msg.NeedAck = true
	return msg
}

func (b *MessageBuilder) JoinRoom(roomID, userID, nickname, avatar string) *protocol.Message {
	payload := map[string]string{
		"room_id":  roomID,
		"nickname": nickname,
		"avatar":   avatar,
	}
	return protocol.NewMessage(protocol.MsgJoinRoom, roomID, userID, payload)
}

func (b *MessageBuilder) Ready(roomID, userID string, ready bool) *protocol.Message {
	payload := map[string]bool{"ready": ready}
	return protocol.NewMessage(protocol.MsgPlayerReady, roomID, userID, payload)
}

func (b *MessageBuilder) Chat(roomID, userID, content string) *protocol.Message {
	payload := map[string]string{"content": content}
	return protocol.NewMessage(protocol.MsgChat, roomID, userID, payload)
}

func (b *MessageBuilder) Danmaku(roomID, userID, content, color string) *protocol.Message {
	payload := map[string]string{
		"content": content,
		"color":   color,
	}
	return protocol.NewMessage(protocol.MsgDanmaku, roomID, userID, payload)
}

func (b *MessageBuilder) Ack(msgID, userID string) *protocol.Message {
	return protocol.NewAck(msgID, userID)
}

func RoomConfigFactory() *RoomConfigF {
	return &RoomConfigF{}
}

type RoomConfigF struct{}

func (f *RoomConfigF) MahjongFriend() *common.RoomConfig {
	return &common.RoomConfig{
		GameType:        common.GameTypeMahjong,
		MaxPlayers:      4,
		MinPlayers:      4,
		IsFriendRoom:    true,
		BaseScore:       100,
		TurnTimeoutSec:  15,
		ReadyTimeoutSec: 60,
		AllowObserver:   true,
		PlaybackEnabled: true,
	}
}

func (f *RoomConfigF) LandlordMatch() *common.RoomConfig {
	return &common.RoomConfig{
		GameType:        common.GameTypeLandlord,
		MaxPlayers:      3,
		MinPlayers:      3,
		IsFriendRoom:    false,
		BaseScore:       10,
		TurnTimeoutSec:  30,
		ReadyTimeoutSec: 30,
		AllowObserver:   false,
		PlaybackEnabled: true,
	}
}

func (f *RoomConfigF) TexasCash() *common.RoomConfig {
	return &common.RoomConfig{
		GameType:        common.GameTypeTexas,
		MaxPlayers:      9,
		MinPlayers:      2,
		IsFriendRoom:    false,
		BaseScore:       1000,
		TurnTimeoutSec:  30,
		ReadyTimeoutSec: 120,
		AllowObserver:   true,
		PlaybackEnabled: true,
	}
}

type MatchRequestFactory struct{}

func NewMatchRequestFactory() *MatchRequestFactory {
	return &MatchRequestFactory{}
}

func (f *MatchRequestFactory) Human(userID string, gameType common.GameType, elo float64) *common.MatchRequest {
	req := &common.MatchRequest{
		UserID:     common.UserID(userID),
		GameType:   gameType,
		Elo:        elo,
		Level:      10,
		RequestedAt: time.Now(),
		Priority:   0,
	}
	req.Rank = common.EloToRank(elo)
	return req
}

func (f *MatchRequestFactory) HumanWithRank(userID string, gameType common.GameType, elo float64, rank common.RankTier) *common.MatchRequest {
	return &common.MatchRequest{
		UserID:      common.UserID(userID),
		GameType:    gameType,
		Elo:         elo,
		Rank:        rank,
		Level:       10,
		RequestedAt: time.Now(),
		Priority:    0,
	}
}

func (f *MatchRequestFactory) Bronze(userID string, gameType common.GameType) *common.MatchRequest {
	return f.HumanWithRank(userID, gameType, 1100, common.RankBronze)
}

func (f *MatchRequestFactory) Silver(userID string, gameType common.GameType) *common.MatchRequest {
	return f.HumanWithRank(userID, gameType, 1300, common.RankSilver)
}

func (f *MatchRequestFactory) Gold(userID string, gameType common.GameType) *common.MatchRequest {
	return f.HumanWithRank(userID, gameType, 1500, common.RankGold)
}

func (f *MatchRequestFactory) Diamond(userID string, gameType common.GameType) *common.MatchRequest {
	return f.HumanWithRank(userID, gameType, 1700, common.RankDiamond)
}

func (f *MatchRequestFactory) Master(userID string, gameType common.GameType) *common.MatchRequest {
	return f.HumanWithRank(userID, gameType, 1900, common.RankMaster)
}

func (f *MatchRequestFactory) WithRequestedAt(req *common.MatchRequest, t time.Time) *common.MatchRequest {
	req.RequestedAt = t
	return req
}

func (f *MatchRequestFactory) Many(count int, gameType common.GameType, baseElo float64, spread float64) []*common.MatchRequest {
	reqs := make([]*common.MatchRequest, count)
	for i := 0; i < count; i++ {
		elo := baseElo + (float64(i)-float64(count)/2)*spread/float64(count)
		reqs[i] = f.Human("player_"+itoa(i), gameType, elo)
	}
	return reqs
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
