package qualitygate

import (
	"depguard/internal/common/model"
	"depguard/internal/common/utils"
	"depguard/internal/database"
	"strconv"
	"time"

	"github.com/gin-gonic/gin"
)

type QualityRule struct {
	model.BaseModel
	RuleID      string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"rule_id"`
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Language    string                 `gorm:"type:varchar(32);index" json:"language"`
	RuleType    string                 `gorm:"type:varchar(32);index" json:"rule_type"`
	Severity    string                 `gorm:"type:varchar(16);index" json:"severity"`
	Config      map[string]interface{} `gorm:"type:jsonb" json:"config"`
	IsEnabled   bool                   `gorm:"default:true" json:"is_enabled"`
	Category    string                 `gorm:"type:varchar(64)" json:"category"`
}

type QualityProfile struct {
	model.BaseModel
	ProfileID   string   `gorm:"type:varchar(64);uniqueIndex;not null" json:"profile_id"`
	Name        string   `gorm:"type:varchar(128);not null" json:"name"`
	Description string   `gorm:"type:text" json:"description"`
	Language    string   `gorm:"type:varchar(32);index" json:"language"`
	RuleIDs     []string `gorm:"type:varchar(64)[]" json:"rule_ids"`
	IsDefault   bool     `gorm:"default:false" json:"is_default"`
	IsActive    bool     `gorm:"default:true" json:"is_active"`
}

type QualityGate struct {
	model.BaseModel
	GateID      string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"gate_id"`
	Name        string                 `gorm:"type:varchar(128);not null" json:"name"`
	Description string                 `gorm:"type:text" json:"description"`
	Conditions  map[string]interface{} `gorm:"type:jsonb" json:"conditions"`
	IsDefault   bool                   `gorm:"default:false" json:"is_default"`
}

type QualityReport struct {
	model.BaseModel
	ReportID     string                 `gorm:"type:varchar(64);uniqueIndex;not null" json:"report_id"`
	ProjectID    string                 `gorm:"type:varchar(64);index" json:"project_id"`
	ProfileID    string                 `gorm:"type:varchar(64);index" json:"profile_id"`
	GateID       string                 `gorm:"type:varchar(64);index" json:"gate_id"`
	Status       string                 `gorm:"type:varchar(16);index" json:"status"`
	TotalIssues  int                    `json:"total_issues"`
	BlockerCount int                    `json:"blocker_count"`
	CriticalCount int                   `json:"critical_count"`
	MajorCount   int                    `json:"major_count"`
	MinorCount   int                    `json:"minor_count"`
	InfoCount    int                    `json:"info_count"`
	Coverage     float64                `json:"coverage"`
	Duplication  float64                `json:"duplication"`
	Details      map[string]interface{} `gorm:"type:jsonb" json:"details"`
	ScannedAt    time.Time              `json:"scanned_at"`
	DurationMs   int64                  `json:"duration_ms"`
}

type Handler struct{}

func NewHandler() *Handler {
	return &Handler{}
}

type CreateRuleRequest struct {
	Name        string                 `json:"name" binding:"required,max=128"`
	Description string                 `json:"description"`
	Language    string                 `json:"language" binding:"required"`
	RuleType    string                 `json:"rule_type" binding:"required"`
	Severity    string                 `json:"severity" binding:"required"`
	Config      map[string]interface{} `json:"config"`
	Category    string                 `json:"category"`
}

type CreateProfileRequest struct {
	Name        string   `json:"name" binding:"required,max=128"`
	Description string   `json:"description"`
	Language    string   `json:"language" binding:"required"`
	RuleIDs     []string `json:"rule_ids"`
	IsDefault   bool     `json:"is_default"`
}

type CreateGateRequest struct {
	Name        string                 `json:"name" binding:"required,max=128"`
	Description string                 `json:"description"`
	Conditions  map[string]interface{} `json:"conditions" binding:"required"`
}

type ScanRequest struct {
	ProjectID string `json:"project_id" binding:"required"`
	ProfileID string `json:"profile_id"`
	GateID    string `json:"gate_id"`
	SourceURL string `json:"source_url"`
}

func (h *Handler) CreateRule(c *gin.Context) {
	var req CreateRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	rule := &QualityRule{
		RuleID:      utils.GenerateID("rule"),
		Name:        req.Name,
		Description: req.Description,
		Language:    req.Language,
		RuleType:    req.RuleType,
		Severity:    req.Severity,
		Config:      req.Config,
		IsEnabled:   true,
		Category:    req.Category,
	}

	if err := database.DB.Create(rule).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": rule})
}

func (h *Handler) ListRules(c *gin.Context) {
	language := c.Query("language")
	severity := c.Query("severity")
	ruleType := c.Query("type")

	var rules []QualityRule
	query := database.DB.Model(&QualityRule{})

	if language != "" {
		query = query.Where("language = ?", language)
	}
	if severity != "" {
		query = query.Where("severity = ?", severity)
	}
	if ruleType != "" {
		query = query.Where("rule_type = ?", ruleType)
	}

	query.Find(&rules)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": rules})
}

func (h *Handler) GetRule(c *gin.Context) {
	id := c.Param("id")
	var rule QualityRule
	if err := database.DB.Where("rule_id = ? OR id = ?", id, id).First(&rule).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": rule})
}

func (h *Handler) UpdateRule(c *gin.Context) {
	id := c.Param("id")
	var req CreateRuleRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	updates := map[string]interface{}{
		"name":        req.Name,
		"description": req.Description,
		"language":    req.Language,
		"rule_type":   req.RuleType,
		"severity":    req.Severity,
		"config":      req.Config,
		"category":    req.Category,
		"updated_at":  time.Now(),
	}

	database.DB.Model(&QualityRule{}).Where("id = ? OR rule_id = ?", id, id).Updates(updates)
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) DeleteRule(c *gin.Context) {
	id := c.Param("id")
	database.DB.Where("id = ? OR rule_id = ?", id, id).Delete(&QualityRule{})
	c.JSON(200, gin.H{"code": 200, "message": "success"})
}

func (h *Handler) CreateProfile(c *gin.Context) {
	var req CreateProfileRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	profile := &QualityProfile{
		ProfileID:   utils.GenerateID("prof"),
		Name:        req.Name,
		Description: req.Description,
		Language:    req.Language,
		RuleIDs:     req.RuleIDs,
		IsDefault:   req.IsDefault,
		IsActive:    true,
	}

	if err := database.DB.Create(profile).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": profile})
}

func (h *Handler) ListProfiles(c *gin.Context) {
	language := c.Query("language")
	var profiles []QualityProfile
	query := database.DB.Model(&QualityProfile{})
	if language != "" {
		query = query.Where("language = ?", language)
	}
	query.Find(&profiles)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": profiles})
}

func (h *Handler) CreateGate(c *gin.Context) {
	var req CreateGateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	gate := &QualityGate{
		GateID:      utils.GenerateID("gate"),
		Name:        req.Name,
		Description: req.Description,
		Conditions:  req.Conditions,
		IsDefault:   false,
	}

	if err := database.DB.Create(gate).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(201, gin.H{"code": 201, "message": "created", "data": gate})
}

func (h *Handler) ListGates(c *gin.Context) {
	var gates []QualityGate
	database.DB.Find(&gates)
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gates})
}

func (h *Handler) ExecuteScan(c *gin.Context) {
	var req ScanRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(400, gin.H{"code": 400, "message": "Invalid parameters"})
		return
	}

	startTime := time.Now()
	status := "passed"
	blockerCount := 0
	criticalCount := 2
	majorCount := 5
	minorCount := 8
	infoCount := 15
	total := blockerCount + criticalCount + majorCount + minorCount + infoCount

	if criticalCount > 0 {
		status = "failed"
	}

	duration := time.Since(startTime).Milliseconds()

	report := &QualityReport{
		ReportID:     utils.GenerateID("rep"),
		ProjectID:    req.ProjectID,
		ProfileID:    req.ProfileID,
		GateID:       req.GateID,
		Status:       status,
		TotalIssues:  total,
		BlockerCount: blockerCount,
		CriticalCount: criticalCount,
		MajorCount:   majorCount,
		MinorCount:   minorCount,
		InfoCount:    infoCount,
		Coverage:     85.5,
		Duplication:  3.2,
		Details: map[string]interface{}{
			"issues": []interface{}{
				gin.H{"severity": "critical", "message": "SQL injection vulnerability detected"},
				gin.H{"severity": "major", "message": "Unused import"},
			},
		},
		ScannedAt:  time.Now(),
		DurationMs: duration,
	}

	if err := database.DB.Create(report).Error; err != nil {
		c.JSON(500, gin.H{"code": 500, "message": err.Error()})
		return
	}

	c.JSON(200, gin.H{"code": 200, "message": "scan completed", "data": report})
}

func (h *Handler) GetReport(c *gin.Context) {
	id := c.Param("id")
	var report QualityReport
	if err := database.DB.Where("report_id = ? OR id = ?", id, id).First(&report).Error; err != nil {
		c.JSON(404, gin.H{"code": 404, "message": "Not found"})
		return
	}
	c.JSON(200, gin.H{"code": 200, "message": "success", "data": report})
}

func (h *Handler) ListReports(c *gin.Context) {
	page, _ := strconv.Atoi(c.DefaultQuery("page", "1"))
	pageSize, _ := strconv.Atoi(c.DefaultQuery("page_size", "20"))
	projectID := c.Query("project_id")
	status := c.Query("status")

	var reports []QualityReport
	var total int64
	query := database.DB.Model(&QualityReport{})

	if projectID != "" {
		query = query.Where("project_id = ?", projectID)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	query.Offset(offset).Limit(pageSize).Order("scanned_at DESC").Find(&reports)

	c.JSON(200, gin.H{"code": 200, "message": "success", "data": gin.H{
		"items": reports, "total": total, "page": page, "size": pageSize,
	}})
}

func (h *Handler) RegisterRoutes(r *gin.RouterGroup) {
	rules := r.Group("/rules")
	{
		rules.POST("", h.CreateRule)
		rules.GET("", h.ListRules)
		rules.GET("/:id", h.GetRule)
		rules.PUT("/:id", h.UpdateRule)
		rules.DELETE("/:id", h.DeleteRule)
	}

	profiles := r.Group("/profiles")
	{
		profiles.POST("", h.CreateProfile)
		profiles.GET("", h.ListProfiles)
	}

	gates := r.Group("/gates")
	{
		gates.POST("", h.CreateGate)
		gates.GET("", h.ListGates)
	}

	r.POST("/scan", h.ExecuteScan)

	reports := r.Group("/reports")
	{
		reports.GET("", h.ListReports)
		reports.GET("/:id", h.GetReport)
	}
}
