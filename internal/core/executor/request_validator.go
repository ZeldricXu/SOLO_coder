package executor

import (
	"github.com/solocoder/task-scheduler/v2/internal/common"
	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type RequestValidator struct {
	validator ports.Validator
}

func NewRequestValidator(validator ports.Validator) *RequestValidator {
	return &RequestValidator{
		validator: validator,
	}
}

func (v *RequestValidator) Validate(req *ports.ProcessRequest) error {
	if req == nil {
		return common.NewValidationError(map[string]string{
			"request": "request is required",
		})
	}

	details := make(map[string]string)

	if req.TraceID == "" {
		details["trace_id"] = "trace_id is required"
	}
	if req.Namespace == "" {
		details["namespace"] = "namespace is required"
	}
	if req.EntityID == "" {
		details["entity_id"] = "entity_id is required"
	}

	if len(details) > 0 {
		return common.NewValidationError(details)
	}

	if verr := v.validator.Validate(req.Params); verr != nil {
		return verr
	}

	return nil
}
