package prompt

import (
	"time"

	"github.com/gin-gonic/gin"
	"session133/pkg/utils"
)

type AsyncHandler struct {
	asyncService *AsyncPromptService
}

func NewAsyncHandler(asyncService *AsyncPromptService) *AsyncHandler {
	return &AsyncHandler{
		asyncService: asyncService,
	}
}

func (h *AsyncHandler) RegisterRoutes(r *gin.RouterGroup) {
	async := r.Group("/async")
	{
		async.POST("/abtest/analysis", h.SubmitABTestAnalysis)
		async.POST("/prompt/evaluation", h.SubmitPromptEvaluation)
		async.POST("/version/cleanup", h.SubmitVersionCleanup)
		async.POST("/metric/aggregation", h.SubmitMetricAggregation)
		async.POST("/report/generation", h.SubmitReportGeneration)

		async.GET("/tasks", h.ListTasks)
		async.GET("/tasks/:task_id", h.GetTask)
		async.DELETE("/tasks/:task_id", h.CancelTask)
	}
}

func (h *AsyncHandler) SubmitABTestAnalysis(c *gin.Context) {
	var req struct {
		ABTestID    string `json:"ab_test_id" binding:"required"`
		CallbackURL string `json:"callback_url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	task, err := h.asyncService.SubmitABTestAnalysis(req.ABTestID, req.CallbackURL)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message":   "AB测试分析任务已提交",
		"task_id":   task.ID,
		"status":    task.Status,
		"task_type": task.Type,
	})
}

func (h *AsyncHandler) SubmitPromptEvaluation(c *gin.Context) {
	var req struct {
		PromptID    string                   `json:"prompt_id" binding:"required"`
		EvalCases   []map[string]interface{} `json:"eval_cases" binding:"required"`
		CallbackURL string                   `json:"callback_url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	task, err := h.asyncService.SubmitPromptEvaluation(req.PromptID, req.EvalCases, req.CallbackURL)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message":   "Prompt评估任务已提交",
		"task_id":   task.ID,
		"status":    task.Status,
		"task_type": task.Type,
	})
}

func (h *AsyncHandler) SubmitVersionCleanup(c *gin.Context) {
	var req struct {
		PromptID     string `json:"prompt_id" binding:"required"`
		MaxVersions  int    `json:"max_versions" binding:"min=1"`
		CallbackURL  string `json:"callback_url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	if req.MaxVersions == 0 {
		req.MaxVersions = 10
	}

	task, err := h.asyncService.SubmitVersionCleanup(req.PromptID, req.MaxVersions, req.CallbackURL)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message":   "版本清理任务已提交",
		"task_id":   task.ID,
		"status":    task.Status,
		"task_type": task.Type,
	})
}

func (h *AsyncHandler) SubmitMetricAggregation(c *gin.Context) {
	var req struct {
		StartDate   string `json:"start_date" binding:"required"`
		EndDate     string `json:"end_date" binding:"required"`
		CallbackURL string `json:"callback_url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	startDate, err := time.Parse(time.RFC3339, req.StartDate)
	if err != nil {
		utils.Error(c, err)
		return
	}

	endDate, err := time.Parse(time.RFC3339, req.EndDate)
	if err != nil {
		utils.Error(c, err)
		return
	}

	task, err := h.asyncService.SubmitMetricAggregation(startDate, endDate, req.CallbackURL)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message":   "指标聚合任务已提交",
		"task_id":   task.ID,
		"status":    task.Status,
		"task_type": task.Type,
	})
}

func (h *AsyncHandler) SubmitReportGeneration(c *gin.Context) {
	var req struct {
		ReportType  string                 `json:"report_type" binding:"required"`
		Params      map[string]interface{} `json:"params"`
		CallbackURL string                 `json:"callback_url"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		utils.Error(c, err)
		return
	}

	task, err := h.asyncService.SubmitReportGeneration(req.ReportType, req.Params, req.CallbackURL)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message":   "报告生成任务已提交",
		"task_id":   task.ID,
		"status":    task.Status,
		"task_type": task.Type,
	})
}

func (h *AsyncHandler) GetTask(c *gin.Context) {
	taskID := c.Param("task_id")

	task, err := h.asyncService.GetTaskManager().GetTask(taskID)
	if err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, task)
}

func (h *AsyncHandler) ListTasks(c *gin.Context) {
	status := c.Query("status")
	taskType := c.Query("type")

	limit := 50
	offset := 0

	tasks := h.asyncService.GetTaskManager().ListTasks(
		TaskStatus(status),
		TaskType(taskType),
		limit,
		offset,
	)

	utils.Success(c, gin.H{
		"total": len(tasks),
		"tasks": tasks,
	})
}

func (h *AsyncHandler) CancelTask(c *gin.Context) {
	taskID := c.Param("task_id")

	if err := h.asyncService.GetTaskManager().CancelTask(taskID); err != nil {
		utils.Error(c, err)
		return
	}

	utils.Success(c, gin.H{
		"message": "任务已取消",
		"task_id": taskID,
	})
}
