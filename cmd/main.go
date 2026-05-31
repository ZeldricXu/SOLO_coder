package main

import (
	"context"
	"fmt"
	"github.com/gin-gonic/gin"
	"go.uber.org/zap"
	"gorm.io/driver/sqlite"
	"gorm.io/gorm"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"taskmanager/internal/alerter"
	"taskmanager/internal/logger"
	"taskmanager/internal/logpipeline"
	"taskmanager/internal/notifier"
	"taskmanager/internal/scheduler"
	"taskmanager/internal/slomonitor"
	"taskmanager/internal/storage"
	"taskmanager/internal/topology"
	"taskmanager/internal/tracing"
	"taskmanager/pkg/models"
	"time"
)

func main() {
	if err := logger.InitLogger("info", "console"); err != nil {
		panic(err)
	}
	defer logger.Sync()

	db, err := gorm.Open(sqlite.Open("taskmanager.db"), &gorm.Config{})
	if err != nil {
		logger.Fatal("connect database failed", zap.Error(err))
	}
	if err := db.AutoMigrate(
		&models.Task{},
		&models.RunInstance{},
		&models.SLO{},
		&models.SLI{},
		&models.StoredFile{},
		&models.AlertRule{},
		&models.Alert{},
		&models.LogEntry{},
		&models.ServiceNode{},
		&models.ServiceDependency{},
		&models.TraceSpan{},
		&models.Notification{},
	); err != nil {
		logger.Fatal("migrate database failed", zap.Error(err))
	}

	sched := scheduler.NewScheduler(db)

	sloMonitor := slomonitor.NewSLOMonitor(db)
	sloMonitor.AddStrategyListener(func(event slomonitor.StrategyChangeEvent) {
		logger.Info("strategy changed event received",
			zap.String("old", event.OldStrategy),
			zap.String("new", event.NewStrategy),
		)
	})

	storagePath := "./storage"
	if err := os.MkdirAll(storagePath, 0755); err != nil {
		logger.Fatal("create storage path failed", zap.Error(err))
	}
	storageMgr := storage.NewStorageManager(db, storagePath, 10*1024*1024*1024)
	storageMgr.AddEventListener(func(event storage.StorageEvent) {
		logger.Debug("storage event",
			zap.String("type", event.EventType),
			zap.String("file_id", event.FileID),
		)
	})

	alerterSvc := alerter.NewAlerter(db)
	logPipeline := logpipeline.NewLogPipeline(db)
	topologySvc := topology.NewTopology(db)
	tracingSvc := tracing.NewTracing(db, 1.0)
	notifierSvc := notifier.NewNotifier(db, 3, 5*time.Second)

	r := gin.Default()

	api := r.Group("/api/v1")
	{
		tasks := api.Group("/tasks")
		{
			tasks.POST("", func(c *gin.Context) {
				var task models.Task
				if err := c.ShouldBindJSON(&task); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				if err := sched.CreateTask(c.Request.Context(), &task); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusCreated, task)
			})
			tasks.GET("", func(c *gin.Context) {
				tasks, err := sched.ListTasks(c.Request.Context())
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, tasks)
			})
			tasks.GET("/:id", func(c *gin.Context) {
				task, err := sched.GetTask(c.Request.Context(), c.Param("id"))
				if err != nil {
					c.JSON(http.StatusNotFound, gin.H{"error": "task not found"})
					return
				}
				c.JSON(http.StatusOK, task)
			})
			tasks.PUT("/:id", func(c *gin.Context) {
				var task models.Task
				if err := c.ShouldBindJSON(&task); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				task.ID = c.Param("id")
				if err := sched.UpdateTask(c.Request.Context(), &task); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, task)
			})
			tasks.DELETE("/:id", func(c *gin.Context) {
				if err := sched.DeleteTask(c.Request.Context(), c.Param("id")); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.Status(http.StatusNoContent)
			})
			tasks.POST("/:id/trigger", func(c *gin.Context) {
				if err := sched.TriggerTask(c.Request.Context(), c.Param("id")); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "task triggered"})
			})
			tasks.GET("/:id/runs", func(c *gin.Context) {
				runs, err := sched.GetRunHistory(c.Request.Context(), c.Param("id"), 20)
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, runs)
			})
		}

		schedulerConfig := api.Group("/scheduler/config")
		{
			schedulerConfig.GET("", func(c *gin.Context) {
				config := sched.GetConfig()
				c.JSON(http.StatusOK, gin.H{
					"config": config,
					"version": sched.GetConfigVersion(),
					"strategies": sched.ListStrategies(),
				})
			})
			schedulerConfig.PUT("", func(c *gin.Context) {
				var config scheduler.SchedulerConfig
				if err := c.ShouldBindJSON(&config); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				if err := sched.UpdateConfig(&config); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "config updated", "version": sched.GetConfigVersion()})
			})
			schedulerConfig.PATCH("", func(c *gin.Context) {
				var updates map[string]interface{}
				if err := c.ShouldBindJSON(&updates); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				if err := sched.UpdateConfigPartial(updates); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "config updated", "version": sched.GetConfigVersion()})
			})
		}

		slos := api.Group("/slos")
		{
			slos.POST("", func(c *gin.Context) {
				var slo models.SLO
				if err := c.ShouldBindJSON(&slo); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				if err := sloMonitor.CreateSLO(c.Request.Context(), &slo); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusCreated, slo)
			})
			slos.GET("", func(c *gin.Context) {
				service := c.Query("service")
				slos, err := sloMonitor.ListSLOs(c.Request.Context(), service)
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, slos)
			})
			slos.GET("/:id", func(c *gin.Context) {
				slo, err := sloMonitor.GetSLO(c.Request.Context(), c.Param("id"))
				if err != nil {
					c.JSON(http.StatusNotFound, gin.H{"error": "SLO not found"})
					return
				}
				c.JSON(http.StatusOK, slo)
			})
			slos.PUT("/:id", func(c *gin.Context) {
				var slo models.SLO
				if err := c.ShouldBindJSON(&slo); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				slo.ID = c.Param("id")
				if err := sloMonitor.UpdateSLO(c.Request.Context(), &slo); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, slo)
			})
			slos.DELETE("/:id", func(c *gin.Context) {
				if err := sloMonitor.DeleteSLO(c.Request.Context(), c.Param("id")); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.Status(http.StatusNoContent)
			})
			slos.POST("/sli", func(c *gin.Context) {
				var sli models.SLI
				if err := c.ShouldBindJSON(&sli); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				if err := sloMonitor.RecordSLI(c.Request.Context(), &sli); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusCreated, sli)
			})
			slos.GET("/:id/status", func(c *gin.Context) {
				status, err := sloMonitor.GetStatus(c.Request.Context(), c.Param("id"))
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, status)
			})
			slos.POST("/:id/reset-budget", func(c *gin.Context) {
				if err := sloMonitor.ResetErrorBudget(c.Request.Context(), c.Param("id")); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "budget reset"})
			})
			slos.GET("/check/burn-rates", func(c *gin.Context) {
				highBurns, err := sloMonitor.CheckHighBurnRates(c.Request.Context())
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, highBurns)
			})
		}

		sloStrategies := api.Group("/slo/strategies")
		{
			sloStrategies.GET("/burn-rate", func(c *gin.Context) {
				c.JSON(http.StatusOK, gin.H{
					"available": sloMonitor.ListBurnRateStrategies(),
					"current": sloMonitor.GetConfig().DefaultBurnRateStrategy,
				})
			})
			sloStrategies.PUT("/burn-rate/:name", func(c *gin.Context) {
				name := c.Param("name")
				if err := sloMonitor.SetBurnRateStrategy(name); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "burn rate strategy updated"})
			})
			sloStrategies.GET("/alerting", func(c *gin.Context) {
				c.JSON(http.StatusOK, gin.H{
					"available": sloMonitor.ListAlertingStrategies(),
					"current": sloMonitor.GetConfig().DefaultAlertingStrategy,
				})
			})
			sloStrategies.PUT("/alerting/:name", func(c *gin.Context) {
				name := c.Param("name")
				if err := sloMonitor.SetAlertingStrategy(name); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "alerting strategy updated"})
			})
		}

		files := api.Group("/files")
		{
			files.POST("", func(c *gin.Context) {
				file, err := c.FormFile("file")
				if err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				f, err := file.Open()
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				defer f.Close()
				content := make([]byte, file.Size)
				if _, err := f.Read(content); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}

				async := c.Query("async") == "true"
				ttl, _ := time.ParseDuration(c.DefaultQuery("ttl", "0"))
				storageClass := storage.StorageClass(c.DefaultQuery("storage_class", "standard"))

				if async {
					fileID, err := storageMgr.StoreFileAsync(c.Request.Context(), file.Filename, file.Header.Get("Content-Type"), content, ttl, storageClass, func(result storage.AsyncResult) {
						logger.Info("async store completed",
							zap.String("file_id", result.FileID),
							zap.Bool("success", result.Success),
						)
					})
					if err != nil {
						c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
						return
					}
					c.JSON(http.StatusAccepted, gin.H{"file_id": fileID, "status": "processing"})
					return
				}

				stored, err := storageMgr.StoreFile(c.Request.Context(), file.Filename, file.Header.Get("Content-Type"), content, ttl, storageClass)
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusCreated, stored)
			})
			files.GET("", func(c *gin.Context) {
				prefix := c.Query("prefix")
				offset := 0
				limit := 20
				files, total, err := storageMgr.ListFiles(c.Request.Context(), prefix, offset, limit)
				if err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"files": files, "total": total})
			})
			files.GET("/:id", func(c *gin.Context) {
				file, content, err := storageMgr.GetFile(c.Request.Context(), c.Param("id"))
				if err != nil {
					c.JSON(http.StatusNotFound, gin.H{"error": "file not found"})
					return
				}
				c.Data(http.StatusOK, file.ContentType, content)
			})
			files.DELETE("/:id", func(c *gin.Context) {
				async := c.Query("async") == "true"
				if async {
					opID, err := storageMgr.DeleteFileAsync(c.Request.Context(), c.Param("id"), func(result storage.AsyncResult) {
						logger.Info("async delete completed",
							zap.String("file_id", result.FileID),
							zap.Bool("success", result.Success),
						)
					})
					if err != nil {
						c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
						return
					}
					c.JSON(http.StatusAccepted, gin.H{"operation_id": opID, "status": "processing"})
					return
				}
				if err := storageMgr.DeleteFile(c.Request.Context(), c.Param("id")); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.Status(http.StatusNoContent)
			})
			files.PATCH("/:id/ttl", func(c *gin.Context) {
				var body struct {
					TTL string `json:"ttl"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				ttl, err := time.ParseDuration(body.TTL)
				if err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": "invalid TTL"})
					return
				}
				if err := storageMgr.UpdateTTL(c.Request.Context(), c.Param("id"), ttl); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "TTL updated"})
			})
			files.PATCH("/:id/storage-class", func(c *gin.Context) {
				var body struct {
					StorageClass string `json:"storage_class"`
				}
				if err := c.ShouldBindJSON(&body); err != nil {
					c.JSON(http.StatusBadRequest, gin.H{"error": err.Error()})
					return
				}
				if err := storageMgr.UpdateStorageClass(c.Request.Context(), c.Param("id"), storage.StorageClass(body.StorageClass)); err != nil {
					c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
					return
				}
				c.JSON(http.StatusOK, gin.H{"message": "storage class updated"})
			})
		}

		storageAPI := api.Group("/storage")
		{
			storageAPI.GET("/stats", func(c *gin.Context) {
				stats := storageMgr.GetStats()
				c.JSON(http.StatusOK, gin.H{
					"stats": stats,
					"queue_length": storageMgr.QueueLength(),
					"worker_count": storageMgr.WorkerCount(),
				})
			})
			storageAPI.POST("/gc", func(c *gin.Context) {
				async := c.Query("async") == "true"
				if async {
					opID, err := storageMgr.CollectExpiredAsync(func(result storage.AsyncResult) {
						if result.Success {
							expired := result.Result.([]string)
							logger.Info("async GC completed", zap.Int("expired", len(expired)))
						}
					})
					if err != nil {
						c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
						return
					}
					c.JSON(http.StatusAccepted, gin.H{"operation_id": opID, "status": "processing"})
					return
				}
				expired := storageMgr.CollectExpired()
				c.JSON(http.StatusOK, gin.H{"expired": expired})
			})
			storageAPI.POST("/transition", func(c *gin.Context) {
				async := c.Query("async") == "true"
				if async {
					opID, err := storageMgr.TransitionStorageClassesAsync(func(result storage.AsyncResult) {
						if result.Success {
							count := result.Result.(int)
							logger.Info("async transition completed", zap.Int("transitioned", count))
						}
					})
					if err != nil {
						c.JSON(http.StatusInternalServerError, gin.H{"error": err.Error()})
						return
					}
					c.JSON(http.StatusAccepted, gin.H{"operation_id": opID, "status": "processing"})
					return
				}
				count := storageMgr.TransitionStorageClasses()
				c.JSON(http.StatusOK, gin.H{"transitioned": count})
			})
		}
	}

	srv := &http.Server{
		Addr:    ":8080",
		Handler: r,
	}

	go func() {
		sched.Start()
	}()

	go func() {
		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			logger.Fatal("server failed", zap.Error(err))
		}
	}()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit
	logger.Info("shutdown signal received")

	sched.Stop()
	storageMgr.Stop()

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		logger.Fatal("server shutdown failed", zap.Error(err))
	}

	logger.Info("graceful shutdown completed")
}
