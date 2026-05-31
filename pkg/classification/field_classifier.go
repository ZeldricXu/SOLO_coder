package classification

import (
	"fmt"

	"github.com/solocoder/session136/pkg/common/interfaces"
)

type FieldClassifier interface {
	ClassifyField(fieldName string, fieldValue interface{}) interfaces.SensitiveField
}

type RegexFieldClassifier struct {
	patternStore PatternStore
}

func NewRegexFieldClassifier(patternStore PatternStore) *RegexFieldClassifier {
	return &RegexFieldClassifier{
		patternStore: patternStore,
	}
}

func (c *RegexFieldClassifier) ClassifyField(fieldName string, fieldValue interface{}) interfaces.SensitiveField {
	strValue := fmt.Sprintf("%v", fieldValue)

	for patternName, pattern := range c.patternStore.GetAll() {
		if pattern.Pattern.MatchString(strValue) {
			return interfaces.SensitiveField{
				Name:        fieldName,
				Type:        patternName,
				Sensitivity: pattern.Sensitivity,
				Masked:      false,
			}
		}
	}

	return interfaces.SensitiveField{
		Name:        fieldName,
		Type:        "general",
		Sensitivity: "low",
		Masked:      false,
	}
}
