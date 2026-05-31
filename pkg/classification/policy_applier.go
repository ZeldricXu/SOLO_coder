package classification

import (
	"context"
	"fmt"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"go.uber.org/zap"
)

type PolicyApplier interface {
	ApplyPolicy(ctx context.Context, result *interfaces.ClassificationResult) error
}

type DefaultPolicyApplier struct {
	policyStore PolicyStore
	logger      *zap.Logger
}

func NewDefaultPolicyApplier(policyStore PolicyStore, logger *zap.Logger) *DefaultPolicyApplier {
	return &DefaultPolicyApplier{
		policyStore: policyStore,
		logger:      logger,
	}
}

func (a *DefaultPolicyApplier) ApplyPolicy(ctx context.Context, result *interfaces.ClassificationResult) error {
	policy, ok := a.policyStore.Get(result.Level)
	if !ok {
		return fmt.Errorf("no policy found for level %d", result.Level)
	}

	result.Policy = policy.Action

	a.logger.Info("Applying classification policy",
		zap.String("data_id", result.DataID),
		zap.String("policy", policy.Action),
		zap.String("description", policy.Description),
	)

	a.applyPolicyAction(result, policy.Action)

	return nil
}

func (a *DefaultPolicyApplier) applyPolicyAction(result *interfaces.ClassificationResult, action string) {
	switch action {
	case "none":
		for i := range result.Fields {
			result.Fields[i].Masked = false
		}
	case "log":
		a.logger.Info("Access logged for sensitive data", zap.String("data_id", result.DataID))
	case "mask":
		for i := range result.Fields {
			if result.Fields[i].Sensitivity != "low" {
				result.Fields[i].Masked = true
			}
		}
	case "encrypt":
		for i := range result.Fields {
			if result.Fields[i].Sensitivity == "high" {
				result.Fields[i].Masked = true
			}
		}
	case "restrict":
		for i := range result.Fields {
			result.Fields[i].Masked = true
		}
	}
}
