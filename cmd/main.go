package main

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/solocoder/session136/pkg/auditlog"
	"github.com/solocoder/session136/pkg/classification"
	"github.com/solocoder/session136/pkg/common/interfaces"
	"github.com/solocoder/session136/pkg/common/utils"
	"github.com/solocoder/session136/pkg/datamasking"
	"github.com/solocoder/session136/pkg/differentialprivacy"
	"github.com/solocoder/session136/pkg/federatedlearning"
	"github.com/solocoder/session136/pkg/monitoring"
	"github.com/solocoder/session136/pkg/notification"
	"github.com/solocoder/session136/pkg/scheduler"
	"github.com/solocoder/session136/pkg/storage"

	"go.uber.org/zap"
)

type App struct {
	classifier       *classification.DefaultClassifier
	privacyInjector  *differentialprivacy.DefaultPrivacyInjector
	monitor          *monitoring.DefaultMonitor
	scheduler        *scheduler.DefaultScheduler
	notifier         *notification.DefaultNotifier
	storageManager   *storage.DefaultStorageManager
	flCoordinator    *federatedlearning.DefaultFederatedCoordinator
	auditLogger      *auditlog.DefaultAuditLogger
	dataMasker       *datamasking.DefaultDataMasker
	logger           *zap.Logger
}

func NewApp() *App {
	utils.InitLogger()
	logger := utils.GetLogger()

	classifier := classification.NewDefaultClassifier()

	privacyConfig := &differentialprivacy.BudgetConfig{
		TotalEpsilon: 10.0,
		TotalDelta:   1e-5,
		ResetPeriod:  24 * time.Hour,
	}
	privacyInjector := differentialprivacy.NewDefaultPrivacyInjector(privacyConfig)

	monitor := monitoring.NewDefaultMonitorWithAsync(10000, 5*time.Minute, 3, 100)

	scheduler := scheduler.NewDefaultScheduler(5, 3)

	notifier := notification.NewDefaultNotifier()
	notifier.AddChannel(notification.NewConsoleChannel())

	storageAdapter := storage.NewInMemoryStorage()
	storageManager := storage.NewDefaultStorageManager(storageAdapter)

	flCoordinator := federatedlearning.NewDefaultFederatedCoordinator(federatedlearning.FedAvg)

	auditLogger := auditlog.NewDefaultAuditLogger()

	dataMasker := datamasking.NewDefaultDataMasker()

	return &App{
		classifier:      classifier,
		privacyInjector: privacyInjector,
		monitor:         monitor,
		scheduler:       scheduler,
		notifier:        notifier,
		storageManager:  storageManager,
		flCoordinator:   flCoordinator,
		auditLogger:     auditLogger,
		dataMasker:      dataMasker,
		logger:          logger,
	}
}

func (a *App) Run() {
	a.logger.Info("Application starting...")

	ctx := context.Background()
	ctx, cancel := context.WithCancel(ctx)
	defer cancel()

	a.monitor.StartAsyncProcessing(ctx)

	a.demoDynamicConfig(ctx)
	a.demoClassification(ctx)
	a.demoPrivacyStrategy(ctx)
	a.demoPrivacyInjection(ctx)
	a.demoAsyncMonitoring(ctx)
	a.demoMonitoring(ctx)
	a.demoScheduling(ctx)
	a.demoNotification(ctx)
	a.demoStorage(ctx)
	a.demoFederatedLearning(ctx)
	a.demoAuditLog(ctx)
	a.demoDataMasking(ctx)

	a.logger.Info("All demos completed successfully")

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGINT, syscall.SIGTERM)
	<-sigChan

	a.cleanup()
	a.logger.Info("Application shutdown complete")
}

func (a *App) demoDynamicConfig(ctx context.Context) {
	a.logger.Info("=== Dynamic Config Demo ===")

	scenarios := a.classifier.ListScenarios()
	a.logger.Info("Available scenarios", zap.Strings("scenarios", scenarios))

	activeScenario := a.classifier.GetActiveScenario()
	if activeScenario != nil {
		a.logger.Info("Current active scenario",
			zap.String("name", activeScenario.Name),
			zap.String("description", activeScenario.Description),
		)
	}

	if err := a.classifier.SwitchScenario("healthcare"); err != nil {
		a.logger.Error("Failed to switch scenario", zap.Error(err))
		return
	}

	a.logger.Info("Switched to healthcare scenario")

	a.classifier.OnScenarioChange(func(name string, scenario *classification.ClassificationScenario) {
		a.logger.Info("Scenario changed via callback",
			zap.String("name", name),
			zap.String("description", scenario.Description),
		)
	})

	if err := a.classifier.SwitchScenario("financial"); err != nil {
		a.logger.Error("Failed to switch scenario", zap.Error(err))
	}

	time.Sleep(100 * time.Millisecond)
}

func (a *App) demoPrivacyStrategy(ctx context.Context) {
	a.logger.Info("=== Privacy Strategy Demo ===")

	strategies := a.privacyInjector.ListStrategies()
	a.logger.Info("Available privacy strategies", zap.Strings("strategies", strategies))

	activeStrategy := a.privacyInjector.GetActiveStrategy()
	a.logger.Info("Current active strategy", zap.String("strategy", activeStrategy))

	a.privacyInjector.OnStrategyChange(func(oldStrategy, newStrategy string) {
		a.logger.Info("Privacy strategy changed",
			zap.String("old", oldStrategy),
			zap.String("new", newStrategy),
		)
	})

	if err := a.privacyInjector.SetPrivacyStrategy("strict"); err != nil {
		a.logger.Error("Failed to set strategy", zap.Error(err))
	}

	time.Sleep(100 * time.Millisecond)

	queryResult := &interfaces.QueryResult{
		Data: []map[string]interface{}{
			{"user_id": 1, "count": 100, "total": 5000.50},
			{"user_id": 2, "count": 150, "total": 7500.75},
		},
		NoiseType: "laplace",
		Epsilon:   1.0,
		Delta:     1e-5,
	}

	noisyResult, err := a.privacyInjector.InjectNoise(ctx, queryResult)
	if err != nil {
		a.logger.Error("Privacy injection with strict strategy failed", zap.Error(err))
		return
	}

	a.logger.Info("Noise injected with strict strategy",
		zap.Float64("epsilon_used", noisyResult.Epsilon),
		zap.String("noise_type", noisyResult.NoiseType),
	)

	if err := a.privacyInjector.SetPrivacyStrategy("adaptive"); err != nil {
		a.logger.Error("Failed to set adaptive strategy", zap.Error(err))
	}

	time.Sleep(100 * time.Millisecond)
	a.logger.Info("Switched to adaptive strategy")
}

func (a *App) demoAsyncMonitoring(ctx context.Context) {
	a.logger.Info("=== Async Monitoring Demo ===")

	a.monitor.Subscribe(monitoring.EventMetricRecorded, func(event *monitoring.MonitorEvent) {
		data := event.Data.(map[string]interface{})
		a.logger.Info("Event received: metric recorded",
			zap.String("metric", data["metric"].(string)),
			zap.Float64("value", data["value"].(float64)),
		)
	})

	for i := 0; i < 10; i++ {
		dimensions := map[string]string{
			"host":   fmt.Sprintf("async-node-%d", i%2),
			"region": "cn-east",
		}

		a.monitor.RecordMetricAsync(ctx, "async_request_latency", float64(50+i*10), dimensions, func(err error) {
			if err != nil {
				a.logger.Error("Async record failed", zap.Error(err))
			}
		})
	}

	time.Sleep(200 * time.Millisecond)

	now := time.Now().Unix()
	oneHourAgo := now - 3600

	a.monitor.AggregateAsync(ctx, "async_request_latency", "avg", oneHourAgo, now, func(result float64, err error) {
		if err != nil {
			a.logger.Error("Async aggregate failed", zap.Error(err))
			return
		}
		a.logger.Info("Async aggregation completed", zap.Float64("avg_latency", result))
	})

	time.Sleep(200 * time.Millisecond)

	queueSize := a.monitor.GetTaskQueue().Size()
	a.logger.Info("Task queue size", zap.Int("size", queueSize))
}

func (a *App) demoClassification(ctx context.Context) {
	a.logger.Info("=== Classification Demo ===")

	testData := []map[string]interface{}{
		{
			"id":       "user_001",
			"name":     "张三",
			"phone":    "13800138000",
			"id_card":  "110101199001011234",
			"email":    "zhangsan@example.com",
			"address":  "北京市朝阳区建国路88号",
			"bank_card": "6222021234567890123",
		},
		{
			"id":    "user_002",
			"name":  "李四",
			"phone": "13900139000",
			"email": "lisi@example.com",
		},
	}

	results, err := a.classifier.Scan(ctx, testData)
	if err != nil {
		a.logger.Error("Classification failed", zap.Error(err))
		return
	}

	for _, result := range results {
		a.logger.Info("Classification result",
			zap.String("data_id", result.DataID),
			zap.String("sensitivity", result.Sensitivity),
			zap.String("category", result.Category),
			zap.Int("level", result.Level),
			zap.String("policy", result.Policy),
			zap.Any("fields", result.Fields),
		)
	}
}

func (a *App) demoPrivacyInjection(ctx context.Context) {
	a.logger.Info("=== Privacy Injection Demo ===")

	queryResult := &interfaces.QueryResult{
		Data: []map[string]interface{}{
			{"user_id": 1, "count": 100, "total": 5000.50},
			{"user_id": 2, "count": 150, "total": 7500.75},
			{"user_id": 3, "count": 80, "total": 3200.25},
		},
		NoiseType: "laplace",
		Epsilon:   1.0,
		Delta:     1e-5,
	}

	noisyResult, err := a.privacyInjector.InjectNoise(ctx, queryResult)
	if err != nil {
		a.logger.Error("Privacy injection failed", zap.Error(err))
		return
	}

	a.logger.Info("Original vs Noisy data comparison")
	for i, row := range noisyResult.Data {
		a.logger.Info("Row",
			zap.Int("index", i),
			zap.Any("noisy_data", row),
		)
	}

	remaining := a.privacyInjector.GetRemainingBudget(ctx)
	a.logger.Info("Remaining privacy budget", zap.Float64("epsilon", remaining))
}

func (a *App) demoMonitoring(ctx context.Context) {
	a.logger.Info("=== Monitoring Demo ===")

	for i := 0; i < 100; i++ {
		dimensions := map[string]string{
			"host":   fmt.Sprintf("node-%d", i%3),
			"region": "cn-east",
		}
		a.monitor.RecordMetric(ctx, "request_latency", float64(50+i%100), dimensions)
		a.monitor.RecordMetric(ctx, "throughput", float64(1000+i*10), dimensions)
	}

	avgLatency, err := a.monitor.Aggregate(ctx, "request_latency", "avg", map[string]string{"region": "cn-east"})
	if err != nil {
		a.logger.Error("Aggregation failed", zap.Error(err))
		return
	}

	p99Latency, err := a.monitor.Aggregate(ctx, "request_latency", "p99", map[string]string{"region": "cn-east"})
	if err != nil {
		a.logger.Error("Aggregation failed", zap.Error(err))
		return
	}

	a.logger.Info("Metrics aggregated",
		zap.Float64("avg_latency", avgLatency),
		zap.Float64("p99_latency", p99Latency),
	)

	if err := a.monitor.Flush(ctx); err != nil {
		a.logger.Error("Flush failed", zap.Error(err))
	}
}

func (a *App) demoScheduling(ctx context.Context) {
	a.logger.Info("=== Scheduling Demo ===")

	a.scheduler.RegisterHandler("data_process", func(ctx context.Context, task *interfaces.Task) error {
		a.logger.Info("Processing task", zap.String("task_id", task.ID))
		time.Sleep(100 * time.Millisecond)
		return nil
	})

	a.scheduler.RegisterHandler("retryable_task", func(ctx context.Context, task *interfaces.Task) error {
		retryCount, _ := task.Payload.(int)
		if retryCount < 2 {
			return utils.NewRetryableError(fmt.Errorf("temporary failure, attempt %d", retryCount))
		}
		return nil
	})

	task1 := &interfaces.Task{
		Type:     "data_process",
		Payload:  "test data",
		Priority: 1,
		Timeout:  30,
	}

	taskID1, err := a.scheduler.SubmitTask(ctx, task1)
	if err != nil {
		a.logger.Error("Task submission failed", zap.Error(err))
		return
	}

	task2 := &interfaces.Task{
		Type:     "retryable_task",
		Payload:  0,
		Priority: 2,
		Timeout:  30,
	}

	taskID2, err := a.scheduler.SubmitTask(ctx, task2)
	if err != nil {
		a.logger.Error("Task submission failed", zap.Error(err))
		return
	}

	time.Sleep(2 * time.Second)

	status1, _ := a.scheduler.GetTaskStatus(ctx, taskID1)
	status2, _ := a.scheduler.GetTaskStatus(ctx, taskID2)

	a.logger.Info("Task status",
		zap.String("task_id", taskID1),
		zap.String("status", status1.Status),
	)
	a.logger.Info("Task status",
		zap.String("task_id", taskID2),
		zap.String("status", status2.Status),
	)
}

func (a *App) demoNotification(ctx context.Context) {
	a.logger.Info("=== Notification Demo ===")

	notification := &interfaces.Notification{
		TemplateID: "task_completed",
		Channel:    "console",
		Recipients: []string{"admin@example.com"},
		Data: map[string]interface{}{
			"TaskID":      "task_001",
			"Status":      "completed",
			"CompletedAt": time.Now().Format(time.RFC3339),
		},
	}

	if err := a.notifier.Send(ctx, notification); err != nil {
		a.logger.Error("Notification failed", zap.Error(err))
		return
	}

	rendered, err := a.notifier.RenderTemplate(ctx, "alert", map[string]interface{}{
		"Level":     "critical",
		"Message":   "High error rate detected",
		"Timestamp": time.Now().Format(time.RFC3339),
	})
	if err != nil {
		a.logger.Error("Template render failed", zap.Error(err))
		return
	}

	a.logger.Info("Rendered template", zap.String("content", rendered))
}

func (a *App) demoStorage(ctx context.Context) {
	a.logger.Info("=== Storage Demo ===")

	obj := &interfaces.StorageObject{
		Key: "documents/report_2026.pdf",
		Metadata: map[string]interface{}{
			"type":      "report",
			"department": "finance",
			"year":      2026,
			"author":    "张三",
		},
		Data: []byte("This is a sample report content"),
	}

	objID, err := a.storageManager.Store(ctx, obj)
	if err != nil {
		a.logger.Error("Storage failed", zap.Error(err))
		return
	}

	a.logger.Info("Object stored", zap.String("id", objID), zap.String("key", obj.Key))

	query := map[string]interface{}{
		"type":       "report",
		"department": "finance",
	}

	results, err := a.storageManager.SearchByMetadata(ctx, query)
	if err != nil {
		a.logger.Error("Search failed", zap.Error(err))
		return
	}

	a.logger.Info("Search results", zap.Int("count", len(results)))
	for _, r := range results {
		a.logger.Info("Found object", zap.String("id", r.ID), zap.Any("metadata", r.Metadata))
	}
}

func (a *App) demoFederatedLearning(ctx context.Context) {
	a.logger.Info("=== Federated Learning Demo ===")

	a.flCoordinator.RegisterClient("client_001", "192.168.1.101:8080", map[string]interface{}{"gpu": true}, 10000)
	a.flCoordinator.RegisterClient("client_002", "192.168.1.102:8080", map[string]interface{}{"gpu": false}, 8000)
	a.flCoordinator.RegisterClient("client_003", "192.168.1.103:8080", map[string]interface{}{"gpu": true}, 12000)

	initialWeights := []float64{0.1, 0.2, 0.3, 0.4, 0.5}
	a.flCoordinator.CreateModel("model_linear_reg", initialWeights)

	task := &interfaces.FLTask{
		TaskID:    "fl_task_001",
		ModelID:   "model_linear_reg",
		Config:    map[string]interface{}{"epochs": 10, "lr": 0.01},
		ClientIDs: []string{"client_001", "client_002", "client_003"},
	}

	if err := a.flCoordinator.DistributeTask(ctx, task); err != nil {
		a.logger.Error("FL task distribution failed", zap.Error(err))
		return
	}

	gradients := []*interfaces.Gradient{
		{ClientID: "client_001", TaskID: "fl_task_001", Weights: []float64{0.15, 0.25, 0.35, 0.45, 0.55}},
		{ClientID: "client_002", TaskID: "fl_task_001", Weights: []float64{0.12, 0.22, 0.32, 0.42, 0.52}},
		{ClientID: "client_003", TaskID: "fl_task_001", Weights: []float64{0.18, 0.28, 0.38, 0.48, 0.58}},
	}

	for _, g := range gradients {
		if err := a.flCoordinator.SubmitGradient(g); err != nil {
			a.logger.Error("Gradient submission failed", zap.Error(err))
		}
	}

	time.Sleep(1 * time.Second)

	model, err := a.flCoordinator.GetModel("model_linear_reg")
	if err != nil {
		a.logger.Error("Get model failed", zap.Error(err))
		return
	}

	a.logger.Info("Global model updated",
		zap.String("model_id", model.ModelID),
		zap.Int("version", model.Version),
		zap.Any("weights", model.Weights),
	)
}

func (a *App) demoAuditLog(ctx context.Context) {
	a.logger.Info("=== Audit Log Demo ===")

	actions := []struct {
		userID   string
		action   string
		resource string
		payload  string
	}{
		{"user_001", "login", "system", "Successful login"},
		{"user_001", "read", "database:users", "Query user table"},
		{"user_002", "update", "database:config", "Update system configuration"},
		{"user_001", "delete", "file:report.pdf", "Delete sensitive document"},
	}

	for _, act := range actions {
		entry := &interfaces.AuditEntry{
			UserID:   act.userID,
			Action:   act.action,
			Resource: act.resource,
			Payload:  act.payload,
		}
		if err := a.auditLogger.Log(ctx, entry); err != nil {
			a.logger.Error("Audit log failed", zap.Error(err))
		}
	}

	valid, err := a.auditLogger.VerifyIntegrity(ctx, 0, 3)
	if err != nil {
		a.logger.Error("Integrity verification failed", zap.Error(err))
		return
	}

	a.logger.Info("Audit chain integrity", zap.Bool("valid", valid))

	tampered, err := a.auditLogger.DetectTampering(ctx)
	if err != nil {
		a.logger.Error("Tampering detection failed", zap.Error(err))
		return
	}

	a.logger.Info("Tampering detection", zap.Int("tampered_count", len(tampered)))

	userLogs := a.auditLogger.GetEntriesByUser("user_001", 10)
	a.logger.Info("User audit logs", zap.String("user_id", "user_001"), zap.Int("count", len(userLogs)))
}

func (a *App) demoDataMasking(ctx context.Context) {
	a.logger.Info("=== Data Masking Demo ===")

	data := map[string]interface{}{
		"name":      "张三",
		"phone":     "13800138000",
		"id_card":   "110101199001011234",
		"email":     "zhangsan@example.com",
		"bank_card": "6222021234567890123",
		"address":   "北京市朝阳区建国路88号",
		"salary":    25680.50,
		"password":  "mysecretpassword",
		"note":      "This is a normal note",
	}

	roles := []string{"viewer"}
	maskedData, err := a.dataMasker.Mask(ctx, data, roles)
	if err != nil {
		a.logger.Error("Data masking failed", zap.Error(err))
		return
	}

	a.logger.Info("Masked data for viewer role")
	printJSON(maskedData)

	adminRoles := []string{"admin"}
	adminData, err := a.dataMasker.Mask(ctx, data, adminRoles)
	if err != nil {
		a.logger.Error("Data masking failed", zap.Error(err))
		return
	}

	a.logger.Info("Data for admin role")
	printJSON(adminData)

	operatorRoles := []string{"operator"}
	operatorData, err := a.dataMasker.Mask(ctx, data, operatorRoles)
	if err != nil {
		a.logger.Error("Data masking failed", zap.Error(err))
		return
	}

	a.logger.Info("Masked data for operator role")
	printJSON(operatorData)
}

func printJSON(data interface{}) {
	bytes, _ := json.MarshalIndent(data, "", "  ")
	fmt.Println(string(bytes))
}

func (a *App) cleanup() {
	a.scheduler.Stop()
	a.monitor.StopAsyncProcessing()
	a.monitor.Stop()
	utils.SyncLogger()
}

func main() {
	app := NewApp()
	app.Run()
}
