package adversarial

import "time"

type AttackStrategy string

const (
	StrategyPromptInjection AttackStrategy = "prompt_injection"
	StrategyJailbreak       AttackStrategy = "jailbreak"
	StrategyGradientDescent AttackStrategy = "gradient_descent"
	StrategyTokenFlipping   AttackStrategy = "token_flipping"
	StrategyAdversarialSuffix AttackStrategy = "adversarial_suffix"
)

type AdversarialSample struct {
	ID           string         `json:"id"`
	Strategy     AttackStrategy `json:"strategy"`
	BasePrompt   string         `json:"base_prompt"`
	AdversarialPrompt string    `json:"adversarial_prompt"`
	TargetOutput string         `json:"target_output,omitempty"`
	GeneratedAt  time.Time      `json:"generated_at"`
}

type AttackEvaluation struct {
	ID            string            `json:"id"`
	SampleCount   int               `json:"sample_count"`
	SuccessRate   float64           `json:"success_rate"`
	AvgConfidence float64           `json:"avg_confidence"`
	StrategyStats map[string]*StrategyStat `json:"strategy_stats"`
	EvaluatedAt   time.Time         `json:"evaluated_at"`
}

type StrategyStat struct {
	TotalCount  int     `json:"total_count"`
	SuccessCount int    `json:"success_count"`
	SuccessRate float64 `json:"success_rate"`
}

type GenerateRequest struct {
	BasePrompt string           `json:"base_prompt"`
	Strategies []AttackStrategy `json:"strategies"`
	Count      int              `json:"count"`
}
