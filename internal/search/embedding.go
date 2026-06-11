package search

import (
	"bytes"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"time"
)

type EmbeddingClient struct {
	ollamaBaseURL string
	model         string
	httpClient    *http.Client
}

func NewEmbeddingClient(baseURL, model string) *EmbeddingClient {
	return &EmbeddingClient{
		ollamaBaseURL: baseURL,
		model:         model,
		httpClient: &http.Client{
			Timeout: 30 * time.Second,
		},
	}
}

type ollamaEmbeddingRequest struct {
	Model  string `json:"model"`
	Prompt string `json:"prompt"`
}

type ollamaEmbeddingResponse struct {
	Embedding []float32 `json:"embedding"`
}

func (c *EmbeddingClient) Embed(text string) ([]float32, error) {
	reqBody := ollamaEmbeddingRequest{
		Model:  c.model,
		Prompt: text,
	}

	jsonData, err := json.Marshal(reqBody)
	if err != nil {
		return nil, fmt.Errorf("failed to marshal embedding request: %w", err)
	}

	url := c.ollamaBaseURL + "/api/embeddings"
	req, err := http.NewRequest("POST", url, bytes.NewReader(jsonData))
	if err != nil {
		return nil, fmt.Errorf("failed to create embedding request: %w", err)
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := c.httpClient.Do(req)
	if err != nil {
		return nil, fmt.Errorf("ollama embedding service unavailable: %w", err)
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("ollama embedding returned status %d", resp.StatusCode)
	}

	var result ollamaEmbeddingResponse
	if err := json.NewDecoder(resp.Body).Decode(&result); err != nil {
		return nil, fmt.Errorf("failed to decode embedding response: %w", err)
	}

	if len(result.Embedding) == 0 {
		return nil, fmt.Errorf("ollama returned empty embedding vector")
	}

	return result.Embedding, nil
}

func (c *EmbeddingClient) EmbedBatch(texts []string) ([][]float32, error) {
	results := make([][]float32, len(texts))

	for i, text := range texts {
		embedding, err := c.Embed(text)
		if err != nil {
			log.Printf("EmbedBatch: failed to embed text at index %d: %v", i, err)
			continue
		}
		results[i] = embedding
	}

	return results, nil
}

func (c *EmbeddingClient) IsAvailable() bool {
	url := c.ollamaBaseURL + "/api/tags"
	resp, err := c.httpClient.Get(url)
	if err != nil {
		return false
	}
	defer resp.Body.Close()
	return resp.StatusCode == http.StatusOK
}
