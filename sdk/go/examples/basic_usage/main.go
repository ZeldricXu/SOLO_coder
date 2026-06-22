package main

import (
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/featureflag/sdk"
)

func main() {
	opts := &featureflag.SDKOptions{
		ServerURL:           "http://localhost:8080",
		AppKey:              "your-app-key",
		AppSecret:           "your-app-secret",
		PollInterval:        30 * time.Second,
		LongPollTimeout:     60 * time.Second,
		CacheType:           "memory",
		CacheTTL:            5 * time.Minute,
		MaxCacheSize:        10000,
		CircuitBreakerThreshold: 5,
		CircuitBreakerTimeout: 30 * time.Second,
		FallbackEnabled:     true,
		StatsEnabled:        true,
		StatsReportInterval: 60 * time.Second,
		ServiceName:         "order-service",
		SDKVersion:          "1.0.0",
	}

	client, err := featureflag.NewClient(opts)
	if err != nil {
		log.Fatalf("Failed to create feature flag client: %v", err)
	}
	defer client.Close()

	fmt.Println("Feature Flag SDK initialized successfully")
	fmt.Printf("Current config version: %d\n", client.GetVersion())

	ctx := featureflag.NewContextBuilder().
		WithUserID("user-12345").
		WithDepartment("engineering").
		WithTags("vip", "beta-tester").
		WithEnvironment("production").
		WithTenantID("tenant-001").
		WithAttribute("region", "cn").
		Build()

	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)

	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for {
		select {
		case <-ticker.C:
			demoEvaluation(client, ctx)
		case <-quit:
			fmt.Println("\nShutting down...")
			return
		}
	}
}

func demoEvaluation(client *featureflag.Client, ctx *featureflag.EvaluationContext) {
	fmt.Println("\n=== Feature Flag Evaluation Demo ===")

	newCheckoutEnabled := client.IsEnabled("new_checkout_flow", ctx)
	fmt.Printf("new_checkout_flow: %v\n", newCheckoutEnabled)

	if newCheckoutEnabled {
		fmt.Println("  -> Using new checkout flow!")
	} else {
		fmt.Println("  -> Using legacy checkout flow")
	}

	darkMode := client.GetBoolean("dark_mode", ctx, false)
	fmt.Printf("dark_mode: %v\n", darkMode)

	buttonColor := client.GetString("button_color", ctx, "blue")
	fmt.Printf("button_color: %s\n", buttonColor)

	discountPercent := client.GetInt("discount_percent", ctx, 0)
	fmt.Printf("discount_percent: %d%%\n", discountPercent)

	maxRetry := client.GetInt("max_retry_attempts", ctx, 3)
	fmt.Printf("max_retry_attempts: %d\n", maxRetry)

	timeoutMs := client.GetFloat64("request_timeout_ms", ctx, 5000.0)
	fmt.Printf("request_timeout_ms: %.1f\n", timeoutMs)

	result := client.Evaluate("premium_features", ctx)
	fmt.Printf("premium_features: enabled=%v, matched=%v, reason=%s\n",
		result.Enabled, result.Matched, result.Reason)

	if result.MatchedStrategy != nil {
		fmt.Printf("  -> Matched strategy: %s\n", result.MatchedStrategy.ID)
	}

	allSwitches := client.GetAllSwitches()
	fmt.Printf("\nTotal switches in cache: %d\n", len(allSwitches))
	for key, sw := range allSwitches {
		fmt.Printf("  - %s (type: %s, enabled: %v)\n", key, sw.Type, sw.Enabled)
	}

	stats := client.GetStats()
	fmt.Printf("\nSDK Stats: %+v\n", stats)
}
