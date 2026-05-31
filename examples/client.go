package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"time"
)

const baseURL = "http://localhost:8080"

type APIClient struct {
	client  *http.Client
	traceID string
}

func NewAPIClient() *APIClient {
	return &APIClient{
		client: &http.Client{
			Timeout: 30 * time.Second,
		},
		traceID: fmt.Sprintf("trace-%d", time.Now().UnixNano()),
	}
}

func (c *APIClient) doRequest(method, path string, body interface{}) ([]byte, error) {
	var reqBody io.Reader
	if body != nil {
		jsonData, err := json.Marshal(body)
		if err != nil {
			return nil, fmt.Errorf("marshal body failed: %w", err)
		}
		reqBody = bytes.NewBuffer(jsonData)
	}

	req, err := http.NewRequest(method, baseURL+path, reqBody)
	if err != nil {
		return nil, fmt.Errorf("create request failed: %w", err)
	}

	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Trace-ID", c.traceID)

	resp, err := c.client.Do(req)
	if err != nil {
		return nil, fmt.Errorf("request failed: %w", err)
	}
	defer resp.Body.Close()

	data, err := io.ReadAll(resp.Body)
	if err != nil {
		return nil, fmt.Errorf("read response failed: %w", err)
	}

	if resp.StatusCode >= 400 {
		return nil, fmt.Errorf("api error (status %d): %s", resp.StatusCode, string(data))
	}

	return data, nil
}

type Resource struct {
	ID     string                 `json:"id"`
	Type   string                 `json:"type"`
	Status string                 `json:"status"`
	Config map[string]interface{} `json:"config,omitempty"`
	Labels map[string]string      `json:"labels,omitempty"`
}

func (c *APIClient) CreateResource(resType string, config map[string]interface{}, labels map[string]string) (*Resource, error) {
	req := map[string]interface{}{
		"type":   resType,
		"config": config,
		"labels": labels,
	}

	data, err := c.doRequest("POST", "/api/v1/resources", req)
	if err != nil {
		return nil, err
	}

	var resp struct {
		Code int      `json:"code"`
		Data Resource `json:"data"`
	}
	if err := json.Unmarshal(data, &resp); err != nil {
		return nil, err
	}

	return &resp.Data, nil
}

func (c *APIClient) Execute(data map[string]interface{}, namespace string) (map[string]interface{}, error) {
	req := map[string]interface{}{
		"data":      data,
		"namespace": namespace,
		"operation": "process",
	}

	result, err := c.doRequest("POST", "/api/v1/execute", req)
	if err != nil {
		return nil, err
	}

	var resp struct {
		Code int                    `json:"code"`
		Data map[string]interface{} `json:"data"`
	}
	if err := json.Unmarshal(result, &resp); err != nil {
		return nil, err
	}

	return resp.Data, nil
}

func (c *APIClient) CreateBackup(backupType string) (string, error) {
	req := map[string]string{"type": backupType}
	data, err := c.doRequest("POST", "/api/v1/storage/backup", req)
	if err != nil {
		return "", err
	}

	var resp struct {
		BackupID string `json:"backup_id"`
	}
	if err := json.Unmarshal(data, &resp); err != nil {
		return "", err
	}

	return resp.BackupID, nil
}

func (c *APIClient) ClassifyData(data interface{}) (map[string]interface{}, error) {
	req := map[string]interface{}{"data": data}
	result, err := c.doRequest("POST", "/api/v1/classification/scan", req)
	if err != nil {
		return nil, err
	}

	var resp map[string]interface{}
	if err := json.Unmarshal(result, &resp); err != nil {
		return nil, err
	}

	return resp, nil
}

func (c *APIClient) AddNoise(value float64, epsilon float64) (float64, error) {
	req := map[string]interface{}{
		"value": value,
		"params": map[string]interface{}{
			"epsilon":     epsilon,
			"sensitivity": 1.0,
			"mechanism":   "laplace",
		},
	}

	result, err := c.doRequest("POST", "/api/v1/privacy/noise", req)
	if err != nil {
		return 0, err
	}

	var resp struct {
		NoisyValue float64 `json:"noisy_value"`
	}
	if err := json.Unmarshal(result, &resp); err != nil {
		return 0, err
	}

	return resp.NoisyValue, nil
}

func main() {
	client := NewAPIClient()
	fmt.Println("=== Session316 API Client Example ===")
	fmt.Printf("Trace ID: %s\n\n", client.traceID)

	fmt.Println("1. Creating resource...")
	resource, err := client.CreateResource("task",
		map[string]interface{}{"timeout": 30, "retries": 3},
		map[string]string{"env": "development"},
	)
	if err != nil {
		fmt.Printf("Error: %v\n", err)
	} else {
		fmt.Printf("Created resource: ID=%s, Status=%s\n\n", resource.ID, resource.Status)
	}

	fmt.Println("2. Executing core processing...")
	result, err := client.Execute(
		map[string]interface{}{
			"user_id":    "user_123",
			"action":     "purchase",
			"amount":     99.99,
			"currency":   "CNY",
			"timestamp":  time.Now().UTC(),
		},
		"default",
	)
	if err != nil {
		fmt.Printf("Error: %v\n", err)
	} else {
		fmt.Printf("Execution result: processed=%v, trace_id=%s\n\n",
			result["processed"], result["trace_id"])
	}

	fmt.Println("3. Creating full backup...")
	backupID, err := client.CreateBackup("full")
	if err != nil {
		fmt.Printf("Error: %v\n", err)
	} else {
		fmt.Printf("Backup created: ID=%s\n\n", backupID)
	}

	fmt.Println("4. Classifying sensitive data...")
	classifyResult, err := client.ClassifyData(map[string]interface{}{
		"name":     "张三",
		"phone":    "13800138000",
		"email":    "zhangsan@example.com",
		"location": "北京市朝阳区",
	})
	if err != nil {
		fmt.Printf("Error: %v\n", err)
	} else {
		summary := classifyResult["summary"].(map[string]interface{})
		fmt.Printf("Classification result: level=%s, matches=%.0f\n\n",
			summary["overall_level"], summary["total_matches"])
	}

	fmt.Println("5. Adding differential privacy noise...")
	originalValue := 100.0
	noisyValue, err := client.AddNoise(originalValue, 0.1)
	if err != nil {
		fmt.Printf("Error: %v\n", err)
	} else {
		fmt.Printf("Original: %.2f, Noisy: %.2f, Diff: %.4f\n\n",
			originalValue, noisyValue, noisyValue-originalValue)
	}

	fmt.Println("6. Creating incremental backup...")
	incBackupID, err := client.CreateBackup("incremental")
	if err != nil {
		fmt.Printf("Error: %v\n", err)
	} else {
		fmt.Printf("Incremental backup created: ID=%s\n\n", incBackupID)
	}

	fmt.Println("=== All examples completed ===")
	fmt.Println("\nAvailable endpoints:")
	fmt.Println("  - POST   /api/v1/resources")
	fmt.Println("  - GET    /api/v1/resources/{id}/status")
	fmt.Println("  - POST   /api/v1/resources/batch")
	fmt.Println("  - POST   /api/v1/execute")
	fmt.Println("  - POST   /api/v1/storage/backup")
	fmt.Println("  - POST   /api/v1/tee/enclave")
	fmt.Println("  - POST   /api/v1/mpc/protocol")
	fmt.Println("  - POST   /api/v1/federated/task")
	fmt.Println("  - POST   /api/v1/classification/scan")
	fmt.Println("  - POST   /api/v1/privacy/noise")
	fmt.Println("  - GET    /health/live")
	fmt.Println("  - GET    /health/ready")
	fmt.Println("  - GET    /health/metrics (Prometheus)")
}
