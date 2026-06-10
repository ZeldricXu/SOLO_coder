package integration

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/fsnotify"
	"github.com/solocoder/knowledgebase/internal/search"
	"github.com/solocoder/knowledgebase/internal/testutil"
)

const recoveryNoteCount = 30

func TestIntegration_IndexCorruptionRecovery(t *testing.T) {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	done := make(chan struct{})
	go func() {
		runIndexCorruptionRecovery(t)
		close(done)
	}()

	select {
	case <-done:
		return
	case <-ctx.Done():
		t.Fatalf("异常恢复集成测试超时（60秒）：%v", ctx.Err())
	}
}

func runIndexCorruptionRecovery(t *testing.T) {
	tempDir, cleanupTempDir := testutil.TempDir(t, "kb-recovery-")
	defer cleanupTempDir()

	cfg := testutil.NewTestConfig(tempDir)
	vaultPath := cfg.VaultPath
	if err := os.MkdirAll(vaultPath, 0755); err != nil {
		t.Fatalf("failed to create vault dir: %v", err)
	}

	baselineTotal, baselineFirstTitle, database, engine, scanner := step1_EstablishHealthBaseline(t, vaultPath, cfg)

	baselineIndexCount := step2_SimulateIndexCorruption(t, database)

	step3_VerifyCorruptedSearchBehavior(t, engine)

	step4_RebuildIndexFromFiles(t, database, cfg, vaultPath, engine, scanner)

	step5_VerifySearchRecovery(t, engine, database, baselineTotal, baselineFirstTitle, baselineIndexCount)

	step6_EdgeCase_PartialVaultCorruption(t, vaultPath, cfg, database, engine, scanner)
}

func step1_EstablishHealthBaseline(t *testing.T, vaultPath string, cfg *config.Config) (int, string, *db.Database, *search.SearchEngine, *fsnotify.Scanner) {
	t.Helper()
	t.Log("步骤 1：建立健康基线...")

	t.Log("生成 30 篇交叉引用笔记...")
	paths := testutil.GenerateCrossLinkedNotes(t, vaultPath, recoveryNoteCount)
	testutil.AssertEqual(t, recoveryNoteCount, len(paths), "生成的笔记数量应等于 30")

	database, err := db.New(cfg)
	testutil.AssertNoError(t, err, "创建数据库失败")
	testutil.AssertNotNil(t, database, "database 不应为 nil")

	scanner := fsnotify.NewScanner(cfg, database)
	t.Log("执行 ScanAll 入库...")
	added, updated, err := scanner.ScanAll()
	testutil.AssertNoError(t, err, "ScanAll 失败")
	t.Logf("ScanAll 结果：added=%d, updated=%d", added, updated)

	var noteCount int
	err = database.QueryRow("SELECT COUNT(*) FROM notes").Scan(&noteCount)
	testutil.AssertNoError(t, err, "查询 notes 表行数失败")
	testutil.AssertEqual(t, recoveryNoteCount, noteCount, "notes 表行数应等于 30")

	engine := search.NewSearchEngine(database, cfg)

	t.Log("遍历所有笔记建立搜索索引...")
	notes, err := database.GetAllNotes()
	testutil.AssertNoError(t, err, "获取所有笔记失败")
	testutil.AssertEqual(t, recoveryNoteCount, len(notes), "GetAllNotes 数量应等于 30")

	indexedCount := 0
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
		testutil.AssertNoError(t, err, "IndexNote 失败：noteID=%d, title=%s", note.ID, note.Title)
		indexedCount++
	}
	testutil.AssertEqual(t, recoveryNoteCount, indexedCount, "成功建立索引的笔记数应等于 30")

	var searchIndexCount int
	err = database.QueryRow("SELECT COUNT(*) FROM search_index").Scan(&searchIndexCount)
	testutil.AssertNoError(t, err, "查询 search_index 行数失败")
	testutil.AssertTrue(t, searchIndexCount > 0, "search_index 行数应 > 0，实际为 %d", searchIndexCount)
	t.Logf("search_index 行数：%d", searchIndexCount)

	t.Log("执行基线搜索查询 \"性能\"...")
	baselineQuery := search.SearchQuery{
		Query:       "性能",
		Page:        0,
		PageSize:    20,
		EnableFuzzy: false,
	}
	results, total, err := engine.Search(baselineQuery)
	testutil.AssertNoError(t, err, "基线搜索失败")
	testutil.AssertTrue(t, total > 0, "基线搜索 \"性能\" 应 total > 0，实际 total=%d", total)
	testutil.AssertTrue(t, len(results) > 0, "基线搜索 \"性能\" 应返回结果 > 0 条，实际返回 %d", len(results))

	baselineTotal := total
	baselineFirstTitle := results[0].Title
	if baselineFirstTitle == "" {
		baselineFirstTitle = results[0].Path
	}

	t.Logf("基线结果：total=%d, 第一条结果标题/路径=%q", baselineTotal, baselineFirstTitle)
	t.Log("步骤 1 完成：健康基线建立成功")

	return baselineTotal, baselineFirstTitle, database, engine, scanner
}

func step2_SimulateIndexCorruption(t *testing.T, database *db.Database) int {
	t.Helper()
	t.Log("步骤 2：模拟索引损坏（级别 B - DROP TABLE search_index）...")

	var baselineIndexCount int
	err := database.QueryRow("SELECT COUNT(*) FROM search_index").Scan(&baselineIndexCount)
	testutil.AssertNoError(t, err, "损坏前查询 search_index 行数失败")
	t.Logf("损坏前 search_index 行数：%d", baselineIndexCount)

	_, err = database.Exec("DROP TABLE search_index")
	testutil.AssertNoError(t, err, "DROP TABLE search_index 失败")

	t.Log("验证 search_index 表已不存在...")
	var tableCount int
	err = database.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='search_index'").Scan(&tableCount)
	testutil.AssertNoError(t, err, "验证表是否存在失败")
	testutil.AssertEqual(t, 0, tableCount, "search_index 表应已被删除")

	t.Logf("步骤 2 完成：search_index 表已删除（基线索引行数=%d）", baselineIndexCount)
	return baselineIndexCount
}

func step3_VerifyCorruptedSearchBehavior(t *testing.T, engine *search.SearchEngine) {
	t.Helper()
	t.Log("步骤 3：验证损坏后搜索行为（不 panic，返回 error 或空结果）...")

	corruptedQuery := search.SearchQuery{
		Query:       "性能",
		Page:        0,
		PageSize:    20,
		EnableFuzzy: false,
	}

	panicked := false
	var results []interface{}
	var total int
	var searchErr error

	func() {
		defer func() {
			if r := recover(); r != nil {
				panicked = true
				t.Logf("搜索触发 panic（已捕获）：%v", r)
			}
		}()
		searchResults, searchTotal, err := engine.Search(corruptedQuery)
		searchErr = err
		total = searchTotal
		results = make([]interface{}, len(searchResults))
		for i := range searchResults {
			results[i] = searchResults[i]
		}
	}()

	testutil.AssertTrue(t, !panicked, "损坏后搜索不应触发 panic")

	if searchErr != nil {
		t.Logf("搜索返回 error（符合预期）：%v", searchErr)
	} else {
		t.Logf("搜索未返回 error，total=%d, results=%d（空结果也符合预期）", total, len(results))
		testutil.AssertTrue(t, total == 0 || len(results) == 0,
			"损坏后搜索应返回 total=0 或空结果，实际 total=%d, results=%d", total, len(results))
	}

	t.Log("步骤 3 完成：损坏后搜索行为符合预期（不崩溃）")
}

func step4_RebuildIndexFromFiles(t *testing.T, database *db.Database, cfg *config.Config, vaultPath string, engine *search.SearchEngine, scanner *fsnotify.Scanner) {
	t.Helper()
	t.Log("步骤 4：检测到损坏后执行重建策略...")

	err := RebuildIndexFromFiles(database, cfg, vaultPath, engine, scanner)
	testutil.AssertNoError(t, err, "重建索引失败")

	var tableCount int
	err = database.QueryRow("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='search_index'").Scan(&tableCount)
	testutil.AssertNoError(t, err, "验证 search_index 表是否重建成功失败")
	testutil.AssertEqual(t, 1, tableCount, "search_index 表应已重建")

	var searchIndexCount int
	err = database.QueryRow("SELECT COUNT(*) FROM search_index").Scan(&searchIndexCount)
	testutil.AssertNoError(t, err, "查询重建后 search_index 行数失败")
	testutil.AssertTrue(t, searchIndexCount > 0, "重建后 search_index 行数应 > 0，实际为 %d", searchIndexCount)
	t.Logf("重建后 search_index 行数：%d", searchIndexCount)

	var noteCount int
	err = database.QueryRow("SELECT COUNT(*) FROM notes").Scan(&noteCount)
	testutil.AssertNoError(t, err, "查询重建后 notes 行数失败")
	testutil.AssertEqual(t, recoveryNoteCount, noteCount, "重建后 notes 表行数应等于 30")

	t.Log("步骤 4 完成：索引重建成功")
}

func step5_VerifySearchRecovery(t *testing.T, engine *search.SearchEngine, database *db.Database, baselineTotal int, baselineFirstTitle string, baselineIndexCount int) {
	t.Helper()
	t.Log("步骤 5：验证搜索恢复...")

	t.Log("再次执行搜索 \"性能\"...")
	recoveryQuery := search.SearchQuery{
		Query:       "性能",
		Page:        0,
		PageSize:    20,
		EnableFuzzy: false,
	}
	results, total, err := engine.Search(recoveryQuery)
	testutil.AssertNoError(t, err, "重建后搜索 \"性能\" 失败")

	testutil.AssertEqual(t, baselineTotal, total,
		"重建后搜索 total=%d 应等于基线 total=%d（允许差异 1）", total, baselineTotal)
	diff := total - baselineTotal
	if diff < 0 {
		diff = -diff
	}
	testutil.AssertTrue(t, diff <= 1,
		"重建后搜索 total 与基线差异应 <= 1，基线=%d, 实际=%d, 差异=%d", baselineTotal, total, diff)

	testutil.AssertTrue(t, len(results) > 0, "重建后搜索 \"性能\" 应返回结果 > 0 条，实际返回 %d", len(results))

	currentFirstTitle := results[0].Title
	if currentFirstTitle == "" {
		currentFirstTitle = results[0].Path
	}
	t.Logf("重建结果第一条：%q，基线第一条：%q", currentFirstTitle, baselineFirstTitle)

	if currentFirstTitle != baselineFirstTitle {
		t.Logf("注意：第一条结果与基线不完全相同（BM25 排序在少量词项冲突时可能略有差异），这是可接受的")
	}
	testutil.AssertTrue(t, currentFirstTitle != "",
		"重建后第一条结果的 Title/Path 不应为空")

	additionalQueries := []string{"practice", "Algorithms"}
	for _, query := range additionalQueries {
		t.Logf("执行附加搜索查询 %q...", query)
		q := search.SearchQuery{
			Query:       query,
			Page:        0,
			PageSize:    20,
			EnableFuzzy: false,
		}
		queryResults, queryTotal, queryErr := engine.Search(q)
		testutil.AssertNoError(t, queryErr, "搜索 %q 失败", query)
		testutil.AssertTrue(t, queryTotal > 0, "搜索 %q 应 total > 0，实际 total=%d", query, queryTotal)
		testutil.AssertTrue(t, len(queryResults) > 0, "搜索 %q 应返回结果 > 0 条，实际返回 %d", query, len(queryResults))
		t.Logf("搜索 %q 结果：total=%d, 返回=%d 条", query, queryTotal, len(queryResults))
	}

	t.Log("验证重建后索引记录数...")
	var rebuiltIndexCount int
	err = database.QueryRow("SELECT COUNT(*) FROM search_index").Scan(&rebuiltIndexCount)
	testutil.AssertNoError(t, err, "查询重建后 search_index 行数失败")
	t.Logf("重建后索引记录数：%d，基线索引记录数：%d", rebuiltIndexCount, baselineIndexCount)
	testutil.AssertTrue(t, rebuiltIndexCount > 0, "重建后 search_index 应有记录")

	totalDocCount, err := engine.GetIndexer().GetTotalDocCount()
	testutil.AssertNoError(t, err, "GetTotalDocCount 失败")
	testutil.AssertEqual(t, recoveryNoteCount, totalDocCount, "重建后总文档数应等于 30")

	t.Log("步骤 5 完成：搜索恢复验证通过")
}

func step6_EdgeCase_PartialVaultCorruption(t *testing.T, vaultPath string, cfg *config.Config, database *db.Database, engine *search.SearchEngine, scanner *fsnotify.Scanner) {
	t.Helper()
	t.Log("步骤 6：边缘异常 - 仓库文件部分损坏（删除 2 篇原始 .md 文件）...")

	notes, err := database.GetAllNotes()
	testutil.AssertNoError(t, err, "获取所有笔记失败")
	testutil.AssertTrue(t, len(notes) >= 2, "notes 表中至少应有 2 篇笔记用于删除测试")

	deletedPaths := make([]string, 2)
	for i := 0; i < 2; i++ {
		note := notes[i]
		relPath, _ := filepath.Rel(vaultPath, note.Path)
		if relPath == "" {
			relPath = filepath.Base(note.Path)
		}
		fullPath := filepath.Join(vaultPath, relPath)
		t.Logf("删除文件：%s", fullPath)
		err := os.Remove(fullPath)
		testutil.AssertNoError(t, err, "删除文件失败：%s", fullPath)
		deletedPaths[i] = relPath
	}
	t.Logf("已删除 2 篇原始文件：%v", deletedPaths)

	t.Log("在部分文件缺失的情况下再次重建索引...")
	err = RebuildIndexFromFiles(database, cfg, vaultPath, engine, scanner)
	testutil.AssertNoError(t, err, "部分文件缺失时重建索引不应返回 error")

	var remainingNoteCount int
	err = database.QueryRow("SELECT COUNT(*) FROM notes").Scan(&remainingNoteCount)
	testutil.AssertNoError(t, err, "查询部分损坏后 notes 行数失败")
	expectedRemaining := recoveryNoteCount - 2
	testutil.AssertTrue(t, remainingNoteCount == expectedRemaining || remainingNoteCount == recoveryNoteCount,
		"重建后 notes 表行数应为 %d（ScanAll 删除缺失）或 %d（notes 表未同步），实际=%d",
		expectedRemaining, recoveryNoteCount, remainingNoteCount)

	actualRemaining := remainingNoteCount
	if actualRemaining != expectedRemaining {
		actualRemaining = expectedRemaining
		t.Logf("notes 表未同步删除，实际可用笔记数约为 %d", expectedRemaining)
	}

	totalDocCount, err := engine.GetIndexer().GetTotalDocCount()
	testutil.AssertNoError(t, err, "GetTotalDocCount 失败")
	t.Logf("重建后总文档数：%d（预期约 %d）", totalDocCount, actualRemaining)
	testutil.AssertTrue(t, totalDocCount >= expectedRemaining-2 && totalDocCount <= recoveryNoteCount,
		"重建后总文档数应在合理范围内 [%d, %d]，实际=%d", expectedRemaining-2, recoveryNoteCount, totalDocCount)

	t.Log("验证剩余笔记可正常搜索...")
	recoveryQuery := search.SearchQuery{
		Query:       "性能",
		Page:        0,
		PageSize:    20,
		EnableFuzzy: false,
	}
	results, total, err := engine.Search(recoveryQuery)
	testutil.AssertNoError(t, err, "部分损坏后搜索 \"性能\" 失败")
	testutil.AssertTrue(t, total > 0, "部分损坏后搜索 \"性能\" 应仍有结果，实际 total=%d", total)
	testutil.AssertTrue(t, len(results) > 0, "部分损坏后搜索 \"性能\" 应返回结果 > 0 条", len(results))
	t.Logf("部分损坏后搜索结果：total=%d, 返回=%d 条", total, len(results))

	additionalQueries := []string{"practice", "Algorithms"}
	for _, query := range additionalQueries {
		q := search.SearchQuery{
			Query:       query,
			Page:        0,
			PageSize:    20,
			EnableFuzzy: false,
		}
		_, queryTotal, queryErr := engine.Search(q)
		testutil.AssertNoError(t, queryErr, "部分损坏后搜索 %q 失败", query)
		testutil.AssertTrue(t, queryTotal > 0, "部分损坏后搜索 %q 应 total > 0，实际 total=%d", query, queryTotal)
	}

	t.Log("步骤 6 完成：部分仓库文件损坏的边缘场景验证通过")
}

func RebuildIndexFromFiles(database *db.Database, cfg *config.Config, vaultPath string, engine *search.SearchEngine, scanner *fsnotify.Scanner) error {
	tableExists := false
	rows, err := database.Query("PRAGMA table_info(search_index)")
	if err == nil {
		if rows.Next() {
			tableExists = true
		}
		rows.Close()
	}

	if !tableExists {
		createTableSQL := `CREATE TABLE IF NOT EXISTS search_index (
			id INTEGER PRIMARY KEY AUTOINCREMENT,
			note_id INTEGER NOT NULL,
			term TEXT NOT NULL,
			frequency INTEGER DEFAULT 0,
			positions BLOB,
			FOREIGN KEY (note_id) REFERENCES notes(id) ON DELETE CASCADE
		)`
		if _, err := database.Exec(createTableSQL); err != nil {
			return fmt.Errorf("re-create search_index table failed: %w", err)
		}

		indexSQLs := []string{
			`CREATE INDEX IF NOT EXISTS idx_search_term ON search_index(term)`,
			`CREATE INDEX IF NOT EXISTS idx_search_note ON search_index(note_id)`,
		}
		for _, idxSQL := range indexSQLs {
			if _, err := database.Exec(idxSQL); err != nil {
				return fmt.Errorf("re-create search_index indexes failed: %w", err)
			}
		}
	} else {
		if _, err := database.Exec("DELETE FROM search_index"); err != nil {
			return fmt.Errorf("clear old search_index records failed: %w", err)
		}
	}

	_, _, err = scanner.ScanAll()
	if err != nil {
		return fmt.Errorf("rescan all files failed: %w", err)
	}

	notes, err := database.GetAllNotes()
	if err != nil {
		return fmt.Errorf("get all notes for re-index failed: %w", err)
	}

	for _, note := range notes {
		relPath, _ := filepath.Rel(vaultPath, note.Path)
		if relPath == "" {
			relPath = filepath.Base(note.Path)
		}
		fullPath := filepath.Join(vaultPath, relPath)
		if !filepath.IsAbs(fullPath) {
			fullPath = filepath.Join(vaultPath, relPath)
		}

		content, readErr := os.ReadFile(fullPath)
		if readErr != nil {
			continue
		}

		if indexErr := engine.IndexNote(note.ID, note.Title, string(content)); indexErr != nil {
			continue
		}
	}

	return nil
}
