package featureflag

import (
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/internal/logger"
	"github.com/solocoder/session138/pkg/database"
	"github.com/solocoder/session138/pkg/models"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"hash/fnv"
	"net/http"
	"strings"
	"time"
)

type CreateFlagRequest struct {
	Name         string                 `json:"name" binding:"required"`
	Description  string                 `json:"description"`
	Enabled      bool                   `json:"enabled"`
	Rules        map[string]interface{} `json:"rules"`
	RolloutPercent int                  `json:"rollout_percent"`
	UserGroups   []string               `json:"user_groups"`
}

type EvaluateRequest struct {
	FlagName   string                 `json:"flag_name" binding:"required"`
	UserID     string                 `json:"user_id"`
	UserGroups []string               `json:"user_groups"`
	Attributes map[string]interface{} `json:"attributes"`
}

type EvaluateResponse struct {
	Enabled   bool                   `json:"enabled"`
	FlagName  string                 `json:"flag_name"`
	Reason    string                 `json:"reason"`
	MatchedRule string               `json:"matched_rule,omitempty"`
}

func CreateFeatureFlag(c *gin.Context) {
	var req CreateFlagRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	flag := models.FeatureFlag{
		ID:             utils.GenerateID("flag"),
		Name:           req.Name,
		Description:    req.Description,
		Enabled:        req.Enabled,
		Rules:          req.Rules,
		RolloutPercent: req.RolloutPercent,
		UserGroups:     req.UserGroups,
		CreatedAt:      utils.Now(),
		UpdatedAt:      utils.Now(),
	}

	if err := database.DB.Create(&flag).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "创建特性开关失败", "error": err.Error()})
		return
	}

	logger.Info("featureflag", "特性开关已创建",
		zap.String("flag_id", flag.ID),
		zap.String("name", flag.Name),
	)

	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": flag})
}

func GetFeatureFlag(c *gin.Context) {
	id := c.Param("id")

	var flag models.FeatureFlag
	if err := database.DB.First(&flag, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "特性开关不存在"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": flag})
}

func ListFeatureFlags(c *gin.Context) {
	enabled := c.Query("enabled")

	var flags []models.FeatureFlag
	query := database.DB.Model(&models.FeatureFlag{})

	if enabled != "" {
		query = query.Where("enabled = ?", enabled == "true")
	}

	query.Order("created_at desc").Find(&flags)

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": flags})
}

func UpdateFeatureFlag(c *gin.Context) {
	id := c.Param("id")

	var flag models.FeatureFlag
	if err := database.DB.First(&flag, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "特性开关不存在"})
		return
	}

	var req CreateFlagRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	flag.Name = req.Name
	flag.Description = req.Description
	flag.Enabled = req.Enabled
	flag.Rules = req.Rules
	flag.RolloutPercent = req.RolloutPercent
	flag.UserGroups = req.UserGroups
	flag.UpdatedAt = utils.Now()

	if err := database.DB.Save(&flag).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "更新失败", "error": err.Error()})
		return
	}

	logger.Info("featureflag", "特性开关已更新",
		zap.String("flag_id", id),
		zap.String("name", flag.Name),
	)

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": flag})
}

func DeleteFeatureFlag(c *gin.Context) {
	id := c.Param("id")

	result := database.DB.Delete(&models.FeatureFlag{}, "id = ?", id)
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "删除失败", "error": result.Error.Error()})
		return
	}

	if result.RowsAffected == 0 {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "特性开关不存在"})
		return
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "特性开关已删除"})
}

func EvaluateFlag(c *gin.Context) {
	var req EvaluateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	var flag models.FeatureFlag
	if err := database.DB.Where("name = ?", req.FlagName).First(&flag).Error; err != nil {
		c.JSON(http.StatusOK, gin.H{
			"code": 200,
			"data": EvaluateResponse{
				Enabled:  false,
				FlagName: req.FlagName,
				Reason:   "flag_not_found",
			},
		})
		return
	}

	result := evaluateFlag(flag, req)

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func evaluateFlag(flag models.FeatureFlag, req EvaluateRequest) EvaluateResponse {
	if !flag.Enabled {
		return EvaluateResponse{
			Enabled:  false,
			FlagName: flag.Name,
			Reason:   "flag_disabled",
		}
	}

	if len(flag.UserGroups) > 0 {
		for _, group := range req.UserGroups {
			if utils.Contains(flag.UserGroups, group) {
				return EvaluateResponse{
					Enabled:     true,
					FlagName:    flag.Name,
					Reason:      "user_group_matched",
					MatchedRule: "user_groups",
				}
			}
		}
	}

	if flag.Rules != nil {
		if region, ok := req.Attributes["region"].(string); ok {
			if allowedRegions, ok := flag.Rules["regions"].([]interface{}); ok {
				for _, r := range allowedRegions {
					if r == region {
						return EvaluateResponse{
							Enabled:     true,
							FlagName:    flag.Name,
							Reason:      "region_matched",
							MatchedRule: "regions",
						}
					}
				}
			}
		}

		if email, ok := req.Attributes["email"].(string); ok {
			if allowedEmails, ok := flag.Rules["emails"].([]interface{}); ok {
				for _, e := range allowedEmails {
					if e == email {
						return EvaluateResponse{
							Enabled:     true,
							FlagName:    flag.Name,
							Reason:      "email_matched",
							MatchedRule: "emails",
						}
					}
				}
			}
		}
	}

	if flag.RolloutPercent > 0 {
		hash := computeHash(req.UserID + flag.Name)
		if hash%100 < uint32(flag.RolloutPercent) {
			return EvaluateResponse{
				Enabled:     true,
				FlagName:    flag.Name,
				Reason:      "rollout_percent",
				MatchedRule: "rollout",
			}
		}
	}

	return EvaluateResponse{
		Enabled:  false,
		FlagName: flag.Name,
		Reason:   "no_rules_matched",
	}
}

func computeHash(s string) uint32 {
	h := fnv.New32a()
	h.Write([]byte(s))
	return h.Sum32()
}

func BatchEvaluateFlags(c *gin.Context) {
	var req struct {
		FlagNames  []string               `json:"flag_names" binding:"required"`
		UserID     string                 `json:"user_id"`
		UserGroups []string               `json:"user_groups"`
		Attributes map[string]interface{} `json:"attributes"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	var flags []models.FeatureFlag
	database.DB.Where("name IN ?", req.FlagNames).Find(&flags)

	results := make([]EvaluateResponse, 0, len(flags))
	for _, flag := range flags {
		evalReq := EvaluateRequest{
			FlagName:   flag.Name,
			UserID:     req.UserID,
			UserGroups: req.UserGroups,
			Attributes: req.Attributes,
		}
		results = append(results, evaluateFlag(flag, evalReq))
	}

	for _, name := range req.FlagNames {
		found := false
		for _, r := range results {
			if r.FlagName == name {
				found = true
				break
			}
		}
		if !found {
			results = append(results, EvaluateResponse{
				Enabled:  false,
				FlagName: name,
				Reason:   "flag_not_found",
			})
		}
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": results})
}

func GetFlagVariations(c *gin.Context) {
	id := c.Param("id")

	var flag models.FeatureFlag
	if err := database.DB.First(&flag, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "特性开关不存在"})
		return
	}

	variations := []gin.H{
		{"name": "disabled", "value": false, "description": "开关关闭"},
		{"name": "enabled", "value": true, "description": "开关开启"},
	}

	if flag.RolloutPercent > 0 {
		variations = append(variations, gin.H{
			"name":        "gradual_rollout",
			"percent":     flag.RolloutPercent,
			"description": "渐进式放量",
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"flag_id":    flag.ID,
			"flag_name":  flag.Name,
			"variations": variations,
		},
	})
}

func RegisterRoutes(r *gin.RouterGroup) {
	ff := r.Group("/feature-flags")
	{
		ff.POST("", CreateFeatureFlag)
		ff.GET("", ListFeatureFlags)
		ff.POST("/evaluate", EvaluateFlag)
		ff.POST("/batch-evaluate", BatchEvaluateFlags)
		ff.GET("/:id", GetFeatureFlag)
		ff.GET("/:id/variations", GetFlagVariations)
		ff.PUT("/:id", UpdateFeatureFlag)
		ff.DELETE("/:id", DeleteFeatureFlag)
	}
}
