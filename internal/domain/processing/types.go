package processing

type TransformRule struct {
	ID          string                 `json:"id"`
	Name        string                 `json:"name"`
	Type        string                 `json:"type"`
	Config      map[string]interface{} `json:"config"`
	Enabled     bool                   `json:"enabled"`
}

type Schema struct {
	Name      string                 `json:"name"`
	Version   string                 `json:"version"`
	Fields    []*FieldSchema         `json:"fields"`
}

type FieldSchema struct {
	Name     string      `json:"name"`
	Type     string      `json:"type"`
	Required bool        `json:"required"`
	Default  interface{} `json:"default,omitempty"`
}

type ProcessRequest struct {
	TraceID    string                 `json:"trace_id"`
	Namespace  string                 `json:"namespace"`
	Payload    interface{}            `json:"payload"`
	RuleIDs    []string               `json:"rule_ids,omitempty"`
	SchemaName string                 `json:"schema_name,omitempty"`
}

type ProcessResult struct {
	TraceID    string                 `json:"trace_id"`
	Output     interface{}            `json:"output"`
	DurationMs int64                  `json:"duration_ms"`
}
