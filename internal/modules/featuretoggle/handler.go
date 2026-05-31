package featuretoggle

import (
	"depguard/internal/common/model"
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

type Feature struct {
	model.BaseModel
	FeatureID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"feature_id"`
	Key          string                 `gorm:"type:varchar(128);uniqueIndex;not null" json:"key"`
	Name         string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description  string                 `gorm:"type:text" json:"description"`
	FeatureType  string                 `gorm:"type:varchar(32);index" json:"feature_type"`
	Enabled      bool                   `gorm:"default:false" json:"enabled"`
	RolloutPercent int                 `gorm:"default:0" json:"rollout_percent"`
	Strategy     string                 `gorm:"type:varchar(32)" json:"strategy"`
	Conditions   map[string]interface{} `gorm:"type:jsonb" json:"conditions"`
	TargetUsers  []string               `gorm:"type:varchar(64)[]" json:"target_users"`
	TargetGroups []string               `gorm:"type:varchar(64)[]" json:"target_groups"`
	Metadata     map[string]interface{} `gorm:"type:jsonb" json:"metadata"`
	CreatedBy    string                 `gorm:"type:varchar(64)" json:"created_by"`
	Tags         []string               `gorm:"type:varchar(64)[]" json:"tags"`
	ExpiresAt    *time.Time             `json:"expires_at"`
}

type ToggleRule struct {
	model.BaseModel
	RuleID       string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"rule_id"`
	FeatureID    string                 `gorm:"type:varchar(64);index" json:"feature_id"`
	RuleType     string                 `gorm:"type:varchar(32);index" json:"rule_type"`
	Condition    map[string]interface{} `gorm:"type:jsonb" json:"condition"`
	Value        interface{}            `gorm:"type:jsonb" json:"value"`
	Priority     int                    `gorm:"default:0" json:"priority"`
	IsEnabled    bool                   `gorm:"default:true" json:"is_enabled"`
}

type UserSegment struct {
	model.BaseModel
	SegmentID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"segment_id"`
	Name         string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description  string                 `gorm:"type:text" json:"description"`
	Conditions   map[string]interface{} `gorm:"type:jsonb" json:"conditions"`
	UserCount    int                    `gorm:"default:0" json:"user_count"`
	IsDynamic    bool                   `gorm:"default:false" json:"is_dynamic"`
}

type ToggleLog struct {
	model.BaseModel
	LogID        string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"log_id"`
	FeatureID    string                 `gorm:"type:varchar(64);index" json:"feature_id"`
	UserID       string                 `gorm:"type:varchar(64);index" json:"user_id"`
	Action       string                 `gorm:"type:varchar(32)" json:"action"`
	Value        interface{}            `gorm:"type:jsonb" json:"value"`
	Context      map[string]interface{} `gorm:"type:jsonb" json:"context"`
	EvaluatedAt  time.Time              `json:"evaluated_at"`
}

type GradualRollout struct {
	model.BaseModel
	RolloutID    string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"rollout_id"`
	FeatureID    string                 `gorm:"type:varchar(64);index" json:"feature_id"`
	Name         string                 `gorm:"type:varchar(128);not null" json:"name"`
	StartPercent int                    `gorm:"default:0" json:"start_percent"`
	TargetPercent int                   `gorm:"default:100" json:"target_percent"`
	CurrentPercent int                  `gorm:"default:0" json:"current_percent"`
	Schedule     map[string]interface{} `gorm:"type:jsonb" json:"schedule"`
	Status       string                 `gorm:"type:varchar(32);index" json:"status"`
	StartedAt    *time.Time             `json:"started_at"`
	CompletedAt  *time.Time             `json:"completed_at"`
}

type Handler struct{}

func NewHandler() *Handler {
	return &Handler{}
}

type CreateFeatureRequest struct {
	Key          string                 `json:"key" binding:"required,max=128"`
	Name         string                 `json:"name" binding:"required,max=128"`
	Description  string                 `json:"description"`
	FeatureType  string                 `json:"feature_type" binding:"required"`
	Enabled      bool                   `json:"enabled"`
	RolloutPercent int                 `json:"rollout_percent" binding:"min=0,max=100"`
	Strategy     string                 `json:"strategy"`
	Conditions   map[string]interface{} `json:"conditions"`
	TargetUsers  []string               `json:"target_users"`
	TargetGroups []string               `json:"target_groups"`
	Metadata     map[string]interface{} `json:"metadata"`
	Tags         []string               `json:"tags"`
	TTLMinutes   int                    `json:"ttl_minutes"`
}

type EvaluateRequest struct {
	FeatureKey string                 `json:"feature_key" binding:"required"`
	UserID     string                 `json:"user_id"`
	Context    map[string]interface{} `json:"context"`
}

type CreateRolloutRequest struct {
	FeatureID    string                 `json:"feature_id" binding:"required"`
	Name         string                 `json:"name" binding:"required,max=128"`
	TargetPercent int                   `json:"target_percent" binding:"required,min=1,max=100"`
	DurationMinutes int                `json:"duration_minutes"`
}

func (h *Handler) CreateFeature(c *gin.Context) {
	var req CreateFeatureRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	var expiresAt *time.Time
	if req.TTLMinutes > 0 {
		t := time.Now().Add(time.Duration(req.TTLMinutes) * time.Minute)
		expiresAt = &t
	}

	feature := &Feature{
		FeatureID:    utils.GenerateID("feat"),
		Key:          req.Key,
		Name:         req.Name,
		Description:  req.Description,
		FeatureType:  req.FeatureType,
		Enabled:      req.Enabled,
		RolloutPercent: req.RolloutPercent,
		Strategy:     req.Strategy,
		Conditions:   req.Conditions,
		TargetUsers:  req.TargetUsers,
		TargetGroups: req.TargetGroups,
		Metadata:     req.Metadata,
		Tags:         req.Tags,
		ExpiresAt:    expiresAt,
	}

	if err := database.DB.Create(feature).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": feature})
}

func (h *Handler) GetFeature(c *gin.Context) {
	id := c.Param("id")
	var feature Feature
	if err := database.DB.Where("feature_id = ? OR key = ? OR id = ?", id, id, id).First(&feature).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": feature})
}

func (h *Handler) ListFeatures(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	featureType := c.Query("type")
	enabled := c.Query("enabled")
	tag := c.Query("tag")

	var features []Feature
	var total int64
	query := database.DB.Model(&Feature{})

	if featureType != "" {
		query = query.Where("feature_type = ?", featureType)
	}
	if enabled != "" {
		query = query.Where("enabled = ?", enabled == "true")
	}
	if tag != "" {
		query = query.Where("tags @> ARRAY[?]::varchar[]", tag)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&features)

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"items": features, "total": total, "page": page, "size": pageSize,
	}})
}

func (h *Handler) UpdateFeature(c *gin.Context) {
	id := c.Param("id")
	var req CreateFeatureRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	updates := map[string]interface{}{
		"name":           req.Name,
		"description":    req.Description,
		"feature_type":   req.FeatureType,
		"enabled":        req.Enabled,
		"rollout_percent": req.RolloutPercent,
		"strategy":       req.Strategy,
		"conditions":     req.Conditions,
		"target_users":   req.TargetUsers,
		"target_groups":  req.TargetGroups,
		"metadata":       req.Metadata,
		"tags":           req.Tags,
		"updated_at":     time.Now(),
	}

	database.DB.Model(&Feature{}).Where("id = ? OR feature_id = ?", id, id).Updates(updates)
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) DeleteFeature(c *gin.Context) {
	id := c.Param("id")
	database.DB.Where("id = ? OR feature_id = ?", id, id).Delete(&Feature{})
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) Evaluate(c *gin.Context) {
	var req EvaluateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	var feature Feature
	if err := database.DB.Where("key = ?", req.FeatureKey).First(&feature).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Feature not found"})
		return
	}

	value := feature.Enabled
	if !value {
		c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
			"feature_key": req.FeatureKey,
			"enabled":     false,
			"reason":      "feature is disabled",
		}})
		return
	}

	if feature.RolloutPercent > 0 && feature.RolloutPercent < 100 {
		hash := utils.GenerateRandomString(8)
		userHash := int(hash[0]) % 100
		value = userHash < feature.RolloutPercent
	}

	if len(feature.TargetUsers) > 0 {
		userInTarget := false
		for _, u := range feature.TargetUsers {
			if u == req.UserID {
				userInTarget = true
				break
			}
		}
		if !userInTarget {
			value = false
		}
	}

	log := &ToggleLog{
		LogID:       utils.GenerateID("log"),
		FeatureID:   feature.FeatureID,
		UserID:      req.UserID,
		Action:      "evaluate",
		Value:       value,
		Context:     req.Context,
		EvaluatedAt: time.Now(),
	}
	database.DB.Create(log)

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"feature_key": req.FeatureKey,
		"enabled":     value,
		"rollout_percent": feature.RolloutPercent,
		"strategy":    feature.Strategy,
	}})
}

func (h *Handler) CreateRollout(c *gin.Context) {
	var req CreateRolloutRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	now := time.Now()
	rollout := &GradualRollout{
		RolloutID:    utils.GenerateID("roll"),
		FeatureID:    req.FeatureID,
		Name:         req.Name,
		StartPercent: 0,
		TargetPercent: req.TargetPercent,
		CurrentPercent: 0,
		Schedule: map[string]interface{}{
			"duration_minutes": req.DurationMinutes,
		},
		Status:    "running",
		StartedAt: &now,
	}

	if err := database.DB.Create(rollout).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": rollout})
}

func (h *Handler) ListRollouts(c *gin.Context) {
	featureID := c.Query("feature_id")
	status := c.Query("status")

	var rollouts []GradualRollout
	query := database.DB.Model(&GradualRollout{})

	if featureID != "" {
		query = query.Where("feature_id = ?", featureID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	query.Order("created_at DESC").Find(&rollouts)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": rollouts})
}

func (h *Handler) CreateSegment(c *gin.Context) {
	var segment UserSegment
	if err := c.ShouldBindJSON(&segment); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	segment.SegmentID = utils.GenerateID("seg")
	if err := database.DB.Create(&segment).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": segment})
}

func (h *Handler) ListSegments(c *gin.Context) {
	var segments []UserSegment
	database.DB.Find(&segments)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": segments})
}

func (h *Handler) GetEvaluationLogs(c *gin.Context) {
	featureID := c.Query("feature_id")
	userID := c.Query("user_id")
	limit, _ := strconv.Atoi(c.DefaultQuery("limit", "100"))

	var logs []ToggleLog
	query := database.DB.Model(&ToggleLog{})

	if featureID != "" {
		query = query.Where("feature_id = ?", featureID)
	}
	if userID != "" {
		query = query.Where("user_id = ?", userID)
	}

	query.Order("evaluated_at DESC").Limit(limit).Find(&logs)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": logs})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	features := r.Group("/features")
	{
		features.POST("", h.CreateFeature)
		features.GET("", h.ListFeatures)
		features.GET("/:id", h.GetFeature)
		features.PUT("/:id", h.UpdateFeature)
		features.DELETE("/:id", h.DeleteFeature)
	}

	r.POST("/evaluate", h.Evaluate)

	rollouts := r.Group("/rollouts")
	{
		rollouts.POST("", h.CreateRollout)
		rollouts.GET("", h.ListRollouts)
	}

	segments := r.Group("/segments")
	{
		segments.POST("", h.CreateSegment)
		segments.GET("", h.ListSegments)
	}

	r.GET("/logs", h.GetEvaluationLogs)
}
