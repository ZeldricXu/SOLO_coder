package bench

import (
	"context"
	"sync"
	"sync/atomic"
	"time"

	"github.com/htest/htest/internal/script"
)

type Config struct {
	Concurrency    int
	Duration       time.Duration
	RPS            int
	ReportInterval time.Duration
	Script         *script.TestScript
}

type Stats struct {
	TotalRequests int64
	SuccessCount  int64
	ErrorCount    int64
	TotalDuration time.Duration
	Latencies     []time.Duration
	Errors        map[string]int
	StartTime     time.Time
	QPS           float64
}

type Runner struct {
	config   Config
	stats    Stats
	executor *script.Executor
	stopCh   chan struct{}
}

func NewRunner(config Config, executor *script.Executor) *Runner {
	return &Runner{
		config:   config,
		executor: executor,
		stopCh:   make(chan struct{}),
	}
}

func (r *Runner) Run(ctx context.Context) (*Stats, error) {
	r.stats = Stats{
		Errors:    make(map[string]int),
		StartTime: time.Now(),
		Latencies: make([]time.Duration, 0),
	}

	ctx, cancel := context.WithTimeout(ctx, r.config.Duration)
	defer cancel()

	var wg sync.WaitGroup
	var latMu sync.Mutex

	var totalReqs int64
	var successCount int64
	var errorCount int64

	concurrency := r.config.Concurrency
	if concurrency <= 0 {
		concurrency = 1
	}

	var ticker *time.Ticker
	var tickerCh <-chan time.Time
	if r.config.RPS > 0 {
		interval := time.Second / time.Duration(r.config.RPS)
		ticker = time.NewTicker(interval)
		tickerCh = ticker.C
		defer ticker.Stop()
	}

	for i := 0; i < concurrency; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for {
				select {
				case <-ctx.Done():
					return
				case <-r.stopCh:
					return
				default:
				}

				if tickerCh != nil {
					select {
					case <-tickerCh:
					case <-ctx.Done():
						return
					case <-r.stopCh:
						return
					}
				}

				start := time.Now()
				result, err := r.executor.Execute(ctx, r.config.Script)
				latency := time.Since(start)

				atomic.AddInt64(&totalReqs, 1)

				latMu.Lock()
				r.stats.Latencies = append(r.stats.Latencies, latency)
				latMu.Unlock()

				if err != nil {
					atomic.AddInt64(&errorCount, 1)
					latMu.Lock()
					r.stats.Errors[err.Error()]++
					latMu.Unlock()
					continue
				}

				if result.Status == "fail" {
					atomic.AddInt64(&errorCount, 1)
					latMu.Lock()
					r.stats.Errors["assertion failed"]++
					latMu.Unlock()
					continue
				}

				atomic.AddInt64(&successCount, 1)
			}
		}()
	}

	wg.Wait()

	r.stats.TotalRequests = atomic.LoadInt64(&totalReqs)
	r.stats.SuccessCount = atomic.LoadInt64(&successCount)
	r.stats.ErrorCount = atomic.LoadInt64(&errorCount)
	r.stats.TotalDuration = time.Since(r.stats.StartTime)

	if r.stats.TotalDuration.Seconds() > 0 {
		r.stats.QPS = float64(r.stats.TotalRequests) / r.stats.TotalDuration.Seconds()
	}

	return &r.stats, nil
}
