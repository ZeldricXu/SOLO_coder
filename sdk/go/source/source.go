package source

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"time"

	"github.com/featureflag/sdk"
)

type HTTPSource struct {
	serverURL       string
	appKey          string
	appSecret       string
	httpClient      *http.Client
	longPollTimeout time.Duration
}

type HTTPSourceOptions struct {
	ServerURL       string
	AppKey          string
	AppSecret       string
	Timeout         time.Duration
	LongPollTimeout time.Duration
}

func NewHTTPSource(opts *HTTPSourceOptions) *HTTPSource {
	if opts == nil {
		opts = &HTTPSourceOptions{
			ServerURL:       "http://localhost:8080",
			Timeout:         30 * time.Second,
			LongPollTimeout: 60 * time.Second,
		}
	}
	if opts.Timeout <= 0 {
		opts.Timeout = 30 * time.Second
	}
	if opts.LongPollTimeout <= 0 {
		opts.LongPollTimeout = 60 * time.Second
	}

	return &HTTPSource{
		serverURL:       opts.ServerURL,
		appKey:          opts.AppKey,
		appSecret:       opts.AppSecret,
		longPollTimeout: opts.LongPollTimeout,
		httpClient: &http.Client{
			Timeout: opts.Timeout,
		},
	}
}

func (h *HTTPSource) Fetch(ctx context.Context, version int64) (*featureflag.SDKConfig, error) {
	endpoint := fmt.Sprintf("%s/api/v1/sdk/config?version=%d", h.serverURL, version)
	req, err := http.NewRequestWithContext(ctx, "GET", endpoint, nil)
	if err != nil {
		return nil, err
	}

	h.addAuthHeaders(req)

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("fetch config failed: status=%d, body=%s", resp.StatusCode, string(body))
	}

	var result struct {
		Code    int                  `json:"code"`
		Message string               `json:"message"`
		Data    *featureflag.SDKConfig `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}

	if result.Code != 0 {
		return nil, fmt.Errorf("fetch config error: %s", result.Message)
	}

	return result.Data, nil
}

func (h *HTTPSource) Evaluate(ctx context.Context, key string, evalCtx *featureflag.EvaluationContext) (*featureflag.EvaluationResult, error) {
	endpoint := fmt.Sprintf("%s/api/v1/sdk/evaluate", h.serverURL)
	return h.evaluateInternal(ctx, endpoint, key, evalCtx)
}

func (h *HTTPSource) BatchEvaluate(ctx context.Context, evalCtx *featureflag.EvaluationContext) (map[string]*featureflag.EvaluationResult, error) {
	endpoint := fmt.Sprintf("%s/api/v1/sdk/evaluate/batch", h.serverURL)

	reqBody, err := json.Marshal(evalCtx)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewBuffer(reqBody))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	h.addAuthHeaders(req)

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("batch evaluate failed: status=%d, body=%s", resp.StatusCode, string(body))
	}

	var result struct {
		Code    int                                     `json:"code"`
		Message string                                  `json:"message"`
		Data    map[string]*featureflag.EvaluationResult `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}

	if result.Code != 0 {
		return nil, fmt.Errorf("batch evaluate error: %s", result.Message)
	}

	return result.Data, nil
}

func (h *HTTPSource) ReportStats(ctx context.Context, stats *featureflag.StatsReport) error {
	endpoint := fmt.Sprintf("%s/api/v1/sdk/stats/report", h.serverURL)

	reqBody, err := json.Marshal(stats)
	if err != nil {
		return err
	}

	req, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewBuffer(reqBody))
	if err != nil {
		return err
	}
	req.Header.Set("Content-Type", "application/json")
	h.addAuthHeaders(req)

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("report stats failed: status=%d", resp.StatusCode)
	}

	return nil
}

func (h *HTTPSource) evaluateInternal(ctx context.Context, endpoint, key string, evalCtx *featureflag.EvaluationContext) (*featureflag.EvaluationResult, error) {
	reqBody := map[string]interface{}{
		"key":         key,
		"user_id":     evalCtx.UserID,
		"department":  evalCtx.Department,
		"tags":        evalCtx.Tags,
		"environment": evalCtx.Environment,
		"tenant_id":   evalCtx.TenantID,
		"attributes":  evalCtx.Attributes,
	}

	bodyBytes, err := json.Marshal(reqBody)
	if err != nil {
		return nil, err
	}

	req, err := http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewBuffer(bodyBytes))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", "application/json")
	h.addAuthHeaders(req)

	resp, err := h.httpClient.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		body, _ := io.ReadAll(resp.Body)
		return nil, fmt.Errorf("evaluate failed: status=%d, body=%s", resp.StatusCode, string(body))
	}

	var result struct {
		Code    int                        `json:"code"`
		Message string                     `json:"message"`
		Data    *featureflag.EvaluationResult `json:"data"`
	}

	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, err
	}

	if result.Code != 0 {
		return nil, fmt.Errorf("evaluate error: %s", result.Message)
	}

	return result.Data, nil
}

func (h *HTTPSource) addAuthHeaders(req *http.Request) {
	if h.appKey != "" {
		req.Header.Set("X-App-Key", h.appKey)
	}
	if h.appSecret != "" {
		timestamp := fmt.Sprintf("%d", time.Now().Unix())
		req.Header.Set("X-Timestamp", timestamp)
		signature := h.generateSignature(timestamp)
		req.Header.Set("X-Signature", signature)
	}
	req.Header.Set("User-Agent", "FeatureFlag-Go-SDK/1.0")
}

func (h *HTTPSource) generateSignature(timestamp string) string {
	return ""
}

func (h *HTTPSource) SetHTTPClient(client *http.Client) {
	if client != nil {
		h.httpClient = client
	}
}

type ConfigMapSource struct {
	namespace string
	configMap string
	dataKey   string
	client    interface{}
}

type ConfigMapSourceOptions struct {
	Namespace string
	ConfigMap string
	DataKey   string
}

func NewConfigMapSource(opts *ConfigMapSourceOptions) *ConfigMapSource {
	if opts == nil {
		opts = &ConfigMapSourceOptions{
			Namespace: "default",
			ConfigMap: "featureflag-config",
			DataKey:   "switches.json",
		}
	}
	return &ConfigMapSource{
		namespace: opts.Namespace,
		configMap: opts.ConfigMap,
		dataKey:   opts.DataKey,
	}
}

func (c *ConfigMapSource) Fetch(ctx context.Context, version int64) (*featureflag.SDKConfig, error) {
	switches := make(map[string]*featureflag.SwitchSnapshot)
	configMapData, err := c.readConfigMap()
	if err != nil {
		return nil, err
	}

	if data, ok := configMapData[c.dataKey]; ok {
		if err := json.Unmarshal([]byte(data), &switches); err != nil {
			return nil, fmt.Errorf("parse configmap data error: %w", err)
		}
	}

	switchList := make([]*featureflag.SwitchSnapshot, 0, len(switches))
	for _, sw := range switches {
		switchList = append(switchList, sw)
	}

	return &featureflag.SDKConfig{
		Version:   time.Now().Unix(),
		Switches:  switchList,
		UpdatedAt: time.Now(),
	}, nil
}

func (c *ConfigMapSource) Evaluate(ctx context.Context, key string, evalCtx *featureflag.EvaluationContext) (*featureflag.EvaluationResult, error) {
	config, err := c.Fetch(ctx, 0)
	if err != nil {
		return nil, err
	}

	for _, sw := range config.Switches {
		if sw.Key == key {
			return featureflag.EvaluateSwitch(sw, evalCtx), nil
		}
	}

	return &featureflag.EvaluationResult{
		SwitchKey: key,
		Enabled:   false,
		Reason:    "switch_not_found",
	}, nil
}

func (c *ConfigMapSource) BatchEvaluate(ctx context.Context, evalCtx *featureflag.EvaluationContext) (map[string]*featureflag.EvaluationResult, error) {
	config, err := c.Fetch(ctx, 0)
	if err != nil {
		return nil, err
	}

	results := make(map[string]*featureflag.EvaluationResult)
	for _, sw := range config.Switches {
		results[sw.Key] = featureflag.EvaluateSwitch(sw, evalCtx)
	}
	return results, nil
}

func (c *ConfigMapSource) ReportStats(ctx context.Context, stats *featureflag.StatsReport) error {
	return nil
}

func (c *ConfigMapSource) readConfigMap() (map[string]string, error) {
	return map[string]string{}, nil
}

func NewSwitchSource(sourceType string, opts interface{}) (featureflag.SwitchSource, error) {
	switch sourceType {
	case "http", "":
		httpOpts, _ := opts.(*HTTPSourceOptions)
		return NewHTTPSource(httpOpts), nil
	case "configmap":
		cmOpts, _ := opts.(*ConfigMapSourceOptions)
		return NewConfigMapSource(cmOpts), nil
	default:
		return nil, fmt.Errorf("unsupported source type: %s", sourceType)
	}
}

func ParseURL(u string) (string, error) {
	parsed, err := url.Parse(u)
	if err != nil {
		return "", err
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", fmt.Errorf("invalid URL scheme: %s", parsed.Scheme)
	}
	return u, nil
}
