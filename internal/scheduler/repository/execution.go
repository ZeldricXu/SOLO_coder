package repository

import (
	"gorm.io/gorm"
	"session187/internal/common"
	"session187/internal/scheduler"
	"session187/pkg/errors"
)

type ExecutionRepository interface {
	Create(execution *scheduler.TaskExecution) (*scheduler.TaskExecution, error)
	Update(execution *scheduler.TaskExecution) (*scheduler.TaskExecution, error)
	List(tenantID, taskID string, limit int) ([]scheduler.TaskExecution, error)
}

type GormExecutionRepository struct {
	db *gorm.DB
}

func NewExecutionRepository(db *gorm.DB) ExecutionRepository {
	return &GormExecutionRepository{db: db}
}

func (r *GormExecutionRepository) Create(execution *scheduler.TaskExecution) (*scheduler.TaskExecution, error) {
	if execution.ID == "" {
		execution.ID = common.GenerateID("exe")
	}
	if execution.Status == "" {
		execution.Status = "running"
	}
	now := common.TimeNowUTC()
	execution.CreatedAt = now
	if err := r.db.Create(execution).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建执行记录失败", err.Error())
	}
	return execution, nil
}

func (r *GormExecutionRepository) Update(execution *scheduler.TaskExecution) (*scheduler.TaskExecution, error) {
	if err := r.db.Save(execution).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新执行记录失败", err.Error())
	}
	return execution, nil
}

func (r *GormExecutionRepository) List(tenantID, taskID string, limit int) ([]scheduler.TaskExecution, error) {
	var executions []scheduler.TaskExecution
	err := r.db.Where("task_id = ? AND tenant_id = ?", taskID, tenantID).
		Order("created_at desc").Limit(limit).Find(&executions).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询任务执行记录失败", err.Error())
	}
	return executions, nil
}
