package scheduler

import (
	"context"
	"fmt"
	"time"

	"github.com/solocoder/cloudci/internal/common/types"
	"github.com/solocoder/cloudci/internal/logstore"
	"github.com/solocoder/cloudci/internal/logger"
	"github.com/solocoder/cloudci/internal/models"
	"github.com/solocoder/cloudci/internal/plugin"
	"go.uber.org/zap"
)

type AsyncPluginExecutor struct {
	pluginMgr *plugin.PluginManager
	logStore  *logstore.LogStore
}

func NewAsyncPluginExecutor(pluginMgr *plugin.PluginManager, logStore *logstore.LogStore) *AsyncPluginExecutor {
	return &AsyncPluginExecutor{
		pluginMgr: pluginMgr,
		logStore:  logStore,
	}
}

func (e *AsyncPluginExecutor) Execute(
	ctx context.Context,
	exec *models.PipelineExecution,
	stageDef types.StageDefinition,
	se *models.StageExecution,
	req *plugin.StageContext,
	logCallback func(*plugin.LogEntry),
) (*plugin.ExecuteResponse, error) {
	client, err := e.pluginMgr.GetClient(ctx, stageDef.Plugin.Name, stageDef.Plugin.Version)
	if err != nil {
		return nil, fmt.Errorf("plugin not available: %w", err)
	}

	mode, err := client.GetExecutionMode(ctx, stageDef.Plugin.Name)
	if err != nil {
		return nil, fmt.Errorf("failed to get execution mode: %w", err)
	}

	if mode == plugin.ExecutionModeSync {
		return client.Execute(ctx, req, logCallback)
	}

	return e.executeAsync(ctx, client, exec, stageDef, se, req, logCallback)
}

func (e *AsyncPluginExecutor) executeAsync(
	ctx context.Context,
	client plugin.StagePluginClient,
	exec *models.PipelineExecution,
	stageDef types.StageDefinition,
	se *models.StageExecution,
	req *plugin.StageContext,
	logCallback func(*plugin.LogEntry),
) (*plugin.ExecuteResponse, error) {
	startResp, err := client.StartExecution(ctx, req)
	if err != nil {
		return nil, fmt.Errorf("failed to start async execution: %w", err)
	}

	if startResp.Status == plugin.StageStatusFailed {
		return &plugin.ExecuteResponse{
			Status: startResp.Status,
			Error:  startResp.Message,
		}, nil
	}

	e.logStore.AppendLog(exec.ID, se.ID, "INFO",
		fmt.Sprintf("Async execution started with ID: %s", startResp.ExecutionID), "")

	pollInterval := 5 * time.Second
	if startResp.PollIntervalSeconds > 0 {
		pollInterval = time.Duration(startResp.PollIntervalSeconds) * time.Second
	}

	timeout := time.Until(*se.TimeoutAt)
	pollCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	ticker := time.NewTicker(pollInterval)
	defer ticker.Stop()

	for {
		select {
		case <-pollCtx.Done():
			_, cancelErr := client.CancelExecution(ctx, stageDef.Plugin.Name, startResp.ExecutionID, req)
			if cancelErr != nil {
				logger.Warn("failed to cancel async execution",
					zap.String("execution_id", string(exec.ID)),
					zap.String("stage", stageDef.Name),
					zap.Error(cancelErr))
			}
			return nil, fmt.Errorf("async execution timeout")

		case <-ticker.C:
			pollResp, err := client.PollStatus(ctx, stageDef.Plugin.Name, startResp.ExecutionID, req)
			if err != nil {
				logger.Warn("poll status failed, retrying",
					zap.String("execution_id", string(exec.ID)),
					zap.String("stage", stageDef.Name),
					zap.Error(err))
				continue
			}

			for _, log := range pollResp.Logs {
				if logCallback != nil {
					logCallback(log)
				}
			}

			if pollResp.Completed {
				return e.convertPollToExecuteResponse(pollResp), nil
			}

			if pollResp.ProgressPercent > 0 {
				e.logStore.AppendLog(exec.ID, se.ID, "INFO",
					fmt.Sprintf("Progress: %d%%", pollResp.ProgressPercent), "")
			}
		}
	}
}

func (e *AsyncPluginExecutor) convertPollToExecuteResponse(pollResp *plugin.PollStatusResponse) *plugin.ExecuteResponse {
	return &plugin.ExecuteResponse{
		Status:     pollResp.Status,
		ExitCode:   0,
		Error:      pollResp.Error,
		Logs:       pollResp.Logs,
		Artifacts:  pollResp.Artifacts,
		Output:     pollResp.Output,
		DurationMs: pollResp.DurationMs,
	}
}

func (e *AsyncPluginExecutor) Cancel(
	ctx context.Context,
	pluginName, pluginVersion, executionID string,
	req *plugin.StageContext,
) error {
	client, err := e.pluginMgr.GetClient(ctx, pluginName, pluginVersion)
	if err != nil {
		return fmt.Errorf("plugin not available: %w", err)
	}

	_, err = client.CancelExecution(ctx, pluginName, executionID, req)
	return err
}
