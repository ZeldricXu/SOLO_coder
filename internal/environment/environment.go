package environment

import (
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/internal/logger"
	"github.com/solocoder/session138/pkg/database"
	"github.com/solocoder/session138/pkg/models"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"net/http"
	"time"
)

type CreateEnvironmentRequest struct {
	Name       string            `json:"name" binding:"required"`
	Namespace  string            `json:"namespace"`
	Owner      string            `json:"owner" binding:"required"`
	TTLMinutes int               `json:"ttl_minutes"`
	Config     map[string]string `json:"config"`
}

type UsageStats struct {
	TotalEnvironments int       `json:"total_environments"`
	ActiveEnvironments int      `json:"active_environments"`
	ExpiredEnvironments int    `json:"expired_environments"`
	TotalUsageMinutes   int64   `json:"total_usage_minutes"`
	TopUsers            []gin.H `json:"top_users"`
}

func CreateEnvironment(c *gin.Context) {
	var req CreateEnvironmentRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	if req.TTLMinutes == 0 {
		req.TTLMinutes = 60
	}

	if req.Namespace == "" {
		req.Namespace = "default"
	}

	env := models.Environment{
		ID:         utils.GenerateID("env"),
		Name:       req.Name,
		Namespace:  req.Namespace,
		Status:     "creating",
		Owner:      req.Owner,
		TTLMinutes: req.TTLMinutes,
		CreatedAt:  utils.Now(),
		ExpiresAt:  utils.Now().Add(time.Duration(req.TTLMinutes) * time.Minute),
	}

	if err := database.DB.Create(&env).Error; err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "创建环境失败", "error": err.Error()})
		return
	}

	go provisionEnvironment(env)

	logger.Info("environment", "环境已创建",
		zap.String("env_id", env.ID),
		zap.String("name", env.Name),
		zap.String("owner", req.Owner),
	)

	c.JSON(http.StatusCreated, gin.H{"code": 201, "data": env})
}

func provisionEnvironment(env models.Environment) {
	time.Sleep(2 * time.Second)

	env.Status = "running"
	database.DB.Save(&env)

	logger.Info("environment", "环境已就绪", zap.String("env_id", env.ID))
}

func GetEnvironment(c *gin.Context) {
	id := c.Param("id")

	var env models.Environment
	if err := database.DB.First(&env, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "环境不存在"})
		return
	}

	if time.Now().After(env.ExpiresAt) && env.Status != "expired" {
		env.Status = "expired"
		database.DB.Save(&env)
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": env})
}

func ListEnvironments(c *gin.Context) {
	owner := c.Query("owner")
	status := c.Query("status")
	namespace := c.Query("namespace")

	var envs []models.Environment
	query := database.DB.Model(&models.Environment{})

	if owner != "" {
		query = query.Where("owner = ?", owner)
	}
	if status != "" {
		query = query.Where("status = ?", status)
	}
	if namespace != "" {
		query = query.Where("namespace = ?", namespace)
	}

	query.Order("created_at desc").Find(&envs)

	now := time.Now()
	for i := range envs {
		if now.After(envs[i].ExpiresAt) && envs[i].Status != "expired" {
			envs[i].Status = "expired"
		}
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": envs})
}

func ExtendEnvironment(c *gin.Context) {
	id := c.Param("id")
	var req struct {
		TTLMinutes int `json:"ttl_minutes" binding:"required"`
	}

	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	var env models.Environment
	if err := database.DB.First(&env, "id = ?", id).Error; err != nil {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "环境不存在"})
		return
	}

	env.ExpiresAt = env.ExpiresAt.Add(time.Duration(req.TTLMinutes) * time.Minute)
	env.TTLMinutes += req.TTLMinutes

	if env.Status == "expired" {
		env.Status = "running"
	}

	database.DB.Save(&env)

	logger.Info("environment", "环境已续期",
		zap.String("env_id", id),
		zap.Time("new_expires_at", env.ExpiresAt),
	)

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": env})
}

func DeleteEnvironment(c *gin.Context) {
	id := c.Param("id")

	result := database.DB.Delete(&models.Environment{}, "id = ?", id)
	if result.Error != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "删除失败", "error": result.Error.Error()})
		return
	}

	if result.RowsAffected == 0 {
		c.JSON(http.StatusNotFound, gin.H{"code": 404, "message": "环境不存在"})
		return
	}

	logger.Info("environment", "环境已删除", zap.String("env_id", id))

	c.JSON(http.StatusOK, gin.H{"code": 200, "message": "环境已删除"})
}

func CleanupExpiredEnvironments() {
	var expiredEnvs []models.Environment
	database.DB.Where("expires_at < ? AND status != ?", time.Now(), "expired").Find(&expiredEnvs)

	for _, env := range expiredEnvs {
		env.Status = "expired"
		database.DB.Save(&env)
		logger.Info("environment", "环境已过期回收", zap.String("env_id", env.ID))
	}
}

func GetUsageStatistics(c *gin.Context) {
	var total int64
	database.DB.Model(&models.Environment{}).Count(&total)

	var active int64
	database.DB.Model(&models.Environment{}).Where("status = ?", "running").Count(&active)

	var expired int64
	database.DB.Model(&models.Environment{}).Where("status = ?", "expired").Count(&expired)

	var envs []models.Environment
	database.DB.Find(&envs)

	totalUsage := int64(0)
	userUsage := make(map[string]int64)

	for _, env := range envs {
		usage := int64(env.TTLMinutes)
		totalUsage += usage
		userUsage[env.Owner] += usage
	}

	topUsers := make([]gin.H, 0)
	for user, usage := range userUsage {
		topUsers = append(topUsers, gin.H{"user": user, "usage_minutes": usage})
	}

	stats := UsageStats{
		TotalEnvironments:   int(total),
		ActiveEnvironments:  int(active),
		ExpiredEnvironments: int(expired),
		TotalUsageMinutes:   totalUsage,
		TopUsers:            topUsers,
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": stats})
}

func RegisterRoutes(r *gin.RouterGroup) {
	env := r.Group("/environment")
	{
		env.POST("", CreateEnvironment)
		env.GET("", ListEnvironments)
		env.GET("/stats", GetUsageStatistics)
		env.GET("/:id", GetEnvironment)
		env.POST("/:id/extend", ExtendEnvironment)
		env.DELETE("/:id", DeleteEnvironment)
	}
}
