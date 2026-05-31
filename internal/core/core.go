package core

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/internal/logger"
	"github.com/solocoder/session138/pkg/database"
	"github.com/solocoder/session138/pkg/models"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"
)

type ProcessRequest struct {
	TraceID   string                 `json:"trace_id"`
	Namespace string                 `json:"namespace"`
	Payload   map[string]interface{} `json:"payload"`
	Params    map[string]interface{} `json:"params"`
}

type ProcessResponse struct {
	Code    int         `json:"code"`
	Message string      `json:"message"`
	Data    interface{} `json:"data,omitempty"`
}

type TransformRule struct {
	Source      string `json:"source"`
	Target      string `json:"target"`
	Transform   string `json:"transform"`
	Default     string `json:"default,omitempty"`
}

type StandardizationConfig struct {
	Rules     []TransformRule `json:"rules"`
	Namespace string          `json:"namespace"`
	Version   int             `json:"version"`
}

type ProcessingContext struct {
	TraceID    string
	StartTime  time.Time
	Config     *StandardizationConfig
	Attributes map[string]interface{}
	CancelFunc context.CancelFunc
}

func NewProcessingContext(traceID string) *ProcessingContext {
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	_ = ctx
	return &ProcessingContext{
		TraceID:    traceID,
		StartTime:  utils.Now(),
		Attributes: make(map[string]interface{}),
		CancelFunc: cancel,
	}
}

func (pc *ProcessingContext) Cleanup() {
	if pc.CancelFunc != nil {
		pc.CancelFunc()
	}
}

func validateParams(params map[string]interface{}) error {
	if params == nil {
		return errors.New("参数不能为空")
	}
	if _, ok := params["action"]; !ok {
		return errors.New("缺少必填参数: action")
	}
	return nil
}

func loadConfig(namespace string) (*StandardizationConfig, error) {
	var configDef models.ConfigDefinition
	result := database.DB.Where("namespace = ? AND enabled = ?", namespace, true).
		Order("version desc").
		First(&configDef)

	if result.Error != nil {
		return &StandardizationConfig{
			Namespace: namespace,
			Version:   1,
			Rules: []TransformRule{
				{Source: "input", Target: "output", Transform: "copy"},
			},
		}, nil
	}

	rules, _ := configDef.Parameters["rules"].([]TransformRule)
	return &StandardizationConfig{
		Rules:     rules,
		Namespace: namespace,
		Version:   configDef.Version,
	}, nil
}

func transformData(payload map[string]interface{}, rules []TransformRule) (map[string]interface{}, error) {
	result := make(map[string]interface{})

	for _, rule := range rules {
		sourceValue, exists := payload[rule.Source]
		if !exists {
			if rule.Default != "" {
				result[rule.Target] = rule.Default
			}
			continue
		}

		switch rule.Transform {
		case "copy":
			result[rule.Target] = sourceValue
		case "uppercase":
			if str, ok := sourceValue.(string); ok {
				result[rule.Target] = strings.ToUpper(str)
			}
		case "lowercase":
			if str, ok := sourceValue.(string); ok {
				result[rule.Target] = strings.ToLower(str)
			}
		case "int":
			switch v := sourceValue.(type) {
			case float64:
				result[rule.Target] = int(v)
			case string:
				if intVal, err := strconv.Atoi(v); err == nil {
					result[rule.Target] = intVal
				}
			}
		case "string":
			result[rule.Target] = fmt.Sprintf("%v", sourceValue)
		default:
			result[rule.Target] = sourceValue
		}
	}

	return result, nil
}

func persistResult(result map[string]interface{}) error {
	entity := models.Entity{
		ID:         utils.GenerateID("ent"),
		Type:       "processed_data",
		Status:     "completed",
		Attributes: result,
		CreatedAt:  utils.Now(),
		UpdatedAt:  utils.Now(),
	}
	return database.DB.Create(&entity).Error
}

func emitEvent(eventType string, data interface{}) {
	logger.Info("core", "事件已发出",
		zap.String("event_type", eventType),
		zap.String("data", utils.ToJSON(data)),
	)
}

func recordMetrics(pc *ProcessingContext) {
	duration := time.Since(pc.StartTime).Milliseconds()
	snapshot := models.Snapshot{
		SnapshotID: utils.GenerateID("snap"),
		Timestamp:  utils.Now(),
		Metrics: map[string]interface{}{
			"duration_ms": duration,
			"trace_id":    pc.TraceID,
		},
		Dimensions: map[string]string{
			"service": "core-processor",
		},
	}
	database.DB.Create(&snapshot)
}

func rollbackTransaction(pc *ProcessingContext) {
	logger.Warn("core", "执行回滚", zap.String("trace_id", pc.TraceID))
}

func ExecuteHandler(request ProcessRequest) ProcessResponse {
	pc := NewProcessingContext(request.TraceID)
	defer pc.Cleanup()
	defer recordMetrics(pc)

	logger.Info("core", "开始处理请求",
		zap.String("trace_id", request.TraceID),
		zap.String("namespace", request.Namespace),
	)

	if err := validateParams(request.Params); err != nil {
		logger.Warn("core", "参数验证失败",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		return ProcessResponse{
			Code:    422,
			Message: "参数验证失败: " + err.Error(),
		}
	}

	config, err := loadConfig(request.Namespace)
	if err != nil {
		logger.Error("core", "加载配置失败",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		return ProcessResponse{
			Code:    500,
			Message: "加载配置失败",
		}
	}
	pc.Config = config

	result, err := transformData(request.Payload, config.Rules)
	if err != nil {
		logger.Error("core", "数据转换失败",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		rollbackTransaction(pc)
		return ProcessResponse{
			Code:    500,
			Message: "数据转换失败: " + err.Error(),
		}
	}

	if err := persistResult(result); err != nil {
		logger.Error("core", "持久化结果失败",
			zap.String("trace_id", request.TraceID),
			zap.Error(err),
		)
		rollbackTransaction(pc)
		return ProcessResponse{
			Code:    500,
			Message: "持久化失败",
		}
	}

	emitEvent("task.completed", gin.H{
		"trace_id": request.TraceID,
		"result":   result,
	})

	logger.Info("core", "请求处理完成",
		zap.String("trace_id", request.TraceID),
		zap.Duration("duration", time.Since(pc.StartTime)),
	)

	return ProcessResponse{
		Code:    200,
		Message: "处理成功",
		Data:    result,
	}
}

func ProcessAPIHandler(c *gin.Context) {
	var req ProcessRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, ProcessResponse{
			Code:    400,
			Message: "参数错误: " + err.Error(),
		})
		return
	}

	if req.TraceID == "" {
		req.TraceID = utils.GenerateID("trace")
	}

	result := ExecuteHandler(req)

	statusCode := http.StatusOK
	if result.Code >= 500 {
		statusCode = http.StatusInternalServerError
	} else if result.Code >= 400 {
		statusCode = http.StatusBadRequest
	}

	c.JSON(statusCode, result)
}

func BatchProcessHandler(c *gin.Context) {
	var requests []ProcessRequest
	if err := c.ShouldBindJSON(&requests); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{
			"code":    400,
			"message": "参数错误",
			"error":   err.Error(),
		})
		return
	}

	results := make([]ProcessResponse, 0, len(requests))
	for _, req := range requests {
		if req.TraceID == "" {
			req.TraceID = utils.GenerateID("trace")
		}
		results = append(results, ExecuteHandler(req))
	}

	c.JSON(http.StatusOK, gin.H{
		"code":  200,
		"data":  results,
		"total": len(results),
	})
}

func GetProcessStatus(c *gin.Context) {
	id := c.Param("id")

	var entity models.Entity
	if err := database.DB.First(&entity, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{
			"code":    404,
			"message": "处理记录不存在",
		})
		return
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": entity,
	})
}

func GetMetrics(c *gin.Context) {
	var snapshots []models.Snapshot
	database.DB.Order("timestamp desc").Limit(100).Find(&snapshots)

	metrics := make([]map[string]interface{}, 0)
	for _, s := range snapshots {
		metrics = append(metrics, map[string]interface{}{
			"timestamp":  s.Timestamp,
			"metrics":    s.Metrics,
			"dimensions": s.Dimensions,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"snapshots": metrics,
			"total":     len(metrics),
		},
	})
}

func RegisterRoutes(r *gin.RouterGroup) {
	core := r.Group("/core")
	{
		core.POST("/process", ProcessAPIHandler)
		core.POST("/process/batch", BatchProcessHandler)
		core.GET("/process/:id/status", GetProcessStatus)
		core.GET("/metrics", GetMetrics)
	}
}
