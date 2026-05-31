package trace

import (
	"math/rand"
	"sync"
	"time"

	"observability-platform/pkg/models"
)

type Sampler interface {
	ShouldSample(span *models.Span) bool
	Name() string
}

type AlwaysSample struct{}

func (s *AlwaysSample) ShouldSample(span *models.Span) bool {
	return true
}

func (s *AlwaysSample) Name() string {
	return "always_sample"
}

type NeverSample struct{}

func (s *NeverSample) ShouldSample(span *models.Span) bool {
	return false
}

func (s *NeverSample) Name() string {
	return "never_sample"
}

type ProbabilisticSampler struct {
	samplingRate float64
	mu           sync.RWMutex
}

func NewProbabilisticSampler(samplingRate float64) *ProbabilisticSampler {
	if samplingRate < 0 {
		samplingRate = 0
	}
	if samplingRate > 1 {
		samplingRate = 1
	}
	return &ProbabilisticSampler{
		samplingRate: samplingRate,
	}
}

func (s *ProbabilisticSampler) ShouldSample(span *models.Span) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	return rand.Float64() < s.samplingRate
}

func (s *ProbabilisticSampler) SetSamplingRate(rate float64) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if rate < 0 {
		rate = 0
	}
	if rate > 1 {
		rate = 1
	}
	s.samplingRate = rate
}

func (s *ProbabilisticSampler) Name() string {
	return "probabilistic"
}

type RateLimitingSampler struct {
	maxTracesPerSecond int
	tokenBucket        float64
	lastRefill         time.Time
	mu                 sync.Mutex
}

func NewRateLimitingSampler(maxTracesPerSecond int) *RateLimitingSampler {
	return &RateLimitingSampler{
		maxTracesPerSecond: maxTracesPerSecond,
		tokenBucket:        float64(maxTracesPerSecond),
		lastRefill:         time.Now(),
	}
}

func (s *RateLimitingSampler) ShouldSample(span *models.Span) bool {
	s.mu.Lock()
	defer s.mu.Unlock()

	now := time.Now()
	elapsed := now.Sub(s.lastRefill).Seconds()
	s.tokenBucket += elapsed * float64(s.maxTracesPerSecond)
	if s.tokenBucket > float64(s.maxTracesPerSecond) {
		s.tokenBucket = float64(s.maxTracesPerSecond)
	}
	s.lastRefill = now

	if s.tokenBucket >= 1 {
		s.tokenBucket--
		return true
	}
	return false
}

func (s *RateLimitingSampler) SetMaxTracesPerSecond(max int) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.maxTracesPerSecond = max
}

func (s *RateLimitingSampler) Name() string {
	return "rate_limiting"
}

type ParentBasedSampler struct {
	rootSampler      Sampler
	remoteParentSampled   Sampler
	remoteParentNotSampled Sampler
	localParentSampled    Sampler
	localParentNotSampled  Sampler
}

func NewParentBasedSampler(rootSampler Sampler) *ParentBasedSampler {
	return &ParentBasedSampler{
		rootSampler:           rootSampler,
		remoteParentSampled:   &AlwaysSample{},
		remoteParentNotSampled: &NeverSample{},
		localParentSampled:    &AlwaysSample{},
		localParentNotSampled:  &NeverSample{},
	}
}

func (s *ParentBasedSampler) ShouldSample(span *models.Span) bool {
	if span.ParentSpanID == "" {
		return s.rootSampler.ShouldSample(span)
	}

	isRemote := true
	if isRemote {
		if span.Sampled {
			return s.remoteParentSampled.ShouldSample(span)
		}
		return s.remoteParentNotSampled.ShouldSample(span)
	}

	if span.Sampled {
		return s.localParentSampled.ShouldSample(span)
	}
	return s.localParentNotSampled.ShouldSample(span)
}

func (s *ParentBasedSampler) Name() string {
	return "parent_based"
}

type TailSamplingDecision string

const (
	TailDecisionSample   TailSamplingDecision = "sample"
	TailDecisionDrop     TailSamplingDecision = "drop"
	TailDecisionPending  TailSamplingDecision = "pending"
)

type TailSamplingPolicy struct {
	Name          string
	Decision      TailSamplingDecision
	Priority      int
	ApplyFunc     func(trace *models.Trace) bool
}

type TailSampler struct {
	policies []TailSamplingPolicy
	mu       sync.RWMutex
}

func NewTailSampler() *TailSampler {
	return &TailSampler{
		policies: make([]TailSamplingPolicy, 0),
	}
}

func (t *TailSampler) AddPolicy(policy TailSamplingPolicy) {
	t.mu.Lock()
	defer t.mu.Unlock()
	t.policies = append(t.policies, policy)
}

func (t *TailSampler) Evaluate(trace *models.Trace) TailSamplingDecision {
	t.mu.RLock()
	defer t.mu.RUnlock()

	for _, policy := range t.policies {
		if policy.ApplyFunc(trace) {
			return policy.Decision
		}
	}
	return TailDecisionSample
}

func ErrorRateTailPolicy(threshold float64) TailSamplingPolicy {
	return TailSamplingPolicy{
		Name:     "error_rate",
		Decision: TailDecisionSample,
		Priority: 10,
		ApplyFunc: func(trace *models.Trace) bool {
			if trace.SpanCount == 0 {
				return false
			}
			errorRate := float64(trace.ErrorCount) / float64(trace.SpanCount)
			return errorRate >= threshold
		},
	}
}

func LatencyTailPolicy(threshold time.Duration) TailSamplingPolicy {
	return TailSamplingPolicy{
		Name:     "latency",
		Decision: TailDecisionSample,
		Priority: 9,
		ApplyFunc: func(trace *models.Trace) bool {
			return trace.Duration >= threshold
		},
	}
}

func RandomDropPolicy(rate float64) TailSamplingPolicy {
	return TailSamplingPolicy{
		Name:     "random_drop",
		Decision: TailDecisionDrop,
		Priority: 1,
		ApplyFunc: func(trace *models.Trace) bool {
			return rand.Float64() < rate
		},
	}
}
