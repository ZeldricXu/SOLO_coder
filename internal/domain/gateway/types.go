package gateway

type InferenceRequest struct {
	TraceID    string                 `json:"trace_id"`
	Model      string                 `json:"model"`
	Prompt     string                 `json:"prompt"`
	Messages   []*Message             `json:"messages,omitempty"`
	Params     map[string]interface{} `json:"params"`
	TimeoutMs  int                    `json:"timeout_ms,omitempty"`
	MaxRetries int                    `json:"max_retries,omitempty"`
}

type Message struct {
	Role    string `json:"role"`
	Content string `json:"content"`
}

type InferenceResponse struct {
	TraceID       string                 `json:"trace_id"`
	Provider      string                 `json:"provider"`
	Model         string                 `json:"model"`
	Text          string                 `json:"text"`
	Usage         *TokenUsage            `json:"usage"`
	LatencyMs     int64                  `json:"latency_ms"`
}

type TokenUsage struct {
	PromptTokens     int `json:"prompt_tokens"`
	CompletionTokens int `json:"completion_tokens"`
	TotalTokens      int `json:"total_tokens"`
}

type ProviderConfig struct {
	Name       string            `json:"name"`
	BaseURL    string            `json:"base_url"`
	APIKey     string            `json:"api_key"`
	Models     []string          `json:"models"`
	Priority   int               `json:"priority"`
	Weight     int               `json:"weight"`
	TimeoutMs  int               `json:"timeout_ms"`
	MaxRetries int               `json:"max_retries"`
}

type CircuitBreakerState string

const (
	CircuitClosed   CircuitBreakerState = "closed"
	CircuitOpen     CircuitBreakerState = "open"
	CircuitHalfOpen CircuitBreakerState = "half_open"
)
