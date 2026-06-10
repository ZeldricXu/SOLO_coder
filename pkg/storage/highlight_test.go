package storage

import (
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

func TestHighlightDetector_DetectBigPatterns(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := make([]common.GameAction, 0)

	for i := 0; i < 10; i++ {
		actions = append(actions, common.GameAction{
			ActionID:   "act_" + itoa(i),
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": "single",
			},
			Timestamp: time.Now(),
			Seq:       int64(i),
		})
	}

	actions = append(actions, common.GameAction{
		ActionID:   "act_bomb",
		RoomID:     roomID,
		UserID:     "player_1",
		ActionType: common.ActionPlayCard,
		Data: map[string]interface{}{
			"pattern": string(game.PatternBomb),
		},
		Timestamp: time.Now(),
		Seq:       10,
	})

	ctx := &GameAnalysisContext{
		RoomID:   roomID,
		GameType: common.GameTypeLandlord,
		Actions:  actions,
	}

	events := hd.Detect(ctx)

	bombEvents := 0
	for _, e := range events {
		if e.Type == HighlightBomb {
			bombEvents++
			assert.Equal(t, "炸弹！", e.Title)
			assert.Equal(t, 8, e.Importance)
			assert.True(t, e.StartSeq <= 10)
			assert.True(t, e.EndSeq >= 10)
		}
	}
	assert.Equal(t, 1, bombEvents)
}

func TestHighlightDetector_DetectRocket(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_rocket",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": string(game.PatternRocket),
			},
			Timestamp: time.Now(),
			Seq:       0,
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:   roomID,
		GameType: common.GameTypeLandlord,
		Actions:  actions,
	}

	events := hd.Detect(ctx)

	var rocketEvent *HighlightEvent
	for _, e := range events {
		if e.Type == HighlightRocket {
			rocketEvent = e
		}
	}
	assert.NotNil(t, rocketEvent)
	assert.Equal(t, "王炸！", rocketEvent.Title)
	assert.Equal(t, 10, rocketEvent.Importance)
	assert.Contains(t, rocketEvent.Players, common.UserID("player_1"))
}

func TestHighlightDetector_DetectKongBloom(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_kong_bloom",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": string(game.PatternKongBloom),
			},
			Timestamp: time.Now(),
			Seq:       0,
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:   roomID,
		GameType: common.GameTypeMahjong,
		Actions:  actions,
	}

	events := hd.Detect(ctx)

	var bloomEvent *HighlightEvent
	for _, e := range events {
		if e.Type == HighlightKongBloom {
			bloomEvent = e
		}
	}
	assert.NotNil(t, bloomEvent)
	assert.Equal(t, "杠上开花！", bloomEvent.Title)
	assert.Equal(t, 9, bloomEvent.Importance)
}

func TestHighlightDetector_DetectThirteenOrphans(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_13orphans",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": string(game.PatternThirteenOrphans),
			},
			Timestamp: time.Now(),
			Seq:       0,
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:   roomID,
		GameType: common.GameTypeMahjong,
		Actions:  actions,
	}

	events := hd.Detect(ctx)

	var event *HighlightEvent
	for _, e := range events {
		if e.Pattern == game.PatternThirteenOrphans {
			event = e
		}
	}
	assert.NotNil(t, event)
	assert.Equal(t, "十三幺！", event.Title)
	assert.Equal(t, 10, event.Importance)
}

func TestHighlightDetector_DetectSevenPairs(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_7pairs",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": string(game.PatternSevenPairs),
			},
			Timestamp: time.Now(),
			Seq:       0,
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:   roomID,
		GameType: common.GameTypeMahjong,
		Actions:  actions,
	}

	events := hd.Detect(ctx)

	var event *HighlightEvent
	for _, e := range events {
		if e.Pattern == game.PatternSevenPairs {
			event = e
		}
	}
	assert.NotNil(t, event)
	assert.Equal(t, "七小对！", event.Title)
	assert.Equal(t, 8, event.Importance)
}

func TestHighlightDetector_DetectBigScoreDiff(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_1",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Timestamp:  time.Now(),
			Seq:        0,
		},
		{
			ActionID:   "act_2",
			RoomID:     roomID,
			UserID:     "player_2",
			ActionType: common.ActionPlayCard,
			Timestamp:  time.Now(),
			Seq:        1,
		},
	}

	settlement := &game.Settlement{
		Results: []game.SettleResult{
			{UserID: "player_1", Score: 200, IsWinner: true, Rank: 1},
			{UserID: "player_2", Score: 50, IsWinner: false, Rank: 2},
			{UserID: "player_3", Score: -250, IsWinner: false, Rank: 3},
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:     roomID,
		GameType:   common.GameTypeLandlord,
		Actions:    actions,
		Settlement: settlement,
	}

	events := hd.Detect(ctx)

	var diffEvent *HighlightEvent
	for _, e := range events {
		if e.Type == HighlightBigScoreDiff {
			diffEvent = e
		}
	}
	assert.NotNil(t, diffEvent)
	assert.Equal(t, int64(450), diffEvent.ScoreDiff)
	assert.Equal(t, "悬殊比分！", diffEvent.Title)
	assert.True(t, diffEvent.Importance >= 5)
}

func TestHighlightDetector_DetectComeback(t *testing.T) {
	hd := NewHighlightDetector(&HighlightThresholds{
		ComebackMinDiff:       50,
		BigScoreDiffMin:       100,
		WinStreakMin:          3,
		LoseStreakMin:         3,
		MinImportance:         1,
		ScoreReversalWindowMs: 60000,
	})

	roomID := common.RoomID("test_room")

	snapshots := make(map[common.UserID][]scoreSnapshot)

	snapshots["player_a"] = []scoreSnapshot{
		{Seq: 0, Timestamp: 1000, Score: 0},
		{Seq: 1, Timestamp: 2000, Score: 100},
		{Seq: 2, Timestamp: 3000, Score: 120},
		{Seq: 3, Timestamp: 4000, Score: 50},
	}

	snapshots["player_b"] = []scoreSnapshot{
		{Seq: 0, Timestamp: 1000, Score: 0},
		{Seq: 1, Timestamp: 2000, Score: 20},
		{Seq: 2, Timestamp: 3000, Score: 50},
		{Seq: 3, Timestamp: 4000, Score: 120},
	}

	ctx := &GameAnalysisContext{
		RoomID:       roomID,
		GameType:     common.GameTypeLandlord,
		Actions:      []common.GameAction{{Seq: 0}, {Seq: 1}, {Seq: 2}, {Seq: 3}},
		PlayerScores: snapshots,
	}

	events := hd.Detect(ctx)

	var comebackEvent *HighlightEvent
	for _, e := range events {
		if e.Type == HighlightComeback {
			comebackEvent = e
		}
	}
	assert.NotNil(t, comebackEvent)
	assert.Equal(t, "极限反杀！", comebackEvent.Title)
	assert.Contains(t, comebackEvent.Players, common.UserID("player_a"))
	assert.Contains(t, comebackEvent.Players, common.UserID("player_b"))
}

func TestHighlightDetector_DetectWinStreak(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")

	snapshots := make(map[common.UserID][]scoreSnapshot)

	playerSnapshots := make([]scoreSnapshot, 5)
	baseScore := int64(0)
	for i := 0; i < 5; i++ {
		baseScore += 50
		playerSnapshots[i] = scoreSnapshot{
			Seq:       int64(i),
			Timestamp: int64(i * 1000),
			Score:     baseScore,
		}
	}
	snapshots["player_winner"] = playerSnapshots

	settlement := &game.Settlement{
		Results: []game.SettleResult{
			{UserID: "player_winner", Score: 250, IsWinner: true, Rank: 1},
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:       roomID,
		GameType:     common.GameTypeMahjong,
		Actions:      []common.GameAction{{Seq: 0}, {Seq: 1}, {Seq: 2}, {Seq: 3}, {Seq: 4}},
		Settlement:   settlement,
		PlayerScores: snapshots,
	}

	events := hd.Detect(ctx)

	var streakEvent *HighlightEvent
	for _, e := range events {
		if e.Type == HighlightWinStreak {
			streakEvent = e
		}
	}
	assert.NotNil(t, streakEvent)
	assert.Equal(t, "连赢 4 局！", streakEvent.Title)
	assert.Equal(t, 1, len(streakEvent.Players))
	assert.Contains(t, streakEvent.Players, common.UserID("player_winner"))
	assert.True(t, streakEvent.Importance >= 5)
}

func TestHighlightDetector_BuildScoreSnapshots(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_1",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Timestamp:  time.UnixMilli(1000),
			Seq:        0,
		},
		{
			ActionID:   "act_2",
			RoomID:     roomID,
			UserID:     "player_2",
			ActionType: common.ActionPlayCard,
			Timestamp:  time.UnixMilli(2000),
			Seq:        1,
		},
		{
			ActionID:   "act_3",
			RoomID:     roomID,
			UserID:     "player_3",
			ActionType: common.ActionPlayCard,
			Timestamp:  time.UnixMilli(3000),
			Seq:        2,
		},
	}

	settlement := &game.Settlement{
		Results: []game.SettleResult{
			{UserID: "player_1", Score: 100, IsWinner: true, Rank: 1},
			{UserID: "player_2", Score: -50, IsWinner: false, Rank: 2},
			{UserID: "player_3", Score: -50, IsWinner: false, Rank: 3},
		},
	}

	snapshots := hd.BuildScoreSnapshots(actions, settlement)

	assert.Len(t, snapshots, 3)
	assert.Len(t, snapshots["player_1"], 4)
	assert.Equal(t, int64(0), snapshots["player_1"][0].Score)
	assert.Equal(t, int64(100), snapshots["player_1"][3].Score)
}

func TestHighlightDetector_GetHighlightClips(t *testing.T) {
	hd := NewHighlightDetector(nil)

	roomID := common.RoomID("test_room")
	actions := []common.GameAction{
		{
			ActionID:   "act_bomb",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": string(game.PatternBomb),
			},
			Timestamp: time.Now(),
			Seq:       0,
		},
		{
			ActionID:   "act_rocket",
			RoomID:     roomID,
			UserID:     "player_2",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": string(game.PatternRocket),
			},
			Timestamp: time.Now(),
			Seq:       1,
		},
		{
			ActionID:   "act_single",
			RoomID:     roomID,
			UserID:     "player_1",
			ActionType: common.ActionPlayCard,
			Data: map[string]interface{}{
				"pattern": "single",
			},
			Timestamp: time.Now(),
			Seq:       2,
		},
	}

	settlement := &game.Settlement{
		Results: []game.SettleResult{
			{UserID: "player_1", Score: 200, IsWinner: true, Rank: 1},
			{UserID: "player_2", Score: -100, IsWinner: false, Rank: 2},
			{UserID: "player_3", Score: -100, IsWinner: false, Rank: 3},
		},
	}

	ctx := &GameAnalysisContext{
		RoomID:     roomID,
		GameType:   common.GameTypeLandlord,
		Actions:    actions,
		Settlement: settlement,
	}

	clips := hd.GetHighlightClips(ctx, 2)
	assert.Len(t, clips, 2)

	assert.True(t, clips[0].Importance >= clips[1].Importance)
}

func TestHighlightDetector_EmptyActions(t *testing.T) {
	hd := NewHighlightDetector(nil)

	ctx := &GameAnalysisContext{
		RoomID:   "test_room",
		GameType: common.GameTypeLandlord,
		Actions:  []common.GameAction{},
	}

	events := hd.Detect(ctx)
	assert.Empty(t, events)
}

func TestHighlightDetector_NilContext(t *testing.T) {
	hd := NewHighlightDetector(nil)

	events := hd.Detect(nil)
	assert.Empty(t, events)
}
