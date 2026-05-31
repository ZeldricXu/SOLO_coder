package handler

import (
	"github.com/edgevision/edgevision/internal/service"
	"github.com/edgevision/edgevision/pkg/errors"
	"github.com/gin-gonic/gin"
)

type InferenceHandler struct {
	inferenceService *service.InferenceService
}

func NewInferenceHandler(inferenceService *service.InferenceService) *InferenceHandler {
	return &InferenceHandler{
		inferenceService: inferenceService,
	}
}

func (h *InferenceHandler) CreateModel(c *gin.Context) {
	var req service.CreateModelRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	model, err := h.inferenceService.CreateModel(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, model)
}

func (h *InferenceHandler) GetModel(c *gin.Context) {
	modelID := c.Param("id")
	if modelID == "" {
		Error(c, errors.BadRequest("Model ID is required"))
		return
	}

	model, err := h.inferenceService.GetModel(c.Request.Context(), modelID)
	if err != nil {
		Error(c, errors.NotFound("Model not found"))
		return
	}

	Success(c, model)
}

func (h *InferenceHandler) ListModels(c *gin.Context) {
	page, pageSize := GetPagination(c)

	models, total, err := h.inferenceService.ListModels(c.Request.Context(), page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, models, total, page, pageSize)
}

func (h *InferenceHandler) DeployModel(c *gin.Context) {
	var req service.DeployModelRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	deployment, err := h.inferenceService.DeployModel(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, deployment)
}

func (h *InferenceHandler) CreateInferenceTask(c *gin.Context) {
	var req service.CreateInferenceTaskRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	task, err := h.inferenceService.CreateInferenceTask(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, task)
}

func (h *InferenceHandler) GetTask(c *gin.Context) {
	taskID := c.Param("id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	task, err := h.inferenceService.GetTask(c.Request.Context(), taskID)
	if err != nil {
		Error(c, errors.NotFound("Task not found"))
		return
	}

	Success(c, task)
}

func (h *InferenceHandler) SubmitTaskResult(c *gin.Context) {
	taskID := c.Param("id")
	if taskID == "" {
		Error(c, errors.BadRequest("Task ID is required"))
		return
	}

	var req struct {
		Result  interface{} `json:"result"`
		Metrics interface{} `json:"metrics"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	task, err := h.inferenceService.SubmitTaskResult(c.Request.Context(), taskID, req.Result, req.Metrics)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, task)
}

type DeviceShadowHandler struct {
	shadowService *service.DeviceShadowService
}

func NewDeviceShadowHandler(shadowService *service.DeviceShadowService) *DeviceShadowHandler {
	return &DeviceShadowHandler{
		shadowService: shadowService,
	}
}

func (h *DeviceShadowHandler) GetShadow(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	shadow, err := h.shadowService.GetShadow(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, shadow)
}

func (h *DeviceShadowHandler) UpdateDesired(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	var state map[string]interface{}
	if err := c.ShouldBindJSON(&state); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	shadow, err := h.shadowService.UpdateDesired(c.Request.Context(), deviceID, state)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, shadow)
}

func (h *DeviceShadowHandler) ReportReported(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	var state map[string]interface{}
	if err := c.ShouldBindJSON(&state); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	shadow, err := h.shadowService.ReportReported(c.Request.Context(), deviceID, state)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, shadow)
}

func (h *DeviceShadowHandler) GetDelta(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	delta, err := h.shadowService.GetDelta(c.Request.Context(), deviceID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, gin.H{"delta": delta})
}

func (h *DeviceShadowHandler) GetHistory(c *gin.Context) {
	deviceID := c.Param("device_id")
	if deviceID == "" {
		Error(c, errors.BadRequest("Device ID is required"))
		return
	}

	page, pageSize := GetPagination(c)

	history, total, err := h.shadowService.GetHistory(c.Request.Context(), deviceID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, history, total, page, pageSize)
}

type ProtocolHandler struct {
	protocolService *service.ProtocolService
}

func NewProtocolHandler(protocolService *service.ProtocolService) *ProtocolHandler {
	return &ProtocolHandler{
		protocolService: protocolService,
	}
}

func (h *ProtocolHandler) RegisterDriver(c *gin.Context) {
	var req service.RegisterDriverRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	driver, err := h.protocolService.RegisterDriver(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, driver)
}

func (h *ProtocolHandler) ListDrivers(c *gin.Context) {
	page, pageSize := GetPagination(c)

	drivers, total, err := h.protocolService.ListDrivers(c.Request.Context(), page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, drivers, total, page, pageSize)
}

func (h *ProtocolHandler) CreateAdapter(c *gin.Context) {
	var req service.CreateAdapterRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	adapter, err := h.protocolService.CreateAdapter(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, adapter)
}

func (h *ProtocolHandler) StartAdapter(c *gin.Context) {
	adapterID := c.Param("id")
	if adapterID == "" {
		Error(c, errors.BadRequest("Adapter ID is required"))
		return
	}

	adapter, err := h.protocolService.StartAdapter(c.Request.Context(), adapterID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, adapter)
}

func (h *ProtocolHandler) StopAdapter(c *gin.Context) {
	adapterID := c.Param("id")
	if adapterID == "" {
		Error(c, errors.BadRequest("Adapter ID is required"))
		return
	}

	adapter, err := h.protocolService.StopAdapter(c.Request.Context(), adapterID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, adapter)
}

func (h *ProtocolHandler) ListAdapters(c *gin.Context) {
	deviceID := c.Query("device_id")
	page, pageSize := GetPagination(c)

	adapters, total, err := h.protocolService.ListAdapters(c.Request.Context(), deviceID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, adapters, total, page, pageSize)
}

func (h *ProtocolHandler) GetRecords(c *gin.Context) {
	adapterID := c.Query("adapter_id")
	page, pageSize := GetPagination(c)

	records, total, err := h.protocolService.GetRecords(c.Request.Context(), adapterID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, records, total, page, pageSize)
}

type RuleEngineHandler struct {
	ruleService *service.RuleEngineService
}

func NewRuleEngineHandler(ruleService *service.RuleEngineService) *RuleEngineHandler {
	return &RuleEngineHandler{
		ruleService: ruleService,
	}
}

func (h *RuleEngineHandler) CreateRule(c *gin.Context) {
	var req service.CreateRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	rule, err := h.ruleService.CreateRule(c.Request.Context(), &req)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Created(c, rule)
}

func (h *RuleEngineHandler) GetRule(c *gin.Context) {
	ruleID := c.Param("id")
	if ruleID == "" {
		Error(c, errors.BadRequest("Rule ID is required"))
		return
	}

	rule, err := h.ruleService.GetRule(c.Request.Context(), ruleID)
	if err != nil {
		Error(c, errors.NotFound("Rule not found"))
		return
	}

	Success(c, rule)
}

func (h *RuleEngineHandler) ListRules(c *gin.Context) {
	enabled := c.Query("enabled")
	page, pageSize := GetPagination(c)

	rules, total, err := h.ruleService.ListRules(c.Request.Context(), enabled, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, rules, total, page, pageSize)
}

func (h *RuleEngineHandler) EnableRule(c *gin.Context) {
	ruleID := c.Param("id")
	if ruleID == "" {
		Error(c, errors.BadRequest("Rule ID is required"))
		return
	}

	rule, err := h.ruleService.EnableRule(c.Request.Context(), ruleID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, rule)
}

func (h *RuleEngineHandler) DisableRule(c *gin.Context) {
	ruleID := c.Param("id")
	if ruleID == "" {
		Error(c, errors.BadRequest("Rule ID is required"))
		return
	}

	rule, err := h.ruleService.DisableRule(c.Request.Context(), ruleID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, rule)
}

func (h *RuleEngineHandler) TriggerRule(c *gin.Context) {
	ruleID := c.Param("id")
	if ruleID == "" {
		Error(c, errors.BadRequest("Rule ID is required"))
		return
	}

	var contextData map[string]interface{}
	if err := c.ShouldBindJSON(&contextData); err != nil {
		Error(c, errors.ValidationError("Invalid request body", err.Error()))
		return
	}

	execution, err := h.ruleService.TriggerRule(c.Request.Context(), ruleID, contextData)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, execution)
}

func (h *RuleEngineHandler) GetExecutions(c *gin.Context) {
	ruleID := c.Query("rule_id")
	page, pageSize := GetPagination(c)

	executions, total, err := h.ruleService.GetExecutions(c.Request.Context(), ruleID, page, pageSize)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	SuccessPaged(c, executions, total, page, pageSize)
}

func (h *RuleEngineHandler) GetActionExecutions(c *gin.Context) {
	executionID := c.Param("execution_id")
	if executionID == "" {
		Error(c, errors.BadRequest("Execution ID is required"))
		return
	}

	executions, err := h.ruleService.GetActionExecutions(c.Request.Context(), executionID)
	if err != nil {
		Error(c, errors.InternalError(err.Error()))
		return
	}

	Success(c, executions)
}
