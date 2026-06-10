package storage

import (
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
	"github.com/studio/gameroom/pkg/room"
)

type PlaybackPlayer struct {
	store  *MongoStore
}

func NewPlaybackPlayer(store *MongoStore) *PlaybackPlayer {
	return &PlaybackPlayer{store: store}
}

type PlaybackState struct {
	Actions   []common.GameAction
	Current int
	RoomID  common.RoomID
}

func (p *PlaybackPlayer) Load(roomID common.RoomID) (*PlaybackState, error) {
	actions, err := p.store.GetActionsForPlayback(roomID)
	if err != nil {
		return nil, err
	}
	return &PlaybackState{
		Actions: actions,
		Current: 0,
		RoomID:  roomID,
	}, nil
}

func (ps *PlaybackState) Next() (*common.GameAction, bool) {
	if ps.Current >= len(ps.Actions) {
		return nil, false
	}
	action := ps.Actions[ps.Current]
	ps.Current++
	return &action, true
}

func (ps *PlaybackState) Seek(seq int64) int {
	for i, a := range ps.Actions {
		if a.Seq >= seq {
			ps.Current = i
			return i
		}
	}
	return -1
}

func (ps *PlaybackState) Reset() {
	ps.Current = 0
}

func (ps *PlaybackState) Total() int {
	return len(ps.Actions)
}

func (p *PlaybackPlayer) ReplayAll(roomID common.RoomID, rule game.GameRule, handler func(*common.GameAction, *game.GameContext) error) error {
	state, err := p.Load(roomID)
	if err != nil {
		return err
	}

	ctx := game.NewGameContext(roomID)
	ctx.State = common.StatePlaying

	for {
		action, ok := state.Next()
		if !ok {
			break
		}
		if handler != nil {
			handler(action, ctx)
		}
	}
	return nil
}

type StatsAggregator struct {
	mongo *MongoStore
}

func NewStatsAggregator(mongo *MongoStore) *StatsAggregator {
	return &StatsAggregator{mongo: mongo}
}

func (sa *StatsAggregator) RecordGameResult(r *room.Room, settlement *game.Settlement) error {
	rec := &GameRecord{
		RoomID:     r.ID,
		GameType:   r.Config.GameType,
		StartTime:  r.CreatedAt,
		EndTime:    time.Now(),
		DurationSec: int64(time.Since(r.CreatedAt).Seconds()),
		Players:    make([]PlayerRecord, 0, len(settlement.Results)),
		IsFinished: true,
	}

	for _, res := range settlement.Results {
		pr := PlayerRecord{
			UserID:   res.UserID,
			Score:    res.Score,
			Rank:     res.Rank,
			IsWinner: res.IsWinner,
		}
		if p, ok := r.GetPlayer(res.UserID); ok {
			pr.Nickname = p.Nickname
			pr.IsRobot = p.IsRobot
		}
		rec.Players = append(rec.Players, pr)
		if res.IsWinner {
			rec.Winners = append(rec.Winners, res.UserID)
		}

		sa.mongo.UpdatePlayerStats(res.UserID, r.Config.GameType, pr)
		sa.mongo.RecordDailyStats(res.UserID, r.Config.GameType, res.IsWinner, res.Score)
	}

	return sa.mongo.SaveGameRecord(rec)
}
