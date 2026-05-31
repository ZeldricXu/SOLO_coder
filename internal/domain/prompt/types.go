package prompt

import "time"

type PromptExperiment struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Description string                 `json:"description"`
	CreatedAt   time.Time              `json:"created_at"`
}

type PromptVersion struct {
	ID          string                 `json:"id"`
	ExperimentID string                `json:"experiment_id"`
	Version     int                    `json:"version"`
	Template    string                 `json:"template"`
	Variables   map[string]interface{} `json:"variables"`
	Metadata    map[string]string      `json:"metadata"`
	CreatedAt   time.Time              `json:"created_at"`
}

type ABTestConfig struct {
	ID            string            `json:"id"`
	ExperimentID  string            `json:"experiment_id"`
	Name          string            `json:"name"`
	VersionIDs    []string          `json:"version_ids"`
	TrafficSplit  map[string]int    `json:"traffic_split"`
	StartTime     time.Time         `json:"start_time"`
	EndTime       *time.Time        `json:"end_time,omitempty"`
}

type ABTest struct {
	ID            string            `json:"id"`
	Config        *ABTestConfig     `json:"config"`
	Status        string            `json:"status"`
	Metrics       map[string]float64 `json:"metrics"`
}

type ABTestResult struct {
	TestID        string            `json:"test_id"`
	WinningVersion string           `json:"winning_version"`
	Confidence    float64           `json:"confidence"`
	Metrics       map[string]*VersionMetric `json:"metrics"`
}

type VersionMetric struct {
	VersionID string             `json:"version_id"`
	Values    map[string]float64 `json:"values"`
	SampleSize int               `json:"sample_size"`
}
