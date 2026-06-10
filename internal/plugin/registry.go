package plugin

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/models"
)

type PluginManifest struct {
	ID            string   `json:"id"`
	Name          string   `json:"name"`
	Version       string   `json:"version"`
	Description   string   `json:"description"`
	Author        string   `json:"author"`
	Entry         string   `json:"entry"`
	Icon          string   `json:"icon"`
	Keywords      []string `json:"keywords"`
	Homepage      string   `json:"homepage"`
	Repository    string   `json:"repository"`
	License       string   `json:"license"`
	MinAppVersion string   `json:"min_app_version"`
	Permissions   []string `json:"permissions"`
}

type Version struct {
	Major int
	Minor int
	Patch int
}

func ParseVersion(v string) (Version, error) {
	v = strings.TrimPrefix(v, "v")
	parts := strings.Split(v, ".")
	if len(parts) != 3 {
		return Version{}, fmt.Errorf("invalid version format: %s", v)
	}

	major, err := strconv.Atoi(parts[0])
	if err != nil {
		return Version{}, fmt.Errorf("invalid major version: %w", err)
	}
	minor, err := strconv.Atoi(parts[1])
	if err != nil {
		return Version{}, fmt.Errorf("invalid minor version: %w", err)
	}
	patch, err := strconv.Atoi(parts[2])
	if err != nil {
		return Version{}, fmt.Errorf("invalid patch version: %w", err)
	}

	return Version{major, minor, patch}, nil
}

func (v Version) String() string {
	return fmt.Sprintf("%d.%d.%d", v.Major, v.Minor, v.Patch)
}

func (v Version) Compare(other Version) int {
	if v.Major != other.Major {
		if v.Major > other.Major {
			return 1
		}
		return -1
	}
	if v.Minor != other.Minor {
		if v.Minor > other.Minor {
			return 1
		}
		return -1
	}
	if v.Patch != other.Patch {
		if v.Patch > other.Patch {
			return 1
		}
		return -1
	}
	return 0
}

type Registry struct {
	cfg         *config.Config
	pluginsDir  string
	installed   map[string]*models.Plugin
	marketplace []*MarketPlugin
}

type MarketPlugin struct {
	ID          string   `json:"id"`
	Name        string   `json:"name"`
	Version     string   `json:"version"`
	Description string   `json:"description"`
	Author      string   `json:"author"`
	Icon        string   `json:"icon"`
	Downloads   int      `json:"downloads"`
	Rating      float64  `json:"rating"`
	Keywords    []string `json:"keywords"`
	Category    string   `json:"category"`
	UpdatedAt   string   `json:"updated_at"`
	IsBuiltin   bool     `json:"is_builtin"`
	Code        string   `json:"-"`
}

func NewRegistry(cfg *config.Config) *Registry {
	pluginsDir := cfg.PluginPath
	if pluginsDir == "" {
		home, _ := os.UserHomeDir()
		pluginsDir = filepath.Join(home, ".knowledgebase", "plugins")
	}

	return &Registry{
		cfg:         cfg,
		pluginsDir:  pluginsDir,
		installed:   make(map[string]*models.Plugin),
		marketplace: make([]*MarketPlugin, 0),
	}
}

func (r *Registry) Init() error {
	if err := os.MkdirAll(r.pluginsDir, 0755); err != nil {
		return fmt.Errorf("failed to create plugins directory: %w", err)
	}

	r.initMarketplace()

	if err := r.loadInstalledPlugins(); err != nil {
		return fmt.Errorf("failed to load installed plugins: %w", err)
	}

	return nil
}

func (r *Registry) initMarketplace() {
	r.marketplace = []*MarketPlugin{
		{
			ID:          "word-count",
			Name:        "字数统计",
			Version:     "1.0.0",
			Description: "实时显示当前笔记的字数、字符数、段落数等统计信息",
			Author:      "KnowledgeBase",
			Icon:        "📊",
			Downloads:   12580,
			Rating:      4.8,
			Keywords:    []string{"统计", "字数", "工具"},
			Category:    "工具",
			UpdatedAt:   "2024-03-15",
			IsBuiltin:   true,
		},
		{
			ID:          "todo-list",
			Name:        "待办事项",
			Version:     "1.2.0",
			Description: "从笔记中提取待办事项，集中管理和跟踪任务进度",
			Author:      "KnowledgeBase",
			Icon:        "✅",
			Downloads:   9876,
			Rating:      4.6,
			Keywords:    []string{"todo", "任务", "待办"},
			Category:    "效率",
			UpdatedAt:   "2024-03-10",
			IsBuiltin:   true,
		},
		{
			ID:          "random-note",
			Name:        "随机笔记",
			Version:     "1.1.0",
			Description: "随机打开一篇笔记，帮助复习和发现被遗忘的内容",
			Author:      "KnowledgeBase",
			Icon:        "🎲",
			Downloads:   5432,
			Rating:      4.4,
			Keywords:    []string{"随机", "复习", "探索"},
			Category:    "工具",
			UpdatedAt:   "2024-02-28",
			IsBuiltin:   true,
		},
		{
			ID:          "tag-cloud",
			Name:        "标签云",
			Version:     "1.0.0",
			Description: "以标签云的形式可视化展示所有标签，点击可快速筛选",
			Author:      "KnowledgeBase",
			Icon:        "☁️",
			Downloads:   7654,
			Rating:      4.5,
			Keywords:    []string{"标签", "可视化", "云图"},
			Category:    "可视化",
			UpdatedAt:   "2024-03-01",
			IsBuiltin:   true,
		},
	}
}

func (r *Registry) loadInstalledPlugins() error {
	entries, err := os.ReadDir(r.pluginsDir)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}

	for _, entry := range entries {
		if !entry.IsDir() {
			continue
		}

		pluginPath := filepath.Join(r.pluginsDir, entry.Name())
		manifestPath := filepath.Join(pluginPath, "plugin.json")

		manifest, err := r.loadManifest(manifestPath)
		if err != nil {
			continue
		}

		info, err := entry.Info()
		if err != nil {
			continue
		}

		plugin := &models.Plugin{
			ID:          manifest.ID,
			Name:        manifest.Name,
			Version:     manifest.Version,
			Description: manifest.Description,
			Author:      manifest.Author,
			Enabled:     true,
			Path:        pluginPath,
			Entry:       manifest.Entry,
			InstalledAt: info.ModTime(),
			UpdatedAt:   info.ModTime(),
		}

		r.installed[manifest.ID] = plugin
	}

	return nil
}

func (r *Registry) loadManifest(path string) (*PluginManifest, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}

	var manifest PluginManifest
	if err := json.Unmarshal(data, &manifest); err != nil {
		return nil, err
	}

	if manifest.ID == "" || manifest.Name == "" || manifest.Version == "" {
		return nil, errors.New("invalid manifest: missing required fields")
	}

	if manifest.Entry == "" {
		manifest.Entry = "index.js"
	}

	return &manifest, nil
}

func (r *Registry) GetInstalledPlugins() []*models.Plugin {
	plugins := make([]*models.Plugin, 0, len(r.installed))
	for _, p := range r.installed {
		plugins = append(plugins, p)
	}
	return plugins
}

func (r *Registry) GetInstalledPlugin(id string) (*models.Plugin, bool) {
	p, ok := r.installed[id]
	return p, ok
}

func (r *Registry) GetMarketplacePlugins(category string, keyword string) []*MarketPlugin {
	result := make([]*MarketPlugin, 0)

	for _, mp := range r.marketplace {
		if category != "" && mp.Category != category {
			continue
		}

		if keyword != "" {
			kw := strings.ToLower(keyword)
			found := strings.Contains(strings.ToLower(mp.Name), kw) ||
				strings.Contains(strings.ToLower(mp.Description), kw)
			if !found {
				for _, k := range mp.Keywords {
					if strings.Contains(strings.ToLower(k), kw) {
						found = true
						break
					}
				}
			}
			if !found {
				continue
			}
		}

		result = append(result, mp)
	}

	return result
}

func (r *Registry) GetMarketplacePlugin(id string) (*MarketPlugin, bool) {
	for _, mp := range r.marketplace {
		if mp.ID == id {
			return mp, true
		}
	}
	return nil, false
}

func (r *Registry) InstallPlugin(id string) (*models.Plugin, error) {
	mp, ok := r.GetMarketplacePlugin(id)
	if !ok {
		return nil, fmt.Errorf("plugin not found in marketplace: %s", id)
	}

	if _, exists := r.installed[id]; exists {
		return nil, fmt.Errorf("plugin already installed: %s", id)
	}

	pluginPath := filepath.Join(r.pluginsDir, id)
	if err := os.MkdirAll(pluginPath, 0755); err != nil {
		return nil, fmt.Errorf("failed to create plugin directory: %w", err)
	}

	manifest := PluginManifest{
		ID:          mp.ID,
		Name:        mp.Name,
		Version:     mp.Version,
		Description: mp.Description,
		Author:      mp.Author,
		Entry:       "index.js",
		Keywords:    mp.Keywords,
	}

	manifestData, _ := json.MarshalIndent(manifest, "", "  ")
	if err := os.WriteFile(filepath.Join(pluginPath, "plugin.json"), manifestData, 0644); err != nil {
		return nil, fmt.Errorf("failed to write manifest: %w", err)
	}

	entryCode := getBuiltinPluginCode(id)
	if entryCode == "" {
		entryCode = "// Plugin: " + mp.Name + "\n// Version: " + mp.Version + "\n\nconsole.log('Plugin loaded: " + mp.Name + "');\n"
	}
	if err := os.WriteFile(filepath.Join(pluginPath, "index.js"), []byte(entryCode), 0644); err != nil {
		return nil, fmt.Errorf("failed to write entry file: %w", err)
	}

	now := time.Now()
	plugin := &models.Plugin{
		ID:          manifest.ID,
		Name:        manifest.Name,
		Version:     manifest.Version,
		Description: manifest.Description,
		Author:      manifest.Author,
		Enabled:     true,
		Path:        pluginPath,
		Entry:       manifest.Entry,
		InstalledAt: now,
		UpdatedAt:   now,
	}

	r.installed[id] = plugin
	return plugin, nil
}

func (r *Registry) UninstallPlugin(id string) error {
	if _, ok := r.installed[id]; !ok {
		return fmt.Errorf("plugin not installed: %s", id)
	}

	pluginPath := filepath.Join(r.pluginsDir, id)
	if err := os.RemoveAll(pluginPath); err != nil {
		return fmt.Errorf("failed to remove plugin directory: %w", err)
	}

	delete(r.installed, id)
	return nil
}

func (r *Registry) EnablePlugin(id string) error {
	p, ok := r.installed[id]
	if !ok {
		return fmt.Errorf("plugin not installed: %s", id)
	}
	p.Enabled = true
	return nil
}

func (r *Registry) DisablePlugin(id string) error {
	p, ok := r.installed[id]
	if !ok {
		return fmt.Errorf("plugin not installed: %s", id)
	}
	p.Enabled = false
	return nil
}

func (r *Registry) CheckForUpdate(id string) (bool, string, error) {
	installed, ok := r.installed[id]
	if !ok {
		return false, "", fmt.Errorf("plugin not installed: %s", id)
	}

	market, ok := r.GetMarketplacePlugin(id)
	if !ok {
		return false, "", fmt.Errorf("plugin not found in marketplace: %s", id)
	}

	installedVer, err := ParseVersion(installed.Version)
	if err != nil {
		return false, "", fmt.Errorf("invalid installed version: %w", err)
	}

	marketVer, err := ParseVersion(market.Version)
	if err != nil {
		return false, "", fmt.Errorf("invalid market version: %w", err)
	}

	if marketVer.Compare(installedVer) > 0 {
		return true, market.Version, nil
	}

	return false, "", nil
}

func (r *Registry) GetPluginEntryPath(plugin *models.Plugin) string {
	return filepath.Join(plugin.Path, plugin.Entry)
}

func (r *Registry) GetPluginsDir() string {
	return r.pluginsDir
}

func getBuiltinPluginCode(id string) string {
	switch id {
	case "word-count":
		return `function countWords(text) {
  if (!text) return 0;
  const chineseChars = (text.match(/[\u4e00-\u9fa5]/g) || []).length;
  const englishWords = (text.match(/[a-zA-Z]+/g) || []).length;
  return chineseChars + englishWords;
}

function countCharacters(text) {
  if (!text) return 0;
  return text.replace(/\s/g, '').length;
}

function countParagraphs(text) {
  if (!text) return 0;
  const paragraphs = text.split(/\n\s*\n/).filter(p => p.trim().length > 0);
  return paragraphs.length;
}

function countLines(text) {
  if (!text) return 0;
  return text.split('\n').length;
}

app.on('note:opened', (note) => {
  const stats = {
    words: countWords(note.content || ''),
    characters: countCharacters(note.content || ''),
    paragraphs: countParagraphs(note.content || ''),
    lines: countLines(note.content || ''),
  };
  console.log('Word count:', JSON.stringify(stats));
  app.sendNotification('字数统计', 
    '字数: ' + stats.words + 
    ' | 字符: ' + stats.characters +
    ' | 段落: ' + stats.paragraphs +
    ' | 行数: ' + stats.lines
  );
});

app.registerCommand('word-count:show', () => {
  const notes = app.searchNotes('');
  let totalWords = 0;
  let totalChars = 0;
  notes.forEach(note => {
    totalWords += countWords(note.title || '');
    totalChars += countCharacters(note.title || '');
  });
  app.sendNotification('总字数统计', 
    '笔记数: ' + notes.length + 
    ' | 总字数(标题): ' + totalWords +
    ' | 总字符(标题): ' + totalChars
  );
});

console.log('Word Count plugin loaded');
`
	case "todo-list":
		return `const todos = [];

function extractTodos(content) {
  if (!content) return [];
  const lines = content.split('\n');
  const extracted = [];
  lines.forEach((line, index) => {
    const trimmed = line.trim();
    if (trimmed.startsWith('- [ ] ')) {
      extracted.push({
        text: trimmed.substring(6),
        done: false,
        line: index + 1,
      });
    } else if (trimmed.startsWith('- [x] ') || trimmed.startsWith('- [X] ')) {
      extracted.push({
        text: trimmed.substring(6),
        done: true,
        line: index + 1,
      });
    }
  });
  return extracted;
}

app.on('note:opened', (note) => {
  const noteTodos = extractTodos(note.content || '');
  const pending = noteTodos.filter(t => !t.done).length;
  const done = noteTodos.filter(t => t.done).length;
  if (noteTodos.length > 0) {
    app.sendNotification('待办事项', 
      '共 ' + noteTodos.length + ' 个待办，已完成 ' + done + ' 个，待完成 ' + pending + ' 个'
    );
  }
});

app.registerCommand('todo-list:summary', () => {
  const notes = app.searchNotes('');
  let totalPending = 0;
  let totalDone = 0;
  notes.forEach(note => {
    const noteTodos = extractTodos(note.title || '');
    totalPending += noteTodos.filter(t => !t.done).length;
    totalDone += noteTodos.filter(t => t.done).length;
  });
  app.sendNotification('待办汇总', 
    '总待办: ' + (totalPending + totalDone) + 
    ' | 已完成: ' + totalDone + 
    ' | 待完成: ' + totalPending
  );
});

console.log('Todo List plugin loaded');
`
	case "random-note":
		return `function getRandomNote() {
  const notes = app.searchNotes('');
  if (notes.length === 0) {
    return null;
  }
  const randomIndex = Math.floor(Math.random() * notes.length);
  return notes[randomIndex];
}

app.registerCommand('random-note:open', () => {
  const note = getRandomNote();
  if (note) {
    app.sendNotification('随机笔记', 
      '标题: ' + note.title + '\n路径: ' + note.path
    );
    app.emit('note:random', note);
  } else {
    app.sendNotification('随机笔记', '没有找到笔记');
  }
});

app.registerView('random-note:widget', {
  title: '随机笔记',
  render: () => {
    const notes = app.searchNotes('');
    return '共有 ' + notes.length + ' 篇笔记，点击按钮随机打开一篇';
  },
});

console.log('Random Note plugin loaded');
`
	case "tag-cloud":
		return `function generateTagCloud(tags) {
  const cloud = tags.map(tag => {
    const size = Math.max(12, Math.min(36, 12 + tag.note_count * 2));
    return {
      name: tag.name,
      count: tag.note_count,
      size: size,
      color: tag.color || '#6366f1',
    };
  });
  return cloud.sort((a, b) => b.count - a.count);
}

app.on('app:ready', () => {
  const tags = app.getTags();
  const cloud = generateTagCloud(tags);
  console.log('Tag cloud generated:', cloud.length, 'tags');
});

app.registerCommand('tag-cloud:show', () => {
  const tags = app.getTags();
  const cloud = generateTagCloud(tags);
  const topTags = cloud.slice(0, 10);
  let message = '热门标签:\n';
  topTags.forEach((tag, i) => {
    message += (i + 1) + '. ' + tag.name + ' (' + tag.count + '篇)\n';
  });
  app.sendNotification('标签云', message.trim());
});

console.log('Tag Cloud plugin loaded');
`
	default:
		return ""
	}
}
