package repository

import (
	"gorm.io/gorm"
	"session187/internal/common"
	"session187/internal/scheduler"
	"session187/pkg/errors"
)

type TaskRepository interface {
	Create(task *scheduler.Task) (*scheduler.Task, error)
	Get(tenantID, taskID string) (*scheduler.Task, error)
	List(tenantID string) ([]scheduler.Task, error)
	Update(task *scheduler.Task) (*scheduler.Task, error)
	UpdateStatus(tenantID, taskID string, status scheduler.TaskStatus) error
	Delete(tenantID, taskID string) error
}

type GormTaskRepository struct {
	db *gorm.DB
}

func NewTaskRepository(db *gorm.DB) TaskRepository {
	return &GormTaskRepository{db: db}
}

func (r *GormTaskRepository) Create(task *scheduler.Task) (*scheduler.Task, error) {
	if task.ID == "" {
		task.ID = common.GenerateID("tsk")
	}
	if task.Status == "" {
		task.Status = scheduler.TaskStatusPending
	}
	if task.MaxRetries == 0 {
		task.MaxRetries = 3
	}
	if task.Timeout == 0 {
		task.Timeout = 3600
	}
	now := common.TimeNowUTC()
	task.CreatedAt = now
	task.UpdatedAt = now
	if err := r.db.Create(task).Error; err != nil {
		return nil, errors.NewWithDetail(500, "创建任务失败", err.Error())
	}
	return task, nil
}

func (r *GormTaskRepository) Get(tenantID, taskID string) (*scheduler.Task, error) {
	var task scheduler.Task
	err := r.db.Where("id = ? AND tenant_id = ?", taskID, tenantID).First(&task).Error
	if err != nil {
		if err == gorm.ErrRecordNotFound {
			return nil, errors.ErrNotFound
		}
		return nil, errors.NewWithDetail(500, "查询任务失败", err.Error())
	}
	return &task, nil
}

func (r *GormTaskRepository) List(tenantID string) ([]scheduler.Task, error) {
	var tasks []scheduler.Task
	err := r.db.Where("tenant_id = ?", tenantID).Order("created_at desc").Find(&tasks).Error
	if err != nil {
		return nil, errors.NewWithDetail(500, "查询任务列表失败", err.Error())
	}
	return tasks, nil
}

func (r *GormTaskRepository) Update(task *scheduler.Task) (*scheduler.Task, error) {
	task.UpdatedAt = common.TimeNowUTC()
	if err := r.db.Save(task).Error; err != nil {
		return nil, errors.NewWithDetail(500, "更新任务失败", err.Error())
	}
	return task, nil
}

func (r *GormTaskRepository) UpdateStatus(tenantID, taskID string, status scheduler.TaskStatus) error {
	return r.db.Model(&scheduler.Task{}).
		Where("id = ? AND tenant_id = ?", taskID, tenantID).
		Updates(map[string]interface{}{
			"status":     status,
			"updated_at": common.TimeNowUTC(),
		}).Error
}

func (r *GormTaskRepository) Delete(tenantID, taskID string) error {
	return r.db.Where("id = ? AND tenant_id = ?", taskID, tenantID).Delete(&scheduler.Task{}).Error
}
