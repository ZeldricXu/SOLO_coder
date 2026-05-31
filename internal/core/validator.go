package core

import (
	"github.com/solocoder/task-scheduler/internal/contracts"
)

type ParameterValidator struct{}

func NewParameterValidator() *ParameterValidator {
	return &ParameterValidator{}
}

func (v *ParameterValidator) Validate(params map[string]interface{}) error {
	if params == nil {
		return &contracts.ValidationError{Details: map[string]string{"params": "params is required"}}
	}

	details := make(map[string]string)
	if _, ok := params["task_type"]; !ok {
		details["task_type"] = "task_type is required"
	}

	if len(details) > 0 {
		return &contracts.ValidationError{Details: details}
	}
	return nil
}
