package scaffold

import (
	"bytes"
	"fmt"
	"github.com/gin-gonic/gin"
	"github.com/solocoder/session138/pkg/metrics"
	"go.uber.org/zap"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"text/template"
	"time"
)

const (
	ModuleName          = "scaffold"
	MaxBatchSize        = 100
	MinBatchSize        = 1
	DefaultMaxWorkers   = 5
	BatchTimeoutSeconds = 120
)

type Template struct {
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Language    string            `json:"language"`
	Type        string            `json:"type"`
	Files       []TemplateFile    `json:"files"`
	Params      []TemplateParam   `json:"params"`
}

type TemplateFile struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

type TemplateParam struct {
	Name        string   `json:"name"`
	Description string   `json:"description"`
	Type        string   `json:"type"`
	Default     string   `json:"default"`
	Required    bool     `json:"required"`
	Options     []string `json:"options,omitempty"`
}

type GenerateRequest struct {
	TemplateName string                 `json:"template_name" binding:"required"`
	Params       map[string]interface{} `json:"params" binding:"required"`
	OutputDir    string                 `json:"output_dir"`
}

type GenerateResponse struct {
	Status    string   `json:"status"`
	Message   string   `json:"message"`
	Files     []string `json:"files"`
	OutputDir string   `json:"output_dir"`
	Duration  int64    `json:"duration_ms"`
}

type BatchGenerateRequest struct {
	Requests   []GenerateRequest `json:"requests" binding:"required,min=1,max=100"`
	MaxWorkers int               `json:"max_workers"`
}

type BatchGenerateResponse struct {
	SuccessCount int                `json:"success_count"`
	FailedCount  int                `json:"failed_count"`
	TotalCount   int                `json:"total_count"`
	Duration     int64              `json:"duration_ms"`
	Results      []*BatchResultItem `json:"results"`
}

type BatchResultItem struct {
	Index     int               `json:"index"`
	Status    string            `json:"status"`
	Message   string            `json:"message,omitempty"`
	OutputDir string            `json:"output_dir,omitempty"`
	Files     []string          `json:"files,omitempty"`
	Duration  int64             `json:"duration_ms"`
	Error     string            `json:"error,omitempty"`
}

type Batcher struct {
	pendingRequests []*PendingRequest
	mutex           sync.Mutex
	flushTimer      *time.Timer
	maxBatchSize    int
	flushInterval   time.Duration
	processing      bool
}

type PendingRequest struct {
	request    GenerateRequest
	resultChan chan *BatchResultItem
	timestamp  time.Time
}

var (
	templates = map[string]Template{
		"go-service": {
			Name:        "go-service",
			Description: "Go RESTful API服务模板",
			Language:    "go",
			Type:        "service",
			Params: []TemplateParam{
				{Name: "module_name", Description: "Go模块名称", Type: "string", Required: true},
				{Name: "service_name", Description: "服务名称", Type: "string", Required: true},
				{Name: "author", Description: "作者名称", Type: "string", Required: false, Default: "Developer"},
				{Name: "with_docker", Description: "是否包含Dockerfile", Type: "boolean", Required: false, Default: "true"},
				{Name: "with_ci", Description: "是否包含CI配置", Type: "boolean", Required: false, Default: "false"},
			},
			Files: []TemplateFile{
				{Path: "go.mod", Content: `module {{.module_name}}

go 1.21

require (
	github.com/gin-gonic/gin v1.9.1
)
`},
				{Path: "main.go", Content: `package main

import (
	"fmt"
	"github.com/gin-gonic/gin"
)

func main() {
	r := gin.Default()

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	fmt.Println("{{.service_name}} starting on :8080")
	r.Run(":8080")
}
`},
				{Path: "README.md", Content: `# {{.service_name}}

{{.service_name}} is a Go service.

## Getting Started

\`\`\`bash
go run main.go
\`\`\`

## Author

{{.author}}
`},
				{Path: "Dockerfile", Content: `FROM golang:1.21-alpine

WORKDIR /app
COPY . .
RUN go build -o server .

EXPOSE 8080
CMD ["./server"]
`},
			},
		},
		"python-api": {
			Name:        "python-api",
			Description: "Python FastAPI服务模板",
			Language:    "python",
			Type:        "service",
			Params: []TemplateParam{
				{Name: "project_name", Description: "项目名称", Type: "string", Required: true},
				{Name: "version", Description: "版本号", Type: "string", Required: false, Default: "0.1.0"},
			},
			Files: []TemplateFile{
				{Path: "requirements.txt", Content: `fastapi==0.104.0
uvicorn==0.24.0
`},
				{Path: "main.py", Content: `from fastapi import FastAPI

app = FastAPI(title="{{.project_name}}", version="{{.version}}")


@app.get("/health")
async def health_check():
    return {"status": "ok"}


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
`},
			},
		},
		"react-app": {
			Name:        "react-app",
			Description: "React前端应用模板",
			Language:    "typescript",
			Type:        "frontend",
			Params: []TemplateParam{
				{Name: "app_name", Description: "应用名称", Type: "string", Required: true},
			},
			Files: []TemplateFile{
				{Path: "package.json", Content: `{
  "name": "{{.app_name}}",
  "version": "0.1.0",
  "private": true,
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0"
  }
}
`},
				{Path: "src/App.tsx", Content: `import React from 'react';

function App() {
  return (
    <div className="App">
      <h1>Welcome to {{.app_name}}</h1>
    </div>
  );
}

export default App;
`},
			},
		},
	}

	globalBatcher = &Batcher{
		maxBatchSize:  MaxBatchSize,
		flushInterval: 100 * time.Millisecond,
	}
)

func init() {
	go globalBatcher.startAutoFlush()
}

func (b *Batcher) startAutoFlush() {
	b.flushTimer = time.NewTimer(b.flushInterval)
	for range b.flushTimer.C {
		b.flushIfNeeded()
		b.flushTimer.Reset(b.flushInterval)
	}
}

func (b *Batcher) flushIfNeeded() {
	b.mutex.Lock()
	if len(b.pendingRequests) == 0 || b.processing {
		b.mutex.Unlock()
		return
	}

	batchSize := len(b.pendingRequests)
	if batchSize > b.maxBatchSize {
		batchSize = b.maxBatchSize
	}

	batch := b.pendingRequests[:batchSize]
	b.pendingRequests = b.pendingRequests[batchSize:]
	b.processing = true
	b.mutex.Unlock()

	go b.processBatch(batch)
}

func (b *Batcher) processBatch(batch []*PendingRequest) {
	results := processBatchRequests(batch, DefaultMaxWorkers)

	for i, item := range results {
		if i < len(batch) {
			batch[i].resultChan <- item
		}
	}

	b.mutex.Lock()
	b.processing = false
	b.mutex.Unlock()
}

func (b *Batcher) AddRequest(req GenerateRequest) *BatchResultItem {
	pending := &PendingRequest{
		request:    req,
		resultChan: make(chan *BatchResultItem, 1),
		timestamp:  time.Now(),
	}

	b.mutex.Lock()
	b.pendingRequests = append(b.pendingRequests, pending)
	shouldFlush := len(b.pendingRequests) >= b.maxBatchSize
	b.mutex.Unlock()

	if shouldFlush {
		b.flushIfNeeded()
	}

	return <-pending.resultChan
}

func ListTemplates(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "list_templates")
	result := make([]Template, 0, len(templates))
	for _, t := range templates {
		result = append(result, t)
	}
	timer.ObserveSuccess()
	c.JSON(200, gin.H{"code": 200, "data": result})
}

func GetTemplate(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "get_template")
	name := c.Param("name")
	t, exists := templates[name]
	if !exists {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "template_not_found")
		c.JSON(404, gin.H{"code": 404, "message": "模板不存在"})
		return
	}
	timer.ObserveSuccess()
	c.JSON(200, gin.H{"code": 200, "data": t})
}

func GenerateProject(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "generate_project")
	startTime := time.Now()

	var req GenerateRequest
	if err := c.ShouldBindJSON(&req); err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "invalid_request")
		c.JSON(400, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	files, err := processSingleRequest(req)
	duration := time.Since(startTime).Milliseconds()

	if err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "generate_failed")
		c.JSON(500, gin.H{"code": 500, "message": "生成失败", "error": err.Error()})
		return
	}

	timer.ObserveSuccess()
	c.JSON(200, gin.H{
		"code": 200,
		"data": GenerateResponse{
			Status:    "success",
			Message:   "项目生成成功",
			Files:     files,
			OutputDir: req.OutputDir,
			Duration:  duration,
		},
	})
}

func BatchGenerateProject(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "batch_generate")
	startTime := time.Now()

	var batchReq BatchGenerateRequest
	if err := c.ShouldBindJSON(&batchReq); err != nil {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "invalid_batch_request")
		c.JSON(400, gin.H{"code": 400, "message": "参数错误", "error": err.Error()})
		return
	}

	if len(batchReq.Requests) > MaxBatchSize {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "batch_too_large")
		c.JSON(400, gin.H{"code": 400, "message": fmt.Sprintf("批量请求不能超过%d个", MaxBatchSize)})
		return
	}

	if len(batchReq.Requests) < MinBatchSize {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "batch_too_small")
		c.JSON(400, gin.H{"code": 400, "message": fmt.Sprintf("批量请求至少需要%d个", MinBatchSize)})
		return
	}

	metrics.RecordBatchSize(ModuleName, "generate", len(batchReq.Requests))

	maxWorkers := batchReq.MaxWorkers
	if maxWorkers <= 0 {
		maxWorkers = DefaultMaxWorkers
	}

	pending := make([]*PendingRequest, len(batchReq.Requests))
	for i := range batchReq.Requests {
		pending[i] = &PendingRequest{
			request:   batchReq.Requests[i],
			resultChan: make(chan *BatchResultItem, 1),
			timestamp:  time.Now(),
		}
	}

	results := processBatchRequests(pending, maxWorkers)
	duration := time.Since(startTime).Milliseconds()

	successCount := 0
	failedCount := 0
	for _, r := range results {
		if r.Status == "success" {
			successCount++
		} else {
			failedCount++
		}
	}

	timer.ObserveSuccess()
	c.JSON(200, gin.H{
		"code": 200,
		"data": BatchGenerateResponse{
			SuccessCount: successCount,
			FailedCount:  failedCount,
			TotalCount:   len(batchReq.Requests),
			Duration:     duration,
			Results:      results,
		},
	})
}

func processBatchRequests(batch []*PendingRequest, maxWorkers int) []*BatchResultItem {
	results := make([]*BatchResultItem, len(batch))
	var wg sync.WaitGroup
	semaphore := make(chan struct{}, maxWorkers)

	for i, pending := range batch {
		wg.Add(1)
		go func(index int, req GenerateRequest) {
			defer wg.Done()
			semaphore <- struct{}{}
			defer func() { <-semaphore }()

			start := time.Now()
			files, err := processSingleRequest(req)
			duration := time.Since(start).Milliseconds()

			result := &BatchResultItem{
				Index:    index,
				OutputDir: req.OutputDir,
				Duration: duration,
			}

			if err != nil {
				result.Status = "failed"
				result.Error = err.Error()
				result.Message = "生成失败"
				zap.L().Warn("Batch item failed",
					zap.Int("index", index),
					zap.Error(err),
				)
			} else {
				result.Status = "success"
				result.Files = files
				result.Message = "生成成功"
			}

			results[index] = result
		}(i, pending.request)
	}

	wg.Wait()
	return results
}

func processSingleRequest(req GenerateRequest) ([]string, error) {
	t, exists := templates[req.TemplateName]
	if !exists {
		return nil, fmt.Errorf("模板不存在: %s", req.TemplateName)
	}

	for _, param := range t.Params {
		if param.Required {
			if _, ok := req.Params[param.Name]; !ok {
				return nil, fmt.Errorf("缺少必填参数: %s", param.Name)
			}
		}
	}

	outputDir := req.OutputDir
	if outputDir == "" {
		outputDir = "./generated/" + req.TemplateName
	}

	if err := os.MkdirAll(outputDir, 0755); err != nil {
		return nil, fmt.Errorf("创建输出目录失败: %w", err)
	}

	generatedFiles := make([]string, 0)
	for _, file := range t.Files {
		rendered, err := renderTemplate(file.Content, req.Params)
		if err != nil {
			return nil, fmt.Errorf("渲染模板失败: %w", err)
		}

		fullPath := filepath.Join(outputDir, file.Path)
		if err := os.MkdirAll(filepath.Dir(fullPath), 0755); err != nil {
			return nil, fmt.Errorf("创建子目录失败: %w", err)
		}

		if err := os.WriteFile(fullPath, []byte(rendered), 0644); err != nil {
			return nil, fmt.Errorf("写入文件失败: %w", err)
		}

		generatedFiles = append(generatedFiles, file.Path)
	}

	return generatedFiles, nil
}

func GetInteractiveQuestions(c *gin.Context) {
	timer := metrics.NewTimer(ModuleName, "get_questions")
	name := c.Param("name")
	t, exists := templates[name]
	if !exists {
		timer.ObserveError()
		metrics.RecordError(ModuleName, "template_not_found")
		c.JSON(404, gin.H{"code": 404, "message": "模板不存在"})
		return
	}

	questions := make([]gin.H, 0, len(t.Params))
	for _, param := range t.Params {
		q := gin.H{
			"name":        param.Name,
			"description": param.Description,
			"type":        param.Type,
			"required":    param.Required,
		}
		if param.Default != "" {
			q["default"] = param.Default
		}
		if len(param.Options) > 0 {
			q["options"] = param.Options
		}
		questions = append(questions, q)
	}

	timer.ObserveSuccess()
	c.JSON(200, gin.H{"code": 200, "data": questions})
}

func GetBatchStats(c *gin.Context) {
	globalBatcher.mutex.Lock()
	pendingCount := len(globalBatcher.pendingRequests)
	processing := globalBatcher.processing
	globalBatcher.mutex.Unlock()

	c.JSON(200, gin.H{
		"code": 200,
		"data": gin.H{
			"pending_requests": pendingCount,
			"max_batch_size":   MaxBatchSize,
			"default_workers":  DefaultMaxWorkers,
			"is_processing":    processing,
		},
	})
}

func renderTemplate(content string, params map[string]interface{}) (string, error) {
	funcMap := template.FuncMap{
		"upper": strings.ToUpper,
		"lower": strings.ToLower,
		"title": strings.Title,
	}

	tmpl, err := template.New("file").Funcs(funcMap).Parse(content)
	if err != nil {
		return "", err
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, params); err != nil {
		return "", err
	}

	return buf.String(), nil
}

func RegisterRoutes(r *gin.RouterGroup) {
	scaffold := r.Group("/scaffold")
	{
		scaffold.GET("/templates", ListTemplates)
		scaffold.GET("/templates/:name", GetTemplate)
		scaffold.GET("/templates/:name/questions", GetInteractiveQuestions)
		scaffold.POST("/generate", GenerateProject)
		scaffold.POST("/batch/generate", BatchGenerateProject)
		scaffold.GET("/batch/stats", GetBatchStats)
	}
}
