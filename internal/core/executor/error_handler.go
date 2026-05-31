package executor

import (
	"context"
	"errors"
	"fmt"

	"go.uber.org/zap"

	"github.com/solocoder/task-scheduler/v2/internal/common"
	"github.com/solocoder/task-scheduler/v2/internal/core/ports"
)

type ErrorHandler struct {
	logger *zap.Logger
}

func NewErrorHandler(logger *zap.Logger) *ErrorHandler {
	return &ErrorHandler{
		logger: logger,
	}
}

func (h *ErrorHandler) HandleValidationError(err error) *ports.ProcessResult {
	var verr *common.ValidationError
	if errors.As(err, &verr) {
		return &ports.ProcessResult{
			Success:    false,
			Error:      verr.Error(),
			StatusCode: 422,
		}
	}

	return &ports.ProcessResult{
		Success:    false,
		Error:      err.Error(),
		StatusCode: 422,
	}
}

func (h *ErrorHandler) HandleTimeoutError(err error) *ports.ProcessResult {
	var terr *common.TimeoutError
	if errors.As(err, &terr) {
		return &ports.ProcessResult{
			Success:    false,
			Error:      terr.Message,
			StatusCode: 504,
		}
	}

	return &ports.ProcessResult{
		Success:    false,
		Error:      "上游服务响应超时",
		StatusCode: 504,
	}
}

func (h *ErrorHandler) HandleProcessingError(
	ctx context.Context,
	err error,
	runID string,
	persister *ResultPersister,
) *ports.ProcessResult {
	h.logger.Error("Processing error",
		zap.String("run_id", runID),
		zap.Error(err))

	_ = persister.UpdateRunPhase(ctx, runID, "failed", err.Error())

	var verr *common.ValidationError
	var terr *common.TimeoutError

	switch {
	case errors.As(err, &verr):
		return &ports.ProcessResult{
			Success:    false,
			Error:      verr.Error(),
			StatusCode: 422,
		}
	case errors.As(err, &terr):
		return &ports.ProcessResult{
			Success:    false,
			Error:      terr.Message,
			StatusCode: 504,
		}
	default:
		h.rollbackTransaction(ctx, runID)
		return &ports.ProcessResult{
			Success:    false,
			Error:      "内部处理错误",
			StatusCode: 500,
		}
	}
}

func (h *ErrorHandler) HandleGenericError(err error, msg string) *ports.ProcessResult {
	return &ports.ProcessResult{
		Success:    false,
		Error:      fmt.Sprintf("%s: %v", msg, err),
		StatusCode: 500,
	}
}

func (h *ErrorHandler) rollbackTransaction(ctx context.Context, runID string) {
	h.logger.Warn("Transaction rollback triggered",
		zap.String("run_id", runID))
}

func (h *ErrorHandler) IsValidationError(err error) bool {
	var verr *common.ValidationError
	return errors.As(err, &verr)
}

func (h *ErrorHandler) IsTimeoutError(err error) bool {
	var terr *common.TimeoutError
	return errors.As(err, &terr)
}
