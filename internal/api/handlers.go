package api

import (
	"context"
	"encoding/json"
	"net/http"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/distributed-task-scheduler/internal/auth"
	"github.com/distributed-task-scheduler/internal/models"
	"github.com/distributed-task-scheduler/internal/scheduler"
	"github.com/distributed-task-scheduler/internal/storage"
	"github.com/google/uuid"
)

type Handler struct {
	db        *storage.Database
	scheduler *scheduler.Scheduler
	auth      *auth.AuthManager
}

func NewHandler(db *storage.Database, scheduler *scheduler.Scheduler, authMgr *auth.AuthManager) *Handler {
	return &Handler{
		db:        db,
		scheduler: scheduler,
		auth:      authMgr,
	}
}

type CreateTaskRequest struct {
	Name            string          `json:"name"`
	Type            models.TaskType `json:"type"`
	Description     string          `json:"description"`
	CronExpression  string          `json:"cron_expression"`
	DelaySeconds    int             `json:"delay_seconds"`
	IntervalSeconds int             `json:"interval_seconds"`
	Payload         json.RawMessage `json:"payload"`
	CallbackURL     string          `json:"callback_url"`
	TimeoutSeconds  int             `json:"timeout_seconds"`
	MaxRetries      int             `json:"max_retries"`
	RetryBackoff    string          `json:"retry_backoff"`
	Dependencies    []string        `json:"dependencies"`
	DagID           string          `json:"dag_id"`
	Priority        int             `json:"priority"`
	Tags            []string        `json:"tags"`
}

func (h *Handler) CreateTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskCreate) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	var req CreateTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	task := &models.Task{
		ID:              uuid.New().String(),
		Namespace:       namespace,
		Name:            req.Name,
		Type:            req.Type,
		Description:     req.Description,
		CronExpression:  req.CronExpression,
		DelaySeconds:    req.DelaySeconds,
		IntervalSeconds: req.IntervalSeconds,
		Payload:         req.Payload,
		CallbackURL:     req.CallbackURL,
		TimeoutSeconds:  req.TimeoutSeconds,
		MaxRetries:      req.MaxRetries,
		RetryBackoff:    req.RetryBackoff,
		Dependencies:    req.Dependencies,
		DagID:           req.DagID,
		Priority:        req.Priority,
		Status:          models.TaskStatusActive,
		CreatedBy:       user.UserID,
		CreatedAt:       time.Now(),
		UpdatedAt:       time.Now(),
		Tags:            req.Tags,
	}

	if task.TimeoutSeconds == 0 {
		task.TimeoutSeconds = 300
	}
	if task.MaxRetries == 0 {
		task.MaxRetries = 3
	}
	if task.RetryBackoff == "" {
		task.RetryBackoff = "exponential"
	}

	query := `
		INSERT INTO tasks (id, namespace, name, type, description, cron_expression, 
			delay_seconds, interval_seconds, payload, callback_url, timeout_seconds,
			max_retries, retry_backoff, dependencies, dag_id, priority, status, 
			created_by, created_at, updated_at, tags)
		VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17, $18, $19, $20, $21)
	`

	_, err := h.db.Exec(query, task.ID, task.Namespace, task.Name, task.Type,
		task.Description, task.CronExpression, task.DelaySeconds, task.IntervalSeconds,
		task.Payload, task.CallbackURL, task.TimeoutSeconds, task.MaxRetries,
		task.RetryBackoff, task.Dependencies, task.DagID, task.Priority, task.Status,
		task.CreatedBy, task.CreatedAt, task.UpdatedAt, task.Tags)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, task)
}

func (h *Handler) GetTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskRead) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	var task models.Task
	err := h.db.Get(&task, "SELECT * FROM tasks WHERE namespace = $1 AND id = $2", namespace, taskID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
		return
	}

	c.JSON(http.StatusOK, task)
}

func (h *Handler) ListTasks(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskRead) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	var tasks []models.Task
	query := `SELECT * FROM tasks WHERE namespace = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3`
	err := h.db.Select(&tasks, query, namespace, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": tasks, "count": len(tasks)})
}

func (h *Handler) UpdateTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskUpdate) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	var updates map[string]interface{}
	if err := c.ShouldBindJSON(&updates); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	updates["updated_at"] = time.Now()

	query := "UPDATE tasks SET "
	args := []interface{}{}
	i := 1
	for k, v := range updates {
		if i > 1 {
			query += ", "
		}
		query += k + " = $" + strconv.Itoa(i)
		args = append(args, v)
		i++
	}
	query += " WHERE id = $" + strconv.Itoa(i) + " AND namespace = $" + strconv.Itoa(i+1)
	args = append(args, taskID, namespace)

	_, err := h.db.Exec(query, args...)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "updated"})
}

func (h *Handler) DeleteTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskDelete) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	_, err := h.db.Exec("DELETE FROM tasks WHERE id = $1 AND namespace = $2", taskID, namespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "deleted"})
}

func (h *Handler) TriggerTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskExecute) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	ctx := auth.ContextWithUser(context.Background(), user)
	execution, err := h.scheduler.TriggerTaskNow(taskID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"execution_id": execution.ID, "message": "triggered"})
}

func (h *Handler) PauseTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskUpdate) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	_, err := h.db.Exec("UPDATE tasks SET status = 'paused', updated_at = NOW() WHERE id = $1 AND namespace = $2", taskID, namespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "paused"})
}

func (h *Handler) ResumeTask(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermTaskUpdate) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	_, err := h.db.Exec("UPDATE tasks SET status = 'active', updated_at = NOW() WHERE id = $1 AND namespace = $2", taskID, namespace)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"message": "resumed"})
}

func (h *Handler) ListExecutions(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	taskID := c.Query("task_id")

	if !h.auth.CheckPermission(user, namespace, auth.PermExecutionRead) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	var executions []models.Execution
	var query string
	var err error

	if taskID != "" {
		query = `SELECT * FROM executions WHERE namespace = $1 AND task_id = $2 ORDER BY created_at DESC LIMIT $3 OFFSET $4`
		err = h.db.Select(&executions, query, namespace, taskID, limit, offset)
	} else {
		query = `SELECT * FROM executions WHERE namespace = $1 ORDER BY created_at DESC LIMIT $2 OFFSET $3`
		err = h.db.Select(&executions, query, namespace, limit, offset)
	}

	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": executions, "count": len(executions)})
}

func (h *Handler) GetExecution(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")
	executionID := c.Param("id")

	if !h.auth.CheckPermission(user, namespace, auth.PermExecutionRead) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	var execution models.Execution
	err := h.db.Get(&execution, "SELECT * FROM executions WHERE namespace = $1 AND id = $2", namespace, executionID)
	if err != nil {
		c.JSON(http.StatusNotFound, gin.H{"error": "execution not found"})
		return
	}

	c.JSON(http.StatusOK, execution)
}

func (h *Handler) ListNamespaces(c *gin.Context) {
	namespaces, err := h.auth.ListNamespaces()
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}
	c.JSON(http.StatusOK, namespaces)
}

func (h *Handler) CreateNamespace(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	if !h.auth.CheckPermission(user, "*", auth.PermNamespaceManage) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	var req struct {
		Name        string `json:"name"`
		Description string `json:"description"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
		return
	}

	ns, err := h.auth.CreateNamespace(req.Name, req.Description, user.UserID)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusCreated, ns)
}

func (h *Handler) GetAuditLogs(c *gin.Context) {
	user, _ := auth.UserFromContext(c.Request.Context())
	namespace := c.Param("namespace")

	if !h.auth.CheckPermission(user, namespace, auth.PermNamespaceManage) {
		c.JSON(http.StatusForbidden, gin.H{"error": "permission denied"})
		return
	}

	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))
	offset, _ := strconv.Atoi(c.DefaultQuery("offset", "0"))

	logs, total, err := h.auth.QueryAuditLogs(namespace, limit, offset)
	if err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
		return
	}

	c.JSON(http.StatusOK, gin.H{"data": logs, "total": total})
}
