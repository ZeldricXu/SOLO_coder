package scaffold

import (
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"time"
)

type TemplateRepository interface {
	Create(template *Template) error
	Update(id string, updates map[string]interface{}) error
	Delete(id string) error
	GetByID(id string) (*Template, error)
	List(page, pageSize int, keyword, language string) ([]Template, int64, error)
	GetByName(name string) (*Template, error)
}

type ProjectRepository interface {
	Create(project *Project) error
	Update(id string, updates map[string]interface{}) error
	Delete(id string) error
	GetByID(id string) (*Project, error)
	List(page, pageSize int, ownerID, namespace string) ([]Project, int64, error)
}

type TaskRepository interface {
	Create(task *GenerationTask) error
	Update(id string, updates map[string]interface{}) error
	GetByID(id string) (*GenerationTask, error)
	GetByTaskID(taskID string) (*GenerationTask, error)
	ListByProjectID(projectID string) ([]GenerationTask, error)
}

type SessionRepository interface {
	Create(session *InteractiveSession) error
	Update(id string, updates map[string]interface{}) error
	GetBySessionID(sessionID string) (*InteractiveSession, error)
	DeleteExpired() error
}

type templateRepo struct{}

func NewTemplateRepository() TemplateRepository {
	return &templateRepo{}
}

func (r *templateRepo) Create(template *Template) error {
	template.ID = utils.GenerateID("tpl")
	return database.DB.Create(template).Error
}

func (r *templateRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&Template{}).Where("id = ?", id).Updates(updates).Error
}

func (r *templateRepo) Delete(id string) error {
	return database.DB.Delete(&Template{}, "id = ?", id).Error
}

func (r *templateRepo) GetByID(id string) (*Template, error) {
	var template Template
	err := database.DB.Where("id = ?", id).First(&template).Error
	if err != nil {
		return nil, err
	}
	return &template, nil
}

func (r *templateRepo) List(page, pageSize int, keyword, language string) ([]Template, int64, error) {
	var templates []Template
	var total int64
	query := database.DB.Model(&Template{})

	if keyword != "" {
		query = query.Where("name LIKE ? OR description LIKE ?", "%"+keyword+"%", "%"+keyword+"%")
	}
	if language != "" {
		query = query.Where("language = ?", language)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err = query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&templates).Error
	return templates, total, err
}

func (r *templateRepo) GetByName(name string) (*Template, error) {
	var template Template
	err := database.DB.Where("name = ?", name).First(&template).Error
	if err != nil {
		return nil, err
	}
	return &template, nil
}

type projectRepo struct{}

func NewProjectRepository() ProjectRepository {
	return &projectRepo{}
}

func (r *projectRepo) Create(project *Project) error {
	project.ID = utils.GenerateID("prj")
	return database.DB.Create(project).Error
}

func (r *projectRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&Project{}).Where("id = ?", id).Updates(updates).Error
}

func (r *projectRepo) Delete(id string) error {
	return database.DB.Delete(&Project{}, "id = ?", id).Error
}

func (r *projectRepo) GetByID(id string) (*Project, error) {
	var project Project
	err := database.DB.Where("id = ?", id).First(&project).Error
	if err != nil {
		return nil, err
	}
	return &project, nil
}

func (r *projectRepo) List(page, pageSize int, ownerID, namespace string) ([]Project, int64, error) {
	var projects []Project
	var total int64
	query := database.DB.Model(&Project{})

	if ownerID != "" {
		query = query.Where("owner_id = ?", ownerID)
	}
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	err := query.Count(&total).Error
	if err != nil {
		return nil, 0, err
	}

	offset := (page - 1) * pageSize
	err = query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&projects).Error
	return projects, total, err
}

type taskRepo struct{}

func NewTaskRepository() TaskRepository {
	return &taskRepo{}
}

func (r *taskRepo) Create(task *GenerationTask) error {
	task.ID = utils.GenerateID("tsk")
	return database.DB.Create(task).Error
}

func (r *taskRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&GenerationTask{}).Where("id = ?", id).Updates(updates).Error
}

func (r *taskRepo) GetByID(id string) (*GenerationTask, error) {
	var task GenerationTask
	err := database.DB.Where("id = ?", id).First(&task).Error
	if err != nil {
		return nil, err
	}
	return &task, nil
}

func (r *taskRepo) GetByTaskID(taskID string) (*GenerationTask, error) {
	var task GenerationTask
	err := database.DB.Where("task_id = ?", taskID).First(&task).Error
	if err != nil {
		return nil, err
	}
	return &task, nil
}

func (r *taskRepo) ListByProjectID(projectID string) ([]GenerationTask, error) {
	var tasks []GenerationTask
	err := database.DB.Where("project_id = ?", projectID).Order("created_at DESC").Find(&tasks).Error
	return tasks, err
}

type sessionRepo struct{}

func NewSessionRepository() SessionRepository {
	return &sessionRepo{}
}

func (r *sessionRepo) Create(session *InteractiveSession) error {
	session.ID = utils.GenerateID("sess")
	return database.DB.Create(session).Error
}

func (r *sessionRepo) Update(id string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()
	return database.DB.Model(&InteractiveSession{}).Where("id = ?", id).Updates(updates).Error
}

func (r *sessionRepo) GetBySessionID(sessionID string) (*InteractiveSession, error) {
	var session InteractiveSession
	err := database.DB.Where("session_id = ?", sessionID).First(&session).Error
	if err != nil {
		return nil, err
	}
	return &session, nil
}

func (r *sessionRepo) DeleteExpired() error {
	return database.DB.Where("expires_at < ?", time.Now()).Delete(&InteractiveSession{}).Error
}
