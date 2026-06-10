package dailynote

import (
	"os"
	"path/filepath"
	"strings"

	"github.com/google/uuid"
	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

type TemplateManager struct {
	cfg         *config.Config
	resolver    *VariableResolver
	builtinTpls map[string]*models.Template
}

func NewTemplateManager(cfg *config.Config) *TemplateManager {
	tm := &TemplateManager{
		cfg:         cfg,
		resolver:    NewVariableResolver(),
		builtinTpls: make(map[string]*models.Template),
	}
	tm.registerBuiltinTemplates()
	return tm
}

func (tm *TemplateManager) registerBuiltinTemplates() {
	tm.builtinTpls["daily-note"] = &models.Template{
		ID:       "builtin-daily-note",
		Name:     "每日笔记",
		IsBuiltin: true,
		Content: `---
title: {{date}}
date: {{date}}
tags: [daily, journal]
---

# {{date}} {{weekday}}

> 天气: {{weather}}

## 今日计划

- [ ] 

## 今日回顾

### 完成的事情

### 学习与成长

### 感恩与思考

## 待办汇总

{{todos}}

## 相关笔记

- 昨天: [[{{yesterday}}]]
- 明天: [[{{tomorrow}}]]
`,
	}

	tm.builtinTpls["project-note"] = &models.Template{
		ID:       "builtin-project-note",
		Name:     "项目笔记",
		IsBuiltin: true,
		Content: `---
title: {{title}}
date: {{date}}
tags: [project]
status: active
---

# {{title}}

## 项目概述

## 目标与里程碑

- [ ] 里程碑1
- [ ] 里程碑2

## 任务列表

- [ ] 

## 会议记录

## 资源链接

## 备注
`,
	}

	tm.builtinTpls["meeting-note"] = &models.Template{
		ID:       "builtin-meeting-note",
		Name:     "会议记录",
		IsBuiltin: true,
		Content: `---
title: {{title}}
date: {{datetime}}
tags: [meeting]
---

# {{title}}

**日期**: {{datetime}}
**参会人员**: 

## 会议议程

1. 

## 讨论内容

## 决议事项

## 行动项

- [ ] 

## 下次会议
`,
	}

	tm.builtinTpls["weekly-review"] = &models.Template{
		ID:       "builtin-weekly-review",
		Name:     "周回顾",
		IsBuiltin: true,
		Content: `---
title: {{date}} 周回顾
date: {{date}}
tags: [weekly, review]
---

# {{date}} 周回顾

## 本周目标

- [ ] 

## 完成情况

### 工作

### 学习

### 生活

## 本周亮点

## 遇到的问题

## 下周计划

- [ ] 

## 反思与改进
`,
	}

	tm.builtinTpls["book-note"] = &models.Template{
		ID:       "builtin-book-note",
		Name:     "读书笔记",
		IsBuiltin: true,
		Content: `---
title: {{title}}
date: {{date}}
tags: [book, reading]
author: 
category: 
rating: 
---

# {{title}}

## 基本信息

- **作者**: 
- **出版社**: 
- **出版日期**: 
- **阅读日期**: {{date}}

## 书籍简介

## 核心观点

## 精彩摘录

> 

## 个人感悟

## 行动清单

- [ ] 

## 相关主题
`,
	}
}

func (tm *TemplateManager) ListTemplates() ([]*models.Template, error) {
	var templates []*models.Template

	for _, tpl := range tm.builtinTpls {
		templates = append(templates, tpl)
	}

	customTemplates, err := tm.scanTemplateDir()
	if err != nil {
		return templates, err
	}

	templates = append(templates, customTemplates...)

	return templates, nil
}

func (tm *TemplateManager) scanTemplateDir() ([]*models.Template, error) {
	templatePath := tm.cfg.TemplatePath
	if templatePath == "" {
		return nil, nil
	}

	if _, err := os.Stat(templatePath); os.IsNotExist(err) {
		return nil, nil
	}

	var templates []*models.Template

	err := filepath.Walk(templatePath, func(path string, info os.FileInfo, err error) error {
		if err != nil {
			return err
		}

		if info.IsDir() {
			return nil
		}

		if !utils.IsMarkdownFile(path) {
			return nil
		}

		content, err := os.ReadFile(path)
		if err != nil {
			return nil
		}

		relPath, _ := filepath.Rel(templatePath, path)
		name := strings.TrimSuffix(relPath, filepath.Ext(relPath))

		templates = append(templates, &models.Template{
			ID:        "custom-" + uuid.New().String(),
			Name:      name,
			Content:   string(content),
			Path:      path,
			IsBuiltin: false,
		})

		return nil
	})

	if err != nil {
		return nil, err
	}

	return templates, nil
}

func (tm *TemplateManager) GetTemplate(id string) (*models.Template, error) {
	if strings.HasPrefix(id, "builtin-") {
		key := strings.TrimPrefix(id, "builtin-")
		if tpl, ok := tm.builtinTpls[key]; ok {
			return tpl, nil
		}
	}

	templates, err := tm.scanTemplateDir()
	if err != nil {
		return nil, err
	}

	for _, tpl := range templates {
		if tpl.ID == id {
			return tpl, nil
		}
	}

	return nil, os.ErrNotExist
}

func (tm *TemplateManager) GetTemplateByName(name string) (*models.Template, error) {
	for _, tpl := range tm.builtinTpls {
		if tpl.Name == name {
			return tpl, nil
		}
	}

	templates, err := tm.scanTemplateDir()
	if err != nil {
		return nil, err
	}

	for _, tpl := range templates {
		if tpl.Name == name {
			return tpl, nil
		}
	}

	return nil, os.ErrNotExist
}

func (tm *TemplateManager) CreateTemplate(name, content string) (*models.Template, error) {
	templatePath := tm.cfg.TemplatePath
	if templatePath == "" {
		return nil, os.ErrInvalid
	}

	if err := os.MkdirAll(templatePath, 0755); err != nil {
		return nil, err
	}

	filename := utils.SanitizeFilename(name) + ".md"
	fullPath := filepath.Join(templatePath, filename)

	if err := os.WriteFile(fullPath, []byte(content), 0644); err != nil {
		return nil, err
	}

	return &models.Template{
		ID:        "custom-" + uuid.New().String(),
		Name:      name,
		Content:   content,
		Path:      fullPath,
		IsBuiltin: false,
	}, nil
}

func (tm *TemplateManager) UpdateTemplate(id, name, content string) (*models.Template, error) {
	tpl, err := tm.GetTemplate(id)
	if err != nil {
		return nil, err
	}

	if tpl.IsBuiltin {
		return nil, os.ErrPermission
	}

	if tpl.Path == "" {
		return nil, os.ErrInvalid
	}

	if name != "" {
		templatePath := tm.cfg.TemplatePath
		filename := utils.SanitizeFilename(name) + ".md"
		newPath := filepath.Join(templatePath, filename)

		if newPath != tpl.Path {
			if err := os.Rename(tpl.Path, newPath); err != nil {
				return nil, err
			}
			tpl.Path = newPath
			tpl.Name = name
		}
	}

	if err := os.WriteFile(tpl.Path, []byte(content), 0644); err != nil {
		return nil, err
	}

	tpl.Content = content

	return tpl, nil
}

func (tm *TemplateManager) DeleteTemplate(id string) error {
	tpl, err := tm.GetTemplate(id)
	if err != nil {
		return err
	}

	if tpl.IsBuiltin {
		return os.ErrPermission
	}

	if tpl.Path == "" {
		return os.ErrInvalid
	}

	return os.Remove(tpl.Path)
}

func (tm *TemplateManager) RenderTemplate(id string, variables map[string]string) (string, error) {
	tpl, err := tm.GetTemplate(id)
	if err != nil {
		return "", err
	}

	return tm.RenderContent(tpl.Content, variables)
}

func (tm *TemplateManager) RenderContent(content string, variables map[string]string) (string, error) {
	resolver := NewVariableResolver()
	if variables != nil {
		resolver.SetCustom(variables)
	}

	return resolver.Resolve(content)
}

func (tm *TemplateManager) SetVariable(name, value string) {
	tm.resolver.Set(name, value)
}

func (tm *TemplateManager) RegisterVariable(name string, fn VariableFunc) {
	tm.resolver.Register(name, fn)
}

func (tm *TemplateManager) GetBuiltinTemplates() []*models.Template {
	var templates []*models.Template
	for _, tpl := range tm.builtinTpls {
		templates = append(templates, tpl)
	}
	return templates
}

func (tm *TemplateManager) GetCustomTemplates() ([]*models.Template, error) {
	return tm.scanTemplateDir()
}
