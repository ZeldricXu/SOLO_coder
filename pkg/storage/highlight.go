package storage

import (
	"math"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
)

type HighlightType string

const (
	HighlightBigPattern     HighlightType = "big_pattern"
	HighlightComeback       HighlightType = "comeback"
	HighlightWinStreak      HighlightType = "win_streak"
	HighlightLoseStreak     HighlightType = "lose_streak"
	HighlightBigScoreDiff   HighlightType = "big_score_diff"
	HighlightKongBloom      HighlightType = "kong_bloom"
	HighlightRobKong        HighlightType = "rob_kong"
	HighlightRocket         HighlightType = "rocket"
	HighlightBomb           HighlightType = "bomb"
)

type HighlightEvent struct {
	ID          string                 `json:"id"`
	RoomID      common.RoomID          `json:"room_id"`
	Type        HighlightType          `json:"type"`
	Title       string                 `json:"title"`
	Description string                 `json:"description"`
	StartSeq    int64                  `json:"start_seq"`
	EndSeq      int64                  `json:"end_seq"`
	StartTime   int64                  `json:"start_time"`
	EndTime     int64                  `json:"end_time"`
	ScoreDiff   int64                  `json:"score_diff,omitempty"`
	Pattern     game.CardPattern       `json:"pattern,omitempty"`
	Players     []common.UserID        `json:"players,omitempty"`
	Metadata    map[string]interface{} `json:"metadata,omitempty"`
	Importance  int                    `json:"importance"`
}

type HighlightDetector struct {
	thresholds *HighlightThresholds
}

type HighlightThresholds struct {
	ComebackMinDiff       int64
	BigScoreDiffMin       int64
	WinStreakMin          int
	LoseStreakMin         int
	MinImportance         int
	ScoreReversalWindowMs int64
}

func DefaultHighlightThresholds() *HighlightThresholds {
	return &HighlightThresholds{
		ComebackMinDiff:       50,
		BigScoreDiffMin:       100,
		WinStreakMin:          3,
		LoseStreakMin:         3,
		MinImportance:         1,
		ScoreReversalWindowMs: 60000,
	}
}

func NewHighlightDetector(thresholds *HighlightThresholds) *HighlightDetector {
	if thresholds == nil {
		thresholds = DefaultHighlightThresholds()
	}
	return &HighlightDetector{thresholds: thresholds}
}

type GameAnalysisContext struct {
	RoomID       common.RoomID
	GameType     common.GameType
	Actions      []common.GameAction
	Settlement   *game.Settlement
	PlayerScores map[common.UserID][]scoreSnapshot
}

type scoreSnapshot struct {
	Seq       int64
	Timestamp int64
	Score     int64
}

func (hd *HighlightDetector) Detect(ctx *GameAnalysisContext) []*HighlightEvent {
	events := make([]*HighlightEvent, 0)

	if ctx == nil || len(ctx.Actions) == 0 {
		return events
	}

	if patternEvents := hd.detectBigPatterns(ctx); len(patternEvents) > 0 {
		events = append(events, patternEvents...)
	}

	if comebackEvents := hd.detectComebacks(ctx); len(comebackEvents) > 0 {
		events = append(events, comebackEvents...)
	}

	if streakEvents := hd.detectStreaks(ctx); len(streakEvents) > 0 {
		events = append(events, streakEvents...)
	}

	if diffEvents := hd.detectBigScoreDiffs(ctx); len(diffEvents) > 0 {
		events = append(events, diffEvents...)
	}

	return hd.sortAndFilter(events)
}

func (hd *HighlightDetector) detectBigPatterns(ctx *GameAnalysisContext) []*HighlightEvent {
	events := make([]*HighlightEvent, 0)

	for i, action := range ctx.Actions {
		if action.ActionType != common.ActionPlayCard && action.ActionType != common.ActionDiscard {
			continue
		}

		pattern, ok := action.Data["pattern"].(string)
		if !ok {
			continue
		}

		cardPattern := game.CardPattern(pattern)
		var highlightType HighlightType
		var title string
		var importance int

		switch cardPattern {
		case game.PatternRocket:
			highlightType = HighlightRocket
			title = "王炸！"
			importance = 10
		case game.PatternBomb:
			highlightType = HighlightBomb
			title = "炸弹！"
			importance = 8
		case game.PatternKongBloom:
			highlightType = HighlightKongBloom
			title = "杠上开花！"
			importance = 9
		case game.PatternRobKong:
			highlightType = HighlightRobKong
			title = "抢杠胡！"
			importance = 9
		case game.PatternThirteenOrphans:
			highlightType = HighlightBigPattern
			title = "十三幺！"
			importance = 10
		case game.PatternSevenPairs:
			highlightType = HighlightBigPattern
			title = "七小对！"
			importance = 8
		case game.PatternAirplane, game.PatternAirplaneOne, game.PatternAirplaneTwo:
			highlightType = HighlightBigPattern
			title = "飞机！"
			importance = 6
		default:
			continue
		}

		event := &HighlightEvent{
			ID:          common.GenerateID(),
			RoomID:      ctx.RoomID,
			Type:        highlightType,
			Title:       title,
			Description: title,
			StartSeq:    action.Seq,
			EndSeq:      action.Seq,
			StartTime:   action.Timestamp.UnixMilli(),
			EndTime:     action.Timestamp.UnixMilli(),
			Pattern:     cardPattern,
			Players:     []common.UserID{action.UserID},
			Importance:  importance,
			Metadata: map[string]interface{}{
				"action_index": i,
			},
		}

		if i > 5 {
			event.StartSeq = ctx.Actions[i-5].Seq
			event.StartTime = ctx.Actions[i-5].Timestamp.UnixMilli()
		}

		events = append(events, event)
	}

	return events
}

func (hd *HighlightDetector) detectComebacks(ctx *GameAnalysisContext) []*HighlightEvent {
	events := make([]*HighlightEvent, 0)

	if len(ctx.PlayerScores) < 2 {
		return events
	}

	playerIDs := make([]common.UserID, 0, len(ctx.PlayerScores))
	for uid := range ctx.PlayerScores {
		playerIDs = append(playerIDs, uid)
	}

	for i := 0; i < len(playerIDs); i++ {
		for j := i + 1; j < len(playerIDs); j++ {
			p1Snapshots := ctx.PlayerScores[playerIDs[i]]
			p2Snapshots := ctx.PlayerScores[playerIDs[j]]

			if len(p1Snapshots) < 2 || len(p2Snapshots) < 2 {
				continue
			}

			comebackPoint := hd.findComebackPoint(p1Snapshots, p2Snapshots)
			if comebackPoint >= 0 {
				p1Score := p1Snapshots[comebackPoint].Score
				p2Score := p2Snapshots[comebackPoint].Score
				diff := int64(math.Abs(float64(p1Score - p2Score)))

				if diff >= hd.thresholds.ComebackMinDiff {
					startIdx := max(0, comebackPoint-5)
					endIdx := min(len(p1Snapshots)-1, comebackPoint+5)

					event := &HighlightEvent{
						ID:          common.GenerateID(),
						RoomID:      ctx.RoomID,
						Type:        HighlightComeback,
						Title:       "极限反杀！",
						Description: "比分逆转实现反杀",
						StartSeq:    p1Snapshots[startIdx].Seq,
						EndSeq:      p1Snapshots[endIdx].Seq,
						StartTime:   p1Snapshots[startIdx].Timestamp,
						EndTime:     p1Snapshots[endIdx].Timestamp,
						ScoreDiff:   diff,
						Players:     []common.UserID{playerIDs[i], playerIDs[j]},
						Importance:  7 + int(diff/50),
					}

					events = append(events, event)
				}
			}
		}
	}

	return events
}

func (hd *HighlightDetector) findComebackPoint(s1, s2 []scoreSnapshot) int {
	wasLeading := false
	wasLosing := false

	for i := 0; i < min(len(s1), len(s2)); i++ {
		diff := s1[i].Score - s2[i].Score
		if diff > 0 {
			wasLeading = true
		} else if diff < 0 {
			wasLosing = true
		}

		if wasLeading && diff < 0 {
			return i
		}
		if wasLosing && diff > 0 {
			return i
		}
	}

	return -1
}

func (hd *HighlightDetector) detectStreaks(ctx *GameAnalysisContext) []*HighlightEvent {
	events := make([]*HighlightEvent, 0)

	if ctx.Settlement == nil {
		return events
	}

	for playerID, snapshots := range ctx.PlayerScores {
		if len(snapshots) < 3 {
			continue
		}

		streaks := hd.findStreaks(snapshots)

		for _, streak := range streaks {
			if streak.count >= hd.thresholds.WinStreakMin {
				event := &HighlightEvent{
					ID:          common.GenerateID(),
					RoomID:      ctx.RoomID,
					Type:        HighlightWinStreak,
					Title:       "连赢 " + itoa(streak.count) + " 局！",
					Description: "连续获胜",
					StartSeq:    snapshots[streak.startIdx].Seq,
					EndSeq:      snapshots[streak.endIdx].Seq,
					StartTime:   snapshots[streak.startIdx].Timestamp,
					EndTime:     snapshots[streak.endIdx].Timestamp,
					Players:     []common.UserID{playerID},
					Importance:  5 + streak.count,
					Metadata: map[string]interface{}{
						"streak_count": streak.count,
					},
				}
				events = append(events, event)
			}
		}
	}

	return events
}

type streakInfo struct {
	startIdx int
	endIdx   int
	count    int
	isWin    bool
}

func (hd *HighlightDetector) findStreaks(snapshots []scoreSnapshot) []streakInfo {
	streaks := make([]streakInfo, 0)
	if len(snapshots) < 2 {
		return streaks
	}

	currentStart := 0
	currentCount := 0
	currentIsWin := false

	for i := 1; i < len(snapshots); i++ {
		diff := snapshots[i].Score - snapshots[i-1].Score
		isWin := diff > 0

		if isWin == currentIsWin && currentCount > 0 {
			currentCount++
		} else {
			if currentCount >= 3 {
				streaks = append(streaks, streakInfo{
					startIdx: currentStart,
					endIdx:   i - 1,
					count:    currentCount - 1,
					isWin:    currentIsWin,
				})
			}
			currentStart = i - 1
			currentCount = 2
			currentIsWin = isWin
		}
	}

	if currentCount >= 3 {
		streaks = append(streaks, streakInfo{
			startIdx: currentStart,
			endIdx:   len(snapshots) - 1,
			count:    currentCount - 1,
			isWin:    currentIsWin,
		})
	}

	return streaks
}

func (hd *HighlightDetector) detectBigScoreDiffs(ctx *GameAnalysisContext) []*HighlightEvent {
	events := make([]*HighlightEvent, 0)

	if ctx.Settlement == nil {
		return events
	}

	maxScore := int64(math.MinInt64)
	minScore := int64(math.MaxInt64)
	var maxPlayer, minPlayer common.UserID

	for _, res := range ctx.Settlement.Results {
		if res.Score > maxScore {
			maxScore = res.Score
			maxPlayer = res.UserID
		}
		if res.Score < minScore {
			minScore = res.Score
			minPlayer = res.UserID
		}
	}

	diff := maxScore - minScore
	if diff >= hd.thresholds.BigScoreDiffMin {
		event := &HighlightEvent{
			ID:          common.GenerateID(),
			RoomID:      ctx.RoomID,
			Type:        HighlightBigScoreDiff,
			Title:       "悬殊比分！",
			Description: "分差达到 " + itoa64(diff),
			StartSeq:    0,
			EndSeq:      int64(len(ctx.Actions)),
			StartTime:   ctx.Actions[0].Timestamp.UnixMilli(),
			EndTime:     ctx.Actions[len(ctx.Actions)-1].Timestamp.UnixMilli(),
			ScoreDiff:   diff,
			Players:     []common.UserID{maxPlayer, minPlayer},
			Importance:  5 + int(diff/100),
		}
		events = append(events, event)
	}

	return events
}

func (hd *HighlightDetector) sortAndFilter(events []*HighlightEvent) []*HighlightEvent {
	for i := 0; i < len(events)-1; i++ {
		for j := i + 1; j < len(events); j++ {
			if events[j].Importance > events[i].Importance {
				events[i], events[j] = events[j], events[i]
			}
		}
	}

	filtered := make([]*HighlightEvent, 0, len(events))
	for _, e := range events {
		if e.Importance >= hd.thresholds.MinImportance {
			filtered = append(filtered, e)
		}
	}

	return filtered
}

func (hd *HighlightDetector) GetHighlightClips(ctx *GameAnalysisContext, maxClips int) []*HighlightEvent {
	events := hd.Detect(ctx)
	if len(events) <= maxClips {
		return events
	}
	return events[:maxClips]
}

func (hd *HighlightDetector) BuildScoreSnapshots(
	actions []common.GameAction,
	settlement *game.Settlement,
) map[common.UserID][]scoreSnapshot {

	snapshots := make(map[common.UserID][]scoreSnapshot)

	if settlement == nil {
		return snapshots
	}

	for _, res := range settlement.Results {
		snapshots[res.UserID] = make([]scoreSnapshot, 0)
	}

	runningScores := make(map[common.UserID]int64)
	for uid := range snapshots {
		runningScores[uid] = 0
	}

	for i, action := range actions {
		for uid := range runningScores {
			snapshots[uid] = append(snapshots[uid], scoreSnapshot{
				Seq:       action.Seq,
				Timestamp: action.Timestamp.UnixMilli(),
				Score:     runningScores[uid],
			})
		}

		if action.ActionType == common.ActionPlayCard || action.ActionType == common.ActionDiscard {
			if scoreDelta, ok := action.Data["score_delta"].(map[string]interface{}); ok {
				for uidStr, delta := range scoreDelta {
					if d, ok := delta.(int64); ok {
						runningScores[common.UserID(uidStr)] += d
					}
				}
			}
		}

		if i == len(actions)-1 {
			for _, res := range settlement.Results {
				runningScores[res.UserID] = res.Score
				snapshots[res.UserID] = append(snapshots[res.UserID], scoreSnapshot{
					Seq:       action.Seq + 1,
					Timestamp: time.Now().UnixMilli(),
					Score:     res.Score,
				})
			}
		}
	}

	return snapshots
}

func max(a, b int) int {
	if a > b {
		return a
	}
	return b
}

func min(a, b int) int {
	if a < b {
		return a
	}
	return b
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

func itoa64(n int64) string {
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
