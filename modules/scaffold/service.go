package scaffold

import (
	"archive/zip"
	"bytes"
	"context"
	"depguard/database"
	"depguard/events"
	"depguard/logger"
	"depguard/utils"
	"go.uber.org/zap"
	"gorm.io/gorm"
	"strings"
	"text/template"
	"time"
)

type Service struct {
	db *gorm.DB
}

func NewService() *Service {
	return &Service{db: database.Get()}
}

func (s *Service) InitDefaultTemplates() {
	var count int64
	s.db.Model(&Template{}).Count(&count)
	if count > 0 {
		return
	}

	now := time.Now()
	templates := []*Template{
		{
			ID:          utils.GenerateID("tpl"),
			Name:        "go-rest-api",
			Description: "Golang REST API service with Gin framework",
			Language:    "go",
			Framework:   "gin",
			Tags:        []string{"go", "api", "microservice"},
			Public:      true,
			Version:     "1.0.0",
			OwnerID:     "system",
			CreatedAt:   now,
			UpdatedAt:   now,
			Parameters: []TemplateParameter{
				{Name: "ProjectName", Label: "Project Name", Type: "string", Required: true, Description: "Name of the project"},
				{Name: "ModulePath", Label: "Go Module Path", Type: "string", Required: true, Description: "Go module path (e.g., github.com/user/project)"},
				{Name: "Port", Label: "HTTP Port", Type: "number", Default: 8080, Description: "HTTP server port"},
				{Name: "EnableAuth", Label: "Enable Authentication", Type: "boolean", Default: false, Description: "Add JWT authentication"},
				{Name: "EnableDB", Label: "Enable Database", Type: "boolean", Default: true, Description: "Add PostgreSQL database support"},
				{Name: "EnableRedis", Label: "Enable Redis", Type: "boolean", Default: false, Description: "Add Redis cache support"},
			},
			Questions: []InteractiveQuestion{
				{ID: "q1", Text: "What is your project name?", Type: "input", Required: true, Parameter: "ProjectName"},
				{ID: "q2", Text: "What is your Go module path?", Type: "input", Required: true, Parameter: "ModulePath"},
				{ID: "q3", Text: "What HTTP port should the server use?", Type: "input", Default: 8080, Parameter: "Port"},
				{ID: "q4", Text: "Do you need authentication?", Type: "confirm", Default: false, Parameter: "EnableAuth"},
				{ID: "q5", Text: "Do you need database support?", Type: "confirm", Default: true, Parameter: "EnableDB"},
				{ID: "q6", Text: "Do you need Redis cache?", Type: "confirm", Default: false, Parameter: "EnableRedis"},
			},
			Files: []TemplateFile{
				{
					Path: "go.mod",
					Type: "template",
					Content: `module {{.ModulePath}}

go 1.21

require (
	github.com/gin-gonic/gin v1.9.1
{{if .EnableDB}}
	gorm.io/gorm v1.25.5
	gorm.io/driver/postgres v1.5.4
{{end}}
{{if .EnableAuth}}
	github.com/golang-jwt/jwt/v5 v5.2.0
{{end}}
{{if .EnableRedis}}
	github.com/go-redis/redis/v9 v9.4.0
{{end}}
)
`,
				},
				{
					Path: "main.go",
					Type: "template",
					Content: `package main

import (
	"fmt"
{{if .EnableDB}}
	"log"
{{end}}
	"net/http"

	"github.com/gin-gonic/gin"
{{if .EnableDB}}
	"gorm.io/driver/postgres"
	"gorm.io/gorm"
{{end}}
)

{{if .EnableDB}}
var DB *gorm.DB
{{end}}

func main() {
	r := gin.Default()

{{if .EnableDB}}
	initDB()
{{end}}

	r.GET("/health", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{"status": "ok"})
	})

	r.GET("/", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"name": "{{.ProjectName}}",
			"version": "1.0.0",
		})
	})

	fmt.Printf("{{.ProjectName}} starting on port {{.Port}}...\n")
	r.Run(fmt.Sprintf(":%d", {{.Port}}))
}

{{if .EnableDB}}
func initDB() {
	dsn := "host=localhost user=postgres password=postgres dbname=app port=5432 sslmode=disable"
	var err error
	DB, err = gorm.Open(postgres.Open(dsn), &gorm.Config{})
	if err != nil {
		log.Fatal("Failed to connect to database")
	}
	fmt.Println("Database connected")
}
{{end}}
`,
				},
				{
					Path: ".gitignore",
					Type: "file",
					Content: `# Binaries
*.exe
*.exe~
*.dll
*.so
*.dylib

# Test binary
*.test

# Output of go coverage
*.out

# Dependency directories
vendor/

# IDE
.idea/
.vscode/
*.swp

# Environment
.env
`,
				},
				{
					Path: "README.md",
					Type: "template",
					Content: `# {{.ProjectName}}

## Description
A REST API service built with Gin framework.

## Features
- HTTP Server on port {{.Port}}
{{if .EnableDB}}- PostgreSQL Database{{end}}
{{if .EnableAuth}}- JWT Authentication{{end}}
{{if .EnableRedis}}- Redis Cache{{end}}

## Getting Started

### Prerequisites
- Go 1.21+
{{if .EnableDB}}- PostgreSQL{{end}}
{{if .EnableRedis}}- Redis{{end}}

### Installation
\`\`\`bash
go mod download
\`\`\`

### Running
\`\`\`bash
go run main.go
\`\`\`

The server will start on http://localhost:{{.Port}}
`,
				},
			},
		},
		{
			ID:          utils.GenerateID("tpl"),
			Name:        "nodejs-express",
			Description: "Node.js Express REST API",
			Language:    "javascript",
			Framework:   "express",
			Tags:        []string{"nodejs", "express", "api"},
			Public:      true,
			Version:     "1.0.0",
			OwnerID:     "system",
			CreatedAt:   now,
			UpdatedAt:   now,
			Parameters: []TemplateParameter{
				{Name: "ProjectName", Label: "Project Name", Type: "string", Required: true},
				{Name: "Port", Label: "Port", Type: "number", Default: 3000},
				{Name: "PackageManager", Label: "Package Manager", Type: "select", Options: []string{"npm", "yarn", "pnpm"}, Default: "npm"},
			},
			Questions: []InteractiveQuestion{
				{ID: "q1", Text: "Project name?", Type: "input", Required: true, Parameter: "ProjectName"},
				{ID: "q2", Text: "Port?", Type: "input", Default: 3000, Parameter: "Port"},
				{ID: "q3", Text: "Package manager?", Type: "select", Options: []Option{{Value: "npm", Label: "NPM"}, {Value: "yarn", Label: "Yarn"}, {Value: "pnpm", Label: "pnpm"}}, Parameter: "PackageManager"},
			},
			Files: []TemplateFile{
				{
					Path: "package.json",
					Type: "template",
					Content: `{
  "name": "{{.ProjectName}}",
  "version": "1.0.0",
  "description": "{{.ProjectName}} API",
  "main": "index.js",
  "scripts": {
    "start": "node index.js",
    "dev": "nodemon index.js"
  },
  "dependencies": {
    "express": "^4.18.2"
  },
  "devDependencies": {
    "nodemon": "^3.0.1"
  }
}
`,
				},
				{
					Path: "index.js",
					Type: "template",
					Content: `const express = require('express');
const app = express();
const port = {{.Port}};

app.use(express.json());

app.get('/health', (req, res) => {
  res.json({ status: 'ok' });
});

app.get('/', (req, res) => {
  res.json({ name: '{{.ProjectName}}', version: '1.0.0' });
});

app.listen(port, () => {
  console.log('{{.ProjectName}} running on port {{.Port}}');
});
`,
				},
				{
					Path: "README.md",
					Type: "template",
					Content: `# {{.ProjectName}}

## Run
\`\`\`bash
{{.PackageManager}} install
{{.PackageManager}} run dev
\`\`\`
`,
				},
			},
		},
	}

	s.db.Create(templates)
	logger.Get().Info("default templates initialized")
}

func (s *Service) ListTemplates(ctx context.Context, language string) ([]Template, error) {
	var templates []Template
	q := s.db.WithContext(ctx)
	if language != "" {
		q = q.Where("language = ?", language)
	}
	if err := q.Order("created_at DESC").Find(&templates).Error; err != nil {
		return nil, err
	}
	return templates, nil
}

func (s *Service) GetTemplate(ctx context.Context, id string) (*Template, error) {
	var tpl Template
	if err := s.db.WithContext(ctx).First(&tpl, "id = ? OR name = ?", id, id).Error; err != nil {
		return nil, err
	}
	return &tpl, nil
}

func (s *Service) CreateTemplate(ctx context.Context, tpl *Template) (*Template, error) {
	tpl.ID = utils.GenerateID("tpl")
	tpl.CreatedAt = time.Now()
	tpl.UpdatedAt = time.Now()

	if err := s.db.WithContext(ctx).Create(tpl).Error; err != nil {
		return nil, err
	}
	return tpl, nil
}

func (s *Service) GetNextQuestions(ctx context.Context, templateID string, answered map[string]interface{}) (*QuestionFlow, error) {
	tpl, err := s.GetTemplate(ctx, templateID)
	if err != nil {
		return nil, err
	}

	var pending []InteractiveQuestion
	for _, q := range tpl.Questions {
		if _, ok := answered[q.ID]; !ok {
			if s.shouldShowQuestion(&q, answered) {
				pending = append(pending, q)
			}
		}
	}

	total := len(tpl.Questions)
	answeredCount := total - len(pending)
	progress := float64(answeredCount) / float64(total)

	return &QuestionFlow{
		Questions: pending,
		Progress:  progress,
		Total:     total,
	}, nil
}

func (s *Service) shouldShowQuestion(q *InteractiveQuestion, answered map[string]interface{}) bool {
	if q.Condition == nil {
		return true
	}

	actual, ok := answered[q.Condition.Parameter]
	if !ok {
		return false
	}

	return compareCondition(actual, q.Condition.Operator, q.Condition.Value)
}

func compareCondition(actual interface{}, op string, expected interface{}) bool {
	switch op {
	case "eq", "equals":
		return fmtStr(actual) == fmtStr(expected)
	case "neq", "not_equals":
		return fmtStr(actual) != fmtStr(expected)
	default:
		return true
	}
}

func fmtStr(v interface{}) string {
	return strings.ToLower(strings.TrimSpace(strings.ReplaceAll(fmt.Sprintf("%v", v), "\n", "")))
}

func (s *Service) Generate(ctx context.Context, templateID string, params map[string]interface{}) (*GenerationRequest, error) {
	tpl, err := s.GetTemplate(ctx, templateID)
	if err != nil {
		return nil, err
	}

	for _, p := range tpl.Parameters {
		if p.Required {
			if _, ok := params[p.Name]; !ok {
				params[p.Name] = p.Default
			}
		}
	}

	projectName, _ := params["ProjectName"].(string)
	if projectName == "" {
		projectName = tpl.Name
	}

	req := &GenerationRequest{
		ID:           utils.GenerateID("gen"),
		TemplateID:   templateID,
		TemplateName: tpl.Name,
		Parameters:   params,
		ProjectName:  projectName,
		OutputFormat: "files",
		Status:       "processing",
		CreatedAt:    time.Now(),
	}

	if err := s.db.WithContext(ctx).Create(req).Error; err != nil {
		return nil, err
	}

	go s.runGeneration(ctx, req, tpl)

	return req, nil
}

func (s *Service) runGeneration(ctx context.Context, req *GenerationRequest, tpl *Template) {
	defer func() {
		now := time.Now()
		req.CompletedAt = &now
		s.db.Save(req)

		events.Get().Publish(ctx, events.Event{
			Type: "scaffold.completed",
			Payload: map[string]interface{}{
				"request_id": req.ID,
				"template":   tpl.Name,
			},
			TraceID: getTraceID(ctx),
		})
	}()

	var files []GeneratedFile
	for _, f := range tpl.Files {
		content, err := s.renderFile(&f, req.Parameters)
		if err != nil {
			logger.Get().Warn("failed to render file", zap.String("path", f.Path), zap.Error(err))
			continue
		}
		files = append(files, GeneratedFile{
			Path:    f.Path,
			Content: content,
		})
	}

	req.GeneratedFiles = files
	req.Status = "completed"

	logger.Get().Info("scaffold generated",
		zap.String("request_id", req.ID),
		zap.Int("files", len(files)),
	)
}

func (s *Service) renderFile(f *TemplateFile, params map[string]interface{}) (string, error) {
	if f.Type != "template" {
		return f.Content, nil
	}

	tmpl, err := template.New(f.Path).Parse(f.Content)
	if err != nil {
		return "", err
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, params); err != nil {
		return "", err
	}

	return buf.String(), nil
}

func (s *Service) GetGeneration(ctx context.Context, id string) (*GenerationRequest, error) {
	var req GenerationRequest
	if err := s.db.WithContext(ctx).First(&req, "id = ?", id).Error; err != nil {
		return nil, err
	}
	return &req, nil
}

func (s *Service) ListGenerations(ctx context.Context, templateID string, page, size int) ([]GenerationRequest, int64, error) {
	if page < 0 {
		page = 0
	}
	if size <= 0 || size > 100 {
		size = 20
	}

	var reqs []GenerationRequest
	var total int64

	q := s.db.WithContext(ctx).Model(&GenerationRequest{})
	if templateID != "" {
		q = q.Where("template_id = ?", templateID)
	}

	q.Count(&total)

	if err := q.Order("created_at DESC").
		Offset(page * size).
		Limit(size).
		Find(&reqs).Error; err != nil {
		return nil, 0, err
	}

	return reqs, total, nil
}

func (s *Service) DownloadZip(ctx context.Context, id string) ([]byte, error) {
	req, err := s.GetGeneration(ctx, id)
	if err != nil {
		return nil, err
	}

	var buf bytes.Buffer
	zw := zip.NewWriter(&buf)

	for _, f := range req.GeneratedFiles {
		w, err := zw.Create(f.Path)
		if err != nil {
			return nil, err
		}
		_, err = w.Write([]byte(f.Content))
		if err != nil {
			return nil, err
		}
	}

	if err := zw.Close(); err != nil {
		return nil, err
	}

	return buf.Bytes(), nil
}

func getTraceID(ctx context.Context) string {
	if v := ctx.Value("trace_id"); v != nil {
		return v.(string)
	}
	return ""
}
