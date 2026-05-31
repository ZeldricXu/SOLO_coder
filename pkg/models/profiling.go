package models

import (
	"time"
)

type ProfileType string

const (
	ProfileTypeCPU         ProfileType = "CPU"
	ProfileTypeHeap        ProfileType = "HEAP"
	ProfileTypeGoroutine   ProfileType = "GOROUTINE"
	ProfileTypeMutex       ProfileType = "MUTEX"
	ProfileTypeBlock       ProfileType = "BLOCK"
	ProfileTypeMemoryAlloc ProfileType = "MEMORY_ALLOC"
)

type ProfileSample struct {
	Timestamp      time.Time              `json:"timestamp"`
	ProfileType    ProfileType            `json:"profile_type"`
	Duration       time.Duration          `json:"duration"`
	SampleRate     int                    `json:"sample_rate"`
	Data           []byte                 `json:"data,omitempty"`
	ServiceName    string                 `json:"service_name"`
	InstanceID     string                 `json:"instance_id"`
	Labels         map[string]string      `json:"labels,omitempty"`
}

type FlameGraphNode struct {
	Name      string           `json:"name"`
	Value     int64            `json:"value"`
	Children  []FlameGraphNode `json:"children,omitempty"`
	Package   string           `json:"package,omitempty"`
	File      string           `json:"file,omitempty"`
	Line      int              `json:"line,omitempty"`
}

type FlameGraph struct {
	ID          string           `json:"id"`
	ProfileType ProfileType      `json:"profile_type"`
	ServiceName string           `json:"service_name"`
	GeneratedAt time.Time        `json:"generated_at"`
	Duration    time.Duration    `json:"duration"`
	Root        FlameGraphNode   `json:"root"`
	TotalSamples int64           `json:"total_samples"`
}

type ProfileComparison struct {
	BaseProfileID    string        `json:"base_profile_id"`
	CompareProfileID string        `json:"compare_profile_id"`
	DiffFlameGraph   *FlameGraph   `json:"diff_flame_graph,omitempty"`
	HotSpots         []HotSpot     `json:"hot_spots,omitempty"`
	TopRegressions   []Regression  `json:"top_regressions,omitempty"`
	TopImprovements  []Improvement `json:"top_improvements,omitempty"`
}

type HotSpot struct {
	Name         string  `json:"name"`
	SelfValue    int64   `json:"self_value"`
	TotalValue   int64   `json:"total_value"`
	Percentage   float64 `json:"percentage"`
}

type Regression struct {
	Name          string  `json:"name"`
	BaseValue     int64   `json:"base_value"`
	CompareValue  int64   `json:"compare_value"`
	AbsoluteDiff  int64   `json:"absolute_diff"`
	RelativeDiff  float64 `json:"relative_diff"`
}

type Improvement struct {
	Name          string  `json:"name"`
	BaseValue     int64   `json:"base_value"`
	CompareValue  int64   `json:"compare_value"`
	AbsoluteDiff  int64   `json:"absolute_diff"`
	RelativeDiff  float64 `json:"relative_diff"`
}
