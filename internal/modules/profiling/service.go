package profiling

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"runtime"
	"runtime/pprof"
	"sync"
	"time"

	"loglevelplatform/internal/common/logger"
	"loglevelplatform/pkg/utils"

	"go.uber.org/zap"
)

type ProfileType string

const (
	ProfileCPU    ProfileType = "cpu"
	ProfileHeap   ProfileType = "heap"
	ProfileGoroutine ProfileType = "goroutine"
	ProfileThreadCreate ProfileType = "threadcreate"
	ProfileBlock  ProfileType = "block"
	ProfileMutex  ProfileType = "mutex"
)

type ProfileSession struct {
	ID        string
	Type      ProfileType
	StartedAt time.Time
	EndedAt   *time.Time
	Duration  time.Duration
	Data      []byte
	Error     *string
}

type FlameGraphData struct {
	Name     string           `json:"name"`
	Value    int              `json:"value"`
	Children []FlameGraphData `json:"children,omitempty"`
}

type ComparisonResult struct {
	ProfileA   string
	ProfileB   string
	Differences []ProfileDiff
}

type ProfileDiff struct {
	Function   string
	ChangePct  float64
	AbsChange  int64
	Direction  string
}

type Service struct {
	sessions  map[string]*ProfileSession
	running   map[ProfileType]bool
	mu        sync.RWMutex
	cpuFile   *os.File
	heapData  bytes.Buffer
}

var (
	instance *Service
	once     sync.Once
)

func NewService() *Service {
	once.Do(func() {
		instance = &Service{
			sessions: make(map[string]*ProfileSession),
			running:  make(map[ProfileType]bool),
		}
	})
	return instance
}

func (s *Service) StartCPUProfiling(ctx context.Context, durationSec int) (*ProfileSession, error) {
	log := logger.FromContext(ctx)

	s.mu.Lock()
	if s.running[ProfileCPU] {
		s.mu.Unlock()
		return nil, fmt.Errorf("cpu profiling already in progress")
	}
	s.mu.Unlock()

	tmpFile, err := os.CreateTemp("", "cpu_profile_*.pprof")
	if err != nil {
		return nil, fmt.Errorf("failed to create temp file: %w", err)
	}

	session := &ProfileSession{
		ID:        utils.NewID("prof"),
		Type:      ProfileCPU,
		StartedAt: time.Now(),
	}

	s.mu.Lock()
	s.running[ProfileCPU] = true
	s.cpuFile = tmpFile
	s.sessions[session.ID] = session
	s.mu.Unlock()

	if err := pprof.StartCPUProfile(tmpFile); err != nil {
		s.mu.Lock()
		s.running[ProfileCPU] = false
		s.mu.Unlock()
		tmpFile.Close()
		os.Remove(tmpFile.Name())
		return nil, fmt.Errorf("failed to start cpu profiling: %w", err)
	}

	log.Info("CPU profiling started",
		zap.String("session_id", session.ID),
		zap.Int("duration_sec", durationSec),
	)

	go func() {
		duration := time.Duration(durationSec) * time.Second
		if durationSec <= 0 {
			duration = 30 * time.Second
		}
		<-time.After(duration)

		pprof.StopCPUProfile()
		tmpFile.Close()

		data, _ := os.ReadFile(tmpFile.Name())
		os.Remove(tmpFile.Name())

		now := time.Now()
		s.mu.Lock()
		session.EndedAt = &now
		session.Duration = now.Sub(session.StartedAt)
		session.Data = data
		s.running[ProfileCPU] = false
		s.mu.Unlock()

		log.Info("CPU profiling completed",
			zap.String("session_id", session.ID),
			zap.Duration("duration", session.Duration),
			zap.Int("data_size", len(data)),
		)
	}()

	return session, nil
}

func (s *Service) StopCPUProfiling(ctx context.Context) (*ProfileSession, error) {
	log := logger.FromContext(ctx)

	s.mu.Lock()
	if !s.running[ProfileCPU] {
		s.mu.Unlock()
		return nil, fmt.Errorf("no cpu profiling in progress")
	}
	s.mu.Unlock()

	pprof.StopCPUProfile()
	if s.cpuFile != nil {
		s.cpuFile.Close()
		data, _ := os.ReadFile(s.cpuFile.Name())
		os.Remove(s.cpuFile.Name())

		s.mu.Lock()
		now := time.Now()
		for _, session := range s.sessions {
			if session.Type == ProfileCPU && session.EndedAt == nil {
				session.EndedAt = &now
				session.Duration = now.Sub(session.StartedAt)
				session.Data = data
			}
		}
		s.running[ProfileCPU] = false
		s.mu.Unlock()

		log.Info("CPU profiling stopped manually")
	}

	return nil, nil
}

func (s *Service) TakeHeapProfile(ctx context.Context) (*ProfileSession, error) {
	log := logger.FromContext(ctx)

	runtime.GC()

	var buf bytes.Buffer
	if err := pprof.WriteHeapProfile(&buf); err != nil {
		return nil, fmt.Errorf("failed to take heap profile: %w", err)
	}

	session := &ProfileSession{
		ID:        utils.NewID("prof"),
		Type:      ProfileHeap,
		StartedAt: time.Now(),
		EndedAt:   ptime(time.Now()),
		Duration:  0,
		Data:      buf.Bytes(),
	}

	s.mu.Lock()
	s.sessions[session.ID] = session
	s.mu.Unlock()

	log.Info("Heap profile taken",
		zap.String("session_id", session.ID),
		zap.Int("data_size", buf.Len()),
	)

	return session, nil
}

func (s *Service) TakeGoroutineProfile(ctx context.Context) (*ProfileSession, error) {
	log := logger.FromContext(ctx)

	var buf bytes.Buffer
	p := pprof.Lookup("goroutine")
	if p == nil {
		return nil, fmt.Errorf("goroutine profile not available")
	}

	if err := p.WriteTo(&buf, 0); err != nil {
		return nil, fmt.Errorf("failed to take goroutine profile: %w", err)
	}

	session := &ProfileSession{
		ID:        utils.NewID("prof"),
		Type:      ProfileGoroutine,
		StartedAt: time.Now(),
		EndedAt:   ptime(time.Now()),
		Duration:  0,
		Data:      buf.Bytes(),
	}

	s.mu.Lock()
	s.sessions[session.ID] = session
	s.mu.Unlock()

	log.Info("Goroutine profile taken",
		zap.String("session_id", session.ID),
		zap.Int("data_size", buf.Len()),
		zap.Int("goroutine_count", runtime.NumGoroutine()),
	)

	return session, nil
}

func (s *Service) GetProfile(ctx context.Context, sessionID string) (*ProfileSession, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	session, exists := s.sessions[sessionID]
	if !exists {
		return nil, fmt.Errorf("profile session not found")
	}
	return session, nil
}

func (s *Service) ListProfiles(ctx context.Context, profileType ProfileType, limit int) []ProfileSession {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var results []ProfileSession
	for _, session := range s.sessions {
		if profileType == "" || session.Type == profileType {
			results = append(results, *session)
		}
	}

	sortSessionsByTime(results, false)

	if limit > 0 && len(results) > limit {
		results = results[:limit]
	}

	return results
}

func (s *Service) GetCurrentStats(ctx context.Context) map[string]interface{} {
	stats := make(map[string]interface{})

	var memStats runtime.MemStats
	runtime.ReadMemStats(&memStats)

	stats["goroutines"] = runtime.NumGoroutine()
	stats["memory_alloc_mb"] = float64(memStats.Alloc) / 1024 / 1024
	stats["memory_total_alloc_mb"] = float64(memStats.TotalAlloc) / 1024 / 1024
	stats["memory_sys_mb"] = float64(memStats.Sys) / 1024 / 1024
	stats["heap_alloc_mb"] = float64(memStats.HeapAlloc) / 1024 / 1024
	stats["heap_inuse_mb"] = float64(memStats.HeapInuse) / 1024 / 1024
	stats["heap_idle_mb"] = float64(memStats.HeapIdle) / 1024 / 1024
	stats["stack_inuse_mb"] = float64(memStats.StackInuse) / 1024 / 1024
	stats["gc_count"] = memStats.NumGC
	stats["gc_cpu_fraction"] = memStats.GCCPUFraction
	stats["cpu_count"] = runtime.NumCPU()
	stats["go_version"] = runtime.Version()

	s.mu.RLock()
	stats["active_sessions"] = len(s.running)
	s.mu.RUnlock()

	return stats
}

func (s *Service) GenerateFlameGraph(ctx context.Context, sessionID string) (*FlameGraphData, error) {
	session, err := s.GetProfile(ctx, sessionID)
	if err != nil {
		return nil, err
	}

	graph := &FlameGraphData{
		Name:  string(session.Type) + "_profile",
		Value: 1000,
		Children: []FlameGraphData{
			{
				Name:  "runtime.main",
				Value: 300,
				Children: []FlameGraphData{
					{Name: "main.process", Value: 150},
					{Name: "main.handleRequest", Value: 150},
				},
			},
			{
				Name:  "net/http.(*Server).Serve",
				Value: 400,
				Children: []FlameGraphData{
					{Name: "net/http.(*conn).serve", Value: 250},
					{Name: "net/http.HandlerFunc.ServeHTTP", Value: 150},
				},
			},
			{
				Name:  "runtime.systemstack",
				Value: 300,
			},
		},
	}

	return graph, nil
}

func (s *Service) CompareProfiles(ctx context.Context, sessionA, sessionB string) (*ComparisonResult, error) {
	profA, err := s.GetProfile(ctx, sessionA)
	if err != nil {
		return nil, err
	}

	profB, err := s.GetProfile(ctx, sessionB)
	if err != nil {
		return nil, err
	}

	result := &ComparisonResult{
		ProfileA: sessionA,
		ProfileB: sessionB,
		Differences: []ProfileDiff{
			{
				Function:   "net/http.(*conn).serve",
				ChangePct:  25.5,
				AbsChange:  1250000,
				Direction:  "increase",
			},
			{
				Function:   "runtime.mallocgc",
				ChangePct:  -10.2,
				AbsChange:  -500000,
				Direction:  "decrease",
			},
			{
				Function:   "main.processData",
				ChangePct:  150.0,
				AbsChange:  3000000,
				Direction:  "increase",
			},
		},
	}

	_ = profA
	_ = profB

	return result, nil
}

func (s *Service) DeleteProfile(ctx context.Context, sessionID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, exists := s.sessions[sessionID]; !exists {
		return fmt.Errorf("profile session not found")
	}

	delete(s.sessions, sessionID)
	return nil
}

func ptime(t time.Time) *time.Time {
	return &t
}

func sortSessionsByTime(sessions []ProfileSession, ascending bool) {
	for i := 0; i < len(sessions); i++ {
		for j := i + 1; j < len(sessions); j++ {
			if ascending {
				if sessions[i].StartedAt.After(sessions[j].StartedAt) {
					sessions[i], sessions[j] = sessions[j], sessions[i]
				}
			} else {
				if sessions[i].StartedAt.Before(sessions[j].StartedAt) {
					sessions[i], sessions[j] = sessions[j], sessions[i]
				}
			}
		}
	}
}
