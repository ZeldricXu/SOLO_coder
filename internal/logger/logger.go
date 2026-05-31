package logger

import (
	"context"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/redis/go-redis/v9"
	"github.com/solocoder/session138/pkg/cache"
	"github.com/solocoder/session138/pkg/config"
	"github.com/solocoder/session138/pkg/database"
	"github.com/solocoder/session138/pkg/models"
	"github.com/solocoder/session138/pkg/utils"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
	"gopkg.in/natefinch/lumberjack.v2"
	"net/http"
	"os"
	"strings"
	"sync"
)

var (
	Logger        *zap.Logger
	SugaredLogger *zap.SugaredLogger
	levelCache    = make(map[string]zapcore.Level)
	levelMutex    sync.RWMutex
	defaultLevel  zapcore.Level
)

const (
	LogLevelChannel = "log_level_updates"
)

type SetLogLevelRequest struct {
	Service string `json:"service" binding:"required"`
	Level   string `json:"level" binding:"required"`
}

type LogLevelInfo struct {
	Service string `json:"service"`
	Level   string `json:"level"`
}

func Init(cfg *config.LoggerConfig) {
	defaultLevel = parseLevel(cfg.Level)

	writer := &lumberjack.Logger{
		Filename:   cfg.FilePath,
		MaxSize:    cfg.MaxSize,
		MaxBackups: cfg.MaxBackups,
		MaxAge:     cfg.MaxAge,
		Compress:   true,
	}

	encoderConfig := zap.NewProductionEncoderConfig()
	encoderConfig.EncodeTime = zapcore.ISO8601TimeEncoder
	encoderConfig.EncodeLevel = zapcore.CapitalLevelEncoder

	consoleEncoder := zapcore.NewConsoleEncoder(encoderConfig)
	fileEncoder := zapcore.NewJSONEncoder(encoderConfig)

	core := zapcore.NewTee(
		zapcore.NewCore(consoleEncoder, zapcore.AddSync(os.Stdout), defaultLevel),
		zapcore.NewCore(fileEncoder, zapcore.AddSync(writer), defaultLevel),
	)

	Logger = zap.New(core, zap.AddCaller(), zap.AddCallerSkip(1))
	SugaredLogger = Logger.Sugar()

	loadLogLevelsFromDB()

	if cache.Client != nil {
		go subscribeLogLevelUpdates()
	}
}

func parseLevel(level string) zapcore.Level {
	switch strings.ToLower(level) {
	case "debug":
		return zapcore.DebugLevel
	case "info":
		return zapcore.InfoLevel
	case "warn", "warning":
		return zapcore.WarnLevel
	case "error":
		return zapcore.ErrorLevel
	case "dpanic":
		return zapcore.DPanicLevel
	case "panic":
		return zapcore.PanicLevel
	case "fatal":
		return zapcore.FatalLevel
	default:
		return zapcore.InfoLevel
	}
}

func loadLogLevelsFromDB() {
	if database.DB == nil {
		return
	}

	var configs []models.LogLevelConfig
	database.DB.Find(&configs)

	levelMutex.Lock()
	defer levelMutex.Unlock()

	for _, cfg := range configs {
		levelCache[cfg.Service] = parseLevel(cfg.Level)
	}
}

func GetLevel(service string) zapcore.Level {
	levelMutex.RLock()
	defer levelMutex.RUnlock()

	if level, ok := levelCache[service]; ok {
		return level
	}
	return defaultLevel
}

func SetLevel(service, level string) error {
	parsedLevel := parseLevel(level)

	levelMutex.Lock()
	levelCache[service] = parsedLevel
	levelMutex.Unlock()

	if database.DB != nil {
		var config models.LogLevelConfig
		result := database.DB.Where("service = ?", service).First(&config)
		if result.Error != nil {
			config = models.LogLevelConfig{
				Service:   service,
				Level:     level,
				UpdatedAt: utils.Now(),
			}
			database.DB.Create(&config)
		} else {
			config.Level = level
			config.UpdatedAt = utils.Now()
			database.DB.Save(&config)
		}
	}

	if cache.Client != nil {
		message, _ := json.Marshal(gin.H{
			"service": service,
			"level":   level,
		})
		cache.Publish(LogLevelChannel, string(message))
	}

	return nil
}

func subscribeLogLevelUpdates() {
	pubsub := cache.Client.Subscribe(cache.Ctx, LogLevelChannel)
	defer pubsub.Close()

	ch := pubsub.Channel()

	for msg := range ch {
		var update LogLevelInfo
		if err := json.Unmarshal([]byte(msg.Payload), &update); err == nil {
			levelMutex.Lock()
			levelCache[update.Service] = parseLevel(update.Level)
			levelMutex.Unlock()
			Logger.Info("日志级别已更新", zap.String("service", update.Service), zap.String("level", update.Level))
		}
	}
}

func Debug(service string, msg string, fields ...zap.Field) {
	if Logger.Core().Enabled(GetLevel(service)) {
		fields = append(fields, zap.String("service", service))
		Logger.Debug(msg, fields...)
	}
}

func Info(service string, msg string, fields ...zap.Field) {
	if Logger.Core().Enabled(GetLevel(service)) {
		fields = append(fields, zap.String("service", service))
		Logger.Info(msg, fields...)
	}
}

func Warn(service string, msg string, fields ...zap.Field) {
	if Logger.Core().Enabled(GetLevel(service)) {
		fields = append(fields, zap.String("service", service))
		Logger.Warn(msg, fields...)
	}
}

func Error(service string, msg string, fields ...zap.Field) {
	if Logger.Core().Enabled(GetLevel(service)) {
		fields = append(fields, zap.String("service", service))
		Logger.Error(msg, fields...)
	}
}

func SetLogLevelHandler(c *gin.Context) {
	var req SetLogLevelRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	validLevels := []string{"debug", "info", "warn", "warning", "error", "panic", "fatal"}
	if !utils.Contains(validLevels, strings.ToLower(req.Level)) {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "无效的日志级别"})
		return
	}

	if err := SetLevel(req.Service, req.Level); err != nil {
		c.JSON(http.StatusInternalServerError, gin.H{"code": 500, "message": "设置失败", "error": err.Error()})
		return
	}

	Info("logger", "日志级别已更新", zap.String("service", req.Service), zap.String("level", req.Level))

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"service": req.Service,
			"level":   req.Level,
			"status":  "updated",
		},
	})
}

func GetLogLevelHandler(c *gin.Context) {
	service := c.Param("service")
	level := GetLevel(service)

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"service": service,
			"level":   level.String(),
		},
	})
}

func ListLogLevelsHandler(c *gin.Context) {
	levelMutex.RLock()
	defer levelMutex.RUnlock()

	result := make([]LogLevelInfo, 0, len(levelCache))
	for service, level := range levelCache {
		result = append(result, LogLevelInfo{
			Service: service,
			Level:   level.String(),
		})
	}

	result = append(result, LogLevelInfo{
		Service: "default",
		Level:   defaultLevel.String(),
	})

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": result})
}

func ResetLogLevelHandler(c *gin.Context) {
	service := c.Param("service")

	levelMutex.Lock()
	delete(levelCache, service)
	levelMutex.Unlock()

	if database.DB != nil {
		database.DB.Where("service = ?", service).Delete(&models.LogLevelConfig{})
	}

	c.JSON(http.StatusOK, gin.H{
		"code": 200,
		"data": gin.H{
			"service": service,
			"level":   defaultLevel.String(),
			"status":  "reset",
		},
	})
}

func BatchSetLogLevelHandler(c *gin.Context) {
	var requests []SetLogLevelRequest
	if err := c.ShouldBindJSON(&requests); err != nil {
		c.JSON(http.StatusBadRequest, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	results := make([]gin.H, 0)
	for _, req := range requests {
		if err := SetLevel(req.Service, req.Level); err != nil {
			results = append(results, gin.H{
				"service": req.Service,
				"status":  "failed",
				"error":   err.Error(),
			})
		} else {
			results = append(results, gin.H{
				"service": req.Service,
				"level":   req.Level,
				"status":  "success",
			})
		}
	}

	c.JSON(http.StatusOK, gin.H{"code": 200, "data": results})
}

func Middleware() gin.HandlerFunc {
	return func(c *gin.Context) {
		service := c.GetHeader("X-Service-Name")
		if service == "" {
			service = "api-gateway"
		}

		Debug(service, "请求开始",
			zap.String("method", c.Request.Method),
			zap.String("path", c.Request.URL.Path),
			zap.String("client_ip", c.ClientIP()),
		)

		c.Next()

		statusCode := c.Writer.Status()
		if statusCode >= 500 {
			Error(service, "请求错误",
				zap.Int("status", statusCode),
				zap.String("method", c.Request.Method),
				zap.String("path", c.Request.URL.Path),
			)
		} else if statusCode >= 400 {
			Warn(service, "请求警告",
				zap.Int("status", statusCode),
				zap.String("method", c.Request.Method),
				zap.String("path", c.Request.URL.Path),
			)
		} else {
			Info(service, "请求完成",
				zap.Int("status", statusCode),
				zap.String("method", c.Request.Method),
				zap.String("path", c.Request.URL.Path),
			)
		}
	}
}

func RegisterRoutes(r *gin.RouterGroup) {
	log := r.Group("/logger")
	{
		log.POST("/level", SetLogLevelHandler)
		log.GET("/level/:service", GetLogLevelHandler)
		log.GET("/levels", ListLogLevelsHandler)
		log.DELETE("/level/:service", ResetLogLevelHandler)
		log.POST("/levels/batch", BatchSetLogLevelHandler)
	}
}
