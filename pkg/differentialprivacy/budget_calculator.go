package differentialprivacy

import "fmt"

type BudgetCalculator interface {
	CalculateUsage(operations int, perOpEpsilon float64) (float64, error)
	OptimizeAllocation(totalBudget float64, priorities map[string]float64) (map[string]float64, error)
}

type DefaultBudgetCalculator struct{}

func NewDefaultBudgetCalculator() *DefaultBudgetCalculator {
	return &DefaultBudgetCalculator{}
}

func (c *DefaultBudgetCalculator) CalculateUsage(operations int, perOpEpsilon float64) (float64, error) {
	if operations <= 0 {
		return 0, fmt.Errorf("invalid number of operations: %d", operations)
	}
	if perOpEpsilon <= 0 {
		return 0, fmt.Errorf("invalid per-operation epsilon: %f", perOpEpsilon)
	}

	return float64(operations) * perOpEpsilon, nil
}

func (c *DefaultBudgetCalculator) OptimizeAllocation(totalBudget float64, priorities map[string]float64) (map[string]float64, error) {
	totalPriority := 0.0
	for _, p := range priorities {
		if p <= 0 {
			return nil, fmt.Errorf("invalid priority value: %f", p)
		}
		totalPriority += p
	}

	if totalPriority == 0 {
		return nil, fmt.Errorf("total priority cannot be zero")
	}

	allocation := make(map[string]float64)
	for key, priority := range priorities {
		allocation[key] = totalBudget * (priority / totalPriority)
	}

	return allocation, nil
}
