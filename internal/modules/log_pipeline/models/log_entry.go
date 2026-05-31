package models

type LogEntry struct {
	ID        string                 `json:"id"`
	Timestamp int64                  `json:"timestamp"`
	Level     string                 `json:"level"`
	Message   string                 `json:"message"`
	Service   string                 `json:"service"`
	Host      string                 `json:"host"`
	TraceID   string                 `json:"trace_id,omitempty"`
	Tags      map[string]string      `json:"tags,omitempty"`
	Fields    map[string]interface{} `json:"fields,omitempty"`
	Raw       string                 `json:"raw,omitempty"`
}

type PipelineStage string

const (
	StageCollect PipelineStage = "collect"
	StageParse   PipelineStage = "parse"
	StageFilter  PipelineStage = "filter"
	StageEnrich  PipelineStage = "enrich"
	StageRoute   PipelineStage = "route"
)

type FilterOperator string

const (
	OpEquals      FilterOperator = "equals"
	OpNotEquals   FilterOperator = "not_equals"
	OpContains    FilterOperator = "contains"
	OpNotContains FilterOperator = "not_contains"
	OpStartsWith  FilterOperator = "starts_with"
	OpEndsWith    FilterOperator = "ends_with"
	OpRegex       FilterOperator = "regex"
)

type FilterRule struct {
	Field    string         `json:"field"`
	Operator FilterOperator `json:"operator"`
	Value    interface{}    `json:"value"`
}

type RouterRule struct {
	Match   map[string]interface{} `json:"match"`
	Outputs []string               `json:"outputs"`
}

type LogProcessor func(entry *LogEntry) (*LogEntry, error)
type LogOutput func(entry *LogEntry) error

type PipelineStats struct {
	FilterCount     int   `json:"filter_count"`
	RouterCount     int   `json:"router_count"`
	OutputCount     int   `json:"output_count"`
	InputQueueSize  int   `json:"input_queue_size"`
	BatchQueueSize  int   `json:"batch_queue_size"`
	ProcessedTotal  int64 `json:"processed_total"`
	DroppedTotal    int64 `json:"dropped_total"`
}
