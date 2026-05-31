package main

import (
	"context"
	"encoding/json"
	"fmt"
	"log"
	"time"

	"gorm.io/driver/postgres"
	"gorm.io/gorm"
	"session172/internal/config"
	"session172/internal/dataaccess"
	"session172/internal/datquality"
	"session172/internal/gateway"
	applogger "session172/internal/logger"
	"session172/pkg/models"
)

func main() {
	applogger.Init(&applogger.Config{
		Level: "info",
		Debug: false,
	})
	defer applogger.Sync()

	dsn := "host=localhost user=postgres password=postgres dbname=test port=5432 sslmode=disable"
	db, err := gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		applogger.Fatalf("Failed to connect database: %v", err)
	}

	fmt.Println("=== Feature 1: Data Access Dynamic Configuration ===")
	demoDynamicConfig(db)

	fmt.Println("\n=== Feature 2: Data Quality Strategy Pluggable ===")
	demoQualityStrategy(db)

	fmt.Println("\n=== Feature 3: API Gateway Async Processing ===")
	demoAsyncGateway()
}

func demoDynamicConfig(db *gorm.DB) {
	dc := dataaccess.NewDynamicConfig()

	scenarios := []dataaccess.Scenario{
		dataaccess.ScenarioDefault,
		dataaccess.ScenarioHighLoad,
		dataaccess.ScenarioLowLatency,
		dataaccess.ScenarioBatch,
		dataaccess.ScenarioMaintenance,
	}

	for _, scenario := range scenarios {
		if err := dc.SetScenario(scenario); err != nil {
			applogger.Errorf("Failed to set scenario %s: %v", scenario, err)
			continue
		}

		cfg := dc.GetActiveConfig()
		configJSON, _ := json.MarshalIndent(cfg, "", "  ")
		fmt.Printf("Scenario: %s\n%s\n", scenario, configJSON)
	}

	dc.AddChangeListener(func(old, new *dataaccess.PoolConfig) {
		fmt.Printf("Config changed: max_conn %d -> %d\n", old.MaxOpenConns, new.MaxOpenConns)
	})

	if err := dc.SetScenario(dataaccess.ScenarioHighLoad); err != nil {
		applogger.Errorf("Failed to set high load scenario: %v", err)
	}

	stats := map[string]interface{}{
		"active_connections": 45,
		"queue_length":       15,
		"avg_wait_time_ms":   800,
	}
	if err := dc.AutoDetectAndSwitch(stats); err != nil {
		applogger.Warnf("Auto detect switch: %v", err)
	}
	fmt.Printf("After auto-detect, scenario: %s\n", dc.GetCurrentScenario())
}

func demoQualityStrategy(db *gorm.DB) {
	sm := datquality.GetStrategyManager()
	engine := datquality.NewRuleEngine()

	rule := &models.DataQualityRule{
		ID:          "rule-001",
		Name:        "null_check",
		RuleType:    "null_check",
		TableName:   "users",
		ColumnName:  "email",
		Enabled:     true,
		CheckPeriod: "daily",
	}
	engine.AddRule(rule)

	strategies := []datquality.StrategyType{
		datquality.StrategyStrict,
		datquality.StrategyStandard,
		datquality.StrategyRelaxed,
	}

	for _, strategy := range strategies {
		if err := sm.SetStrategy(strategy); err != nil {
			applogger.Errorf("Failed to set strategy %s: %v", strategy, err)
			continue
		}

		result, err := sm.ExecuteWithStrategy(context.Background(), engine, "rule-001", db)
		if err != nil {
			fmt.Printf("Strategy %s error: %v\n", strategy, err)
			continue
		}

		resultJSON, _ := json.MarshalIndent(result, "", "  ")
		fmt.Printf("Strategy: %s\n%s\n", strategy, resultJSON)
	}

	if err := sm.SetRuleStrategy("rule-001", datquality.StrategyStrict); err != nil {
		applogger.Errorf("Failed to set rule strategy: %v", err)
	}

	customStrategy := &datquality.CustomStrategy{
		PreExecuteFunc: func(ctx context.Context, rule *models.DataQualityRule) error {
			fmt.Printf("Custom pre-execute: %s\n", rule.ID)
			return nil
		},
		PostExecuteFunc: func(ctx context.Context, result *models.QualityCheckResult) error {
			fmt.Printf("Custom post-execute: %s\n", result.Status)
			return nil
		},
	}
	if err := sm.RegisterStrategy(datquality.StrategyCustom, customStrategy); err != nil {
		applogger.Warnf("Register custom strategy: %v", err)
	}

	fmt.Printf("Available strategies: %v\n", sm.GetAvailableStrategies())
}

func demoAsyncGateway() {
	am := gateway.GetAsyncManager()

	am.AddEventHandler(func(event gateway.AsyncEvent) {
		fmt.Printf("[Event] %s - Request: %s\n", event.EventType, event.RequestID)
	})

	slowHandler := func(ctx context.Context, request map[string]interface{}) (interface{}, error) {
		fmt.Printf("Processing request: %v\n", request)
		time.Sleep(2 * time.Second)

		if val, ok := request["fail"]; ok && val == true {
			return nil, fmt.Errorf("simulated failure")
		}

		return map[string]interface{}{
			"status":  "success",
			"data":    "processed data",
			"request": request,
		}, nil
	}

	fmt.Println("Submitting async requests...")

	testPayload1 := map[string]interface{}{"action": "process", "id": 1}
	testPayload2 := map[string]interface{}{"action": "process", "id": 2, "fail": true}

	resp1 := am.SubmitAsyncPayload(
		"trace-001",
		"/api/test",
		"POST",
		testPayload1,
		"",
		slowHandler,
	)
	fmt.Printf("Request 1 submitted: ID=%s, Status=%s\n", resp1.RequestID, resp1.Status)

	resp2 := am.SubmitAsyncPayload(
		"trace-002",
		"/api/test",
		"POST",
		testPayload2,
		"",
		slowHandler,
	)
	fmt.Printf("Request 2 submitted: ID=%s, Status=%s\n", resp2.RequestID, resp2.Status)

	fmt.Println("Requests submitted, waiting for completion...")
	time.Sleep(3 * time.Second)

	result1, exists1 := am.GetResult(resp1.RequestID)
	if exists1 {
		result1JSON, _ := json.MarshalIndent(result1, "", "  ")
		fmt.Printf("Result 1:\n%s\n", result1JSON)
	}

	result2, exists2 := am.GetResult(resp2.RequestID)
	if exists2 {
		result2JSON, _ := json.MarshalIndent(result2, "", "  ")
		fmt.Printf("Result 2:\n%s\n", result2JSON)
	}

	stats := am.GetStats()
	statsJSON, _ := json.MarshalIndent(stats, "", "  ")
	fmt.Printf("Async manager stats:\n%s\n", statsJSON)
}
