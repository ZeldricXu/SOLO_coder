package config

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"time"
)

var (
	Version = "dev"
	Commit  = "none"
	Date    = "unknown"
)

const (
	AppName         = "kbnote"
	AppNameHuman    = "KB Note"
	DefaultFileName = "config.toml"
	LegacyJSONFile  = "config.json"
	ConfigDirEnv    = "XDG_CONFIG_HOME"
	DataDirEnv      = "XDG_DATA_HOME"
)

type Config struct {
	VaultPath     string `json:"vault_path" toml:"vault_path"`
	DBPath        string `json:"db_path" toml:"db_path"`
	IndexPath     string `json:"index_path" toml:"index_path"`
	PluginPath    string `json:"plugin_path" toml:"plugin_path"`
	TemplatePath  string `json:"template_path" toml:"template_path"`
	DailyNotePath string `json:"daily_note_path" toml:"daily_note_path"`

	Search struct {
		BM25K1          float64 `json:"bm25_k1" toml:"bm25_k1"`
		BM25B           float64 `json:"bm25_b" toml:"bm25_b"`
		UseCJK          bool    `json:"use_cjk" toml:"use_cjk"`
		EnableSemantic  bool    `json:"enable_semantic" toml:"enable_semantic"`
		OllamaBaseURL   string  `json:"ollama_base_url" toml:"ollama_base_url"`
		EmbeddingModel  string  `json:"embedding_model" toml:"embedding_model"`
		VectorIndexPath string  `json:"vector_index_path" toml:"vector_index_path"`
		BM25Weight      float64 `json:"bm25_weight" toml:"bm25_weight"`
		VectorWeight    float64 `json:"vector_weight" toml:"vector_weight"`
		IndexPath       string  `json:"index_path" toml:"index_path"`
	} `json:"search" toml:"search"`

	Graph struct {
		NodeMinSize     int `json:"node_min_size" toml:"node_min_size"`
		NodeMaxSize     int `json:"node_max_size" toml:"node_max_size"`
		RepulsiveForce  int `json:"repulsive_force" toml:"repulsive_force"`
		AttractiveForce int `json:"attractive_force" toml:"attractive_force"`
	} `json:"graph" toml:"graph"`

	Editor struct {
		DefaultMode      string `json:"default_mode" toml:"default_mode"`
		AutoSave         bool   `json:"auto_save" toml:"auto_save"`
		AutoSaveInterval int    `json:"auto_save_interval" toml:"auto_save_interval"`
		FontFamily       string `json:"font_family" toml:"font_family"`
		FontSize         int    `json:"font_size" toml:"font_size"`
		TabWidth         int    `json:"tab_width" toml:"tab_width"`
		LineNumbers      bool   `json:"line_numbers" toml:"line_numbers"`
		WordWrap         bool   `json:"word_wrap" toml:"word_wrap"`
	} `json:"editor" toml:"editor"`

	Theme struct {
		Name              string `json:"name" toml:"name"`
		ColorScheme       string `json:"color_scheme" toml:"color_scheme"`
		AccentColor       string `json:"accent_color" toml:"accent_color"`
		BgColor           string `json:"bg_color" toml:"bg_color"`
		TextColor         string `json:"text_color" toml:"text_color"`
		BorderColor       string `json:"border_color" toml:"border_color"`
		SidebarBgColor    string `json:"sidebar_bg_color" toml:"sidebar_bg_color"`
		SidebarTextColor  string `json:"sidebar_text_color" toml:"sidebar_text_color"`
	} `json:"theme" toml:"theme"`

	Server struct {
		Host string `json:"host" toml:"host"`
		Port int    `json:"port" toml:"port"`
	} `json:"server" toml:"server"`

	UI struct {
		SidebarWidth    int    `json:"sidebar_width" toml:"sidebar_width"`
		InitialView     string `json:"initial_view" toml:"initial_view"`
		WindowWidth     int    `json:"window_width" toml:"window_width"`
		WindowHeight    int    `json:"window_height" toml:"window_height"`
		Language        string `json:"language" toml:"language"`
		ShowStatusBar   bool   `json:"show_status_bar" toml:"show_status_bar"`
		ShowToolbar     bool   `json:"show_toolbar" toml:"show_toolbar"`
	} `json:"ui" toml:"ui"`
}

func (c *Config) BuildInfo() string {
	return fmt.Sprintf("%s v%s (commit %s, built %s)", AppNameHuman, Version, Commit, Date)
}

func BuildInfo() string {
	return Default().BuildInfo()
}

func DefaultConfigDir() (string, error) {
	if xdg := os.Getenv(ConfigDirEnv); xdg != "" {
		return filepath.Join(xdg, AppName), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".config", AppName), nil
}

func DefaultDataDir() (string, error) {
	if xdg := os.Getenv(DataDirEnv); xdg != "" {
		return filepath.Join(xdg, AppName), nil
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".local", "share", AppName), nil
}

func DefaultConfigPath() (string, error) {
	dir, err := DefaultConfigDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, DefaultFileName), nil
}

func LegacyJSONConfigPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, ".knowledgebase", LegacyJSONFile), nil
}

func Load(configPath string) (*Config, error) {
	var explicitlySet bool
	if configPath == "" {
		p, err := DefaultConfigPath()
		if err != nil {
			return nil, err
		}
		configPath = p
	} else {
		explicitlySet = true
	}

	data, err := os.ReadFile(configPath)
	if err != nil {
		if !os.IsNotExist(err) {
			return nil, fmt.Errorf("read config %q: %w", configPath, err)
		}

		legacyPath, legacyErr := LegacyJSONConfigPath()
		if legacyErr == nil && !explicitlySet {
			if legacyData, readErr := os.ReadFile(legacyPath); readErr == nil {
				var cfg Config
				if jsonErr := json.Unmarshal(legacyData, &cfg); jsonErr == nil {
					cfg.applyDefaults()
					if saveErr := Save(&cfg, configPath); saveErr == nil {
						_ = ensureSampleTemplates(cfg.TemplatePath)
					}
					return &cfg, nil
				}
			}
		}

		cfg := Default()
		cfg.applyDefaults()
		if !explicitlySet {
			if saveErr := Save(cfg, configPath); saveErr != nil {
				_, _ = fmt.Fprintf(os.Stderr, "[warn] failed to write default config: %v\n", saveErr)
			}
			if sampleErr := ensureSampleTemplates(cfg.TemplatePath); sampleErr != nil {
				_, _ = fmt.Fprintf(os.Stderr, "[warn] failed to write sample templates: %v\n", sampleErr)
			}
		}
		return cfg, nil
	}

	var cfg Config
	if tomlErr := decodeTOML(string(data), &cfg); tomlErr == nil {
		cfg.applyDefaults()
		return &cfg, nil
	}

	if jsonErr := json.Unmarshal(data, &cfg); jsonErr == nil {
		cfg.applyDefaults()
		return &cfg, nil
	}

	return nil, fmt.Errorf("parse config %q: invalid TOML or JSON", configPath)
}

func Save(cfg *Config, configPath string) error {
	if configPath == "" {
		p, err := DefaultConfigPath()
		if err != nil {
			return err
		}
		configPath = p
	}

	if err := os.MkdirAll(filepath.Dir(configPath), 0o755); err != nil {
		return fmt.Errorf("create config dir %q: %w", filepath.Dir(configPath), err)
	}

	tomlStr, err := encodeTOML(*cfg)
	if err != nil {
		return fmt.Errorf("encode config toml: %w", err)
	}

	header := fmt.Sprintf("# %s Configuration\n# Generated: %s\n# Edit this file or use the Settings UI.\n\n", AppNameHuman, time.Now().Format(time.RFC3339))

	return os.WriteFile(configPath, []byte(header+tomlStr), 0o644)
}

func (cfg *Config) SaveToString() (string, error) {
	tomlStr, err := encodeTOML(*cfg)
	if err != nil {
		return "", fmt.Errorf("encode config toml: %w", err)
	}
	header := fmt.Sprintf("# %s Effective Config\n# %s\n\n", AppNameHuman, cfg.BuildInfo())
	return header + tomlStr, nil
}

func Default() *Config {
	home, _ := os.UserHomeDir()
	dataDir, _ := DefaultDataDir()
	if dataDir == "" {
		dataDir = filepath.Join(home, ".local", "share", AppName)
	}

	cfg := &Config{
		VaultPath:     filepath.Join(home, "KnowledgeVault"),
		DBPath:        filepath.Join(dataDir, "index.db"),
		IndexPath:     filepath.Join(dataDir, "search_index"),
		PluginPath:    filepath.Join(dataDir, "plugins"),
		TemplatePath:  filepath.Join(dataDir, "templates"),
		DailyNotePath: filepath.Join(home, "KnowledgeVault", "Daily"),
	}

	cfg.applyDefaults()
	return cfg
}

func (c *Config) applyDefaults() {
	if c.Search.BM25K1 == 0 {
		c.Search.BM25K1 = 1.5
	}
	if c.Search.BM25B == 0 {
		c.Search.BM25B = 0.75
	}
	if !c.Search.UseCJK {
		c.Search.UseCJK = true
	}
	if !c.Search.EnableSemantic {
		c.Search.EnableSemantic = true
	}
	if c.Search.OllamaBaseURL == "" {
		c.Search.OllamaBaseURL = "http://localhost:11434"
	}
	if c.Search.EmbeddingModel == "" {
		c.Search.EmbeddingModel = "bge-small-zh-v1.5"
	}
	if c.Search.VectorIndexPath == "" {
		dataDir, _ := DefaultDataDir()
		if dataDir != "" {
			c.Search.VectorIndexPath = filepath.Join(dataDir, "vectors.json")
		}
	}
	if c.Search.BM25Weight == 0 {
		c.Search.BM25Weight = 0.6
	}
	if c.Search.VectorWeight == 0 {
		c.Search.VectorWeight = 0.4
	}
	if c.Search.IndexPath == "" {
		dataDir, _ := DefaultDataDir()
		if dataDir != "" {
			c.Search.IndexPath = filepath.Join(dataDir, "disk_index")
		}
	}

	if c.Graph.NodeMinSize == 0 {
		c.Graph.NodeMinSize = 10
	}
	if c.Graph.NodeMaxSize == 0 {
		c.Graph.NodeMaxSize = 50
	}
	if c.Graph.RepulsiveForce == 0 {
		c.Graph.RepulsiveForce = 200
	}
	if c.Graph.AttractiveForce == 0 {
		c.Graph.AttractiveForce = 5
	}

	if c.Editor.DefaultMode == "" {
		c.Editor.DefaultMode = "wysiwyg"
	}
	if !c.Editor.AutoSave {
		c.Editor.AutoSave = true
	}
	if c.Editor.AutoSaveInterval == 0 {
		c.Editor.AutoSaveInterval = 30
	}
	if c.Editor.FontFamily == "" {
		c.Editor.FontFamily = "JetBrains Mono, Menlo, Consolas, monospace"
	}
	if c.Editor.FontSize == 0 {
		c.Editor.FontSize = 15
	}
	if c.Editor.TabWidth == 0 {
		c.Editor.TabWidth = 4
	}
	if !c.Editor.LineNumbers {
		c.Editor.LineNumbers = true
	}
	if !c.Editor.WordWrap {
		c.Editor.WordWrap = true
	}

	if c.Theme.Name == "" {
		c.Theme.Name = "github-dark"
	}
	if c.Theme.ColorScheme == "" {
		c.Theme.ColorScheme = "dark"
	}
	if c.Theme.AccentColor == "" {
		c.Theme.AccentColor = "#3b82f6"
	}
	if c.Theme.BgColor == "" {
		c.Theme.BgColor = "#0d1117"
	}
	if c.Theme.TextColor == "" {
		c.Theme.TextColor = "#e6edf3"
	}
	if c.Theme.BorderColor == "" {
		c.Theme.BorderColor = "#30363d"
	}
	if c.Theme.SidebarBgColor == "" {
		c.Theme.SidebarBgColor = "#010409"
	}
	if c.Theme.SidebarTextColor == "" {
		c.Theme.SidebarTextColor = "#c9d1d9"
	}

	if c.Server.Host == "" {
		c.Server.Host = "127.0.0.1"
	}
	if c.Server.Port == 0 {
		c.Server.Port = 58765
	}

	if c.UI.SidebarWidth == 0 {
		c.UI.SidebarWidth = 280
	}
	if c.UI.InitialView == "" {
		c.UI.InitialView = "editor"
	}
	if c.UI.WindowWidth == 0 {
		c.UI.WindowWidth = 1280
	}
	if c.UI.WindowHeight == 0 {
		c.UI.WindowHeight = 820
	}
	if c.UI.Language == "" {
		c.UI.Language = "zh-CN"
	}
	if !c.UI.ShowStatusBar {
		c.UI.ShowStatusBar = true
	}
	if !c.UI.ShowToolbar {
		c.UI.ShowToolbar = true
	}
}

func ensureSampleTemplates(templateDir string) error {
	if templateDir == "" {
		return nil
	}
	if err := os.MkdirAll(templateDir, 0o755); err != nil {
		return err
	}

	samples := map[string]string{
		"daily-note.ejs": dailyNoteTemplateSample,
		"meeting.ejs":    meetingTemplateSample,
		"review.ejs":     reviewTemplateSample,
		"zettle.ejs":     zettleTemplateSample,
	}

	for name, content := range samples {
		full := filepath.Join(templateDir, name)
		if _, err := os.Stat(full); err == nil {
			continue
		}
		if err := os.WriteFile(full, []byte(content), 0o644); err != nil {
			return err
		}
	}
	return nil
}

const dailyNoteTemplateSample = `# <%= today %> - 每日笔记

## 📝 今日计划
- [ ] 

## ✅ 完成事项
- 

## 💡 灵感与思考


## 🔗 相关笔记
- 

## 📊 统计
- 创建笔记：<%= stats.created_today %>
- 修改笔记：<%= stats.modified_today %>
- 字数：<%= stats.word_count %>
`

const meetingTemplateSample = `# <%= title %>

**日期**: <%= today %>
**参会人**: 
**地点**: 

---

## 议题
1. 

## 讨论内容


## 决议
- [ ] 

## 后续行动
| 事项 | 负责人 | 截止日期 |
|------|--------|----------|
|      |        |          |

## 下次会议
`

const reviewTemplateSample = `# <%= period %> 回顾

## 🎯 目标完成
- 

## 🌟 亮点
- 

## 📈 改进点
- 

## 📚 学习笔记
- 

## 🎲 下一阶段计划
1. 
`

const zettleTemplateSample = `# <%= title %>

> 创建于：<%= today %>
> 标签：#待分类

---

## 核心观点


## 支撑论据

1. 
2. 
3. 

## 相关链接
- 

## 个人思考
`
