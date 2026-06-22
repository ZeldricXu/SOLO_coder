package engine

import (
	"strings"

	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
	"github.com/featureflag/platform/pkg/utils"
)

type StrategyEngine struct{}

func NewStrategyEngine() *StrategyEngine {
	return &StrategyEngine{}
}

func (e *StrategyEngine) Evaluate(
	sw *model.Switch,
	ctx *model.EvaluationContext,
	strategies []*model.Strategy,
) *model.EvaluationResult {
	result := &model.EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
	}

	if !sw.Enabled {
		result.Reason = "switch_disabled"
		return result
	}

	if !e.matchScope(sw, ctx) {
		result.Reason = "scope_not_match"
		return result
	}

	switch sw.Type {
	case model.SwitchTypeBoolean:
		return e.evaluateBoolean(sw, ctx)
	case model.SwitchTypePercentage:
		return e.evaluatePercentage(sw, ctx, strategies)
	case model.SwitchTypeWhitelist:
		return e.evaluateWhitelist(sw, ctx, strategies)
	default:
		result.Reason = "unknown_type"
		return result
	}
}

func (e *StrategyEngine) matchScope(sw *model.Switch, ctx *model.EvaluationContext) bool {
	switch sw.Scope {
	case model.ScopeGlobal:
		return true
	case model.ScopeEnvironment:
		return sw.Environment == ctx.Environment
	case model.ScopeTenant:
		return sw.Environment == ctx.Environment && sw.TenantID == ctx.TenantID
	default:
		return false
	}
}

func (e *StrategyEngine) evaluateBoolean(sw *model.Switch, ctx *model.EvaluationContext) *model.EvaluationResult {
	return &model.EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
		Matched:  sw.BooleanValue,
		Reason:   "boolean_type",
		Value:    sw.BooleanValue,
	}
}

func (e *StrategyEngine) evaluatePercentage(
	sw *model.Switch,
	ctx *model.EvaluationContext,
	strategies []*model.Strategy,
) *model.EvaluationResult {
	result := &model.EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
	}

	if ctx.UserID == "" {
		result.Reason = "no_user_id"
		return result
	}

	hash := utils.PercentageHash(ctx.UserID, sw.Key)
	percentagePassed := hash < sw.PercentageValue

	if len(strategies) == 0 {
		result.Matched = percentagePassed
		if percentagePassed {
			result.Reason = "percentage_passed"
		} else {
			result.Reason = "percentage_not_passed"
		}
		result.Value = percentagePassed
		return result
	}

	for _, strategy := range strategies {
		if !strategy.Enabled {
			continue
		}

		strategyMatched := e.evaluateStrategy(strategy, ctx)

		switch strategy.Operator {
		case model.OperatorAND:
			if percentagePassed && strategyMatched {
				result.Matched = true
				result.Reason = "percentage_and_strategy_passed"
				result.MatchedStrategy = strategy
				result.Value = true
				return result
			}
		case model.OperatorOR:
			if percentagePassed || strategyMatched {
				result.Matched = true
				result.Reason = "percentage_or_strategy_passed"
				result.MatchedStrategy = strategy
				result.Value = true
				return result
			}
		}
	}

	result.Reason = "no_strategy_matched"
	return result
}

func (e *StrategyEngine) evaluateWhitelist(
	sw *model.Switch,
	ctx *model.EvaluationContext,
	strategies []*model.Strategy,
) *model.EvaluationResult {
	result := &model.EvaluationResult{
		SwitchKey: sw.Key,
		Enabled:   sw.Enabled,
	}

	if len(strategies) == 0 {
		result.Reason = "no_strategy_configured"
		return result
	}

	for _, strategy := range strategies {
		if !strategy.Enabled {
			continue
		}

		if e.evaluateStrategy(strategy, ctx) {
			result.Matched = true
			result.Reason = "strategy_matched"
			result.MatchedStrategy = strategy
			result.Value = true
			return result
		}
	}

	result.Reason = "no_strategy_matched"
	return result
}

func (e *StrategyEngine) evaluateStrategy(strategy *model.Strategy, ctx *model.EvaluationContext) bool {
	if len(strategy.Conditions) == 0 {
		return true
	}

	allMatch := true
	anyMatch := false

	for _, condition := range strategy.Conditions {
		matched := e.evaluateCondition(condition, ctx)

		switch strategy.Operator {
		case model.OperatorAND:
			if !matched {
				allMatch = false
			}
		case model.OperatorOR:
			if matched {
				anyMatch = true
			}
		}
	}

	switch strategy.Operator {
	case model.OperatorAND:
		return allMatch
	case model.OperatorOR:
		return anyMatch
	default:
		return false
	}
}

func (e *StrategyEngine) evaluateCondition(cond *model.WhitelistCondition, ctx *model.EvaluationContext) bool {
	var fieldValue string
	var fieldValues []string

	switch cond.Field {
	case model.FieldUserID:
		fieldValue = ctx.UserID
	case model.FieldDepartment:
		fieldValue = ctx.Department
	case model.FieldTag:
		fieldValues = ctx.Tags
	default:
		return false
	}

	switch cond.Operator {
	case model.OpIn:
		if cond.Field == model.FieldTag {
			for _, v := range fieldValues {
				if utils.ContainsString(cond.Values, v) {
					return true
				}
			}
			return false
		}
		return utils.ContainsString(cond.Values, fieldValue)

	case model.OpNotIn:
		if cond.Field == model.FieldTag {
			for _, v := range fieldValues {
				if utils.ContainsString(cond.Values, v) {
					return false
				}
			}
			return true
		}
		return !utils.ContainsString(cond.Values, fieldValue)

	case model.OpContains:
		if cond.Field == model.FieldTag {
			for _, tag := range fieldValues {
				for _, v := range cond.Values {
					if strings.Contains(tag, v) {
						return true
					}
				}
			}
			return false
		}
		for _, v := range cond.Values {
			if strings.Contains(fieldValue, v) {
				return true
			}
		}
		return false

	case model.OpNotContains:
		if cond.Field == model.FieldTag {
			for _, tag := range fieldValues {
				for _, v := range cond.Values {
					if strings.Contains(tag, v) {
						return false
					}
				}
			}
			return true
		}
		for _, v := range cond.Values {
			if strings.Contains(fieldValue, v) {
				return false
			}
		}
		return true

	default:
		logger.Warnf("unknown operator: %s", cond.Operator)
		return false
	}
}

func (e *StrategyEngine) BatchEvaluate(
	switches []*model.Switch,
	ctx *model.EvaluationContext,
	allStrategies map[string][]*model.Strategy,
) map[string]*model.EvaluationResult {
	results := make(map[string]*model.EvaluationResult)

	for _, sw := range switches {
		strategies := allStrategies[sw.ID]
		results[sw.Key] = e.Evaluate(sw, ctx, strategies)
	}

	return results
}
