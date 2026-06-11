package stress

import (
	"fmt"
	"sync"
	"sync/atomic"
	"time"

	"github.com/studio/gameroom/pkg/common"
	"github.com/studio/gameroom/pkg/game"
	"github.com/studio/gameroom/pkg/match"
	"github.com/studio/gameroom/pkg/room"
)

type StressConfig struct {
	NumPlayers        int
	NumRooms          int
	RoomPlayers       int
	RampUpMs          int
	MessageRatePerSec int
	DurationSec       int
}

type StressReport struct {
	TotalMessages      uint64
	TotalActions       uint64
	TotalErrors        uint64
	TotalLatencyNs     uint64
	MaxLatencyNs       uint64
	MinLatencyNs       uint64
	AvgLatencyMs       float64
	P50LatencyMs       float64
	P95LatencyMs       float64
	P99LatencyMs       float64
	MessagesPerSec     float64
	ErrorsPerSec       float64
	PeakGoroutines     int
	PeakMemoryMb       float64
	CPUPct             float64
	DurationSec        float64
}

type StressTester struct {
	config      *StressConfig
	matcher     *match.Matcher
	roomManager *room.Manager
	rankedMatch *match.RankedMatcher

	msgCount    atomic.Uint64
	errCount    atomic.Uint64
	actionCount atomic.Uint64
	totalLatency atomic.Uint64
	minLatency   atomic.Uint64
	maxLatency   atomic.Uint64

	latencies   []int64
	latencyMu   sync.Mutex

	startTime   time.Time
	endTime     time.Time

	peakGoroutines atomic.Int32
}

func DefaultStressConfig() *StressConfig {
	return &StressConfig{
		NumPlayers:        5000,
		NumRooms:          1000,
		RoomPlayers:       4,
		RampUpMs:          10000,
		MessageRatePerSec: 1000,
		DurationSec:       60,
	}
}

func NewStressTester(config *StressConfig, m *match.Matcher, rm *room.Manager, rankedMatch *match.RankedMatcher) *StressTester {
	if config == nil {
		config = DefaultStressConfig()
	}
	st := &StressTester{
		config:      config,
		matcher:     m,
		roomManager: rm,
		rankedMatch: rankedMatch,
	}
	st.minLatency.Store(^uint64(0))
	return st
}

func (st *StressTester) Run() *StressReport {
	st.startTime = time.Now()
	st.minLatency.Store(^uint64(0))

	fmt.Printf("[Stress] Starting stress test: players=%d, rooms=%d, duration=%ds\n",
		st.config.NumPlayers, st.config.NumRooms, st.config.DurationSec)

	st.rampUpPlayers()
	st.runWorkload()

	st.endTime = time.Now()
	duration := st.endTime.Sub(st.startTime)

	return st.buildReport(duration)
}

func (st *StressTester) rampUpPlayers() {
	rampStep := time.Duration(st.config.RampUpMs/st.config.NumPlayers) * time.Millisecond

	fmt.Printf("[Stress] Ramping up %d players over %dms...\n", st.config.NumPlayers, st.config.RampUpMs)

	for i := 0; i < st.config.NumPlayers; i++ {
		userID := common.UserID(fmt.Sprintf("stress_player_%d", i))
		elo := 1200.0 + float64(i%10)*50

		req := &common.MatchRequest{
			UserID:      userID,
			GameType:    common.GameTypeLandlord,
			Elo:         elo,
			Rank:        common.EloToRank(elo),
			Level:       1 + (i % 50),
			RequestedAt: time.Now(),
		}

		if st.rankedMatch != nil {
			st.rankedMatch.AddRequest(req)
		} else if st.matcher != nil {
			st.matcher.AddRequest(req)
		}

		if (i+1)%1000 == 0 {
			fmt.Printf("[Stress]   ...%d players queued\n", i+1)
		}

		time.Sleep(rampStep)
	}

	fmt.Printf("[Stress] Player ramp-up complete. Running matching cycles...\n")

	roomsNeeded := st.config.NumRooms
	roomsCreated := 0
	for roomsCreated < roomsNeeded {
		var results []match.MatchResult
		if st.rankedMatch != nil {
			results = st.rankedMatch.TryMatch(common.GameTypeLandlord)
		} else if st.matcher != nil {
			results = st.matcher.TryMatch(common.GameTypeLandlord)
		}

		if len(results) > 0 {
			roomsCreated += len(results)
			fmt.Printf("[Stress]   ...%d rooms created\n", roomsCreated)
		} else {
			time.Sleep(50 * time.Millisecond)
		}
	}
}

func (st *StressTester) runWorkload() {
	fmt.Printf("[Stress] Running workload for %ds...\n", st.config.DurationSec)

	totalDuration := time.Duration(st.config.DurationSec) * time.Second
	msgInterval := time.Second / time.Duration(st.config.MessageRatePerSec)

	done := make(chan struct{})
	time.AfterFunc(totalDuration, func() {
		close(done)
	})

	ticker := time.NewTicker(msgInterval)
	defer ticker.Stop()

	roomIDs := st.roomManager.GetAllRoomIDs()
	if len(roomIDs) == 0 {
		roomIDs = []common.RoomID{"stress_room_1"}
	}

	i := 0
	for {
		select {
		case <-done:
			fmt.Printf("[Stress] Workload complete. Total msgs=%d, errors=%d\n",
				st.msgCount.Load(), st.errCount.Load())
			return
		case <-ticker.C:
			roomID := roomIDs[i%len(roomIDs)]
			st.simulateAction(roomID, i)
			i++
		}
	}
}

func (st *StressTester) simulateAction(roomID common.RoomID, seq int) {
	start := time.Now()

	action := &common.GameAction{
		ActionID:   fmt.Sprintf("stress_%d_%d", seq, start.UnixNano()),
		RoomID:     roomID,
		UserID:     common.UserID(fmt.Sprintf("stress_player_%d", seq%st.config.RoomPlayers)),
		ActionType: common.ActionPlayCard,
		Data: map[string]interface{}{
			"cards":    []int{1, 2, 3},
			"pattern":  "single",
			"seq":      seq,
		},
		Timestamp:  start,
		Seq:        int64(seq),
	}

	if r, ok := st.roomManager.GetRoom(roomID); ok {
		if err := r.ValidateAction(action); err == nil {
			st.actionCount.Add(1)
		} else {
			st.errCount.Add(1)
		}
	}

	st.msgCount.Add(1)

	latency := uint64(time.Since(start).Nanoseconds())
	st.totalLatency.Add(latency)

	for {
		currentMin := st.minLatency.Load()
		if latency >= currentMin {
			break
		}
		if st.minLatency.CompareAndSwap(currentMin, latency) {
			break
		}
	}

	for {
		currentMax := st.maxLatency.Load()
		if latency <= currentMax {
			break
		}
		if st.maxLatency.CompareAndSwap(currentMax, latency) {
			break
		}
	}

	st.latencyMu.Lock()
	st.latencies = append(st.latencies, int64(latency))
	st.latencyMu.Unlock()
}

func (st *StressTester) buildReport(duration time.Duration) *StressReport {
	totalMsgs := st.msgCount.Load()
	totalErrors := st.errCount.Load()
	totalLat := st.totalLatency.Load()
	avgLatencyNs := uint64(0)
	if totalMsgs > 0 {
		avgLatencyNs = totalLat / totalMsgs
	}

	st.latencyMu.Lock()
	latencies := make([]int64, len(st.latencies))
	copy(latencies, st.latencies)
	st.latencyMu.Unlock()

	p50 := percentile(latencies, 50)
	p95 := percentile(latencies, 95)
	p99 := percentile(latencies, 99)

	durationSec := duration.Seconds()

	return &StressReport{
		TotalMessages:  totalMsgs,
		TotalActions:   st.actionCount.Load(),
		TotalErrors:    totalErrors,
		TotalLatencyNs: totalLat,
		MinLatencyNs:   st.minLatency.Load(),
		MaxLatencyNs:   st.maxLatency.Load(),
		AvgLatencyMs:   float64(avgLatencyNs) / 1_000_000.0,
		P50LatencyMs:   float64(p50) / 1_000_000.0,
		P95LatencyMs:   float64(p95) / 1_000_000.0,
		P99LatencyMs:   float64(p99) / 1_000_000.0,
		MessagesPerSec: float64(totalMsgs) / durationSec,
		ErrorsPerSec:   float64(totalErrors) / durationSec,
		PeakGoroutines: int(st.peakGoroutines.Load()),
		DurationSec:    durationSec,
	}
}

func (r *StressReport) Format() string {
	return fmt.Sprintf(`
╔══════════════════════════════════════════════════════╗
║              STRESS TEST REPORT                      ║
╠══════════════════════════════════════════════════════╣
  Duration:         %.2f sec
  Total Messages:   %d
  Total Actions:    %d
  Total Errors:     %d
╠══════════════════════════════════════════════════════╣
  Throughput:
    Messages/sec:   %.0f
    Errors/sec:     %.2f
╠══════════════════════════════════════════════════════╣
  Latency:
    Average:        %.3f ms
    P50:            %.3f ms
    P95:            %.3f ms
    P99:            %.3f ms
    Min:            %.3f ms
    Max:            %.3f ms
╠══════════════════════════════════════════════════════╣
  SLA Check:
    P95 < 100ms:    %v (%.3f ms)
    CPU < 60%%:      N/A (needs runtime stats)
╚══════════════════════════════════════════════════════╝`,
		r.DurationSec,
		r.TotalMessages,
		r.TotalActions,
		r.TotalErrors,
		r.MessagesPerSec,
		r.ErrorsPerSec,
		r.AvgLatencyMs,
		r.P50LatencyMs,
		r.P95LatencyMs,
		r.P99LatencyMs,
		float64(r.MinLatencyNs)/1_000_000.0,
		float64(r.MaxLatencyNs)/1_000_000.0,
		r.P95LatencyMs < 100.0,
		r.P95LatencyMs,
	)
}

func (r *StressReport) Pass() bool {
	return r.P95LatencyMs < 100.0
}

func percentile(data []int64, p int) int64 {
	if len(data) == 0 {
		return 0
	}

	sorted := make([]int64, len(data))
	copy(sorted, data)
	quickSort(sorted)

	idx := (p * len(sorted)) / 100
	if idx >= len(sorted) {
		idx = len(sorted) - 1
	}
	return sorted[idx]
}

func quickSort(arr []int64) {
	if len(arr) < 2 {
		return
	}
	left, right := 0, len(arr)-1
	pivot := arr[len(arr)/2]
	arr[right], arr[len(arr)/2] = arr[len(arr)/2], arr[right]
	for i := range arr {
		if arr[i] < pivot {
			arr[left], arr[i] = arr[i], arr[left]
			left++
		}
	}
	arr[left], arr[right] = arr[right], arr[left]
	quickSort(arr[:left])
	quickSort(arr[left+1:])
}

func (st *StressTester) ValidateCowHands(ctx *game.GameContext) int {
	count := 0
	if ctx.CowHands != nil {
		count = ctx.CowHands.Count()
	}
	return count
}
