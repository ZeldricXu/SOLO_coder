package api

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"

	"llmgateway/internal/domain/entity"
	"llmgateway/internal/service/adversarial"
	"llmgateway/internal/service/document"
	"llmgateway/internal/service/evaluation"
	"llmgateway/internal/service/feature_store"
	"llmgateway/internal/service/gateway"
	"llmgateway/internal/service/model_registry"
	"llmgateway/internal/service/prompt_eval"
	"llmgateway/internal/service/scheduler"
	"llmgateway/pkg/utils"
)

type Handler struct {
	modelRegistry *model_registry.Service
	gateway       *gateway.Service
	scheduler     *scheduler.Service
	promptEval    *prompt_eval.Service
	evaluation    *evaluation.Service
	adversarial   *adversarial.Service
	document      *document.Service
	featureStore  *feature_store.Service
}

func NewHandler(
	modelRegistry *model_registry.Service,
	gateway *gateway.Service,
	scheduler *scheduler.Service,
	promptEval *prompt_eval.Service,
	evaluation *evaluation.Service,
	adversarial *adversarial.Service,
	document *document.Service,
	featureStore *feature_store.Service,
) *Handler {
	return &Handler{
		modelRegistry: modelRegistry,
		gateway:       gateway,
		scheduler:     scheduler,
		promptEval:    promptEval,
		evaluation:    evaluation,
		adversarial:   adversarial,
		document:      document,
		featureStore:  featureStore,
	}
}

func getPageParams(c *gin.Context) (int, int) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	if page < 1 {
		page = 1
	}
	if pageSize < 1 || pageSize > 100 {
		pageSize = 20
	}
	return page, pageSize
}

func (h *Handler) CreateModel(c *gin.Context) {
	var req model_registry.CreateModelRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	model, err := h.modelRegistry.CreateModel(&req, "user")
	if err != nil {
		if strings.Contains(err.Error(), "already exists") {
			Conflict(c, err.Error(), req.Name)
			return
		}
		InternalError(c, err.Error())
		return
	}

	Created(c, model)
}

func (h *Handler) GetModel(c *gin.Context) {
	id := c.Param("id")
	model, err := h.modelRegistry.GetModel(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, model)
}

func (h *Handler) ListModels(c *gin.Context) {
	page, pageSize := getPageParams(c)
	provider := c.Query("provider")
	modelType := c.Query("model_type")

	models, total, err := h.modelRegistry.ListModels(page, pageSize, provider, modelType)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(models, total, page, pageSize))
}

func (h *Handler) UpdateModel(c *gin.Context) {
	id := c.Param("id")
	var req model_registry.UpdateModelRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	model, err := h.modelRegistry.UpdateModel(id, &req)
	if err != nil {
		NotFound(c, err.Error())
		return
	}

	Success(c, model)
}

func (h *Handler) DeleteModel(c *gin.Context) {
	id := c.Param("id")
	if err := h.modelRegistry.DeleteModel(id); err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, gin.H{"message": "model deleted"})
}

func (h *Handler) CreateModelVersion(c *gin.Context) {
	var req model_registry.CreateVersionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	version, err := h.modelRegistry.CreateModelVersion(&req)
	if err != nil {
		if strings.Contains(err.Error(), "already exists") {
			Conflict(c, err.Error(), req.Version)
			return
		}
		InternalError(c, err.Error())
		return
	}

	Created(c, version)
}

func (h *Handler) GetModelVersion(c *gin.Context) {
	id := c.Param("id")
	version, err := h.modelRegistry.GetModelVersion(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, version)
}

func (h *Handler) ListModelVersions(c *gin.Context) {
	modelID := c.Query("model_id")
	page, pageSize := getPageParams(c)
	stage := c.Query("stage")

	versions, total, err := h.modelRegistry.ListModelVersions(modelID, page, pageSize, stage)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(versions, total, page, pageSize))
}

func (h *Handler) PromoteVersion(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		TargetStage string `json:"target_stage" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	version, err := h.modelRegistry.PromoteVersion(id, entity.ModelStage(req.TargetStage))
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, version)
}

func (h *Handler) Infer(c *gin.Context) {
	var req entity.InferenceRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	if req.RequestID == "" {
		req.RequestID = utils.GenerateID("req")
	}

	resp, err := h.gateway.Infer(context.Background(), &req)
	if err != nil {
		GatewayTimeout(c, err.Error())
		return
	}

	Success(c, resp)
}

func (h *Handler) ListProviders(c *gin.Context) {
	providers, err := h.gateway.ListProviders()
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, providers)
}

func (h *Handler) SubmitTask(c *gin.Context) {
	var req scheduler.SubmitTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	task, err := h.scheduler.SubmitTask(context.Background(), &req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, task)
}

func (h *Handler) GetTask(c *gin.Context) {
	id := c.Param("id")
	task, err := h.scheduler.GetTask(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, task)
}

func (h *Handler) ListTasks(c *gin.Context) {
	page, pageSize := getPageParams(c)
	status := c.Query("status")
	taskType := c.Query("type")

	tasks, total, err := h.scheduler.ListTasks(page, pageSize, status, taskType)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(tasks, total, page, pageSize))
}

func (h *Handler) CancelTask(c *gin.Context) {
	id := c.Param("id")
	if err := h.scheduler.CancelTask(id); err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, gin.H{"message": "task canceled"})
}

func (h *Handler) ListGPUs(c *gin.Context) {
	gpus, err := h.scheduler.ListGPUs()
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, gpus)
}

func (h *Handler) GetQueueStats(c *gin.Context) {
	stats := h.scheduler.GetQueueStats()
	Success(c, stats)
}

func (h *Handler) GetSchedulerMetrics(c *gin.Context) {
	metrics := h.scheduler.GetMetrics()
	Success(c, metrics)
}

func (h *Handler) GetPrometheusMetrics(c *gin.Context) {
	metrics := h.scheduler.GetPrometheusMetrics()
	c.String(200, metrics)
}

func (h *Handler) GetTaskEvents(c *gin.Context) {
	taskID := c.Query("task_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "50"))
	if limit <= 0 || limit > 500 {
		limit = 50
	}

	events := h.scheduler.GetTaskEvents(taskID, limit)
	Success(c, events)
}

func (h *Handler) GetGPUHistory(c *gin.Context) {
	gpuID := c.Query("gpu_id")
	minutes, _ := strconv.Atoi(c.DefaultQuery("minutes", "60"))
	if minutes <= 0 || minutes > 1440 {
		minutes = 60
	}

	if gpuID != "" {
		history := h.scheduler.GetGPUHistory(gpuID, minutes)
		Success(c, history)
	} else {
		history := h.scheduler.GetAllGPUHistory(minutes)
		Success(c, history)
	}
}

func (h *Handler) StartStreamEvaluation(c *gin.Context) {
	var req evaluation.StartStreamEvaluationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	handler := func(batchIndex int, metrics map[string]float64, progress float64) error {
		return nil
	}

	run, err := h.evaluation.StartStreamEvaluation(&req, handler)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, run)
}

func (h *Handler) GetStreamingStatus(c *gin.Context) {
	runID := c.Param("id")
	status, err := h.evaluation.GetStreamingStatus(runID)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, status)
}

func (h *Handler) ListStreamingEvaluations(c *gin.Context) {
	evaluations := h.evaluation.ListStreamingEvaluations()
	Success(c, evaluations)
}

func (h *Handler) CancelStreamingEvaluation(c *gin.Context) {
	runID := c.Param("id")
	if err := h.evaluation.CancelStreamingEvaluation(runID); err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, gin.H{"message": "streaming evaluation canceled"})
}

func (h *Handler) GetBatchMetrics(c *gin.Context) {
	runID := c.Query("run_id")
	batchIndex, _ := strconv.Atoi(c.Query("batch_index"))

	metrics, err := h.evaluation.GetBatchMetrics(runID, batchIndex)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, metrics)
}

func (h *Handler) GetAdversarialCacheStats(c *gin.Context) {
	stats := h.adversarial.GetCacheStats()
	Success(c, stats)
}

func (h *Handler) ClearAdversarialCache(c *gin.Context) {
	h.adversarial.ClearCache()
	Success(c, gin.H{"message": "cache cleared"})
}

func (h *Handler) SetAdversarialCacheConfig(c *gin.Context) {
	var req struct {
		Enabled  bool `json:"enabled"`
		MaxSize  int  `json:"max_size"`
		TTLSec   int  `json:"ttl_seconds"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	h.adversarial.SetCacheConfig(req.Enabled, req.MaxSize, time.Duration(req.TTLSec)*time.Second)
	Success(c, gin.H{"message": "cache config updated"})
}

func (h *Handler) CreatePrompt(c *gin.Context) {
	var req prompt_eval.CreatePromptRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	prompt, err := h.promptEval.CreatePrompt(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, prompt)
}

func (h *Handler) GetPrompt(c *gin.Context) {
	id := c.Param("id")
	prompt, err := h.promptEval.GetPrompt(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, prompt)
}

func (h *Handler) ListPrompts(c *gin.Context) {
	page, pageSize := getPageParams(c)
	createdBy := c.Query("created_by")
	status := c.Query("status")

	prompts, total, err := h.promptEval.ListPrompts(page, pageSize, createdBy, status)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(prompts, total, page, pageSize))
}

func (h *Handler) CreatePromptVersion(c *gin.Context) {
	var req prompt_eval.CreateVersionRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	version, err := h.promptEval.CreateVersion(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, version)
}

func (h *Handler) ListPromptVersions(c *gin.Context) {
	promptID := c.Query("prompt_id")
	page, pageSize := getPageParams(c)

	versions, total, err := h.promptEval.ListVersions(promptID, page, pageSize)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(versions, total, page, pageSize))
}

func (h *Handler) CreateExperiment(c *gin.Context) {
	var req prompt_eval.CreateExperimentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	exp, err := h.promptEval.CreateExperiment(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, exp)
}

func (h *Handler) GetExperiment(c *gin.Context) {
	id := c.Param("id")
	exp, err := h.promptEval.GetExperiment(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, exp)
}

func (h *Handler) StartExperiment(c *gin.Context) {
	id := c.Param("id")
	exp, err := h.promptEval.StartExperiment(id)
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, exp)
}

func (h *Handler) GetExperimentResults(c *gin.Context) {
	id := c.Param("id")
	results, err := h.promptEval.GetExperimentResults(id)
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, results)
}

func (h *Handler) CreateDataset(c *gin.Context) {
	var req evaluation.CreateDatasetRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	dataset, err := h.evaluation.CreateDataset(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, dataset)
}

func (h *Handler) GetDataset(c *gin.Context) {
	id := c.Param("id")
	dataset, err := h.evaluation.GetDataset(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, dataset)
}

func (h *Handler) ListDatasets(c *gin.Context) {
	page, pageSize := getPageParams(c)
	datasetType := c.Query("type")

	datasets, total, err := h.evaluation.ListDatasets(page, pageSize, datasetType)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(datasets, total, page, pageSize))
}

func (h *Handler) StartEvaluation(c *gin.Context) {
	var req evaluation.StartEvaluationRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	run, err := h.evaluation.StartEvaluation(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, run)
}

func (h *Handler) GetEvaluationMetrics(c *gin.Context) {
	modelVersionID := c.Query("model_version_id")
	hours, _ := strconv.Atoi(c.DefaultQuery("hours", "24"))

	metrics, err := h.evaluation.GetEvaluationMetrics(modelVersionID, hours)
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, metrics)
}

func (h *Handler) CompareModels(c *gin.Context) {
	var req struct {
		ModelVersionIDs []string `json:"model_version_ids" binding:"required"`
		MetricNames     []string `json:"metric_names"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	result, err := h.evaluation.CompareModels(req.ModelVersionIDs, req.MetricNames)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, result)
}

func (h *Handler) DetectDrift(c *gin.Context) {
	var req evaluation.DriftDetectionConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	detection, err := h.evaluation.DetectDrift(req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, detection)
}

func (h *Handler) GetDriftHistory(c *gin.Context) {
	modelVersionID := c.Query("model_version_id")
	hours, _ := strconv.Atoi(c.DefaultQuery("hours", "24"))

	history, err := h.evaluation.GetDriftHistory(modelVersionID, hours)
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, history)
}

func (h *Handler) RegisterAttackStrategy(c *gin.Context) {
	var req adversarial.AttackStrategyConfig
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	strategy, err := h.adversarial.RegisterStrategy(req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, strategy)
}

func (h *Handler) ListAttackStrategies(c *gin.Context) {
	strategies, err := h.adversarial.ListStrategies()
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, strategies)
}

func (h *Handler) GenerateAdversarialPrompt(c *gin.Context) {
	var req adversarial.GenerateAdversarialRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.GeneratedBy = "user"

	prompt, err := h.adversarial.GenerateAdversarialPrompt(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, prompt)
}

func (h *Handler) StartSecurityAssessment(c *gin.Context) {
	var req adversarial.StartAssessmentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	assessment, err := h.adversarial.StartSecurityAssessment(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, assessment)
}

func (h *Handler) GetAssessment(c *gin.Context) {
	id := c.Param("id")
	assessment, err := h.adversarial.GetAssessment(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, assessment)
}

func (h *Handler) ListAssessments(c *gin.Context) {
	modelID := c.Query("model_id")
	page, pageSize := getPageParams(c)

	assessments, total, err := h.adversarial.ListAssessments(modelID, page, pageSize)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(assessments, total, page, pageSize))
}

func (h *Handler) GetVulnerabilities(c *gin.Context) {
	assessmentID := c.Query("assessment_id")
	vulns, err := h.adversarial.GetVulnerabilities(assessmentID)
	if err != nil {
		InternalError(c, err.Error())
		return
	}
	Success(c, vulns)
}

func (h *Handler) UploadDocument(c *gin.Context) {
	var req document.UploadDocumentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	doc, err := h.document.UploadDocument(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, doc)
}

func (h *Handler) GetDocument(c *gin.Context) {
	id := c.Param("id")
	doc, err := h.document.GetDocument(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, doc)
}

func (h *Handler) ListDocuments(c *gin.Context) {
	page, pageSize := getPageParams(c)
	docType := c.Query("type")
	status := c.Query("status")

	docs, total, err := h.document.ListDocuments(page, pageSize, docType, status)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(docs, total, page, pageSize))
}

func (h *Handler) CreatePipeline(c *gin.Context) {
	var req document.CreatePipelineRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	pipeline, err := h.document.CreatePipeline(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, pipeline)
}

func (h *Handler) GetPipeline(c *gin.Context) {
	id := c.Param("id")
	pipeline, err := h.document.GetPipeline(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, pipeline)
}

func (h *Handler) ListPipelines(c *gin.Context) {
	page, pageSize := getPageParams(c)

	pipelines, total, err := h.document.ListPipelines(page, pageSize)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(pipelines, total, page, pageSize))
}

func (h *Handler) ExecutePipeline(c *gin.Context) {
	var req document.ExecutePipelineRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	execution, err := h.document.ExecutePipeline(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, execution)
}

func (h *Handler) GetExecution(c *gin.Context) {
	id := c.Param("id")
	execution, err := h.document.GetExecution(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, execution)
}

func (h *Handler) GetDocumentChunks(c *gin.Context) {
	docID := c.Query("document_id")
	page, pageSize := getPageParams(c)

	chunks, total, err := h.document.GetDocumentChunks(docID, page, pageSize)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(chunks, total, page, pageSize))
}

func (h *Handler) RegisterFeature(c *gin.Context) {
	var req feature_store.RegisterFeatureRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	feature, err := h.featureStore.RegisterFeature(&req)
	if err != nil {
		if strings.Contains(err.Error(), "already exists") {
			Conflict(c, err.Error(), req.Name)
			return
		}
		InternalError(c, err.Error())
		return
	}

	Created(c, feature)
}

func (h *Handler) GetFeature(c *gin.Context) {
	id := c.Param("id")
	feature, err := h.featureStore.GetFeature(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, feature)
}

func (h *Handler) ListFeatures(c *gin.Context) {
	page, pageSize := getPageParams(c)
	entityType := c.Query("entity")
	status := c.Query("status")

	features, total, err := h.featureStore.ListFeatures(page, pageSize, entityType, status)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, PageResult(features, total, page, pageSize))
}

func (h *Handler) UpdateFeature(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		Description *string                `json:"description"`
		Tags        []string               `json:"tags"`
		Config      map[string]interface{} `json:"config"`
		Status      *string                `json:"status"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	feature, err := h.featureStore.UpdateFeature(id, req.Description, req.Tags, req.Config, req.Status)
	if err != nil {
		NotFound(c, err.Error())
		return
	}

	Success(c, feature)
}

func (h *Handler) InsertFeatureValue(c *gin.Context) {
	var req feature_store.InsertFeatureValueRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	value, err := h.featureStore.InsertFeatureValue(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, value)
}

func (h *Handler) BatchInsertFeatureValues(c *gin.Context) {
	var req []feature_store.InsertFeatureValueRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	values, err := h.featureStore.BatchInsertFeatureValues(req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, values)
}

func (h *Handler) GetOnlineFeatureValue(c *gin.Context) {
	featureName := c.Query("feature_name")
	entityKey := c.Query("entity_key")

	value, err := h.featureStore.GetOnlineFeatureValue(featureName, entityKey)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, gin.H{"value": value})
}

func (h *Handler) CreateFeatureSet(c *gin.Context) {
	var req feature_store.CreateFeatureSetRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	featureSet, err := h.featureStore.CreateFeatureSet(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, featureSet)
}

func (h *Handler) GetFeatureSet(c *gin.Context) {
	id := c.Param("id")
	featureSet, err := h.featureStore.GetFeatureSet(id)
	if err != nil {
		NotFound(c, err.Error())
		return
	}
	Success(c, featureSet)
}

func (h *Handler) GetFeatureSetValues(c *gin.Context) {
	featureSetID := c.Query("feature_set_id")
	entityKey := c.Query("entity_key")

	values, err := h.featureStore.GetFeatureSetValues(featureSetID, entityKey)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, values)
}

func (h *Handler) CreateFeatureView(c *gin.Context) {
	var req feature_store.CreateFeatureViewRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}
	req.CreatedBy = "user"

	view, err := h.featureStore.CreateFeatureView(&req)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Created(c, view)
}

func (h *Handler) CompareOnlineOffline(c *gin.Context) {
	featureID := c.Query("feature_id")
	entityKey := c.Query("entity_key")

	result, err := h.featureStore.CompareOnlineOffline(featureID, entityKey)
	if err != nil {
		InternalError(c, err.Error())
		return
	}

	Success(c, result)
}

func (h *Handler) HealthCheck(c *gin.Context) {
	Success(c, gin.H{
		"status": "healthy",
		"time":   utils.Now(),
	})
}

func (h *Handler) CreateResource(c *gin.Context) {
	var req struct {
		Type   string                 `json:"type" binding:"required"`
		Config map[string]interface{} `json:"config"`
		Labels map[string]string      `json:"labels"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	Created(c, gin.H{
		"id":     utils.GenerateID("rsc"),
		"status": "provisioning",
		"type":   req.Type,
	})
}

func (h *Handler) GetResourceStatus(c *gin.Context) {
	id := c.Param("id")
	Success(c, gin.H{
		"id":       id,
		"status":   "running",
		"progress": 0.8,
	})
}

func (h *Handler) BatchOperation(c *gin.Context) {
	var req struct {
		Operations []struct {
			Action string `json:"action" binding:"required"`
			ID     string `json:"id" binding:"required"`
		} `json:"operations" binding:"required"`
	}
	if err := c.ShouldBindJSON(&req); err != nil {
		BadRequest(c, "invalid request body")
		return
	}

	results := make([]map[string]interface{}, len(req.Operations))
	for i, op := range req.Operations {
		results[i] = map[string]interface{}{
			"id":     op.ID,
			"action": op.Action,
			"status": "success",
		}
	}

	Success(c, gin.H{
		"batch_id": utils.GenerateID("batch"),
		"results":  results,
	})
}
