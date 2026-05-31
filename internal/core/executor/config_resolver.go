package executor

import (
	"context"
	"fmt"
	"time"

	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type ConfigResolver struct {
	configLoader ports.ConfigLoader
}

func NewConfigResolver(configLoader ports.ConfigLoader) *ConfigResolver {
	return &ConfigResolver{
		configLoader: configLoader,
	}
}

func (r *ConfigResolver) Resolve(
	ctx context.Context,
	namespace string,
) (*ports.ProcessingRules, context.Context, context.CancelFunc, error) {
	config, err := r.configLoader.Load(ctx, namespace)
	if err != nil {
		return nil, nil, nil, fmt.Errorf("config load failed: %w", err)
	}

	rules := r.extractRules(config)
	processCtx, cancel := context.WithTimeout(ctx, rules.Timeout)

	return rules, processCtx, cancel, nil
}

func (r *ConfigResolver) extractRules(config *ports.ConfigDefinition) *ports.ProcessingRules {
	rules := &ports.ProcessingRules{
		Timeout:    30 * time.Second,
		MaxRetries: 3,
	}

	if config.Parameters == nil {
		return rules
	}

	if timeout, ok := config.Parameters["timeout"].(float64); ok {
		rules.Timeout = time.Duration(timeout) * time.Second
	}

	if retries, ok := config.Parameters["retries"].(float64); ok {
		rules.MaxRetries = int(retries)
	}

	if validation, ok := config.Parameters["validation"].(map[string]interface{}); ok {
		rules.Validation = validation
	}

	return rules
}
