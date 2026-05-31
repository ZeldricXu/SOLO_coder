package scaffold

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io/ioutil"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"text/template"
	"time"

	"techplatform/internal/dao"
	"techplatform/pkg/common"
	"techplatform/pkg/common/logger"
	"techplatform/pkg/common/utils"
	"techplatform/pkg/models"

	"github.com/AlecAivazis/survey/v2"
	"gorm.io/gorm"
)

type Template struct {
	models.BaseModel
	Name          string   `json:"name" gorm:"index;size:100"`
	Description   string   `json:"description"`
	Category      string   `json:"category" gorm:"index;size:50"`
	Language      string   `json:"language" gorm:"index;size:50"`
	Framework     string   `json:"framework" gorm:"size:100"`
	Version       string   `json:"version" gorm:"size:50"`
	Path          string   `json:"path"`
	Params        string   `json:"params"`
	Tags          string   `json:"tags"`
	Author        string   `json:"author"`
	IsOfficial    bool     `json:"is_official"`
	Downloads     int      `json:"downloads"`
	Rating        float64  `json:"rating"`
	Enabled       bool     `json:"enabled" gorm:"index"`
}

type TemplateParam struct {
	Name         string      `json:"name"`
	Type         string      `json:"type"`
	Default      interface{} `json:"default"`
	Description  string      `json:"description"`
	Required     bool        `json:"required"`
	Options      []string    `json:"options,omitempty"`
	Validation   string      `json:"validation,omitempty"`
}

type GenerationRequest struct {
	TemplateID  string                 `json:"template_id"`
	ProjectName string                 `json:"project_name"`
	OutputPath string                 `json:"output_path"`
	Params     map[string]interface{} `json:"params"`
	Overwrite  bool                   `json:"overwrite"`
	InitGit    bool                   `json:"init_git"`
}

type GenerationResult struct {
	ID           string    `json:"id"`
	TemplateName string    `json:"template_name"`
	ProjectName string    `json:"project_name"`
	OutputPath  string    `json:"output_path"`
	Files       []string  `json:"files"`
	CreatedAt   time.Time `json:"created_at"`
	Duration    int64     `json:"duration_ms"`
	Success     bool      `json:"success"`
	Error       string    `json:"error,omitempty"`
}

type InteractiveQuestion struct {
	Name        string      `json:"name"`
	Message     string      `json:"message"`
	Type        string      `json:"type"`
	Default     interface{} `json:"default"`
	Options     []string    `json:"options,omitempty"`
	Validation  string      `json:"validation,omitempty"`
}

type GenerationHistory struct {
	models.BaseModel
	TemplateID   string    `json:"template_id"`
	TemplateName string    `json:"template_name"`
	ProjectName  string    `json:"project_name"`
	OutputPath   string    `json:"output_path"`
	Params       string    `json:"params"`
	Files        string    `json:"files"`
	CreatedBy    string    `json:"created_by"`
	Duration     int64     `json:"duration_ms"`
	Success      bool      `json:"success"`
	Error        string    `json:"error"`
}

type ScaffoldManager struct {
	mu            sync.RWMutex
	db            *dao.DAO
	templatePath  string
	outputPath    string
	templates     map[string]*Template
}

func NewScaffoldManager(db *dao.DAO, templatePath, outputPath string) *ScaffoldManager {
	if templatePath == "" {
		templatePath = "./templates"
	}
	if outputPath == "" {
		outputPath = "./output"
	}
	os.MkdirAll(templatePath, 0755)
	os.MkdirAll(outputPath, 0755)

	sm := &ScaffoldManager{
		db:           db,
		templatePath: templatePath,
		outputPath:   outputPath,
		templates:    make(map[string]*Template),
	}

	db.AutoMigrate(&Template{}, &GenerationHistory{})
	sm.loadBuiltinTemplates()
	sm.loadTemplatesFromDB()
	logger.Info("Scaffold manager initialized, template path: %s, output path: %s", templatePath, outputPath)
	return sm
}

func (sm *ScaffoldManager) loadBuiltinTemplates() {
	builtinTemplates := []*Template{
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "go-rest-api",
			Description: "Go语言RESTful API服务模板，包含Gin框架、JWT认证、数据库ORM、配置管理等",
			Category:    "backend",
			Language:    "go",
			Framework:   "gin",
			Version:     "1.0.0",
			Path:        "go-rest-api",
			Tags:        "api,rest,gin,go",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "go-cli",
			Description: "Go语言命令行工具模板，包含Cobra命令框架、配置文件管理",
			Category:    "cli",
			Language:    "go",
			Framework:   "cobra",
			Version:     "1.0.0",
			Path:        "go-cli",
			Tags:        "cli,cobra,go",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "java-spring-boot",
			Description: "Java Spring Boot 后端服务模板，包含Spring Security、MyBatis等",
			Category:    "backend",
			Language:    "java",
			Framework:   "spring-boot",
			Version:     "1.0.0",
			Path:        "java-spring-boot",
			Tags:        "spring-boot,java,web",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "python-fastapi",
			Description: "Python FastAPI 后端服务模板，包含Pydantic、Uvicorn",
			Category:    "backend",
			Language:    "python",
			Framework:   "fastapi",
			Version:     "1.0.0",
			Path:        "python-fastapi",
			Tags:        "fastapi,python,api",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "react-webapp",
			Description: "React Web应用模板，包含TypeScript、Ant Design、状态管理",
			Category:    "frontend",
			Language:    "typescript",
			Framework:   "react",
			Version:     "1.0.0",
			Path:        "react-webapp",
			Tags:        "react,web,frontend",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "vue3-webapp",
			Description: "Vue 3 Web应用模板，包含TypeScript、Vite、Pinia",
			Category:    "frontend",
			Language:    "typescript",
			Framework:   "vue3",
			Version:     "1.0.0",
			Path:        "vue3-webapp",
			Tags:        "vue,web,frontend",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "microservice",
			Description: "微服务模板，包含服务发现、配置中心、链路追踪",
			Category:    "microservice",
			Language:    "go",
			Framework:   "grpc",
			Version:     "1.0.0",
			Path:        "microservice",
			Tags:        "microservice,grpc",
			IsOfficial: true,
			Enabled:     true,
		},
		{
			BaseModel:   models.BaseModel{ID: utils.GenerateUUID()},
			Name:        "library-go",
			Description: "Go语言库项目模板，包含单元测试、CI配置",
			Category:    "library",
			Language:    "go",
			Framework:   "none",
			Version:     "1.0.0",
			Path:        "library-go",
			Tags:        "library,go",
			IsOfficial: true,
			Enabled:     true,
		},
	}

	for _, tpl := range builtinTemplates {
		var existing Template
		result := sm.db.DB().Where("name = ?", tpl.Name).First(&existing)
		if result.Error == gorm.ErrRecordNotFound {
			sm.db.DB().Create(tpl)
		}
	}
}

func (sm *ScaffoldManager) loadTemplatesFromDB() {
	var templates []Template
	sm.db.DB().Where("enabled = ?", true).Find(&templates)
	for i := range templates {
		sm.templates[templates[i].ID] = &templates[i]
	}
	logger.Info("Loaded %d templates from database", len(templates))
}

func (sm *ScaffoldManager) GetTemplate(id string) (*Template, error) {
	sm.mu.RLock()
	if tpl, exists := sm.templates[id]; exists {
		sm.mu.RUnlock()
		return tpl, nil
	}
	sm.mu.RUnlock()

	var tpl Template
	if err := sm.db.DB().First(&tpl, "id = ?", id).Error; err != nil {
		return nil, common.ErrNotFound
	}
	return &tpl, nil
}

func (sm *ScaffoldManager) ListTemplates(page, pageSize int, category, language, keyword string) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var templates []Template
	var total int64

	query := sm.db.DB().Model(&Template{}).Where("enabled = ?", true)
	if category != "" {
		query = query.Where("category = ?", category)
	}
	if language != "" {
		query = query.Where("language = ?", language)
	}
	if keyword != "" {
		keyword = "%" + keyword + "%"
		query = query.Where("name LIKE ? OR description LIKE ? OR tags LIKE ?", keyword, keyword, keyword)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("is_official DESC, downloads DESC").Find(&templates).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    templates,
	}, nil
}

func (sm *ScaffoldManager) GetTemplateParams(templateID string) ([]TemplateParam, error) {
	tpl, err := sm.GetTemplate(templateID)
	if err != nil {
		return nil, err
	}

	defaultParams := sm.getDefaultParams(tpl)
	if tpl.Params != "" {
		var customParams []TemplateParam
		if err := json.Unmarshal([]byte(tpl.Params), &customParams); err == nil {
			return customParams, nil
		}
	}

	return defaultParams, nil
}

func (sm *ScaffoldManager) getDefaultParams(tpl *Template) []TemplateParam {
	baseParams := []TemplateParam{
		{Name: "module_name", Type: "string", Default: tpl.Name, Description: "模块/包名称", Required: true},
		{Name: "author", Type: "string", Default: "anonymous", Description: "作者名称", Required: false},
		{Name: "version", Type: "string", Default: "0.1.0", Description: "初始版本", Required: false},
		{Name: "description", Type: "string", Default: "A project generated by scaffold", Description: "项目描述", Required: false},
		{Name: "license", Type: "string", Default: "MIT", Description: "开源协议", Required: false, Options: []string{"MIT", "Apache-2.0", "GPL-3.0", "BSD-3-Clause"}},
		{Name: "use_docker", Type: "boolean", Default: true, Description: "是否生成Docker配置", Required: false},
		{Name: "use_ci", Type: "boolean", Default: true, Description: "是否生成CI/CD配置", Required: false},
	}

	switch tpl.Language {
	case "go":
		baseParams = append(baseParams,
			TemplateParam{Name: "go_version", Type: "string", Default: "1.21", Description: "Go版本", Required: true},
			TemplateParam{Name: "use_database", Type: "boolean", Default: true, Description: "是否需要数据库支持", Required: false},
			TemplateParam{Name: "database_type", Type: "string", Default: "mysql", Description: "数据库类型", Required: false, Options: []string{"mysql", "postgresql", "sqlite", "mongodb"}},
		)
	case "java":
		baseParams = append(baseParams,
			TemplateParam{Name: "java_version", Type: "string", Default: "17", Description: "Java版本", Required: true},
			TemplateParam{Name: "build_tool", Type: "string", Default: "maven", Description: "构建工具", Required: false, Options: []string{"maven", "gradle"}},
		)
	case "python":
		baseParams = append(baseParams,
			TemplateParam{Name: "python_version", Type: "string", Default: "3.10", Description: "Python版本", Required: true},
			TemplateParam{Name: "package_manager", Type: "string", Default: "pip", Description: "包管理器", Required: false, Options: []string{"pip", "poetry", "pipenv"}},
		)
	case "typescript":
		baseParams = append(baseParams,
			TemplateParam{Name: "node_version", Type: "string", Default: "18", Description: "Node版本", Required: true},
			TemplateParam{Name: "package_manager", Type: "string", Default: "npm", Description: "包管理器", Required: false, Options: []string{"npm", "yarn", "pnpm"}},
			TemplateParam{Name: "use_typescript", Type: "boolean", Default: true, Description: "是否使用TypeScript", Required: false},
		)
	}

	return baseParams
}

func (sm *ScaffoldManager) Generate(ctx context.Context, req GenerationRequest, createdBy string) (*GenerationResult, error) {
	startTime := time.Now()

	if req.TemplateID == "" {
		return nil, fmt.Errorf("%w: template ID required", common.ErrInvalidInput)
	}
	if req.ProjectName == "" {
		return nil, fmt.Errorf("%w: project name required", common.ErrInvalidInput)
	}

	tpl, err := sm.GetTemplate(req.TemplateID)
	if err != nil {
		return nil, fmt.Errorf("template not found: %w", err)
	}

	if req.Params == nil {
		req.Params = make(map[string]interface{})
	}
	req.Params["project_name"] = req.ProjectName

	sm.mu.Lock()
	tpl.Downloads++
	sm.db.DB().Save(tpl)
	sm.mu.Unlock()

	outputPath := req.OutputPath
	if outputPath == "" {
		outputPath = filepath.Join(sm.outputPath, req.ProjectName)
	}

	if !req.Overwrite {
		if _, err := os.Stat(outputPath); err == nil {
			return nil, fmt.Errorf("%w: output path already exists", common.ErrAlreadyExists)
		}
	}

	generatedFiles, err := sm.generateFromTemplate(tpl, req)
	if err != nil {
		sm.saveHistory(tpl, req, generatedFiles, createdBy, time.Since(startTime).Milliseconds(), false, err.Error())
		return nil, err
	}

	duration := time.Since(startTime).Milliseconds()
	sm.saveHistory(tpl, req, generatedFiles, createdBy, duration, true, "")

	result := &GenerationResult{
		ID:           utils.GenerateUUID(),
		TemplateName: tpl.Name,
		ProjectName:  req.ProjectName,
		OutputPath:   outputPath,
		Files:        generatedFiles,
		CreatedAt:   time.Now(),
		Duration:    duration,
		Success:     true,
	}

	logger.Info("Project generated successfully: %s (template: %s, files: %d, duration: %dms)",
		req.ProjectName, tpl.Name, len(generatedFiles), duration)

	return result, nil
}

func (sm *ScaffoldManager) generateFromTemplate(tpl *Template, req GenerationRequest) ([]string, error) {
	outputPath := req.OutputPath
	if outputPath == "" {
		outputPath = filepath.Join(sm.outputPath, req.ProjectName)
	}

	if err := os.MkdirAll(outputPath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create output directory: %w", err)
	}

	var generatedFiles []string

	fileGenerators := sm.getFileGenerators(tpl, req)

	for fileName, contentGenerator := range fileGenerators {
		fullPath := filepath.Join(outputPath, fileName)
		dir := filepath.Dir(fullPath)
		if err := os.MkdirAll(dir, 0755); err != nil {
			return generatedFiles, err
		}

		content, err := contentGenerator()
		if err != nil {
			logger.Warn("Failed to generate file %s: %v", fileName, err)
			continue
		}

		if err := ioutil.WriteFile(fullPath, []byte(content), 0644); err != nil {
			return generatedFiles, err
		}
		generatedFiles = append(generatedFiles, fileName)
	}

	return generatedFiles, nil
}

func (sm *ScaffoldManager) getFileGenerators(tpl *Template, req GenerationRequest) map[string]func() (string, error) {
	generators := make(map[string]func() (string, error))

	params := req.Params
	if params == nil {
		params = make(map[string]interface{})
	}

	moduleName, _ := params["module_name"].(string)
	author, _ := params["author"].(string)
	version, _ := params["version"].(string)
	description, _ := params["description"].(string)
	license, _ := params["license"].(string)

	data := map[string]interface{}{
		"ProjectName":  req.ProjectName,
		"ModuleName": moduleName,
		"Author":     author,
		"Version":    version,
		"Description": description,
		"License":    license,
		"Year":       time.Now().Year(),
		"CreatedAt":  time.Now().Format("2006-01-02"),
	}

	for k, v := range params {
		data[k] = v
	}

	generators["README.md"] = func() (string, error) {
		return renderTemplate(`# {{.ProjectName}}

{{.Description}}

## 项目介绍

这是一个使用 TechPlatform 脚手架生成的项目。

- **模板**: ` + tpl.Name + `
- **版本**: {{.Version}}
- **作者**: {{.Author}}
- **创建时间**: {{.CreatedAt}}

## 快速开始

根据项目类型查看具体的使用说明。

## License

{{.License}}
`, data)
	}

	generators[".gitignore"] = func() (string, error) {
		return `# OS
.DS_Store
Thumbs.db

# Editor
.idea/
.vscode/
*.swp
*.swo

# Build
dist/
build/
target/
*.exe
*.dll
*.so

# Logs
logs/
*.log

# Env
.env
.env.local
.env.*.local
`, nil
	}

	generators["LICENSE"] = func() (string, error) {
		return getLicenseContent(license, author), nil
	}

	switch tpl.Language {
	case "go":
		sm.addGoTemplates(generators, tpl, data, params)
	case "java":
		sm.addJavaTemplates(generators, tpl, data, params)
	case "python":
		sm.addPythonTemplates(generators, tpl, data, params)
	case "typescript":
		sm.addTypeScriptTemplates(generators, tpl, data, params)
	}

	if useDocker, ok := params["use_docker"].(bool); ok && useDocker {
		sm.addDockerTemplates(generators, tpl, data)
	}

	if useCI, ok := params["use_ci"].(bool); ok && useCI {
		sm.addCITemplates(generators, tpl, data)
	}

	return generators
}

func (sm *ScaffoldManager) addGoTemplates(generators map[string]func() (string, error), tpl *Template, data map[string]interface{}, params map[string]interface{}) {
	moduleName, _ := params["module_name"].(string)
	goVersion, _ := params["go_version"].(string)

	generators["go.mod"] = func() (string, error) {
		return renderTemplate(`module {{.ModuleName}}

go {{.GoVersion}}

require (
	github.com/gin-gonic/gin v1.9.1
	github.com/spf13/viper v1.17.0
	gorm.io/gorm v1.25.5
)
`, map[string]interface{}{
			"ModuleName": moduleName,
			"GoVersion": goVersion,
		})
	}

	generators["main.go"] = func() (string, error) {
		return renderTemplate(`package main

import (
	"log"
	"{{.ModuleName}}/internal/config"
	"{{.ModuleName}}/internal/server"
)

func main() {
	if err := config.Load(); err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	s := server.New()
	if err := s.Run(); err != nil {
		log.Fatalf("Failed to run server: %v", err)
	}
}
`, map[string]interface{}{"ModuleName": moduleName})
	}

	generators["internal/config/config.go"] = func() (string, error) {
		return `package config

func Load() error {
	return nil
}
`, nil
	}

	generators["internal/server/server.go"] = func() (string, error) {
		return `package server

import "github.com/gin-gonic/gin"

type Server struct {
	engine *gin.Engine
}

func New() *Server {
	return &Server{
		engine: gin.Default(),
	}
}

func (s *Server) Run() error {
	return s.engine.Run(":8080")
}
`, nil
	}

	if useDB, ok := params["use_database"].(bool); ok && useDB {
		dbType, _ := params["database_type"].(string)
		generators["internal/db/db.go"] = func() (string, error) {
			return renderTemplate(`package db

import (
	"gorm.io/driver/` + dbType + `"
	"gorm.io/gorm"
)

func Connect(dsn string) (*gorm.DB, error) {
	return gorm.Open(`+dbType+`.Open(dsn), &gorm.Config{})
}
`, nil)
		}
	}
}

func (sm *ScaffoldManager) addJavaTemplates(generators map[string]func() (string, error), tpl *Template, data map[string]interface{}, params map[string]interface{}) {
	javaVersion, _ := params["java_version"].(string)

	generators["pom.xml"] = func() (string, error) {
		return renderTemplate(`<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.2.0</version>
        <relativePath/>
    </parent>
    <groupId>com.example</groupId>
    <artifactId>{{.ProjectName}}</artifactId>
    <version>{{.Version}}</version>
    <name>{{.ProjectName}}</name>
    <description>{{.Description}}</description>
    <properties>
        <java.version>{{.JavaVersion}}</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
    </dependencies>
</project>
`, map[string]interface{}{
			"ProjectName": data["ProjectName"],
			"Version":     data["Version"],
			"Description": data["Description"],
			"JavaVersion": javaVersion,
		})
	}

	generators["src/main/java/com/example/Application.java"] = func() (string, error) {
		return `package com.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
`, nil
	}

	generators["src/main/resources/application.yml"] = func() (string, error) {
		return `server:
  port: 8080

spring:
  application:
    name: demo
`, nil
	}
}

func (sm *ScaffoldManager) addPythonTemplates(generators map[string]func() (string, error), tpl *Template, data map[string]interface{}, params map[string]interface{}) {
	pythonVersion, _ := params["python_version"].(string)

	generators["requirements.txt"] = func() (string, error) {
		return `fastapi==0.104.1
uvicorn==0.24.0
pydantic==2.5.0
`, nil
	}

	generators["main.py"] = func() (string, error) {
		return `from fastapi import FastAPI

app = FastAPI(title="{{.ProjectName}}", version="{{.Version}}")


@app.get("/")
async def root():
    return {"message": "Hello World"}
`, nil
	}

	generators["pyproject.toml"] = func() (string, error) {
		return renderTemplate(`[tool.poetry]
name = "{{.ProjectName}}"
version = "{{.Version}}"
description = "{{.Description}}"
authors = ["{{.Author}}"]

[tool.poetry.dependencies]
python = "^{{.PythonVersion}}"

[build-system]
requires = ["poetry-core"]
build-backend = "poetry.core.masonry.api"
`, map[string]interface{}{
			"ProjectName":   data["ProjectName"],
			"Version":       data["Version"],
			"Description": data["Description"],
			"Author":      data["Author"],
			"PythonVersion": pythonVersion,
		})
	}
}

func (sm *ScaffoldManager) addTypeScriptTemplates(generators map[string]func() (string, error), _ *Template, data map[string]interface{}, params map[string]interface{}) {
	_ = params["node_version"]

	generators["package.json"] = func() (string, error) {
		return renderTemplate(`{
  "name": "{{.ProjectName}}",
  "version": "{{.Version}}",
  "description": "{{.Description}}",
  "main": "index.js",
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview"
  },
  "dependencies": {
    "vue": "^3.3.0"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^4.5.0",
    "typescript": "^5.3.0",
    "vite": "^5.0.0"
  }
}
`, data)
	}

	generators["src/main.ts"] = func() (string, error) {
		return `import { createApp } from 'vue'
import App from './App.vue'

const app = createApp(App)
app.mount('#app')
`, nil
	}

	generators["src/App.vue"] = func() (string, error) {
		return `<template>
  <div>
    <h1>Hello {{.ProjectName}}!</h1>
  </div>
</template>

<script setup lang="ts">
</script>
`, nil
	}

	generators["tsconfig.json"] = func() (string, error) {
		return `{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "esModuleInterop": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true
  }
}
`, nil
	}
}

func (sm *ScaffoldManager) addDockerTemplates(generators map[string]func() (string, error), tpl *Template, data map[string]interface{}) {
	dockerfile := `FROM `
	switch tpl.Language {
	case "go":
		dockerfile += `golang:1.21-alpine AS builder
WORKDIR /app
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o main .

FROM alpine:latest
WORKDIR /app
COPY --from=builder /app/main .
EXPOSE 8080
CMD ["./main"]
`
	case "java":
		dockerfile += `maven:3.9-amazoncorretto-17 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn package -DskipTests

FROM amazoncorretto:17-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
`
	case "python":
		dockerfile += `python:3.10-slim
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
`
	default:
		dockerfile += `node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install
COPY . .
RUN npm run build
EXPOSE 5173
CMD ["npm", "run", "dev"]
`
	}

	generators["Dockerfile"] = func() (string, error) {
		return dockerfile, nil
	}

	generators[".dockerignore"] = func() (string, error) {
		return `node_modules
dist
build
.git
.gitignore
Dockerfile
.dockerignore
npm-debug.log
*.md
`, nil
	}

	generators["docker-compose.yml"] = func() (string, error) {
		return `version: '3.8'
services:
  app:
    build: .
    ports:
      - "8080:8080"
    environment:
      - NODE_ENV=production
    restart: unless-stopped
`, nil
	}
}

func (sm *ScaffoldManager) addCITemplates(generators map[string]func() (string, error), tpl *Template, data map[string]interface{}) {
	generators[".github/workflows/ci.yml"] = func() (string, error) {
		ciContent := `name: CI

on:
  push:
    branches: [ main, master ]
  pull_request:
    branches: [ main, master ]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
    - uses: actions/checkout@v4
`
		switch tpl.Language {
		case "go":
			ciContent += `
    - name: Set up Go
      uses: actions/setup-go@v4
      with:
        go-version: '1.21'
    - name: Build
      run: go build -v ./...
    - name: Test
      run: go test -v ./...
`
		case "java":
			ciContent += `
    - name: Set up JDK 17
      uses: actions/setup-java@v4
      with:
        java-version: '17'
        distribution: 'temurin'
    - name: Build with Maven
      run: mvn -B package --file pom.xml
`
		case "python":
			ciContent += `
    - name: Set up Python
      uses: actions/setup-python@v4
      with:
        python-version: '3.10'
    - name: Install dependencies
      run: |
        python -m pip install --upgrade pip
        pip install -r requirements.txt
    - name: Test
      run: python -m pytest
`
		default:
			ciContent += `
    - name: Set up Node.js
      uses: actions/setup-node@v4
      with:
        node-version: '18'
    - name: Install dependencies
      run: npm ci
    - name: Build
      run: npm run build
`
		}

		return ciContent, nil
	}
}

func (sm *ScaffoldManager) GetInteractiveQuestions(templateID string) ([]InteractiveQuestion, error) {
	_, err := sm.GetTemplate(templateID)
	if err != nil {
		return nil, err
	}

	params, err := sm.GetTemplateParams(templateID)
	if err != nil {
		return nil, err
	}

	questions := make([]InteractiveQuestion, 0, len(params)+2)

	questions = append(questions, InteractiveQuestion{
		Name:    "project_name",
		Message: "项目名称",
		Type:    "input",
		Default: "my-project",
	})

	for _, p := range params {
		q := InteractiveQuestion{
			Name:    p.Name,
			Message: p.Description,
			Type:    p.Type,
			Default: p.Default,
			Options: p.Options,
		}
		questions = append(questions, q)
	}

	questions = append(questions, InteractiveQuestion{
		Name:    "overwrite",
		Message: "是否覆盖已存在的目录",
		Type:    "confirm",
		Default: false,
	})

	return questions, nil
}

func (sm *ScaffoldManager) RunInteractive(templateID string) (map[string]interface{}, error) {
	questions, err := sm.GetInteractiveQuestions(templateID)
	if err != nil {
		return nil, err
	}

	answers := make(map[string]interface{})

	for _, q := range questions {
		var prompt survey.Prompt

		switch q.Type {
		case "input", "string":
			prompt = &survey.Input{
				Message: q.Message,
				Default: fmt.Sprintf("%v", q.Default),
			}
		case "select":
			prompt = &survey.Select{
				Message: q.Message,
				Options: q.Options,
				Default: q.Default,
			}
		case "confirm", "boolean":
			prompt = &survey.Confirm{
				Message: q.Message,
				Default: q.Default.(bool),
			}
		default:
			prompt = &survey.Input{
				Message: q.Message,
				Default: fmt.Sprintf("%v", q.Default),
			}
		}

		var answer interface{}
		if err := survey.AskOne(prompt, &answer); err != nil {
			return nil, err
		}
		answers[q.Name] = answer
	}

	return answers, nil
}

func (sm *ScaffoldManager) GetHistory(page, pageSize int, templateID, createdBy string) (*models.PageResult, error) {
	page, pageSize = normalizePagination(page, pageSize)

	var history []GenerationHistory
	var total int64

	query := sm.db.DB().Model(&GenerationHistory{})
	if templateID != "" {
		query = query.Where("template_id = ?", templateID)
	}
	if createdBy != "" {
		query = query.Where("created_by = ?", createdBy)
	}

	query.Count(&total)
	offset := (page - 1) * pageSize
	if err := query.Offset(offset).Limit(pageSize).Order("created_at DESC").Find(&history).Error; err != nil {
		return nil, err
	}

	return &models.PageResult{
		Total:    total,
		Page:     page,
		PageSize: pageSize,
		Items:    history,
	}, nil
}

func (sm *ScaffoldManager) GetStats() map[string]interface{} {
	var totalTemplates int64
	var totalGenerations int64
	var successRate float64

	sm.db.DB().Model(&Template{}).Where("enabled = ?", true).Count(&totalTemplates)
	sm.db.DB().Model(&GenerationHistory{}).Count(&totalGenerations)

	var successCount int64
	sm.db.DB().Model(&GenerationHistory{}).Where("success = ?", true).Count(&successCount)
	if totalGenerations > 0 {
		successRate = float64(successCount) / float64(totalGenerations) * 100
	}

	byLanguage := make(map[string]int64)
	rows, _ := sm.db.DB().Model(&Template{}).Select("language, COUNT(*) as count").Group("language").Rows()
	for rows.Next() {
		var lang string
		var count int64
		rows.Scan(&lang, &count)
		byLanguage[lang] = count
	}
	rows.Close()

	byCategory := make(map[string]int64)
	rows, _ = sm.db.DB().Model(&Template{}).Select("category, COUNT(*) as count").Group("category").Rows()
	for rows.Next() {
		var cat string
		var count int64
		rows.Scan(&cat, &count)
		byCategory[cat] = count
	}
	rows.Close()

	var totalDownloads int64
	sm.db.DB().Model(&Template{}).Select("COALESCE(SUM(downloads), 0").Scan(&totalDownloads)

	return map[string]interface{}{
		"total_templates":   totalTemplates,
		"total_generations": totalGenerations,
		"success_rate":      successRate,
		"total_downloads":   totalDownloads,
		"by_language":     byLanguage,
		"by_category":      byCategory,
		"template_path":     sm.templatePath,
		"output_path":     sm.outputPath,
	}
}

func (sm *ScaffoldManager) saveHistory(tpl *Template, req GenerationRequest, files []string, createdBy string, duration int64, success bool, errMsg string) {
	history := &GenerationHistory{
		BaseModel:    models.BaseModel{ID: utils.GenerateUUID()},
		TemplateID:   tpl.ID,
		TemplateName: tpl.Name,
		ProjectName:  req.ProjectName,
		OutputPath:   req.OutputPath,
		Params:       utils.ToJSON(req.Params),
		Files:        strings.Join(files, ","),
		CreatedBy:    createdBy,
		Duration:     duration,
		Success:      success,
		Error:       errMsg,
	}
	sm.db.DB().Create(history)
}

func renderTemplate(tmplStr string, data interface{}) (string, error) {
	tmpl, err := template.New("").Parse(tmplStr)
	if err != nil {
		return "", err
	}
	var buf bytes.Buffer
	if err := tmpl.Execute(&buf, data); err != nil {
		return "", err
	}
	return buf.String(), nil
}

func getLicenseContent(license, author string) string {
	year := fmt.Sprintf("%d", time.Now().Year())
	switch license {
	case "MIT":
		return fmt.Sprintf(`MIT License

Copyright (c) ` + year + ` ` + author + `

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
`)
	case "Apache-2.0":
		return `Apache License 2.0
...
`
	default:
		return `Copyright (c) ` + year + ` ` + author + `
All Rights Reserved.
`
	}
}

func normalizePagination(page, pageSize int) (int, int) {
	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}
	if pageSize > 100 {
		pageSize = 100
	}
	return page, pageSize
}
