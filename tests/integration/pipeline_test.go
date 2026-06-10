package integration

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/export"
	"github.com/solocoder/knowledgebase/internal/fsnotify"
	"github.com/solocoder/knowledgebase/internal/graph"
	"github.com/solocoder/knowledgebase/internal/markdown"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/internal/search"
	"github.com/solocoder/knowledgebase/internal/testutil"
)

const expectedNoteCount = 50
const expectedAvgLinksPerNote = 3
const expectedMinLinks = 49

func TestIntegration_FullPipeline(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	done := make(chan struct{})
	go func() {
		runFullPipeline(t)
		close(done)
	}()

	select {
	case <-done:
		return
	case <-ctx.Done():
		t.Fatalf("集成测试超时（60秒）：%v", ctx.Err())
	}
}

func runFullPipeline(t *testing.T) {
	tempDir, cleanupTempDir := testutil.TempDir(t, "kb-integration-")
	defer cleanupTempDir()

	cfg := testutil.NewTestConfig(tempDir)
	vaultPath := cfg.VaultPath
	if err := os.MkdirAll(vaultPath, 0755); err != nil {
		t.Fatalf("failed to create vault dir: %v", err)
	}

	step1_CreateTestRepository(t, vaultPath)

	database := step2_InitCoreComponents(t, cfg)
	defer database.Close()

	parser := markdown.NewParser(cfg)
	scanner := fsnotify.NewScanner(cfg, database)

	step3_ScanAndVerifyDB(t, scanner, database, parser, vaultPath)

	engine := step4_BuildInvertedIndex(t, database, cfg, vaultPath)

	step5_KeywordSearch(t, engine, database)

	_ = step6_BuildGraph(t, cfg, database)

	sitePath := step7_ExportSite(t, cfg, database)

	step8_VerifyLinks(t, sitePath)
}

func step1_CreateTestRepository(t *testing.T, vaultPath string) {
	t.Helper()
	t.Log("步骤 1：创建测试仓库并生成 50 篇交叉引用笔记...")

	paths := testutil.GenerateCrossLinkedNotes(t, vaultPath, expectedNoteCount)

	testutil.AssertEqual(t, expectedNoteCount, len(paths), "生成的笔记数量应等于 50")

	files, _ := os.ReadDir(vaultPath)
	mdCount := 0
	for _, f := range files {
		if !f.IsDir() && filepath.Ext(f.Name()) == ".md" {
			mdCount++
		}
	}
	testutil.AssertTrue(t, mdCount >= expectedNoteCount, "vault 目录下至少应有 50 个 md 文件")

	t.Logf("步骤 1 完成：生成 %d 篇笔记", len(paths))
}

func step2_InitCoreComponents(t *testing.T, cfg *config.Config) *db.Database {
	t.Helper()
	t.Log("步骤 2：初始化所有核心组件...")

	database, err := db.New(cfg)
	testutil.AssertNoError(t, err, "创建数据库失败")

	testutil.AssertNotNil(t, database, "database 不应为 nil")

	t.Log("步骤 2 完成：组件初始化成功")
	return database
}

func step3_ScanAndVerifyDB(t *testing.T, scanner *fsnotify.Scanner, database *db.Database, parser *markdown.MarkdownParser, vaultPath string) {
	t.Helper()
	t.Log("步骤 3：文件监听器识别并入库...")

	added, updated, err := scanner.ScanAll()
	testutil.AssertNoError(t, err, "ScanAll 失败")

	t.Logf("ScanAll 结果：added=%d, updated=%d", added, updated)

	testutil.AssertTrue(t, added >= expectedNoteCount, "至少应入库 50 篇笔记")

	var noteCount int
	err = database.QueryRow("SELECT COUNT(*) FROM notes").Scan(&noteCount)
	testutil.AssertNoError(t, err, "查询 notes 表行数失败")
	testutil.AssertEqual(t, expectedNoteCount, noteCount, "notes 表行数应等于 50")

	notes, err := database.GetAllNotes()
	testutil.AssertNoError(t, err, "GetAllNotes 失败")
	testutil.AssertEqual(t, expectedNoteCount, len(notes), "GetAllNotes 数量应等于 50")

	t.Log("开始解析并保存 Wiki 链接...")
	noteMap := make(map[string]*models.Note)
	for _, n := range notes {
		relPath, _ := filepath.Rel(vaultPath, n.Path)
		if relPath == "" {
			relPath = filepath.Base(n.Path)
		}
		noteMap[filepath.Clean(relPath)] = n
	}

	for _, note := range notes {
		relPath, _ := filepath.Rel(vaultPath, note.Path)
		if relPath == "" {
			relPath = filepath.Base(note.Path)
		}
		relPath = filepath.Clean(relPath)

		fullPath := filepath.Join(vaultPath, relPath)
		content, err := os.ReadFile(fullPath)
		if err != nil {
			continue
		}

		result, err := parser.Parse(string(content), relPath)
		if err != nil {
			continue
		}

		var links []models.Link
		for _, l := range result.Links {
			targetPath := filepath.Clean(l.Target + ".md")
			var targetID uint
			if targetNote, ok := noteMap[targetPath]; ok {
				targetID = targetNote.ID
			}

			links = append(links, models.Link{
				SourceID:   note.ID,
				TargetID:   targetID,
				SourcePath: relPath,
				TargetPath: targetPath,
				AnchorText: l.Display,
				LineNum:    l.LineNum,
			})
		}

		if len(links) > 0 {
			_ = database.SaveLinks(note.ID, links)
		}
	}

	var linkCount int
	err = database.QueryRow("SELECT COUNT(*) FROM links").Scan(&linkCount)
	testutil.AssertNoError(t, err, "查询 links 表行数失败")

	expectedMin := expectedNoteCount - 1
	expectedMax := expectedNoteCount * (expectedAvgLinksPerNote + 2)
	testutil.AssertTrue(t, linkCount >= expectedMin,
		fmt.Sprintf("links 表行数应 >= %d（连通图最少边数），实际为 %d", expectedMin, linkCount))
	testutil.AssertTrue(t, linkCount <= expectedMax,
		fmt.Sprintf("links 表行数应 <= %d（合理上限），实际为 %d", expectedMax, linkCount))

	t.Logf("步骤 3 完成：notes=%d, links=%d", noteCount, linkCount)
}

func step4_BuildInvertedIndex(t *testing.T, database *db.Database, cfg *config.Config, vaultPath string) *search.SearchEngine {
	t.Helper()
	t.Log("步骤 4：构建倒排索引...")

	engine := search.NewSearchEngine(database, cfg)

	notes, err := database.GetAllNotes()
	testutil.AssertNoError(t, err, "获取所有笔记失败")

	for _, note := range notes {
		relPath, _ := filepath.Rel(vaultPath, note.Path)
		if relPath == "" {
			relPath = filepath.Base(note.Path)
		}
		fullPath := filepath.Join(vaultPath, relPath)
		content, err := os.ReadFile(fullPath)
		if err != nil {
			t.Logf("警告：无法读取文件 %s：%v", note.Path, err)
			continue
		}

		err = engine.IndexNote(note.ID, note.Title, string(content))
		testutil.AssertNoError(t, err, "IndexNote 失败：noteID=%d", note.ID)
	}

	var searchIndexCount int
	err = database.QueryRow("SELECT COUNT(*) FROM search_index").Scan(&searchIndexCount)
	testutil.AssertNoError(t, err, "查询 search_index 行数失败")
	testutil.AssertTrue(t, searchIndexCount > 0, "search_index 行数应 > 0（应有大量词项），实际为 %d", searchIndexCount)

	totalDocCount, err := engine.GetIndexer().GetTotalDocCount()
	testutil.AssertNoError(t, err, "GetTotalDocCount 失败")
	testutil.AssertEqual(t, expectedNoteCount, totalDocCount, "总文档数应等于 50")

	t.Logf("步骤 4 完成：search_index 行数=%d, 文档总数=%d", searchIndexCount, totalDocCount)
	return engine
}

func step5_KeywordSearch(t *testing.T, engine *search.SearchEngine, database *db.Database) {
	t.Helper()
	t.Log("步骤 5：关键词搜索验证命中...")

	testQueries := []string{
		"性能",
		"practice",
		"Algorithms",
	}

	for _, query := range testQueries {
		t.Logf("执行搜索：query=%q", query)

		sq := search.SearchQuery{
			Query:      query,
			Page:       0,
			PageSize:   20,
			EnableFuzzy: false,
		}

		results, total, err := engine.Search(sq)
		testutil.AssertNoError(t, err, "搜索失败：query=%q", query)

		t.Logf("搜索结果：total=%d, 返回=%d 条", total, len(results))

		testutil.AssertTrue(t, total > 0, "搜索 %q 应 total > 0，实际 total=%d", query, total)
		testutil.AssertTrue(t, len(results) > 0, "搜索 %q 应返回结果 > 0 条，实际返回 %d", query, len(results))

		for i, r := range results {
			testutil.AssertTrue(t, r.Path != "", "第 %d 条结果 Path 不应为空", i)
		}

		if len(results) >= 2 {
			prevScore := results[0].Score
			for i := 1; i < len(results); i++ {
				testutil.AssertTrue(t, prevScore >= results[i].Score,
					"搜索结果 Score 应降序排列：results[%d].Score=%f >= results[%d].Score=%f 失败",
					i-1, prevScore, i, results[i].Score)
				prevScore = results[i].Score
			}
		}

		for i, r := range results {
			hasExcerpt := r.Excerpt != ""
			hasHighlights := len(r.Highlights) > 0
			testutil.AssertTrue(t, hasExcerpt || hasHighlights,
				"第 %d 条结果的 Excerpt 或 Highlights 至少一个非空。Excerpt=%q, Highlights=%v",
				i, r.Excerpt, r.Highlights)
		}
	}

	t.Log("步骤 5 完成：所有搜索查询验证通过")
}

func step6_BuildGraph(t *testing.T, cfg *config.Config, database *db.Database) *graph.Graph {
	t.Helper()
	t.Log("步骤 6：构建图谱验证节点边...")

	g := graph.New(cfg)
	err := g.BuildFromDB(database)
	testutil.AssertNoError(t, err, "BuildFromDB 失败")

	nodeCount := g.GetNodeCount()
	edgeCount := g.GetEdgeCount()
	testutil.AssertEqual(t, expectedNoteCount, nodeCount, "图谱节点数应等于 50")
	testutil.AssertTrue(t, edgeCount >= expectedMinLinks,
		"图边数应 >= %d（连通图至少需要），实际为 %d", expectedMinLinks, edgeCount)

	t.Logf("节点数=%d, 边数=%d", nodeCount, edgeCount)

	notes, _ := database.GetAllNotes()
	dbNoteIDs := make(map[uint]bool)
	for _, n := range notes {
		dbNoteIDs[n.ID] = true
	}

	graphNodeIDs := make(map[uint]bool)
	connectedNodes := 0
	for id, node := range g.Nodes {
		graphNodeIDs[id] = true
		testutil.AssertTrue(t, dbNoteIDs[id], "图谱节点 ID=%d 在 notes 表中不存在", id)
		testutil.AssertTrue(t, node.Path != "", "节点 ID=%d 的 Path 不应为空", id)

		if node.InDegree+node.OutDegree > 0 {
			connectedNodes++
		}
	}

	for id := range dbNoteIDs {
		testutil.AssertTrue(t, graphNodeIDs[id], "notes 表中的 ID=%d 在图谱节点中不存在", id)
	}

	minConnected := expectedNoteCount - 2
	testutil.AssertTrue(t, connectedNodes >= minConnected,
		"至少应有 %d 个节点的 InDegree+OutDegree > 0，实际为 %d", minConnected, connectedNodes)

	t.Logf("步骤 6 完成：节点=%d, 边=%d, 连通节点=%d", nodeCount, edgeCount, connectedNodes)
	return g
}

func step7_ExportSite(t *testing.T, cfg *config.Config, database *db.Database) string {
	t.Helper()
	t.Log("步骤 7：导出 HTML 站点...")

	tempDir := filepath.Dir(cfg.VaultPath)
	sitePath := filepath.Join(tempDir, "site")

	exporter := export.NewManager(cfg, database)
	opts := export.ExportOptions{
		Format:     export.FormatSite,
		OutputPath: sitePath,
		Theme:      "light",
	}

	err := exporter.Export(opts)
	testutil.AssertNoError(t, err, "导出站点失败")

	_, err = os.Stat(sitePath)
	testutil.AssertNoError(t, err, "导出目录 site/ 应存在")

	htmlFiles, err := filepath.Glob(filepath.Join(sitePath, "*.html"))
	testutil.AssertNoError(t, err, "查找 HTML 文件失败")

	t.Logf("site/ 下的 HTML 文件数：%d", len(htmlFiles))
	fileNames := make(map[string]bool)
	for _, f := range htmlFiles {
		fileNames[filepath.Base(f)] = true
	}

	testutil.AssertTrue(t, fileNames["index.html"], "site/ 下应存在 index.html")

	noteHTMLCount := 0
	for name := range fileNames {
		if name != "index.html" && !regexp.MustCompile(`^tag-.+\.html$`).MatchString(name) {
			noteHTMLCount++
		}
	}

	testutil.AssertTrue(t, noteHTMLCount >= expectedNoteCount-2,
		"site/ 下至少应有 %d 个笔记 HTML 文件（允许少量 slug 冲突），实际为 %d",
		expectedNoteCount-2, noteHTMLCount)

	checkFiles := make([]string, 0)
	count := 0
	for _, f := range htmlFiles {
		if count >= 3 {
			break
		}
		base := filepath.Base(f)
		if base != "index.html" && !regexp.MustCompile(`^tag-.+\.html$`).MatchString(base) {
			checkFiles = append(checkFiles, f)
			count++
		}
	}
	if len(checkFiles) < 3 {
		for _, f := range htmlFiles {
			if len(checkFiles) >= 3 {
				break
			}
			if !containsStr(checkFiles, f) {
				checkFiles = append(checkFiles, f)
			}
		}
	}

	for _, f := range checkFiles {
		content, err := os.ReadFile(f)
		testutil.AssertNoError(t, err, "读取 HTML 文件失败：%s", f)
		htmlStr := string(content)
		baseName := filepath.Base(f)

		testutil.AssertContains(t, htmlStr, "<html", "%s 应包含 <html 标签", baseName)
		testutil.AssertContains(t, htmlStr, "<head", "%s 应包含 <head 标签", baseName)
		testutil.AssertContains(t, htmlStr, "<body", "%s 应包含 <body 标签", baseName)
		testutil.AssertContains(t, htmlStr, "</html>", "%s 应包含 </html> 闭合标签", baseName)
	}

	t.Logf("步骤 7 完成：共导出 %d 个 HTML 文件", len(htmlFiles))
	return sitePath
}

func step8_VerifyLinks(t *testing.T, sitePath string) {
	t.Helper()
	t.Log("步骤 8：模拟无头浏览器验证链接可点击...")

	htmlFiles, err := filepath.Glob(filepath.Join(sitePath, "*.html"))
	testutil.AssertNoError(t, err, "查找 HTML 文件失败")

	noteHTMLs := make([]string, 0)
	for _, f := range htmlFiles {
		base := filepath.Base(f)
		if base != "index.html" && !regexp.MustCompile(`^tag-.+\.html$`).MatchString(base) {
			noteHTMLs = append(noteHTMLs, f)
		}
	}
	if len(noteHTMLs) == 0 {
		noteHTMLs = htmlFiles
	}

	existingFiles := make(map[string]bool)
	for _, f := range htmlFiles {
		existingFiles[filepath.Base(f)] = true
	}

	hrefRegex := regexp.MustCompile(`href="([^"]+\.html)"`)

	sampleCount := 3
	if len(noteHTMLs) < sampleCount {
		sampleCount = len(noteHTMLs)
	}
	testSamples := noteHTMLs[:sampleCount]

	for _, sampleFile := range testSamples {
		content, err := os.ReadFile(sampleFile)
		testutil.AssertNoError(t, err, "读取样本 HTML 失败：%s", sampleFile)
		htmlStr := string(content)

		matches := hrefRegex.FindAllStringSubmatch(htmlStr, -1)
		internalLinks := make([]string, 0)
		for _, m := range matches {
			href := m[1]
			if !strings.HasPrefix(href, "http://") &&
				!strings.HasPrefix(href, "https://") &&
				!strings.HasPrefix(href, "css/") &&
				!strings.HasPrefix(href, "js/") &&
				!strings.HasPrefix(href, "data:") {
				internalLinks = append(internalLinks, href)
			}
		}

		baseName := filepath.Base(sampleFile)
		t.Logf("样本 %s：内部链接数=%d", baseName, len(internalLinks))

		testutil.AssertTrue(t, len(internalLinks) > 0,
			"%s 中应包含内部链接（href=\"xxx.html\"），实际找到 %d 个", baseName, len(internalLinks))

		for _, href := range internalLinks {
			linkTarget := filepath.Base(href)
			testutil.AssertTrue(t, existingFiles[linkTarget],
				"%s 中的链接 href=%q 指向的文件 %s 在 site/ 目录下不存在（空洞链接）",
				baseName, href, linkTarget)
		}
	}

	t.Log("步骤 8 完成：所有链接验证通过")
}

func containsStr(slice []string, target string) bool {
	for _, s := range slice {
		if s == target {
			return true
		}
	}
	return false
}

