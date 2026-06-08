package pipeline

import (
	"context"
	"fmt"
	"regexp"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"

	"github.com/datateam/loganalyzer/internal/config"
	"github.com/datateam/loganalyzer/internal/models"
	"github.com/datateam/loganalyzer/internal/testdata"
)

func TestParseRule_NginxLog_ExtractStatusAndResponseTime(t *testing.T) {
	pattern := `^(\S+) - \S+ \[([^\]]+)\] "(\S+) (\S+) \S+" (\d+) (\d+) "([^"]*)" "([^"]*)"$`
	fields := []interface{}{"client_ip", "timestamp", "method", "path", "status_code", "response_size", "referer", "user_agent"}

	ruleCfg := config.PipelineRule{
		ID:      "parse-nginx",
		Name:    "Parse Nginx Access Logs",
		Type:    "parse",
		Enabled: true,
		Config: map[string]interface{}{
			"pattern": pattern,
			"fields":  fields,
		},
		Order: 1,
	}

	p := &Pipeline{
		errorCodeMap: make(map[string]string),
	}

	rule, err := p.createParseRule(BaseRule{
		id:      ruleCfg.ID,
		name:    ruleCfg.Name,
		typ:     ruleCfg.Type,
		enabled: ruleCfg.Enabled,
		order:   ruleCfg.Order,
		cfg:     ruleCfg.Config,
	}, ruleCfg)
	require.NoError(t, err)

	ts := time.Now()
	expectedStatus := 500
	expectedResponseTime := int64(456)
	tsStr := ts.Format("02/Jan/2006:15:04:05 -0700")
	nginxLog := fmt.Sprintf(`10.0.0.1 - - [%s] "GET /api/users HTTP/1.1" %d %d "-" "curl/7.68.0"`, tsStr, expectedStatus, expectedResponseTime)

	event := testdata.NewLogEvent(
		testdata.WithMessage(nginxLog),
		testdata.WithTimestamp(ts),
	)

	ctx := context.Background()
	result, err := rule.Process(ctx, event)
	require.NoError(t, err)
	require.NotNil(t, result)

	assert.Equal(t, "10.0.0.1", result.ParsedFields["client_ip"], "Should extract client_ip from nginx log")
	assert.Equal(t, "GET", result.ParsedFields["method"], "Should extract method")
	assert.Equal(t, "/api/users", result.ParsedFields["path"], "Should extract path")
	assert.Equal(t, fmt.Sprintf("%d", expectedStatus), result.ParsedFields["status_code"], "Should extract status_code")
}

func TestParseRule_InvalidRegex_SkipAndLogError(t *testing.T) {
	invalidPattern := "[invalid-regex("

	ruleCfg := config.PipelineRule{
		ID:      "parse-invalid",
		Name:    "Invalid Parse Rule",
		Type:    "parse",
		Enabled: true,
		Config: map[string]interface{}{
			"pattern": invalidPattern,
			"fields":  []interface{}{"field1"},
		},
		Order: 1,
	}

	p := &Pipeline{
		errorCodeMap: make(map[string]string),
	}

	_, err := p.createParseRule(BaseRule{
		id:      ruleCfg.ID,
		name:    ruleCfg.Name,
		typ:     ruleCfg.Type,
		enabled: ruleCfg.Enabled,
		order:   ruleCfg.Order,
		cfg:     ruleCfg.Config,
	}, ruleCfg)

	assert.Error(t, err, "Should return error for invalid regex")
	assert.Contains(t, err.Error(), "invalid regex pattern", "Error message should indicate regex compilation failure")
}

func TestParseRule_NoMatch_SkipGracefully(t *testing.T) {
	pattern := `^(\d{3})-(\d+)$`
	fields := []interface{}{"status", "time"}

	ruleCfg := config.PipelineRule{
		ID:      "parse-test",
		Name:    "Test Parse Rule",
		Type:    "parse",
		Enabled: true,
		Config: map[string]interface{}{
			"pattern": pattern,
			"fields":  fields,
		},
		Order: 1,
	}

	p := &Pipeline{
		errorCodeMap: make(map[string]string),
	}

	rule, err := p.createParseRule(BaseRule{
		id:      ruleCfg.ID,
		name:    ruleCfg.Name,
		typ:     ruleCfg.Type,
		enabled: ruleCfg.Enabled,
		order:   ruleCfg.Order,
		cfg:     ruleCfg.Config,
	}, ruleCfg)
	require.NoError(t, err)

	event := testdata.NewLogEvent(
		testdata.WithMessage("this message does not match the pattern"),
	)

	ctx := context.Background()
	result, err := rule.Process(ctx, event)
	require.NoError(t, err)
	require.NotNil(t, result)
	assert.Empty(t, result.ParsedFields, "ParsedFields should remain empty when no match")
	assert.Equal(t, "this message does not match the pattern", result.Message, "Original message should be preserved")
}

func TestParseRule_ConcurrentProcessing_DataIntegrity(t *testing.T) {
	pattern := `^(\S+) - \S+ \[([^\]]+)\] "(\S+) (\S+) \S+" (\d+) (\d+) "([^"]*)" "([^"]*)"$`
	fields := []interface{}{"client_ip", "timestamp", "method", "path", "status_code", "response_size", "referer", "user_agent"}

	ruleCfg := config.PipelineRule{
		ID:      "parse-nginx-concurrent",
		Name:    "Concurrent Parse Rule",
		Type:    "parse",
		Enabled: true,
		Config: map[string]interface{}{
			"pattern": pattern,
			"fields":  fields,
		},
		Order: 1,
	}

	p := &Pipeline{
		errorCodeMap: make(map[string]string),
	}

	rule, err := p.createParseRule(BaseRule{
		id:      ruleCfg.ID,
		name:    ruleCfg.Name,
		typ:     ruleCfg.Type,
		enabled: ruleCfg.Enabled,
		order:   ruleCfg.Order,
		cfg:     ruleCfg.Config,
	}, ruleCfg)
	require.NoError(t, err)

	const numWorkers = 10
	const eventsPerWorker = 100
	var processed int32
	var errorCount int32
	var wg sync.WaitGroup

	results := make(chan *models.LogEvent, numWorkers*eventsPerWorker)

	for w := 0; w < numWorkers; w++ {
		wg.Add(1)
		go func(workerID int) {
			defer wg.Done()
			ctx := context.Background()

			for i := 0; i < eventsPerWorker; i++ {
				status := 200 + (i % 5) * 100
				respTime := int64(100 + i*5)
				ip := fmt.Sprintf("192.168.%d.%d", workerID, i%255)
				logMsg := testdata.NewNginxAccessLog(time.Now(), status, respTime, ip)

				event := testdata.NewLogEvent(testdata.WithMessage(logMsg))
				result, err := rule.Process(ctx, event)

				if err != nil {
					atomic.AddInt32(&errorCount, 1)
					continue
				}

				atomic.AddInt32(&processed, 1)
				results <- result
			}
		}(w)
	}

	wg.Wait()
	close(results)

	assert.Equal(t, int32(0), atomic.LoadInt32(&errorCount), "No errors should occur during processing")
	assert.Equal(t, int32(numWorkers*eventsPerWorker), atomic.LoadInt32(&processed), "All events should be processed")

	for result := range results {
		assert.NotNil(t, result.ParsedFields, "All events should have parsed fields")
		assert.Greater(t, result.StatusCode, 0, "Status code should be extracted")
		assert.NotEmpty(t, result.ClientIP, "Client IP should be extracted")
	}
}

func TestFilterRule_DropHealthcheckLogs(t *testing.T) {
	pattern := `/healthcheck|/ping|/metrics`

	ruleCfg := config.PipelineRule{
		ID:      "filter-healthcheck",
		Name:    "Filter Healthcheck",
		Type:    "filter",
		Enabled: true,
		Config: map[string]interface{}{
			"pattern": pattern,
			"mode":    "exclude",
		},
		Order: 1,
	}

	p := &Pipeline{
		errorCodeMap: make(map[string]string),
	}

	rule, err := p.createFilterRule(BaseRule{
		id:      ruleCfg.ID,
		name:    ruleCfg.Name,
		typ:     ruleCfg.Type,
		enabled: ruleCfg.Enabled,
		order:   ruleCfg.Order,
		cfg:     ruleCfg.Config,
	}, ruleCfg)
	require.NoError(t, err)

	ctx := context.Background()

	healthcheckEvent := testdata.NewLogEvent(
		testdata.WithMessage("GET /healthcheck HTTP/1.1 200 0"),
	)

	result, err := rule.Process(ctx, healthcheckEvent)
	require.NoError(t, err)
	assert.Nil(t, result, "Healthcheck log should be dropped")

	normalEvent := testdata.NewLogEvent(
		testdata.WithMessage("GET /api/users HTTP/1.1 200 1234"),
	)

	result2, err := rule.Process(ctx, normalEvent)
	require.NoError(t, err)
	assert.NotNil(t, result2, "Normal log should pass through")
	assert.Equal(t, normalEvent.Message, result2.Message)
}

func TestEnrichRule_ErrorCodeMapping(t *testing.T) {
	errorMappings := map[string]interface{}{
		"500": "Internal Server Error",
		"404": "Resource Not Found",
		"403": "Access Forbidden",
	}

	ruleCfg := config.PipelineRule{
		ID:      "enrich-error-code",
		Name:    "Enrich Error Code",
		Type:    "enrich",
		Enabled: true,
		Config: map[string]interface{}{
			"type":      "error_code",
			"error_map": errorMappings,
		},
		Order: 1,
	}

	p := &Pipeline{
		errorCodeMap: map[string]string{
			"500": "Internal Server Error",
			"404": "Resource Not Found",
			"403": "Access Forbidden",
		},
	}

	rule, err := p.createEnrichRule(BaseRule{
		id:      ruleCfg.ID,
		name:    ruleCfg.Name,
		typ:     ruleCfg.Type,
		enabled: ruleCfg.Enabled,
		order:   ruleCfg.Order,
		cfg:     ruleCfg.Config,
	}, ruleCfg)
	require.NoError(t, err)

	testCases := []struct {
		name          string
		errorCode     string
		expectedDesc  string
	}{
		{"500 error", "500", "Internal Server Error"},
		{"404 error", "404", "Resource Not Found"},
		{"403 error", "403", "Access Forbidden"},
		{"unknown code", "999", ""},
	}

	for _, tc := range testCases {
		t.Run(tc.name, func(t *testing.T) {
			event := testdata.NewLogEvent(
				testdata.WithErrorCode(tc.errorCode),
			)

			ctx := context.Background()
			result, err := rule.Process(ctx, event)
			require.NoError(t, err)
			require.NotNil(t, result)
			assert.Equal(t, tc.expectedDesc, result.ErrorDesc)
		})
	}
}

func TestPipeline_RuleChain_OrderPreserved(t *testing.T) {
	input := make(chan *models.LogEvent, 10)
	defer close(input)

	pipelineCfg := config.PipelineConfig{
		WorkerCount: 1,
		BufferSize:  100,
		Rules: []config.PipelineRule{
			{
				ID:      "filter-1",
				Name:    "Filter 1",
				Type:    "filter",
				Enabled: true,
				Order:   1,
				Config: map[string]interface{}{
					"pattern": "DROP-ME",
					"mode":    "exclude",
				},
			},
			{
				ID:      "parse-1",
				Name:    "Parse 1",
				Type:    "parse",
				Enabled: true,
				Order:   2,
				Config: map[string]interface{}{
					"pattern": `status:(\d+)`,
					"fields":  []interface{}{"status_code"},
				},
			},
			{
				ID:      "enrich-1",
				Name:    "Enrich 1",
				Type:    "enrich",
				Enabled: true,
				Order:   3,
				Config: map[string]interface{}{
					"type": "error_code",
					"error_map": map[string]interface{}{
						"500": "Internal Error",
					},
				},
			},
		},
	}

	p, err := NewPipeline(pipelineCfg, input, "")
	require.NoError(t, err)

	p.rulesMu.RLock()
	assert.Equal(t, 3, len(p.rules))
	assert.Equal(t, "filter-1", p.rules[0].ID())
	assert.Equal(t, "parse-1", p.rules[1].ID())
	assert.Equal(t, "enrich-1", p.rules[2].ID())
	p.rulesMu.RUnlock()
}

func TestParseRule_Reload_BadRegex(t *testing.T) {
	validPattern := `^(\d+)$`
	invalidPattern := "[invalid("

	rule, err := NewParseRuleForTest("test-rule", "Test Rule", validPattern, []string{"value"})
	require.NoError(t, err)

	newCfg := config.PipelineRule{
		ID:      "test-rule",
		Name:    "Test Rule Updated",
		Type:    "parse",
		Enabled: true,
		Config: map[string]interface{}{
			"pattern": invalidPattern,
			"fields":  []interface{}{"value"},
		},
	}

	err = rule.Reload(newCfg)
	assert.Error(t, err, "Should return error for invalid regex during reload")
}

func NewParseRuleForTest(id, name, pattern string, fields []string) (*ParseRule, error) {
	re, err := regexp.Compile(pattern)
	if err != nil {
		return nil, err
	}

	return &ParseRule{
		BaseRule: BaseRule{
			id:      id,
			name:    name,
			typ:     "parse",
			enabled: true,
			order:   1,
		},
		regex:      re,
		fieldNames: fields,
	}, nil
}
