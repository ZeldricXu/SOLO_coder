package core

import (
	"context"
	"time"

	"github.com/solocoder/task-scheduler/internal/contracts"
)

type TaskProcessor struct{}

func NewTaskProcessor() *TaskProcessor {
	return &TaskProcessor{}
}

func (p *TaskProcessor) Process(ctx context.Context, payload map[string]interface{}, rules *contracts.ProcessingRules) (map[string]interface{}, error) {
	select {
	case <-ctx.Done():
		return nil, &contracts.TimeoutError{Message: "上游服务响应超时"}
	default:
	}

	result := make(map[string]interface{})
	result["processed"] = true
	result["timestamp"] = time.Now().UTC()
	result["original_payload"] = payload

	if taskType, ok := payload["task_type"].(string); ok {
		result["task_type_processed"] = taskType + "_processed"
	}

	time.Sleep(10 * time.Millisecond)

	select {
	case <-ctx.Done():
		return nil, &contracts.TimeoutError{Message: "processing timeout"}
	default:
	}

	return result, nil
}
