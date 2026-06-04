package retry

import (
	"context"
	"fmt"
	"math"
	"time"

	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/google/uuid"
)

type BackoffStrategy string

const (
	BackoffExponential BackoffStrategy = "exponential"
	BackoffFixed       BackoffStrategy = "fixed"
	BackoffLinear      BackoffStrategy = "linear"
)

type RetryManager struct {
	db    *storage.Database
	redis *storage.RedisClient
	ctx   context.Context
}

func NewRetryManager(db *storage.Database, redis *storage.RedisClient) *RetryManager {
	return &RetryManager{
		db:    db,
		redis: redis,
		ctx:   context.Background(),
	}
}

func (rm *RetryManager) CalculateBackoff(strategy BackoffStrategy, retryCount int, baseDelay time.Duration) time.Duration {
	switch strategy {
	case BackoffExponential:
		return time.Duration(math.Pow(2, float64(retryCount))) * baseDelay
	case BackoffFixed:
		return baseDelay
	case BackoffLinear:
		return time.Duration(retryCount+1) * baseDelay
	default:
		return time.Duration(math.Pow(2, float64(retryCount))) * baseDelay
	}
}

func (rm *RetryManager) ShouldRetry(execution *models.Execution, maxRetries int) bool {
	return execution.RetryCount < maxRetries
}

func (rm *RetryManager) ScheduleRetry(execution *models.Execution, task *models.Task) error {
	if !rm.ShouldRetry(execution, task.MaxRetries) {
		return rm.MoveToDeadLetter(execution, "max retries exceeded")
	}

	strategy := BackoffStrategy(task.RetryBackoff)
	backoff := rm.CalculateBackoff(strategy, execution.RetryCount, 5*time.Second)

	newExecution := &models.Execution{
		ID:              uuid.New().String(),
		TaskID:          execution.TaskID,
		Namespace:       execution.Namespace,
		Status:          models.ExecutionStatusRetrying,
		InputPayload:    execution.InputPayload,
		RetryCount:      execution.RetryCount + 1,
		CreatedAt:       time.Now(),
		ParentExecutionID: execution.ID,
	}

	query := `
		INSERT INTO executions (id, task_id, namespace, status, input_payload, retry_count, created_at, parent_execution_id)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8)
	`

	_, err := rm.db.Exec(query, newExecution.ID, newExecution.TaskID, newExecution.Namespace,
		newExecution.Status, newExecution.InputPayload, newExecution.RetryCount,
		newExecution.CreatedAt, newExecution.ParentExecutionID)
	if err != nil {
		return fmt.Errorf("failed to create retry execution: %w", err)
	}

	retryKey := fmt.Sprintf("retry:%s", newExecution.ID)
	rm.redis.Set(rm.ctx, retryKey, newExecution.ID, backoff)

	return nil
}

func (rm *RetryManager) MoveToDeadLetter(execution *models.Execution, reason string) error {
	deadLetter := &models.DeadLetter{
		ID:             uuid.New().String(),
		ExecutionID:    execution.ID,
		TaskID:         execution.TaskID,
		Namespace:      execution.Namespace,
		ErrorMessage:   reason + ": " + execution.ErrorMessage,
		OriginalStatus: string(execution.Status),
		Payload:        execution.InputPayload,
		CreatedAt:      time.Now(),
		Replayed:       false,
	}

	query := `
		INSERT INTO dead_letters (id, execution_id, task_id, namespace, error_message, original_status, payload, created_at, replayed)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
	`

	_, err := rm.db.Exec(query, deadLetter.ID, deadLetter.ExecutionID, deadLetter.TaskID,
		deadLetter.Namespace, deadLetter.ErrorMessage, deadLetter.OriginalStatus,
		deadLetter.Payload, deadLetter.CreatedAt, deadLetter.Replayed)
	if err != nil {
		return fmt.Errorf("failed to create dead letter: %w", err)
	}

	updateQuery := `
		UPDATE executions SET status = 'dead_letter' WHERE id = $1
	`
	rm.db.Exec(updateQuery, execution.ID)

	return nil
}

func (rm *RetryManager) ReplayDeadLetter(deadLetterID string, replayedBy string) (*models.Execution, error) {
	var deadLetter models.DeadLetter
	err := rm.db.Get(&deadLetter, "SELECT * FROM dead_letters WHERE id = $1", deadLetterID)
	if err != nil {
		return nil, fmt.Errorf("dead letter not found: %w", err)
	}

	now := time.Now()
	_, err = rm.db.Exec(`
		UPDATE dead_letters 
		SET replayed = true, replayed_at = $2, replayed_by = $3 
		WHERE id = $1
	`, deadLetterID, now, replayedBy)
	if err != nil {
		return nil, fmt.Errorf("failed to update dead letter: %w", err)
	}

	execution := &models.Execution{
		ID:              uuid.New().String(),
		TaskID:          deadLetter.TaskID,
		Namespace:       deadLetter.Namespace,
		Status:          models.ExecutionStatusPending,
		InputPayload:    deadLetter.Payload,
		CreatedAt:       time.Now(),
	}

	query := `
		INSERT INTO executions (id, task_id, namespace, status, input_payload, created_at)
		VALUES ($1, $2, $3, $4, $5, $6)
	`
	_, err = rm.db.Exec(query, execution.ID, execution.TaskID, execution.Namespace,
		execution.Status, execution.InputPayload, execution.CreatedAt)
	if err != nil {
		return nil, fmt.Errorf("failed to create replay execution: %w", err)
	}

	return execution, nil
}

func (rm *RetryManager) ListDeadLetters(namespace string, limit, offset int) ([]models.DeadLetter, int, error) {
	var letters []models.DeadLetter
	var total int

	countQuery := `SELECT COUNT(*) FROM dead_letters WHERE namespace = $1 OR $1 = ''`
	err := rm.db.Get(&total, countQuery, namespace)
	if err != nil {
		return nil, 0, err
	}

	query := `SELECT * FROM dead_letters WHERE namespace = $1 OR $1 = '' ORDER BY created_at DESC LIMIT $2 OFFSET $3`
	err = rm.db.Select(&letters, query, namespace, limit, offset)
	if err != nil {
		return nil, 0, err
	}

	return letters, total, nil
}

func (rm *RetryManager) GetDueRetries() ([]string, error) {
	keys, err := rm.redis.Keys(rm.ctx, "retry:*").Result()
	if err != nil {
		return nil, err
	}

	var dueIDs []string
	for _, key := range keys {
		ttl := rm.redis.TTL(rm.ctx, key).Val()
		if ttl <= 0 {
			id, err := rm.redis.Get(rm.ctx, key).Result()
			if err == nil {
				dueIDs = append(dueIDs, id)
			}
			rm.redis.Del(rm.ctx, key)
		}
	}

	return dueIDs, nil
}
