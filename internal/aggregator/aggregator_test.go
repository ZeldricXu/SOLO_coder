package aggregator

import (
	"context"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/storage"
	"github.com/datateam/loganalyzer/internal/testdata"
)

func TestAggregator_SameServiceMultipleAlerts_AggregatedIntoSingleIncident(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name", "alert_type", "error_code"},
		TimeWindow:           5 * time.Minute,
		SuppressLowerPriority: true,
		MaxIncidentSize:      50,
		DedupKeyTemplate:     "{{.AlertType}}-{{.ServiceName}}-{{.ErrorCode}}",
	}

	alertInput := make(chan *models.Alert, 100)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	now := time.Now()
	alerts := make([]*models.Alert, 5)
	for i := 0; i < 5; i++ {
		alerts[i] = testdata.NewAlert(
			testdata.WithAlertType(models.AlertTypeErrorRate),
			testdata.WithAlertSeverity(models.SeverityHigh),
			testdata.WithAlertServiceName("order-service"),
			testdata.WithAlertErrorCode("DB_TIMEOUT"),
		)
		alerts[i].Timestamp = now.Add(time.Duration(i) * time.Second)
		alertInput <- alerts[i]
	}

	time.Sleep(1 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.Equal(t, 1, len(incidents), "Should aggregate all alerts into one incident")
	if len(incidents) > 0 {
		assert.Equal(t, 5, len(incidents[0].Alerts), "Incident should contain 5 alerts")
		assert.Equal(t, models.SeverityHigh, incidents[0].Severity)
		assert.GreaterOrEqual(t, len(incidents[0].ServiceNames), 1)
		assert.Equal(t, "order-service", incidents[0].ServiceNames[0])
	}
}

func TestAggregator_ConcurrentDeduplication_NoDuplicateIncidents(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name", "alert_type"},
		TimeWindow:           5 * time.Minute,
		SuppressLowerPriority: false,
		MaxIncidentSize:      100,
		DedupKeyTemplate:     "{{.AlertType}}-{{.ServiceName}}",
	}

	alertInput := make(chan *models.Alert, 1000)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	const numGoroutines = 10
	const alertsPerGoroutine = 20
	var wg sync.WaitGroup

	for g := 0; g < numGoroutines; g++ {
		wg.Add(1)
		go func(goroutineID int) {
			defer wg.Done()
			for i := 0; i < alertsPerGoroutine; i++ {
				alert := testdata.NewAlert(
					testdata.WithAlertType(models.AlertTypeErrorRate),
					testdata.WithAlertSeverity(models.SeverityHigh),
					testdata.WithAlertServiceName("concurrent-service"),
				)
				alert.Timestamp = time.Now()
				alertInput <- alert
			}
		}(g)
	}

	wg.Wait()
	time.Sleep(1 * time.Second)
	cancel()

	incidentCount := 0
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidentCount++
				assert.GreaterOrEqual(t, len(incident.ServiceNames), 1)
				assert.Equal(t, "concurrent-service", incident.ServiceNames[0])
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.LessOrEqual(t, incidentCount, numGoroutines, "Should have at most one incident per goroutine due to dedup")
}

func TestAggregator_SeverityBasedSuppression(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name"},
		TimeWindow:           5 * time.Minute,
		SuppressLowerPriority: true,
		MaxIncidentSize:      50,
		DedupKeyTemplate:     "{{.ServiceName}}",
	}

	alertInput := make(chan *models.Alert, 100)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	criticalAlert := testdata.NewAlert(
		testdata.WithAlertType(models.AlertTypeErrorRate),
		testdata.WithAlertSeverity(models.SeverityCritical),
		testdata.WithAlertServiceName("payment-service"),
	)
	alertInput <- criticalAlert

	time.Sleep(100 * time.Millisecond)

	lowAlerts := make([]*models.Alert, 3)
	for i := 0; i < 3; i++ {
		lowAlerts[i] = testdata.NewAlert(
			testdata.WithAlertType(models.AlertTypeErrorRate),
			testdata.WithAlertSeverity(models.SeverityLow),
			testdata.WithAlertServiceName("payment-service"),
		)
		alertInput <- lowAlerts[i]
	}

	time.Sleep(1 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.GreaterOrEqual(t, len(incidents), 1, "Should have at least one incident")
	if len(incidents) > 0 {
		assert.Equal(t, models.SeverityCritical, incidents[0].Severity, "Incident severity should be the highest severity")
	}
}

func TestAggregator_DifferentServices_IndependentIncidents(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name", "alert_type"},
		TimeWindow:           5 * time.Minute,
		SuppressLowerPriority: false,
		MaxIncidentSize:      50,
		DedupKeyTemplate:     "{{.ServiceName}}-{{.AlertType}}",
	}

	alertInput := make(chan *models.Alert, 100)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	services := []string{"user-service", "order-service", "payment-service", "notification-service"}
	now := time.Now()

	for i, service := range services {
		for j := 0; j < 3; j++ {
			alert := testdata.NewAlert(
				testdata.WithAlertType(models.AlertTypeErrorRate),
				testdata.WithAlertSeverity(models.Severity(models.SeverityHigh)),
				testdata.WithAlertServiceName(service),
			)
			alert.Timestamp = now.Add(time.Duration(i*3+j) * time.Second)
			alertInput <- alert
		}
	}

	time.Sleep(1 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.Equal(t, 4, len(incidents), "Should have one incident per service")

	serviceIncidents := make(map[string]int)
	for _, inc := range incidents {
		assert.GreaterOrEqual(t, len(inc.ServiceNames), 1)
		serviceIncidents[inc.ServiceNames[0]]++
		assert.Equal(t, 3, len(inc.Alerts), "Each incident should contain 3 alerts")
	}

	for _, service := range services {
		assert.Equal(t, 1, serviceIncidents[service], fmt.Sprintf("Service %s should have exactly one incident", service))
	}
}

func TestAggregator_MaxIncidentSize_LimitReached(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name"},
		TimeWindow:           5 * time.Minute,
		SuppressLowerPriority: false,
		MaxIncidentSize:      5,
		DedupKeyTemplate:     "{{.ServiceName}}",
	}

	alertInput := make(chan *models.Alert, 100)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	for i := 0; i < 10; i++ {
		alert := testdata.NewAlert(
			testdata.WithAlertType(models.AlertTypeErrorRate),
			testdata.WithAlertSeverity(models.SeverityHigh),
			testdata.WithAlertServiceName("max-size-test"),
		)
		alertInput <- alert
	}

	time.Sleep(1 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.GreaterOrEqual(t, len(incidents), 1, "Should have at least one incident")
	if len(incidents) > 0 {
		assert.LessOrEqual(t, len(incidents[0].Alerts), 5, "Incident should not exceed max size")
	}
}

func TestAggregator_IncidentSeverityEscalation(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name"},
		TimeWindow:           5 * time.Minute,
		SuppressLowerPriority: false,
		MaxIncidentSize:      50,
		DedupKeyTemplate:     "{{.ServiceName}}",
	}

	alertInput := make(chan *models.Alert, 100)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	lowAlert := testdata.NewAlert(
		testdata.WithAlertType(models.AlertTypeErrorRate),
		testdata.WithAlertSeverity(models.SeverityLow),
		testdata.WithAlertServiceName("escalation-test"),
	)
	alertInput <- lowAlert

	time.Sleep(100 * time.Millisecond)

	mediumAlert := testdata.NewAlert(
		testdata.WithAlertType(models.AlertTypeErrorRate),
		testdata.WithAlertSeverity(models.SeverityMedium),
		testdata.WithAlertServiceName("escalation-test"),
	)
	alertInput <- mediumAlert

	time.Sleep(100 * time.Millisecond)

	criticalAlert := testdata.NewAlert(
		testdata.WithAlertType(models.AlertTypeErrorRate),
		testdata.WithAlertSeverity(models.SeverityCritical),
		testdata.WithAlertServiceName("escalation-test"),
	)
	alertInput <- criticalAlert

	time.Sleep(1 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.GreaterOrEqual(t, len(incidents), 1, "Should have at least one incident")
	if len(incidents) > 0 {
		assert.Equal(t, models.SeverityCritical, incidents[0].Severity, "Severity should escalate to critical")
		assert.Equal(t, 3, len(incidents[0].Alerts), "Should contain all 3 alerts")
	}
}

func TestAggregator_Disabled_PassthroughMode(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled: false,
	}

	alertInput := make(chan *models.Alert, 10)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	err = aggregator.Start(ctx)
	require.NoError(t, err)
	defer aggregator.Stop()

	for i := 0; i < 5; i++ {
		alert := testdata.NewAlert(
			testdata.WithAlertType(models.AlertTypeErrorRate),
			testdata.WithAlertSeverity(models.SeverityHigh),
			testdata.WithAlertServiceName("passthrough-test"),
		)
		alertInput <- alert
	}

	time.Sleep(1 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(500 * time.Millisecond)
collectIncidents:
	for {
		select {
		case incident := <-aggregator.Incidents():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	assert.Equal(t, 5, len(incidents), "Should pass through all alerts as individual incidents when disabled")
}

func TestAggregator_GenerateDedupKey(t *testing.T) {
	mockRedis := storage.NewMockRedisClient()

	cfg := config.AggregationConfig{
		Enabled:          true,
		DedupKeyTemplate: "{{.AlertType}}-{{.ServiceName}}-{{.ErrorCode}}",
	}

	alertInput := make(chan *models.Alert, 10)
	eventChainInput := make(chan *models.EventChain, 10)

	aggregator, err := NewAggregator(cfg, mockRedis, alertInput, eventChainInput)
	require.NoError(t, err)

	testCases := []struct {
		alert      *models.Alert
		expectedKey string
	}{
		{
			alert: testdata.NewAlert(
				testdata.WithAlertType(models.AlertTypeErrorRate),
				testdata.WithAlertServiceName("user-service"),
				testdata.WithAlertErrorCode("500"),
			),
			expectedKey: "ERROR_RATE_SPIKE:user-service:500",
		},
		{
			alert: testdata.NewAlert(
				testdata.WithAlertType(models.AlertTypeP99Latency),
				testdata.WithAlertServiceName("order-service"),
				testdata.WithAlertErrorCode(""),
			),
			expectedKey: "P99_LATENCY_SPIKE:order-service",
		},
	}

	for _, tc := range testCases {
		t.Run(fmt.Sprintf("dedupkey-%s", tc.expectedKey), func(t *testing.T) {
			key := aggregator.generateDedupKey(tc.alert)
			assert.Equal(t, tc.expectedKey, key)
		})
	}
}
