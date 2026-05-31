package scaffold

import (
	"bytes"
	"fmt"
	"github.com/solocoder/tasktracker/internal/logger"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"text/template"
)

type Template struct {
	Name        string            `json:"name"`
	Description string            `json:"description"`
	Language    string            `json:"language"`
	Framework   string            `json:"framework"`
	Version     string            `json:"version"`
	Params      []TemplateParam   `json:"params"`
	Files       []TemplateFile    `json:"files"`
	Path        string            `json:"path"`
}

type TemplateParam struct {
	Name        string      `json:"name"`
	Description string      `json:"description"`
	Type        string      `json:"type"`
	Default     interface{} `json:"default"`
	Required    bool        `json:"required"`
	Prompt      string      `json:"prompt"`
	Options     []string    `json:"options,omitempty"`
}

type TemplateFile struct {
	Source      string `json:"source"`
	Destination string `json:"destination"`
	IsTemplate  bool   `json:"is_template"`
}

type ProjectConfig struct {
	Name         string                 `json:"name"`
	Description  string                 `json:"description"`
	TemplateName string                 `json:"template_name"`
	Params       map[string]interface{} `json:"params"`
	OutputDir    string                 `json:"output_dir"`
	Overwrite    bool                   `json:"overwrite"`
}

type GeneratedFile struct {
	Path    string `json:"path"`
	Size    int    `json:"size"`
	Content string `json:"content,omitempty"`
}

type ScaffoldGenerator struct {
	mu        sync.RWMutex
	templates map[string]*Template
	templatesDir string
}

type Config struct {
	TemplatesDir string `json:"templates_dir"`
}

func NewScaffoldGenerator(cfg Config) *ScaffoldGenerator {
	if cfg.TemplatesDir == "" {
		cfg.TemplatesDir = "./templates"
	}

	sg := &ScaffoldGenerator{
		templates:    make(map[string]*Template),
		templatesDir: cfg.TemplatesDir,
	}

	os.MkdirAll(cfg.TemplatesDir, 0755)
	sg.loadBuiltinTemplates()
	return sg
}

func (sg *ScaffoldGenerator) loadBuiltinTemplates() {
	builtinTemplates := []*Template{
		{
			Name:        "go-service",
			Description: "Go microservice template with Gin framework",
			Language:    "go",
			Framework:   "gin",
			Version:     "1.0.0",
			Params: []TemplateParam{
				{Name: "module_name", Description: "Go module name", Type: "string", Default: "github.com/example/service", Required: true, Prompt: "Enter Go module name"},
				{Name: "service_name", Description: "Service name", Type: "string", Default: "my-service", Required: true, Prompt: "Enter service name"},
				{Name: "port", Description: "HTTP port", Type: "number", Default: 8080, Required: true, Prompt: "Enter HTTP port"},
				{Name: "use_db", Description: "Use database", Type: "boolean", Default: false, Required: false, Prompt: "Enable database support?"},
				{Name: "use_redis", Description: "Use Redis", Type: "boolean", Default: false, Required: false, Prompt: "Enable Redis support?"},
			},
			Files: []TemplateFile{
				{Source: "go.mod.tpl", Destination: "go.mod", IsTemplate: true},
				{Source: "main.go.tpl", Destination: "main.go", IsTemplate: true},
				{Source: "README.md.tpl", Destination: "README.md", IsTemplate: true},
				{Source: "Makefile.tpl", Destination: "Makefile", IsTemplate: true},
				{Source: "docker-compose.yml.tpl", Destination: "docker-compose.yml", IsTemplate: true},
			},
		},
		{
			Name:        "react-app",
			Description: "React application template with TypeScript",
			Language:    "typescript",
			Framework:   "react",
			Version:     "1.0.0",
			Params: []TemplateParam{
				{Name: "app_name", Description: "Application name", Type: "string", Default: "my-react-app", Required: true, Prompt: "Enter application name"},
				{Name: "use_typescript", Description: "Use TypeScript", Type: "boolean", Default: true, Required: false, Prompt: "Use TypeScript?"},
				{Name: "use_router", Description: "Use React Router", Type: "boolean", Default: true, Required: false, Prompt: "Include React Router?"},
				{Name: "use_redux", Description: "Use Redux", Type: "boolean", Default: false, Required: false, Prompt: "Include Redux?"},
			},
			Files: []TemplateFile{
				{Source: "package.json.tpl", Destination: "package.json", IsTemplate: true},
				{Source: "tsconfig.json.tpl", Destination: "tsconfig.json", IsTemplate: true},
				{Source: "src/App.tsx.tpl", Destination: "src/App.tsx", IsTemplate: true},
				{Source: "src/main.tsx.tpl", Destination: "src/main.tsx", IsTemplate: true},
			},
		},
		{
			Name:        "python-api",
			Description: "Python FastAPI service template",
			Language:    "python",
			Framework:   "fastapi",
			Version:     "1.0.0",
			Params: []TemplateParam{
				{Name: "project_name", Description: "Project name", Type: "string", Default: "my-api", Required: true, Prompt: "Enter project name"},
				{Name: "python_version", Description: "Python version", Type: "string", Default: "3.11", Required: true, Prompt: "Python version", Options: []string{"3.9", "3.10", "3.11", "3.12"}},
				{Name: "use_database", Description: "Use database", Type: "boolean", Default: true, Required: false, Prompt: "Enable database support?"},
				{Name: "use_auth", Description: "Use authentication", Type: "boolean", Default: false, Required: false, Prompt: "Enable authentication?"},
			},
			Files: []TemplateFile{
				{Source: "requirements.txt.tpl", Destination: "requirements.txt", IsTemplate: true},
				{Source: "main.py.tpl", Destination: "main.py", IsTemplate: true},
				{Source: "pyproject.toml.tpl", Destination: "pyproject.toml", IsTemplate: true},
			},
		},
	}

	for _, tpl := range builtinTemplates {
		sg.templates[tpl.Name] = tpl
	}
}

func (sg *ScaffoldGenerator) RegisterTemplate(tpl *Template) {
	sg.mu.Lock()
	defer sg.mu.Unlock()
	sg.templates[tpl.Name] = tpl
	logger.Info("Template registered", logger.String("template_name", tpl.Name))
}

func (sg *ScaffoldGenerator) GetTemplate(name string) (*Template, error) {
	sg.mu.RLock()
	defer sg.mu.RUnlock()

	tpl, ok := sg.templates[name]
	if !ok {
		return nil, fmt.Errorf("template not found: %s", name)
	}
	return tpl, nil
}

func (sg *ScaffoldGenerator) ListTemplates() []*Template {
	sg.mu.RLock()
	defer sg.mu.RUnlock()

	result := make([]*Template, 0, len(sg.templates))
	for _, tpl := range sg.templates {
		result = append(result, tpl)
	}
	return result
}

func (sg *ScaffoldGenerator) Generate(cfg *ProjectConfig) ([]GeneratedFile, error) {
	sg.mu.RLock()
	tpl, ok := sg.templates[cfg.TemplateName]
	sg.mu.RUnlock()

	if !ok {
		return nil, fmt.Errorf("template not found: %s", cfg.TemplateName)
	}

	if err := sg.validateParams(tpl, cfg.Params); err != nil {
		return nil, fmt.Errorf("parameter validation failed: %w", err)
	}

	if err := os.MkdirAll(cfg.OutputDir, 0755); err != nil {
		return nil, fmt.Errorf("failed to create output directory: %w", err)
	}

	generatedFiles := make([]GeneratedFile, 0, len(tpl.Files))

	for _, file := range tpl.Files {
		content, err := sg.renderFile(tpl, file, cfg.Params)
		if err != nil {
			return nil, fmt.Errorf("failed to render %s: %w", file.Source, err)
		}

		destPath := filepath.Join(cfg.OutputDir, file.Destination)

		if _, err := os.Stat(destPath); err == nil && !cfg.Overwrite {
			return nil, fmt.Errorf("file already exists: %s (use overwrite=true to force)", destPath)
		}

		os.MkdirAll(filepath.Dir(destPath), 0755)
		if err := ioutil.WriteFile(destPath, []byte(content), 0644); err != nil {
			return nil, fmt.Errorf("failed to write %s: %w", destPath, err)
		}

		generatedFiles = append(generatedFiles, GeneratedFile{
			Path: destPath,
			Size: len(content),
		})

		logger.Info("Generated file", logger.String("path", destPath))
	}

	return generatedFiles, nil
}

func (sg *ScaffoldGenerator) validateParams(tpl *Template, params map[string]interface{}) error {
	for _, param := range tpl.Params {
		val, exists := params[param.Name]
		if !exists {
			if param.Required {
				return fmt.Errorf("required parameter missing: %s", param.Name)
			}
			continue
		}

		if err := sg.validateParamType(param, val); err != nil {
			return err
		}
	}
	return nil
}

func (sg *ScaffoldGenerator) validateParamType(param TemplateParam, value interface{}) error {
	switch param.Type {
	case "string":
		_, ok := value.(string)
		if !ok {
			return fmt.Errorf("parameter %s: expected string, got %T", param.Name, value)
		}
	case "number":
		switch value.(type) {
		case float64, int, float32:
		default:
			return fmt.Errorf("parameter %s: expected number, got %T", param.Name, value)
		}
	case "boolean":
		_, ok := value.(bool)
		if !ok {
			return fmt.Errorf("parameter %s: expected boolean, got %T", param.Name, value)
		}
	}
	return nil
}

func (sg *ScaffoldGenerator) renderFile(tpl *Template, file TemplateFile, params map[string]interface{}) (string, error) {
	if !file.IsTemplate {
		return "", fmt.Errorf("non-template files not implemented")
	}

	return sg.renderTemplate(file.Source, params)
}

func (sg *ScaffoldGenerator) renderTemplate(source string, data map[string]interface{}) (string, error) {
	tplContent := sg.getTemplateContent(source)

	tmpl, err := template.New(source).Parse(tplContent)
	if err != nil {
		return "", fmt.Errorf("failed to parse template: %w", err)
	}

	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		return "", fmt.Errorf("failed to execute template: %w", err)
	}

	return buf.String(), nil
}

func (sg *ScaffoldGenerator) getTemplateContent(source string) string {
	templates := map[string]string{
		"go.mod.tpl": `module {{.module_name}}

go 1.21

require (
	github.com/gin-gonic/gin v1.9.1
)
`,
		"main.go.tpl": `package main

import (
	"fmt"
	"github.com/gin-gonic/gin"
)

func main() {
	r := gin.Default()

	r.GET("/health", func(c *gin.Context) {
		c.JSON(200, gin.H{"status": "ok"})
	})

	r.Run(fmt.Sprintf(":%d", {{.port}}))
}
`,
		"README.md.tpl": `# {{.service_name}}

{{.description}}

## Getting Started

\`\`\`bash
go run main.go
\`\`\`
`,
		"Makefile.tpl": `.PHONY: build run test

build:
	go build -o bin/{{.service_name}} .

run:
	go run main.go

test:
	go test ./...
`,
		"docker-compose.yml.tpl": `version: '3.8'
services:
  {{.service_name}}:
    build: .
    ports:
      - "{{.port}}:{{.port}}"
{{if .use_db}}
  db:
    image: postgres:15
    environment:
      POSTGRES_DB: {{.service_name}}
      POSTGRES_PASSWORD: secret
{{end}}
{{if .use_redis}}
  redis:
    image: redis:7
{{end}}
`,
		"package.json.tpl": `{
  "name": "{{.app_name}}",
  "version": "1.0.0",
  "private": true,
  "dependencies": {
    "react": "^18.2.0",
    "react-dom": "^18.2.0"{{if .use_router}},
    "react-router-dom": "^6.8.0"{{end}}{{if .use_redux}},
    "redux": "^4.2.0",
    "react-redux": "^8.0.0"{{end}}
  }
}
`,
		"tsconfig.json.tpl": `{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "jsx": "react-jsx",
    "strict": true,
    "moduleResolution": "node"
  },
  "include": ["src"]
}
`,
		"src/App.tsx.tpl": `import React from 'react';

export default function App() {
  return (
    <div className="app">
      <h1>Welcome to {{.app_name}}</h1>
    </div>
  );
}
`,
		"src/main.tsx.tpl": `import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
`,
		"requirements.txt.tpl": `fastapi==0.104.0
uvicorn[standard]==0.24.0
{{if .use_database}}
sqlalchemy==2.0.0
{{end}}
{{if .use_auth}}
python-jose[cryptography]==3.3.0
passlib[bcrypt]==1.7.4
{{end}}
`,
		"main.py.tpl": `from fastapi import FastAPI

app = FastAPI(title="{{.project_name}}")

@app.get("/health")
async def health_check():
    return {"status": "ok"}
`,
		"pyproject.toml.tpl": `[tool.poetry]
name = "{{.project_name}}"
version = "0.1.0"
description = ""
authors = ["Your Name <you@example.com>"]

[build-system]
requires = ["poetry-core"]
build-backend = "poetry.core.masonry.api"
`,
	}

	if content, ok := templates[source]; ok {
		return content
	}

	return fmt.Sprintf("Template: %s\nParams: %v", source, nil), nil
}

func (sg *ScaffoldGenerator) GetInteractiveQuestions(templateName string) ([]map[string]interface{}, error) {
	tpl, err := sg.GetTemplate(templateName)
	if err != nil {
		return nil, err
	}

	questions := make([]map[string]interface{}, 0, len(tpl.Params))
	for _, param := range tpl.Params {
		q := map[string]interface{}{
			"name":        param.Name,
			"type":        param.Type,
			"message":     param.Prompt,
			"default":     param.Default,
			"required":    param.Required,
			"description": param.Description,
		}
		if len(param.Options) > 0 {
			q["options"] = param.Options
		}
		questions = append(questions, q)
	}

	return questions, nil
}

func (sg *ScaffoldGenerator) ApplyDefaults(templateName string, params map[string]interface{}) map[string]interface{} {
	tpl, err := sg.GetTemplate(templateName)
	if err != nil {
		return params
	}

	result := make(map[string]interface{})
	for k, v := range params {
		result[k] = v
	}

	for _, param := range tpl.Params {
		if _, exists := result[param.Name]; !exists {
			result[param.Name] = param.Default
		}
	}

	return result
}
