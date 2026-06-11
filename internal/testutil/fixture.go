package testutil

import (
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

func NewTestConfig(tempDir string) *config.Config {
	cfg := config.Default()
	cfg.VaultPath = filepath.Join(tempDir, "vault")
	cfg.DBPath = filepath.Join(tempDir, "test.db")
	cfg.IndexPath = filepath.Join(tempDir, "search_index")
	cfg.PluginPath = filepath.Join(tempDir, "plugins")
	cfg.TemplatePath = filepath.Join(tempDir, "templates")
	cfg.DailyNotePath = filepath.Join(cfg.VaultPath, "Daily")
	// 设置磁盘倒排索引的路径（避免测试之间共享全局路径）
	cfg.Search.IndexPath = filepath.Join(tempDir, "search_disk_index")
	cfg.Search.VectorIndexPath = filepath.Join(tempDir, "vectors.json")
	return cfg
}

func NewTestDatabase(t *testing.T) (*db.Database, func()) {
	t.Helper()
	tempDir, cleanupDir := TempDir(t, "kb-test-db-")
	cfg := NewTestConfig(tempDir)
	database, err := db.New(cfg)
	if err != nil {
		cleanupDir()
		t.Fatalf("failed to create test database: %v", err)
	}
	cleanup := func() {
		database.Close()
		cleanupDir()
	}
	t.Cleanup(cleanup)
	return database, cleanup
}

func NewTestVault(t *testing.T) (string, func()) {
	t.Helper()
	vaultPath, cleanup := TempDir(t, "kb-test-vault-")
	return vaultPath, cleanup
}

type NoteFactory struct {
	title       string
	content     string
	tags        []string
	links       []string
	relPath     string
	seed        int64
	rng         *rand.Rand
	autoTitle   bool
	autoContent bool
}

func NewTestNoteFactory() *NoteFactory {
	seed := time.Now().UnixNano()
	return &NoteFactory{
		seed:        seed,
		rng:         rand.New(rand.NewSource(seed)),
		autoTitle:   true,
		autoContent: true,
	}
}

func (f *NoteFactory) WithSeed(seed int64) *NoteFactory {
	f.seed = seed
	f.rng = rand.New(rand.NewSource(seed))
	return f
}

func (f *NoteFactory) WithTitle(title string) *NoteFactory {
	f.title = title
	f.autoTitle = false
	return f
}

func (f *NoteFactory) WithContent(content string) *NoteFactory {
	f.content = content
	f.autoContent = false
	return f
}

func (f *NoteFactory) WithTags(tags []string) *NoteFactory {
	f.tags = append([]string{}, tags...)
	return f
}

func (f *NoteFactory) WithLinks(targetPaths []string) *NoteFactory {
	f.links = append([]string{}, targetPaths...)
	return f
}

func (f *NoteFactory) WithPath(relPath string) *NoteFactory {
	f.relPath = relPath
	return f
}

var randomTitles = []string{
	"Introduction to Algorithms",
	"Deep Learning Fundamentals",
	"System Design Patterns",
	"Microservices Architecture",
	"Database Optimization",
	"Go Programming Best Practices",
	"Concurrency Patterns",
	"Distributed Systems",
	"Data Structure Notes",
	"Network Protocols Explained",
	"Machine Learning Basics",
	"Software Engineering Principles",
	"Design Patterns Handbook",
	"API Design Guidelines",
	"Security Best Practices",
	"Performance Tuning Tips",
	"Code Review Checklist",
	"Testing Strategies",
	"DevOps Roadmap",
	"Container Orchestration",
}

var randomParagraphs = []string{
	"这是关于该主题的一个重要概念，需要深入理解其核心原理和应用场景。",
	"In practice, this approach offers significant advantages in terms of scalability and maintainability.",
	"通过实践项目可以更好地掌握这些知识点，建议结合实际案例进行学习。",
	"The key insight here is recognizing the trade-offs between different implementation strategies.",
	"在实际应用中，需要根据具体场景选择合适的方案，没有万能的解决方案。",
	"This pattern is particularly useful when dealing with complex state management scenarios.",
	"性能优化是一个持续迭代的过程，需要通过测量数据来驱动决策。",
	"Good documentation is often the difference between a maintainable system and a legacy nightmare.",
}

func (f *NoteFactory) randomTitle() string {
	idx := f.rng.Intn(len(randomTitles))
	suffix := f.rng.Intn(10000)
	return fmt.Sprintf("%s-%d", randomTitles[idx], suffix)
}

func (f *NoteFactory) randomContent() string {
	numParas := 2 + f.rng.Intn(4)
	var paragraphs []string
	for i := 0; i < numParas; i++ {
		idx := f.rng.Intn(len(randomParagraphs))
		paragraphs = append(paragraphs, randomParagraphs[idx])
	}
	return joinStrings(paragraphs, "\n\n")
}

func (f *NoteFactory) buildTitle() string {
	if f.autoTitle && f.title == "" {
		return f.randomTitle()
	}
	return f.title
}

func (f *NoteFactory) buildBody() string {
	body := f.content
	if f.autoContent && body == "" {
		body = f.randomContent()
	}
	if len(f.links) > 0 {
		var linkLines []string
		for _, target := range f.links {
			display := target
			if utils.IsMarkdownFile(target) {
				display = target[:len(target)-len(filepath.Ext(target))]
			}
			linkLines = append(linkLines, fmt.Sprintf("参考：[[%s]]", display))
		}
		if body != "" {
			body += "\n\n"
		}
		body += joinStrings(linkLines, "\n")
	}
	return body
}

func (f *NoteFactory) buildTagsSection() string {
	if len(f.tags) == 0 {
		return ""
	}
	var tagStrs []string
	for _, tag := range f.tags {
		tagStrs = append(tagStrs, "#"+tag)
	}
	return "\n\n" + joinStrings(tagStrs, " ")
}

func (f *NoteFactory) Build() (path string, content string) {
	title := f.buildTitle()
	body := f.buildBody()
	tags := f.buildTagsSection()

	if f.relPath != "" {
		path = f.relPath
	} else {
		slug := utils.Slugify(title)
		if slug == "" {
			slug = fmt.Sprintf("note-%d", f.rng.Int63())
		}
		path = slug + ".md"
	}

	content = fmt.Sprintf("# %s\n\n%s%s", title, body, tags)
	return path, content
}

func (f *NoteFactory) BuildToFile(vaultPath string) string {
	relPath, content := f.Build()
	fullPath := filepath.Join(vaultPath, relPath)
	writeContent(fullPath, content)
	return relPath
}

func (f *NoteFactory) Clone() *NoteFactory {
	return &NoteFactory{
		title:       f.title,
		content:     f.content,
		tags:        append([]string{}, f.tags...),
		links:       append([]string{}, f.links...),
		relPath:     f.relPath,
		seed:        f.seed,
		rng:         rand.New(rand.NewSource(f.seed)),
		autoTitle:   f.autoTitle,
		autoContent: f.autoContent,
	}
}

func GenerateCrossLinkedNotes(t *testing.T, vaultPath string, count int) []string {
	t.Helper()
	if count <= 0 {
		return []string{}
	}
	return generateCrossLinkedNotes(t, vaultPath, count, time.Now().UnixNano())
}

func GenerateCrossLinkedNotesWithSeed(t *testing.T, vaultPath string, count int, seed int64) []string {
	t.Helper()
	if count <= 0 {
		return []string{}
	}
	return generateCrossLinkedNotes(t, vaultPath, count, seed)
}

func generateCrossLinkedNotes(t *testing.T, vaultPath string, count int, seed int64) []string {
	t.Helper()
	rng := rand.New(rand.NewSource(seed))
	factory := &NoteFactory{
		rng:         rng,
		seed:        seed,
		autoTitle:   true,
		autoContent: true,
	}

	paths := make([]string, count)
	for i := 0; i < count; i++ {
		title := fmt.Sprintf("Note-%04d-%s", i+1, randomTitles[rng.Intn(len(randomTitles))])
		slug := utils.Slugify(title)
		if slug == "" {
			slug = fmt.Sprintf("note-%04d", i+1)
		}
		paths[i] = slug + ".md"

		f := factory.Clone()
		f.WithTitle(title)
		f.WithPath(paths[i])

		bodyParas := 2 + rng.Intn(3)
		var paras []string
		for j := 0; j < bodyParas; j++ {
			paras = append(paras, randomParagraphs[rng.Intn(len(randomParagraphs))])
		}
		f.WithContent(joinStrings(paras, "\n\n"))

		numTags := rng.Intn(4)
		if numTags > 0 {
			tagPool := []string{"programming", "design", "architecture", "algorithms",
				"database", "network", "performance", "testing", "devops", "security"}
			tagSet := make(map[string]bool)
			for len(tagSet) < numTags {
				tagSet[tagPool[rng.Intn(len(tagPool))]] = true
			}
			tags := make([]string, 0, len(tagSet))
			for tag := range tagSet {
				tags = append(tags, tag)
			}
			f.WithTags(tags)
		}

		relPath, content := f.Build()
		fullPath := filepath.Join(vaultPath, relPath)
		if err := ensureDir(filepath.Dir(fullPath)); err != nil {
			t.Fatalf("failed to create directory: %v", err)
		}
		if err := writeFileContent(fullPath, content); err != nil {
			t.Fatalf("failed to write note: %v", err)
		}
		paths[i] = relPath
	}

	adj := make([][]int, count)
	inDegree := make([]int, count)
	outDegree := make([]int, count)

	for i := 1; i < count; i++ {
		target := rng.Intn(i)
		adj[i] = append(adj[i], target)
		outDegree[i]++
		inDegree[target]++
	}

	targetAvgOut := 3
	for i := 0; i < count; i++ {
		needed := targetAvgOut - outDegree[i]
		if needed <= 0 {
			continue
		}
		candidates := make([]int, 0, count)
		for j := 0; j < count; j++ {
			if i == j {
				continue
			}
			exists := false
			for _, k := range adj[i] {
				if k == j {
					exists = true
					break
				}
			}
			if !exists {
				candidates = append(candidates, j)
			}
		}
		for k := 0; k < needed && len(candidates) > 0; k++ {
			idx := rng.Intn(len(candidates))
			target := candidates[idx]
			candidates = append(candidates[:idx], candidates[idx+1:]...)
			adj[i] = append(adj[i], target)
			outDegree[i]++
			inDegree[target]++
		}
	}

	for i := 0; i < count; i++ {
		if len(adj[i]) == 0 {
			continue
		}
		fullPath := filepath.Join(vaultPath, paths[i])
		existing, err := readFileContent(fullPath)
		if err != nil {
			t.Fatalf("failed to read note: %v", err)
		}

		var linkSection string
		if !endsWithNewline(existing) {
			linkSection = "\n\n"
		} else if !endsWithDoubleNewline(existing) {
			linkSection = "\n"
		}

		linkSection += "## 相关笔记\n\n"
		for _, targetIdx := range adj[i] {
			targetPath := paths[targetIdx]
			display := targetPath[:len(targetPath)-len(filepath.Ext(targetPath))]
			linkSection += fmt.Sprintf("- [[%s]]\n", display)
		}

		if err := writeFileContent(fullPath, existing+linkSection); err != nil {
			t.Fatalf("failed to update note with links: %v", err)
		}
	}

	return paths
}

func joinStrings(strs []string, sep string) string {
	if len(strs) == 0 {
		return ""
	}
	result := strs[0]
	for i := 1; i < len(strs); i++ {
		result += sep + strs[i]
	}
	return result
}

func ensureDir(dir string) error {
	if dir == "" || dir == "." {
		return nil
	}
	return os.MkdirAll(dir, 0755)
}

func writeContent(path, content string) {
	if err := ensureDir(filepath.Dir(path)); err != nil {
		panic(err)
	}
	if err := writeFileContent(path, content); err != nil {
		panic(err)
	}
}

func writeFileContent(path string, content string) error {
	return os.WriteFile(path, []byte(content), 0644)
}

func readFileContent(path string) (string, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return "", err
	}
	return string(data), nil
}

func endsWithNewline(s string) bool {
	return len(s) > 0 && s[len(s)-1] == '\n'
}

func endsWithDoubleNewline(s string) bool {
	return len(s) >= 2 && s[len(s)-2] == '\n' && s[len(s)-1] == '\n'
}
