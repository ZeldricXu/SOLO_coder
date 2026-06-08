package integration_test

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/elastic/go-elasticsearch/v8"
	"github.com/elastic/go-elasticsearch/v8/esapi"
	"github.com/google/uuid"
	"github.com/segmentio/kafka-go"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
	tc "github.com/testcontainers/testcontainers-go"
	"github.com/testcontainers/testcontainers-go/wait"

	"github.com/datateam/loganalyzer/internal/aggregator"
	"github.com/datateam/loganalyzer/internal/collector"
	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/detector"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/pipeline"
	"github.com/datateam/loganalyzer/internal/storage"
	"github.com/datateam/loganalyzer/internal/testdata"
)

type TestInfrastructure struct {
	ctx         context.Context
	esContainer tc.Container
	esURL       string
	kafkaContainer tc.Container
	kafkaBrokers []string
	redisContainer tc.Container
	redisURL    string
}

func SetupTestInfrastructure(t *testing.T) *TestInfrastructure {
	t.Helper()
	ctx := context.Background()

	infra := &TestInfrastructure{
		ctx: ctx,
	}

	var wg sync.WaitGroup
	var errCh = make(chan error, 4)

	wg.Add(1)
	go func() {
		defer wg.Done()
		esC, err := tc.GenericContainer(ctx, tc.GenericContainerRequest{
			ContainerRequest: tc.ContainerRequest{
				Image:        "docker.elastic.co/elasticsearch/elasticsearch:8.11.3",
				ExposedPorts: []string{"9200/tcp"},
				Env: map[string]string{
					"discovery.type":         "single-node",
					"xpack.security.enabled": "false",
					"ES_JAVA_OPTS":           "-Xms512m -Xmx512m",
				},
				WaitingFor: wait.ForListeningPort("9200/tcp").WithStartupTimeout(2 * time.Minute),
			},
			Started: true,
		})
		if err != nil {
			errCh <- fmt.Errorf("elasticsearch: %w", err)
			return
		}
		infra.esContainer = esC
		host, err := esC.Host(ctx)
		if err != nil {
			errCh <- err
			return
		}
		port, err := esC.MappedPort(ctx, "9200")
		if err != nil {
			errCh <- err
			return
		}
		infra.esURL = fmt.Sprintf("http://%s:%s", host, port.Port())
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		redisC, err := tc.GenericContainer(ctx, tc.GenericContainerRequest{
			ContainerRequest: tc.ContainerRequest{
				Image:        "redis:7-alpine",
				ExposedPorts: []string{"6379/tcp"},
				WaitingFor:   wait.ForListeningPort("6379/tcp").WithStartupTimeout(60 * time.Second),
			},
			Started: true,
		})
		if err != nil {
			errCh <- fmt.Errorf("redis: %w", err)
			return
		}
		infra.redisContainer = redisC
		host, err := redisC.Host(ctx)
		if err != nil {
			errCh <- err
			return
		}
		port, err := redisC.MappedPort(ctx, "6379")
		if err != nil {
			errCh <- err
			return
		}
		infra.redisURL = fmt.Sprintf("%s:%s", host, port.Port())
	}()

	wg.Add(1)
	go func() {
		defer wg.Done()
		kafkaC, err := tc.GenericContainer(ctx, tc.GenericContainerRequest{
			ContainerRequest: tc.ContainerRequest{
				Image:        "confluentinc/cp-kafka:7.5.3",
				ExposedPorts: []string{"9092/tcp", "9093/tcp"},
				Env: map[string]string{
					"KAFKA_NODE_ID":                        "1",
					"KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT",
					"KAFKA_ADVERTISED_LISTENERS":           "INTERNAL://localhost:9092,EXTERNAL://localhost:9093",
					"KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1",
					"KAFKA_TRANSACTION_STATE_LOG_MIN_ISR":  "1",
					"KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR": "1",
				},
				WaitingFor: wait.ForListeningPort("9093/tcp").WithStartupTimeout(2 * time.Minute),
			},
			Started: true,
		})
		if err != nil {
			errCh <- fmt.Errorf("kafka: %w", err)
			return
		}
		infra.kafkaContainer = kafkaC
		host, err := kafkaC.Host(ctx)
		if err != nil {
			errCh <- err
			return
		}
		port, err := kafkaC.MappedPort(ctx, "9093")
		if err != nil {
			errCh <- err
			return
		}
		infra.kafkaBrokers = []string{fmt.Sprintf("%s:%s", host, port.Port())}
	}()

	wg.Wait()
	close(errCh)

	for err := range errCh {
		if err != nil {
			infra.Teardown(t)
			t.Fatalf("Failed to setup infrastructure: %v", err)
		}
	}

	t.Logf("ES URL: %s", infra.esURL)
	t.Logf("Redis URL: %s", infra.redisURL)
	t.Logf("Kafka brokers: %v", infra.kafkaBrokers)

	time.Sleep(10 * time.Second)

	return infra
}

func (infra *TestInfrastructure) Teardown(t *testing.T) {
	t.Helper()
	if infra.esContainer != nil {
		if err := infra.esContainer.Terminate(infra.ctx); err != nil {
			t.Logf("Failed to terminate ES container: %v", err)
		}
	}
	if infra.redisContainer != nil {
		if err := infra.redisContainer.Terminate(infra.ctx); err != nil {
			t.Logf("Failed to terminate Redis container: %v", err)
		}
	}
	if infra.kafkaContainer != nil {
		if err := infra.kafkaContainer.Terminate(infra.ctx); err != nil {
			t.Logf("Failed to terminate Kafka container: %v", err)
		}
	}
}

func TestEndToEndPipeline_FullFlow(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test in short mode")
	}

	infra := SetupTestInfrastructure(t)
	defer infra.Teardown(t)

	indexName := fmt.Sprintf("test-logs-%s", uuid.New().String()[:8])

	esCfg := elasticsearch.Config{
		Addresses: []string{infra.esURL},
	}
	esClient, err := elasticsearch.NewClient(esCfg)
	require.NoError(t, err)

	createIndex(t, esClient, indexName)
	docCount := 100
	anomalyStartIdx := 70
	events := writeTestDataToES(t, esClient, indexName, docCount, anomalyStartIdx)
	refreshIndex(t, esClient, indexName)

	redisCfg := config.RedisConfig{
		Address:  infra.redisURL,
		Password: "",
		DB:       0,
	}
	redisClient, err := storage.NewRedisClient(redisCfg)
	require.NoError(t, err)

	collectorCfg := config.CollectorConfig{
		Enabled: true,
		Elasticsearch: config.ESCollectorConfig{
			Enabled:    true,
			Addresses:  []string{infra.esURL},
			Index:      indexName,
			Query:      `{"match_all": {}}`,
			ScrollSize: 10,
			Interval:   1 * time.Second,
			BatchSize:  10,
		},
	}

	esCollector, err := collector.NewElasticsearchCollector(collectorCfg.Elasticsearch)
	require.NoError(t, err)

	processorCfg := config.PipelineConfig{
		Enabled: true,
		Rules: []config.PipelineRule{
			{
				ID:       "parse-nginx",
				Name:     "Parse Nginx Logs",
				Type:     "parse",
				Enabled:  true,
				Pattern:  `(?P<status_code>\d{3})`,
				SourceField: "message",
			},
		},
		WorkerCount: 2,
		BufferSize:  100,
	}

	processor, err := pipeline.NewProcessor(processorCfg)
	require.NoError(t, err)

	detectorCfg := config.DetectionConfig{
		Enabled: true,
		Rules: []config.DetectionRule{
			{
				ID:           "error-rate-spike",
				Name:         "Error Rate Spike Detection",
				Enabled:      true,
				MetricType:   "error_rate",
				Algorithm:    "zscore",
				WindowSize:   1 * time.Minute,
				Threshold:    2.0,
				MinDataPoints: 5,
			},
		},
		Interval: 2 * time.Second,
	}

	detectEngine, err := detector.NewDetector(detectorCfg, redisClient)
	require.NoError(t, err)

	aggregatorCfg := config.AggregationConfig{
		Enabled:          true,
		GroupByFields:    []string{"service_name", "alert_type"},
		TimeWindow:       5 * time.Minute,
		MaxIncidentSize:  50,
		DedupKeyTemplate: "{{.AlertType}}-{{.ServiceName}}",
	}

	alertInput := make(chan *models.Alert, 100)
	eventChainInput := make(chan *models.EventChain, 10)
	aggregatorEngine, err := aggregator.NewAggregator(aggregatorCfg, redisClient, alertInput, eventChainInput)
	require.NoError(t, err)

	webhookReceived := make(chan *models.Incident, 10)
	webhookServer := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var incident models.Incident
		if err := json.NewDecoder(r.Body).Decode(&incident); err == nil {
			webhookReceived <- &incident
		}
		w.WriteHeader(http.StatusOK)
	}))
	defer webhookServer.Close()

	ctx, cancel := context.WithTimeout(infra.ctx, 60*time.Second)
	defer cancel()

	err = processor.Start(ctx)
	require.NoError(t, err)
	defer processor.Stop()

	err = detectEngine.Start(ctx)
	require.NoError(t, err)
	defer detectEngine.Stop()

	err = aggregatorEngine.Start(ctx)
	require.NoError(t, err)
	defer aggregatorEngine.Stop()

	err = esCollector.Start(ctx)
	require.NoError(t, err)
	defer esCollector.Stop()

	go func() {
		for event := range esCollector.Output() {
			select {
			case processor.Input() <- event:
			case <-ctx.Done():
				return
			}
		}
	}()

	go func() {
		for event := range processor.Output() {
			select {
			case detectEngine.Input() <- event:
			case <-ctx.Done():
				return
			}
		}
	}()

	go func() {
		for alert := range detectEngine.Output() {
			select {
			case alertInput <- alert:
			case <-ctx.Done():
				return
			}
		}
	}()

	go func() {
		for incident := range aggregatorEngine.Output() {
			if incident == nil {
				continue
			}
			payload, err := json.Marshal(incident)
			if err != nil {
				t.Logf("Failed to marshal incident: %v", err)
				continue
			}
			_, err = http.Post(webhookServer.URL, "application/json", bytes.NewReader(payload))
			if err != nil {
				t.Logf("Failed to send webhook: %v", err)
			}
		}
	}()

	processedCount := int32(0)
	alertCount := int32(0)
	incidentCount := int32(0)

	timeout := time.After(50 * time.Second)
	ticker := time.NewTicker(1 * time.Second)
	defer ticker.Stop()

loop:
	for {
		select {
		case <-ticker.C:
			t.Logf("Processed: %d, Alerts: %d, Incidents: %d",
				atomic.LoadInt32(&processedCount),
				atomic.LoadInt32(&alertCount),
				atomic.LoadInt32(&incidentCount))

			select {
			case incident := <-webhookReceived:
				atomic.AddInt32(&incidentCount, 1)
				t.Logf("Received incident via webhook: %s, severity: %s, alerts: %d",
					incident.ID, incident.Severity, incident.AlertCount)

				assert.NotEmpty(t, incident.ID)
				assert.NotEmpty(t, incident.ServiceName)
				assert.NotEmpty(t, incident.Severity)
				assert.Greater(t, incident.AlertCount, 0)
				assert.NotNil(t, incident.Alerts)
				assert.NotNil(t, incident.CreatedAt)

				if atomic.LoadInt32(&incidentCount) >= 1 {
					cancel()
				}
			default:
			}

		case <-ctx.Done():
			break loop
		case <-timeout:
			t.Log("Timeout waiting for webhook")
			break loop
		}
	}

	assert.GreaterOrEqual(t, atomic.LoadInt32(&incidentCount), int32(1),
		"Should receive at least one incident via webhook")

	t.Logf("Final stats - Events: %d, Alerts: %d, Incidents: %d",
		docCount, atomic.LoadInt32(&alertCount), atomic.LoadInt32(&incidentCount))
}

func TestCollector_ESUnavailable_DegradesGracefully(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test in short mode")
	}

	infra := SetupTestInfrastructure(t)
	defer infra.Teardown(t)

	badESURL := "http://localhost:19999"
	indexName := "test-unavailable"

	collectorCfg := config.ESCollectorConfig{
		Enabled:     true,
		Addresses:   []string{badESURL},
		Index:       indexName,
		Query:       `{"match_all": {}}`,
		ScrollSize:  10,
		Interval:    1 * time.Second,
		BatchSize:   10,
		MaxRetries:  2,
		RetryDelay:  1 * time.Second,
	}

	esCollector, err := collector.NewElasticsearchCollector(collectorCfg)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(infra.ctx, 15*time.Second)
	defer cancel()

	err = esCollector.Start(ctx)
	require.NoError(t, err)
	defer esCollector.Stop()

	eventsReceived := 0
	timeout := time.After(10 * time.Second)

loop:
	for {
		select {
		case event := <-esCollector.Output():
			if event != nil {
				eventsReceived++
			}
		case <-ctx.Done():
			break loop
		case <-timeout:
			break loop
		}
	}

	assert.Equal(t, 0, eventsReceived, "Should not receive any events when ES is unavailable")
	t.Log("Collector degraded gracefully when ES was unavailable")
}

func TestKafka_BackpressureHandling(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test in short mode")
	}

	infra := SetupTestInfrastructure(t)
	defer infra.Teardown(t)

	topic := fmt.Sprintf("test-topic-%s", uuid.New().String()[:8])

	writer := kafka.NewWriter(kafka.WriterConfig{
		Brokers:  infra.kafkaBrokers,
		Topic:    topic,
		Balancer: &kafka.LeastBytes{},
	})
	defer writer.Close()

	totalMessages := 1000
	t.Logf("Producing %d messages to Kafka...", totalMessages)

	for i := 0; i < totalMessages; i++ {
		event := testdata.NewLogEvent(
			testdata.WithServiceName("kafka-test-service"),
			testdata.WithLevel(models.LogLevelInfo),
		)
		event.Message = fmt.Sprintf("test message %d", i)
		eventBytes, err := json.Marshal(event)
		require.NoError(t, err)

		err = writer.WriteMessages(infra.ctx, kafka.Message{
			Key:   []byte(event.ID),
			Value: eventBytes,
		})
		if err != nil {
			t.Logf("Failed to write message %d: %v", i, err)
		}
	}
	t.Logf("Finished producing %d messages", totalMessages)

	consumerCfg := config.KafkaCollectorConfig{
		Enabled:  true,
		Brokers:  infra.kafkaBrokers,
		Topic:    topic,
		GroupID:  "test-group",
		BufferSize: 100,
	}

	kafkaCollector, err := collector.NewKafkaCollector(consumerCfg)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(infra.ctx, 60*time.Second)
	defer cancel()

	err = kafkaCollector.Start(ctx)
	require.NoError(t, err)
	defer kafkaCollector.Stop()

	receivedCount := int32(0)
	start := time.Now()

	go func() {
		for event := range kafkaCollector.Output() {
			if event != nil {
				atomic.AddInt32(&receivedCount, 1)
			}
		}
	}()

	timeout := time.After(45 * time.Second)
loop:
	for {
		select {
		case <-timeout:
			break loop
		case <-ctx.Done():
			break loop
		default:
			if atomic.LoadInt32(&receivedCount) >= int32(totalMessages) {
				break loop
			}
			time.Sleep(100 * time.Millisecond)
		}
	}

	duration := time.Since(start)
	t.Logf("Received %d/%d messages in %v", atomic.LoadInt32(&receivedCount), totalMessages, duration)

	assert.GreaterOrEqual(t, atomic.LoadInt32(&receivedCount), int32(totalMessages*0.8),
		"Should receive at least 80% of messages")

	t.Logf("Throughput: %.2f messages/sec", float64(atomic.LoadInt32(&receivedCount))/duration.Seconds())
}

func TestAlertStorm_NoiseSuppression(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping integration test in short mode")
	}

	infra := SetupTestInfrastructure(t)
	defer infra.Teardown(t)

	redisCfg := config.RedisConfig{
		Address:  infra.redisURL,
		Password: "",
		DB:       0,
	}
	redisClient, err := storage.NewRedisClient(redisCfg)
	require.NoError(t, err)

	aggregatorCfg := config.AggregationConfig{
		Enabled:              true,
		GroupByFields:        []string{"service_name", "alert_type"},
		TimeWindow:           1 * time.Minute,
		SuppressLowerPriority: true,
		MaxIncidentSize:      100,
		DedupKeyTemplate:     "{{.AlertType}}-{{.ServiceName}}",
	}

	alertInput := make(chan *models.Alert, 1000)
	eventChainInput := make(chan *models.EventChain, 10)
	aggregatorEngine, err := aggregator.NewAggregator(aggregatorCfg, redisClient, alertInput, eventChainInput)
	require.NoError(t, err)

	ctx, cancel := context.WithTimeout(infra.ctx, 30*time.Second)
	defer cancel()

	err = aggregatorEngine.Start(ctx)
	require.NoError(t, err)
	defer aggregatorEngine.Stop()

	totalAlerts := 100
	t.Logf("Producing alert storm: %d alerts for same service...", totalAlerts)

	for i := 0; i < totalAlerts; i++ {
		severity := models.SeverityHigh
		if i == 0 {
			severity = models.SeverityCritical
		}

		alert := testdata.NewAlert(
			testdata.WithAlertType(models.AlertTypeErrorRate),
			testdata.WithAlertSeverity(severity),
			testdata.WithAlertServiceName("storm-service"),
		)
		alert.Timestamp = time.Now()
		alert.Message = fmt.Sprintf("DB timeout error #%d", i)
		alertInput <- alert
	}

	time.Sleep(2 * time.Second)
	cancel()

	incidents := make([]*models.Incident, 0)
	timeout := time.After(1 * time.Second)
collectIncidents:
	for {
		select {
		case incident := <-aggregatorEngine.Output():
			if incident != nil {
				incidents = append(incidents, incident)
			}
		case <-timeout:
			break collectIncidents
		}
	}

	t.Logf("Alert storm result: %d alerts generated, %d incidents produced", totalAlerts, len(incidents))

	assert.GreaterOrEqual(t, len(incidents), 1, "Should have at least one incident")
	if len(incidents) > 0 {
		assert.Equal(t, models.SeverityCritical, incidents[0].Severity,
			"Incident should have the highest severity")
		assert.Greater(t, incidents[0].AlertCount, 1, "Incident should contain multiple alerts")
	}

	noiseReductionRatio := float64(totalAlerts) / float64(len(incidents))
	t.Logf("Noise reduction ratio: %.2f:1", noiseReductionRatio)
	assert.Greater(t, noiseReductionRatio, 5.0,
		"Should reduce noise by at least 5x")
}

func createIndex(t *testing.T, client *elasticsearch.Client, indexName string) {
	t.Helper()

	mapping := `{
		"mappings": {
			"properties": {
				"@timestamp": { "type": "date" },
				"timestamp": { "type": "date" },
				"service_name": { "type": "keyword" },
				"level": { "type": "keyword" },
				"message": { "type": "text" },
				"status_code": { "type": "integer" },
				"response_time_ms": { "type": "long" },
				"trace_id": { "type": "keyword" },
				"client_ip": { "type": "ip" },
				"error_code": { "type": "keyword" }
			}
		}
	}`

	req := esapi.IndicesCreateRequest{
		Index: indexName,
		Body:  strings.NewReader(mapping),
	}

	res, err := req.Do(context.Background(), client)
	require.NoError(t, err)
	defer res.Body.Close()

	if res.IsError() {
		t.Logf("Create index response: %s", res.String())
	}
}

func writeTestDataToES(t *testing.T, client *elasticsearch.Client, indexName string, count int, anomalyStartIdx int) []*models.LogEvent {
	t.Helper()

	events := make([]*models.LogEvent, count)
	now := time.Now()

	for i := 0; i < count; i++ {
		statusCode := 200
		responseTime := int64(100 + i%200)

		if i >= anomalyStartIdx {
			statusCode = 500
			responseTime = int64(1000 + i*10)
		}

		ts := now.Add(time.Duration(i-count) * time.Second)

		event := testdata.NewLogEvent(
			testdata.WithServiceName("order-service"),
			testdata.WithLevel(models.LogLevelInfo),
			testdata.WithTimestamp(ts),
		)
		event.StatusCode = statusCode
		event.ResponseTime = responseTime
		event.TraceID = uuid.New().String()

		if statusCode >= 500 {
			event.Level = models.LogLevelError
			event.ErrorCode = "DB_TIMEOUT"
		}

		event.Message = testdata.NewNginxAccessLog(ts, statusCode, responseTime, "192.168.1.100")

		events[i] = event
	}

	bulkBody := testdata.NewESBulkRequest(indexName, events)

	req := esapi.BulkRequest{
		Index:   indexName,
		Body:    strings.NewReader(bulkBody),
		Refresh: "true",
	}

	res, err := req.Do(context.Background(), client)
	require.NoError(t, err)
	defer res.Body.Close()

	if res.IsError() {
		t.Logf("Bulk insert response: %s", res.String())
	}

	t.Logf("Wrote %d documents to ES index %s", count, indexName)
	return events
}

func refreshIndex(t *testing.T, client *elasticsearch.Client, indexName string) {
	t.Helper()

	req := esapi.IndicesRefreshRequest{
		Index: []string{indexName},
	}

	res, err := req.Do(context.Background(), client)
	require.NoError(t, err)
	defer res.Body.Close()

	if res.IsError() {
		t.Logf("Refresh index response: %s", res.String())
	}

	time.Sleep(2 * time.Second)
}
