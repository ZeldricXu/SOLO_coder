package classification

import (
	"context"

	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
)

type DataClassifier interface {
	Classify(ctx context.Context, data map[string]interface{}) (*interfaces.ClassificationResult, error)
}

type DefaultDataClassifier struct {
	fieldClassifier FieldClassifier
	patternStore    PatternStore
}

func NewDefaultDataClassifier(fieldClassifier FieldClassifier, patternStore PatternStore) *DefaultDataClassifier {
	return &DefaultDataClassifier{
		fieldClassifier: fieldClassifier,
		patternStore:    patternStore,
	}
}

func (c *DefaultDataClassifier) Classify(ctx context.Context, data map[string]interface{}) (*interfaces.ClassificationResult, error) {
	dataID, ok := data["id"].(string)
	if !ok {
		dataID = utils.GenerateID("data")
	}

	result := &interfaces.ClassificationResult{
		DataID: dataID,
		Fields: make([]interfaces.SensitiveField, 0),
	}

	maxLevel := 0

	for fieldName, fieldValue := range data {
		if fieldName == "id" {
			continue
		}

		field := c.fieldClassifier.ClassifyField(fieldName, fieldValue)
		result.Fields = append(result.Fields, field)

		if pattern, exists := c.patternStore.Get(field.Type); exists {
			if pattern.Level > maxLevel {
				maxLevel = pattern.Level
			}
		}
	}

	result.Level = maxLevel
	result.Sensitivity = determineSensitivity(maxLevel)
	result.Category = c.determineCategory(result.Fields)

	return result, nil
}

func determineSensitivity(level int) string {
	switch {
	case level >= 4:
		return "high"
	case level >= 2:
		return "medium"
	default:
		return "low"
	}
}

func (c *DefaultDataClassifier) determineCategory(fields []interfaces.SensitiveField) string {
	categoryCount := make(map[string]int)
	for _, f := range fields {
		if pattern, ok := c.patternStore.Get(f.Type); ok {
			categoryCount[pattern.Category]++
		}
	}

	maxCount := 0
	dominantCategory := "general"
	for cat, count := range categoryCount {
		if count > maxCount {
			maxCount = count
			dominantCategory = cat
		}
	}
	return dominantCategory
}
