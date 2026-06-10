package export

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
	"github.com/solocoder/knowledgebase/internal/testutil"
	"github.com/solocoder/knowledgebase/pkg/utils"
)

func setupTestEnv(t *testing.T) (*config.Config, *db.Database, func()) {
	t.Helper()

	tempDir, cleanupDir := testutil.TempDir(t, "kb-export-test-")

	cfg := testutil.NewTestConfig(tempDir)

	if err := os.MkdirAll(cfg.VaultPath, 0755); err != nil {
		cleanupDir()
		t.Fatalf("failed to create vault dir: %v", err)
	}

	database, err := db.New(cfg)
	if err != nil {
		cleanupDir()
		t.Fatalf("failed to create database: %v", err)
	}

	cleanup := func() {
		database.Close()
		cleanupDir()
	}
	t.Cleanup(cleanup)

	return cfg, database, cleanup
}

func createNote(t *testing.T, cfg *config.Config, database *db.Database, title, content string, tags []string) *models.Note {
	t.Helper()

	slug := utils.Slugify(title)
	if slug == "" {
		slug = fmt.Sprintf("note-%d", time.Now().UnixNano())
	}
	relPath := slug + ".md"

	fullPath := filepath.Join(cfg.VaultPath, relPath)
	if err := os.MkdirAll(filepath.Dir(fullPath), 0755); err != nil {
		t.Fatalf("failed to create note dir: %v", err)
	}

	mdContent := fmt.Sprintf("# %s\n\n%s", title, content)
	if len(tags) > 0 {
		var tagStrs []string
		for _, tag := range tags {
			tagStrs = append(tagStrs, "#"+tag)
		}
		mdContent += "\n\n" + strings.Join(tagStrs, " ")
	}

	if err := os.WriteFile(fullPath, []byte(mdContent), 0644); err != nil {
		t.Fatalf("failed to write note file: %v", err)
	}

	noteTags := make([]models.Tag, 0, len(tags))
	for _, tagName := range tags {
		noteTags = append(noteTags, models.Tag{Name: tagName})
	}

	note := &models.Note{
		Path:      relPath,
		Title:     title,
		Hash:      utils.Hash(mdContent),
		WordCount: utils.CountWords(mdContent),
		Tags:      noteTags,
	}
	if err := database.SaveNote(note); err != nil {
		t.Fatalf("failed to save note to db: %v", err)
	}

	return note
}

func createCrossLinkedNotes(t *testing.T, cfg *config.Config, database *db.Database) []*models.Note {
	t.Helper()

	noteA := createNote(t, cfg, database, "NoteA",
		"这是笔记A的内容。参考：[[NoteB]]", []string{"go"})
	noteB := createNote(t, cfg, database, "NoteB",
		"这是笔记B的内容。参考：[[NoteC]]", []string{"python"})
	noteC := createNote(t, cfg, database, "NoteC",
		"这是笔记C的内容。参考：[[NoteA]]", []string{"go", "programming"})

	return []*models.Note{noteA, noteB, noteC}
}

func countHTMLFilesWithTitle(t *testing.T, dir string) int {
	t.Helper()
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatalf("failed to read dir: %v", err)
	}
	count := 0
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		if strings.HasSuffix(name, ".html") && name != "index.html" &&
			!strings.HasPrefix(name, "tag-") {
			count++
		}
	}
	return count
}

func TestExport_HTMLSingleNote(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)

	noteTitle := "Go 并发编程指南"
	noteContent := `这是关于 Go 语言并发编程的详细介绍。

## Goroutine

Goroutine 是 Go 并发的基础。参考：[[其他笔记]]

## Channel

Channel 用于 goroutine 之间的通信。

通过 channel 可以安全地在多个 goroutine 之间传递数据。`

	createNote(t, cfg, database, noteTitle, noteContent, []string{"go", "concurrency"})

	outputDir, _ := testutil.TempDir(t, "kb-export-out-")
	outputPath := filepath.Join(outputDir, "single-note.html")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatHTML,
		OutputPath: outputPath,
	})
	testutil.AssertNoError(t, err, "export single note HTML")

	info, err := os.Stat(outputPath)
	testutil.AssertNoError(t, err, "stat output file")
	testutil.AssertTrue(t, info.Size() > 0, "file should not be empty")

	content, err := os.ReadFile(outputPath)
	testutil.AssertNoError(t, err, "read output file")

	contentStr := string(content)
	lowerContent := strings.ToLower(contentStr)
	testutil.AssertTrue(t,
		strings.Contains(lowerContent, "<html") || strings.Contains(lowerContent, "<body"),
		"should contain HTML structure, got: %.200s", contentStr)

	testutil.AssertContains(t, contentStr, noteTitle, "should contain note title")
	testutil.AssertContains(t, contentStr, "Goroutine", "should contain note content")
	testutil.AssertContains(t, contentStr, "Channel", "should contain note content")
}

func TestExport_SiteMultiNotes(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)
	createCrossLinkedNotes(t, cfg, database)

	outputDir, _ := testutil.TempDir(t, "kb-export-site-")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatSite,
		OutputPath: outputDir,
	})
	testutil.AssertNoError(t, err, "export site")

	info, err := os.Stat(outputDir)
	testutil.AssertNoError(t, err, "stat output dir")
	testutil.AssertTrue(t, info.IsDir(), "output should be directory")

	htmlFiles, err := filepath.Glob(filepath.Join(outputDir, "*.html"))
	testutil.AssertNoError(t, err, "glob html files")

	noteHTMLCount := 0
	for _, f := range htmlFiles {
		base := filepath.Base(f)
		if strings.HasSuffix(base, ".html") && !strings.HasPrefix(base, "tag-") {
			fi, err := os.Stat(f)
			testutil.AssertNoError(t, err, "stat html file %s", base)
			testutil.AssertTrue(t, fi.Size() > 0, "file %s should not be empty", base)
			if base != "index.html" {
				noteHTMLCount++
			}
		}
	}
	testutil.AssertTrue(t, noteHTMLCount >= 3, "should have at least 3 note HTML files, got %d", noteHTMLCount)

	hasIndex := false
	for _, f := range htmlFiles {
		if filepath.Base(f) == "index.html" {
			hasIndex = true
			break
		}
	}
	testutil.AssertTrue(t, hasIndex, "should have index.html")

	aHTML := filepath.Join(outputDir, utils.Slugify("NoteA")+".html")
	aContent, err := os.ReadFile(aHTML)
	testutil.AssertNoError(t, err, "read A.html")

	expectedLink := utils.Slugify("NoteB") + ".html"
	lowerContent := strings.ToLower(string(aContent))
	lowerLink := strings.ToLower(expectedLink)

	foundLink := strings.Contains(lowerContent, fmt.Sprintf(`href="%s"`, lowerLink)) ||
		strings.Contains(lowerContent, fmt.Sprintf("href=%q", expectedLink)) ||
		strings.Contains(string(aContent), expectedLink)
	testutil.AssertTrue(t, foundLink,
		"A.html should contain link to B.html (%s), content sample: %.500s",
		expectedLink, string(aContent))
}

func TestExport_EmptyNotesError(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)

	outputDir, _ := testutil.TempDir(t, "kb-export-empty-")
	outputPath := filepath.Join(outputDir, "empty.html")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatHTML,
		OutputPath: outputPath,
	})
	testutil.AssertError(t, err, "should error with no notes")
	testutil.AssertContains(t, strings.ToLower(err.Error()), "no notes",
		"error should mention no notes, got: %v", err)
}

func TestExport_PDFNoWritePermission(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)
	createNote(t, cfg, database, "TestNote", "PDF 导出测试内容", nil)

	baseDir, _ := testutil.TempDir(t, "kb-pdf-perm-")
	outputDir := filepath.Join(baseDir, "out")
	if err := os.MkdirAll(outputDir, 0755); err != nil {
		t.Fatalf("failed to create output dir: %v", err)
	}

	origMode := os.FileMode(0755)
	if info, err := os.Stat(outputDir); err == nil {
		origMode = info.Mode()
	}
	defer os.Chmod(outputDir, origMode)

	if err := os.Chmod(outputDir, 0444); err != nil {
		t.Skipf("cannot chmod output dir (probably running as root): %v", err)
		return
	}

	outputPath := filepath.Join(outputDir, "file.pdf")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatPDF,
		OutputPath: outputPath,
	})

	os.Chmod(outputDir, origMode)

	testutil.AssertError(t, err, "should error with no write permission")

	errStr := strings.ToLower(err.Error())
	hasKeyword := strings.Contains(errStr, "create output directory failed") ||
		strings.Contains(errStr, "permission denied") ||
		strings.Contains(errStr, "export failed")
	testutil.AssertTrue(t, hasKeyword,
		"error should contain permission keyword, got: %v", err)

	_, statErr := os.Stat(outputPath)
	testutil.AssertTrue(t, os.IsNotExist(statErr),
		"should not create partial output file, stat error: %v", statErr)
}

func TestExport_InvalidFormat(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)
	createNote(t, cfg, database, "TestNote", "测试内容", nil)

	outputDir, _ := testutil.TempDir(t, "kb-export-invalid-")
	outputPath := filepath.Join(outputDir, "test.word")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     ExportFormat("word"),
		OutputPath: outputPath,
	})
	testutil.AssertError(t, err, "should error with invalid format")
	testutil.AssertContains(t, strings.ToLower(err.Error()), "unsupported export format",
		"error should mention unsupported format, got: %v", err)
}

func TestExport_TagFilter(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)

	createNote(t, cfg, database, "GolangGuide", "Go 语言指南", []string{"go", "programming"})
	createNote(t, cfg, database, "GolangConcurrency", "Go 并发详解", []string{"go", "concurrency"})
	createNote(t, cfg, database, "PythonBasics", "Python 基础", []string{"python"})
	createNote(t, cfg, database, "JavaIntro", "Java 介绍", []string{"java"})
	createNote(t, cfg, database, "GoPatterns", "Go 设计模式", []string{"go", "design"})

	outputDir, _ := testutil.TempDir(t, "kb-export-tagfilter-")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatHTML,
		OutputPath: outputDir,
		Tags:       []string{"go"},
	})
	testutil.AssertNoError(t, err, "export with go tag filter")

	noteCount := countHTMLFilesWithTitle(t, outputDir)
	testutil.AssertEqual(t, 3, noteCount, "should export only 3 go-tagged notes (multi-file mode)")

	entries, err := os.ReadDir(outputDir)
	testutil.AssertNoError(t, err, "read output dir")
	for _, entry := range entries {
		if entry.IsDir() || !strings.HasSuffix(entry.Name(), ".html") {
			continue
		}
		name := entry.Name()
		if name == "index.html" || strings.HasPrefix(name, "tag-") {
			continue
		}
		content, err := os.ReadFile(filepath.Join(outputDir, name))
		testutil.AssertNoError(t, err, "read %s", name)
		contentStr := string(content)
		hasGo := strings.Contains(contentStr, "Go") || strings.Contains(contentStr, "Golang")
		testutil.AssertTrue(t, hasGo,
			"exported note should be Go-related: %s, content sample: %.300s", name, contentStr)
	}
}

func TestExport_ConcurrentExports(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)

	noteCount := 5
	notes := make([]*models.Note, noteCount)
	for i := 0; i < noteCount; i++ {
		title := fmt.Sprintf("ConcurrentNote-%d", i+1)
		content := fmt.Sprintf("这是笔记 %d 的内容。\n\n包含一些测试内容用于并发导出验证。", i+1)
		tags := []string{fmt.Sprintf("tag%d", (i % 3) + 1)}
		notes[i] = createNote(t, cfg, database, title, content, tags)
	}
	_ = notes

	baseDir, _ := testutil.TempDir(t, "kb-export-concurrent-")

	formats := []ExportFormat{FormatHTML, FormatSite, FormatHTML, FormatSite, FormatHTML}

	var wg sync.WaitGroup
	errs := make([]error, 5)
	outputDirs := make([]string, 5)

	panicOccurred := false
	defer func() {
		if r := recover(); r != nil {
			panicOccurred = true
			t.Errorf("panic during concurrent export: %v", r)
		}
	}()

	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func(idx int) {
			defer wg.Done()
			defer func() {
				if r := recover(); r != nil {
					panicOccurred = true
					errs[idx] = fmt.Errorf("panic: %v", r)
				}
			}()

			outDir := filepath.Join(baseDir, fmt.Sprintf("export-%d", idx))
			outputDirs[idx] = outDir

			var outPath string
			if formats[idx] == FormatSite || formats[idx] == FormatHTML {
				if idx%2 == 1 {
					outPath = outDir
				} else {
					outPath = filepath.Join(outDir, "output.html")
				}
			} else {
				outPath = filepath.Join(outDir, "output.pdf")
			}

			mgr := NewManager(cfg, database)
			errs[idx] = mgr.Export(ExportOptions{
				Format:     formats[idx],
				OutputPath: outPath,
			})
		}(i)
	}

	done := make(chan struct{})
	go func() {
		wg.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-time.After(60 * time.Second):
		t.Fatal("concurrent exports timed out after 60s")
	}

	testutil.AssertTrue(t, !panicOccurred, "no panic should occur during concurrent exports")

	for i := 0; i < 5; i++ {
		testutil.AssertNoError(t, errs[i], "export %d should succeed", i)
	}

	for i := 0; i < 5; i++ {
		_, err := os.Stat(outputDirs[i])
		testutil.AssertNoError(t, err, "output dir %d should exist: %s", i, outputDirs[i])
	}

	for i := 0; i < 5; i++ {
		for j := i + 1; j < 5; j++ {
			testutil.AssertTrue(t, outputDirs[i] != outputDirs[j],
				"output dirs should be different: %s vs %s", outputDirs[i], outputDirs[j])
		}
	}
}

func TestExport_LargeBatchNoHang(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)

	batchSize := 500
	for i := 0; i < batchSize; i++ {
		title := fmt.Sprintf("LargeBatchNote-%04d", i+1)
		content := fmt.Sprintf(`# %s

## 章节一

这是第 %d 篇批量测试笔记的内容。
包含一些基础的文本段落用于测试导出性能。

## 章节二

更多的内容信息用于填充笔记。

- 列表项 A
- 列表项 B
- 列表项 C

### 子章节

代码块示例：

`+"```go"+`
package main

import "fmt"

func main() {
	fmt.Println("Hello %d")
}
`+"```"+`

结论部分。`, title, i+1, i+1)
		tags := []string{fmt.Sprintf("batch%d", (i%10)+1)}
		createNote(t, cfg, database, title, content, tags)
	}

	outputDir, _ := testutil.TempDir(t, "kb-export-largebatch-")

	completed := make(chan error, 1)
	go func() {
		mgr := NewManager(cfg, database)
		completed <- mgr.Export(ExportOptions{
			Format:     FormatHTML,
			OutputPath: outputDir,
		})
	}()

	select {
	case err := <-completed:
		testutil.AssertNoError(t, err, "large batch export should complete")
	case <-time.After(30 * time.Second):
		t.Fatal("large batch export timed out after 30 seconds")
	}

	entries, err := os.ReadDir(outputDir)
	testutil.AssertNoError(t, err, "read output dir")

	htmlCount := 0
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := entry.Name()
		if strings.HasSuffix(name, ".html") {
			htmlCount++
			fi, statErr := entry.Info()
			testutil.AssertNoError(t, statErr, "stat %s", name)
			testutil.AssertTrue(t, fi.Size() > 0, "%s should not be empty", name)
		}
	}
	testutil.AssertTrue(t, htmlCount > 0, "should produce HTML files, got %d", htmlCount)
}

func TestExport_CSSPathApplied(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)
	createNote(t, cfg, database, "CSSNote", "CSS 测试内容", nil)

	outputDir, _ := testutil.TempDir(t, "kb-export-css-")

	cssContent := `body { background-color: #ff0000; }
.custom-class { color: blue; }`
	cssPath := filepath.Join(outputDir, "custom.css")
	if err := os.WriteFile(cssPath, []byte(cssContent), 0644); err != nil {
		t.Fatalf("failed to write custom css: %v", err)
	}

	outputPath := filepath.Join(outputDir, "output.html")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatHTML,
		OutputPath: outputPath,
		CSSPath:    cssPath,
	})
	testutil.AssertNoError(t, err, "export with custom CSS")

	content, err := os.ReadFile(outputPath)
	testutil.AssertNoError(t, err, "read output")

	contentStr := string(content)
	hasStyle := strings.Contains(contentStr, "<style>") ||
		strings.Contains(contentStr, `href="custom.css"`) ||
		strings.Contains(contentStr, "#ff0000") ||
		strings.Contains(contentStr, ".custom-class")
	testutil.AssertTrue(t, hasStyle,
		"output should include custom CSS reference or inline content, sample: %.500s", contentStr)
}

func TestExport_TOC(t *testing.T) {
	cfg, database, _ := setupTestEnv(t)

	content := `# 主标题

这是介绍段落。

## 第一章

第一章内容。

### 1.1 小节

小节内容。

## 第二章

第二章内容。

### 2.1 小节

更多内容。

## 第三章

第三章总结。`

	createNote(t, cfg, database, "TOCNote", content, nil)

	outputDir, _ := testutil.TempDir(t, "kb-export-toc-")
	outputPath := filepath.Join(outputDir, "output.html")

	mgr := NewManager(cfg, database)
	err := mgr.Export(ExportOptions{
		Format:     FormatHTML,
		OutputPath: outputPath,
		IncludeTOC: true,
	})
	testutil.AssertNoError(t, err, "export with TOC")

	contentBytes, err := os.ReadFile(outputPath)
	testutil.AssertNoError(t, err, "read output")

	contentStr := string(contentBytes)
	lowerContent := strings.ToLower(contentStr)

	hasTOCMarker := strings.Contains(contentStr, "目录") ||
		strings.Contains(lowerContent, "table of contents") ||
		strings.Contains(lowerContent, "<nav") ||
		strings.Contains(lowerContent, "<ol") ||
		strings.Contains(lowerContent, "toc")

	testutil.AssertTrue(t, hasTOCMarker,
		"output should contain TOC marker (目录/nav/ol/toc), sample: %.500s", contentStr)

	testutil.AssertContains(t, contentStr, "第一章", "TOC should reference chapter 1")
	testutil.AssertContains(t, contentStr, "第二章", "TOC should reference chapter 2")
}
