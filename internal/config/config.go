package config

import (
	"encoding/json"
	"os"
	"path/filepath"
)

const AppVersion = "2.0.0"

type Config struct {
	VaultPath     string `json:"vault_path"`
	DBPath        string `json:"db_path"`
	IndexPath     string `json:"index_path"`
	PluginPath    string `json:"plugin_path"`
	TemplatePath  string `json:"template_path"`
	DailyNotePath string `json:"daily_note_path"`

	Search struct {
		BM25K1          float64 `json:"bm25_k1"`
		BM25B           float64 `json:"bm25_b"`
		UseCJK          bool    `json:"use_cjk"`
		EnableSemantic  bool    `json:"enable_semantic"`
		OllamaBaseURL   string  `json:"ollama_base_url"`
		EmbeddingModel  string  `json:"embedding_model"`
		VectorIndexPath string  `json:"vector_index_path"`
		BM25Weight      float64 `json:"bm25_weight"`
		VectorWeight    float64 `json:"vector_weight"`
		IndexPath       string  `json:"index_path"`
	} `json:"search"`

	Graph struct {
		NodeMinSize  int `json:"node_min_size"`
		NodeMaxSize  int `json:"node_max_size"`
		RepulsiveForce int `json:"repulsive_force"`
		AttractiveForce int `json:"attractive_force"`
	} `json:"graph"`

	Editor struct {
		DefaultMode string `json:"default_mode"`
		AutoSave    bool   `json:"auto_save"`
		AutoSaveInterval int `json:"auto_save_interval"`
	} `json:"editor"`

	Server struct {
		Host string `json:"host"`
		Port int    `json:"port"`
	} `json:"server"`
}

func Load(configPath string) (*Config, error) {
	if configPath == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return nil, err
		}
		configPath = filepath.Join(home, ".knowledgebase", "config.json")
	}

	data, err := os.ReadFile(configPath)
	if err != nil {
		if os.IsNotExist(err) {
			return Default(), nil
		}
		return nil, err
	}

	var cfg Config
	if err := json.Unmarshal(data, &cfg); err != nil {
		return nil, err
	}

	cfg.applyDefaults()
	return &cfg, nil
}

func Save(cfg *Config, configPath string) error {
	if configPath == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return err
		}
		configPath = filepath.Join(home, ".knowledgebase", "config.json")
	}

	if err := os.MkdirAll(filepath.Dir(configPath), 0755); err != nil {
		return err
	}

	data, err := json.MarshalIndent(cfg, "", "  ")
	if err != nil {
		return err
	}

	return os.WriteFile(configPath, data, 0644)
}

func Default() *Config {
	home, _ := os.UserHomeDir()
	cfg := &Config{
		VaultPath:     filepath.Join(home, "KnowledgeVault"),
		DBPath:        filepath.Join(home, ".knowledgebase", "index.db"),
		IndexPath:     filepath.Join(home, ".knowledgebase", "search_index"),
		PluginPath:    filepath.Join(home, ".knowledgebase", "plugins"),
		TemplatePath:  filepath.Join(home, ".knowledgebase", "templates"),
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
	if c.Search.UseCJK == false {
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
		home, _ := os.UserHomeDir()
		c.Search.VectorIndexPath = filepath.Join(home, ".knowledgebase", "vectors.json")
	}
	if c.Search.BM25Weight == 0 {
		c.Search.BM25Weight = 0.6
	}
	if c.Search.VectorWeight == 0 {
		c.Search.VectorWeight = 0.4
	}
	if c.Search.IndexPath == "" {
		home, _ := os.UserHomeDir()
		c.Search.IndexPath = filepath.Join(home, ".knowledgebase", "index")
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
	if c.Editor.AutoSaveInterval == 0 {
		c.Editor.AutoSaveInterval = 30
	}
	if c.Server.Host == "" {
		c.Server.Host = "127.0.0.1"
	}
	if c.Server.Port == 0 {
		c.Server.Port = 58765
	}
}
