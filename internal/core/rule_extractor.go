package core

import (
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
	"github.com/solocoder/task-scheduler/internal/models"
)

type RuleExtractor struct{}

func NewRuleExtractor() *RuleExtractor {
	return &RuleExtractor{}
}

func (r *RuleExtractor) ExtractRules(config *models.ConfigDefinition) *contracts.ProcessingRules {
	return r.ExtractRulesForScene(config, contracts.SceneDefault)
}

func (r *RuleExtractor) ExtractRulesForScene(config *models.ConfigDefinition, scene contracts.SceneStrategy) *contracts.ProcessingRules {
	rules := &contracts.ProcessingRules{
		Timeout:    30 * time.Second,
		MaxRetries: 3,
		Scene:      string(scene),
	}

	if config.Parameters != nil {
		if sceneConfig, ok := config.Parameters["scenes"].(map[string]interface{}); ok {
			if sceneRules, ok := sceneConfig[string(scene)].(map[string]interface{}); ok {
				if timeout, ok := sceneRules["timeout"].(float64); ok {
					rules.Timeout = time.Duration(timeout) * time.Second
				}
				if retries, ok := sceneRules["retries"].(float64); ok {
					rules.MaxRetries = int(retries)
				}
				if validation, ok := sceneRules["validation"].(map[string]interface{}); ok {
					rules.Validation = validation
				}
				if postProcessors, ok := sceneRules["post_processors"].([]string); ok {
					rules.PostProcessors = postProcessors
				}
				return rules
			}
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
		if postProcessors, ok := config.Parameters["post_processors"].([]string); ok {
			rules.PostProcessors = postProcessors
		}
	}

	return rules
}
