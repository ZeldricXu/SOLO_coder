package vcs

import (
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"configsync/internal/models"
)

func createTestRepo(t *testing.T) (string, *GitManager) {
	tempDir, err := os.MkdirTemp("", "configsync-test-*")
	if err != nil {
		t.Fatalf("创建临时目录失败: %v", err)
	}

	gm, err := NewGitManager(tempDir)
	if err != nil {
		os.RemoveAll(tempDir)
		t.Fatalf("创建GitManager失败: %v", err)
	}

	return tempDir, gm
}

func cleanupTestRepo(t *testing.T, path string) {
	if err := os.RemoveAll(path); err != nil {
		t.Logf("清理临时目录失败: %v", err)
	}
}

func TestGitManager_InitRepository(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	if gm == nil {
		t.Fatal("GitManager不应为nil")
	}

	if gm.GetRepoPath() != tempDir {
		t.Errorf("期望仓库路径 '%s'，实际: '%s'", tempDir, gm.GetRepoPath())
	}

	info, err := os.Stat(filepath.Join(tempDir, ".git"))
	if err != nil {
		t.Fatalf(".git目录不存在: %v", err)
	}
	if !info.IsDir() {
		t.Error(".git应该是目录")
	}
}

func TestGitManager_WriteAndCommit(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	testContent := []byte("server {\n    listen 80;\n    server_name localhost;\n}")
	err := gm.WriteConfigFile("nginx.conf", testContent)
	if err != nil {
		t.Fatalf("写入配置文件失败: %v", err)
	}

	commitHash, err := gm.Commit("Initial commit: add nginx.conf")
	if err != nil {
		t.Fatalf("提交失败: %v", err)
	}

	if commitHash.IsZero() {
		t.Error("提交哈希不应为零")
	}
}

func TestGitManager_CommitWithMeta(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	testContent := []byte("test configuration v1")
	err := gm.WriteConfigFile("test.conf", testContent)
	if err != nil {
		t.Fatalf("写入配置文件失败: %v", err)
	}

	meta := &models.VersionSnapshotMeta{
		ConfigFile:  "test.conf",
		TargetGroup:   "test_servers",
		Operator:     "test_operator",
		ChangeType:    "update",
		ServerCount:   5,
	}

	commitHash, err := gm.CommitWithMeta("Push snapshot: test.conf to group test_servers", meta)
	if err != nil {
		t.Fatalf("带元数据的提交失败: %v", err)
	}

	if commitHash.IsZero() {
		t.Error("提交哈希不应为零")
	}

	retrievedMeta, err := gm.GetCommitMeta(commitHash)
	if err != nil {
		t.Fatalf("获取提交元数据失败: %v", err)
	}

	if retrievedMeta.ConfigFile != meta.ConfigFile {
		t.Errorf("期望ConfigFile '%s'，实际: '%s'", meta.ConfigFile, retrievedMeta.ConfigFile)
	}
	if retrievedMeta.TargetGroup != meta.TargetGroup {
		t.Errorf("期望TargetGroup '%s'，实际: '%s'", meta.TargetGroup, retrievedMeta.TargetGroup)
	}
	if retrievedMeta.Operator != meta.Operator {
		t.Errorf("期望Operator '%s'，实际: '%s'", meta.Operator, retrievedMeta.Operator)
	}
	if retrievedMeta.ChangeType != meta.ChangeType {
		t.Errorf("期望ChangeType '%s'，实际: '%s'", meta.ChangeType, retrievedMeta.ChangeType)
	}
	if retrievedMeta.ServerCount != meta.ServerCount {
		t.Errorf("期望ServerCount %d，实际: %d", meta.ServerCount, retrievedMeta.ServerCount)
	}
	if retrievedMeta.ExecutedAt.IsZero() {
		t.Error("ExecutedAt不应为零")
	}
}

func TestGitManager_TagManagement(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	testContent := []byte("test content")
	err := gm.WriteConfigFile("tagged.conf", testContent)
	if err != nil {
		t.Fatalf("写入配置文件失败: %v", err)
	}

	commitHash, err := gm.Commit("Commit for tag test")
	if err != nil {
		t.Fatalf("提交失败: %v", err)
	}

	tagName := "v1.0.0"
	err = gm.CreateTag(tagName, commitHash)
	if err != nil {
		t.Fatalf("创建标签失败: %v", err)
	}

	tags, err := gm.ListTags()
	if err != nil {
		t.Fatalf("列出标签失败: %v", err)
	}

	found := false
	for _, tag := range tags {
		if tag == tagName {
			found = true
			break
		}
	}
	if !found {
		t.Errorf("期望找到标签 '%s'，实际标签: %v", tagName, tags)
	}

	tagRef, err := gm.GetTag(tagName)
	if err != nil {
		t.Fatalf("获取标签失败: %v", err)
	}
	if tagRef.Hash() != commitHash {
		t.Errorf("标签哈希不匹配")
	}
}

func TestGitManager_Checkout(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	contentV1 := []byte("version 1 content")
	err := gm.WriteConfigFile("versioned.conf", contentV1)
	if err != nil {
		t.Fatalf("写入v1失败: %v", err)
	}
	commit1, err := gm.Commit("Version 1")
	if err != nil {
		t.Fatalf("提交v1失败: %v", err)
	}

	tagV1 := "v1.0.0"
	err = gm.CreateTag(tagV1, commit1)
	if err != nil {
		t.Fatalf("创建标签v1失败: %v", err)
	}

	contentV2 := []byte("version 2 content updated")
	err = gm.WriteConfigFile("versioned.conf", contentV2)
	if err != nil {
		t.Fatalf("写入v2失败: %v", err)
	}
	commit2, err := gm.Commit("Version 2")
	if err != nil {
		t.Fatalf("提交v2失败: %v", err)
	}

	currentContent, err := os.ReadFile(filepath.Join(tempDir, "versioned.conf"))
	if err != nil {
		t.Fatalf("读取当前内容失败: %v", err)
	}
	if string(currentContent) != string(contentV2) {
		t.Error("当前应为v2内容")
	}

	err = gm.CheckoutByTag(tagV1)
	if err != nil {
		t.Fatalf("通过标签检出失败: %v", err)
	}

	checkoutContent, err := os.ReadFile(filepath.Join(tempDir, "versioned.conf"))
	if err != nil {
		t.Fatalf("读取检出后内容失败: %v", err)
	}
	if string(checkoutContent) != string(contentV1) {
		t.Errorf("检出后期望v1内容，实际: '%s'", string(checkoutContent))
	}

	err = gm.CheckoutByCommit(commit2)
	if err != nil {
		t.Fatalf("通过提交哈希检出失败: %v", err)
	}

	restoredContent, err := os.ReadFile(filepath.Join(tempDir, "versioned.conf"))
	if err != nil {
		t.Fatalf("读取恢复后内容失败: %v", err)
	}
	if string(restoredContent) != string(contentV2) {
		t.Error("恢复后期望v2内容")
	}
}

func TestGitManager_ReadFileAtVersion(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	fileName := "readtest.conf"
	contentV1 := []byte("content for reading v1")
	err := gm.WriteConfigFile(fileName, contentV1)
	if err != nil {
		t.Fatalf("写入v1失败: %v", err)
	}
	commit1, err := gm.Commit("First version")
	if err != nil {
		t.Fatalf("提交v1失败: %v", err)
	}
	tagV1 := "read-v1"
	err = gm.CreateTag(tagV1, commit1)
	if err != nil {
		t.Fatalf("创建标签失败: %v", err)
	}

	contentV2 := []byte("content for reading v2, much longer and different")
	err = gm.WriteConfigFile(fileName, contentV2)
	if err != nil {
		t.Fatalf("写入v2失败: %v", err)
	}
	_, err = gm.Commit("Second version")
	if err != nil {
		t.Fatalf("提交v2失败: %v", err)
	}

	readV1, err := gm.ReadFileAtVersion(fileName, tagV1)
	if err != nil {
		t.Fatalf("读取v1版本失败: %v", err)
	}
	if string(readV1) != string(contentV1) {
		t.Errorf("期望v1内容 '%s'，实际: '%s'", string(contentV1), string(readV1))
	}

	readLatest, err := gm.ReadFileAtVersion(fileName, "")
	if err != nil {
		t.Errorf("读取最新版本失败: %v", err)
	}
	if string(readLatest) != string(contentV2) {
		t.Errorf("期望最新内容 '%s'，实际: '%s'", string(contentV2), string(readLatest))
	}
}

func TestGitManager_GetHistory(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	for i := 1; i <= 5; i++ {
		content := []byte(fmt.Sprintf("commit %d content", i))
		err := gm.WriteConfigFile("history_test.conf", content)
		if err != nil {
			t.Fatalf("写入失败: %v", err)
		}
		_, err = gm.Commit(fmt.Sprintf("Commit %d", i))
		if err != nil {
			t.Fatalf("提交失败: %v", err)
		}
	}

	history, err := gm.GetHistory(3)
	if err != nil {
		t.Fatalf("获取历史失败: %v", err)
	}

	if len(history) != 3 {
		t.Errorf("期望3条历史记录，实际: %d", len(history))
	}

	fullHistory, err := gm.GetHistory(0)
	if err != nil {
		t.Fatalf("获取完整历史失败: %v", err)
	}

	if len(fullHistory) != 5 {
		t.Errorf("期望5条历史记录，实际: %d", len(fullHistory))
	}
}

func TestGitManager_ListVersionHistory(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	for i := 1; i <= 3; i++ {
		content := []byte(fmt.Sprintf("version %d content", i))
		err := gm.WriteConfigFile("meta_test.conf", content)
		if err != nil {
			t.Fatalf("写入失败: %v", err)
		}

		meta := &models.VersionSnapshotMeta{
			ConfigFile:  "meta_test.conf",
			TargetGroup:  fmt.Sprintf("group_%d", i),
			Operator:     "tester",
			ChangeType:   "update",
			ServerCount:  i * 2,
		}
		_, err = gm.CommitWithMeta(fmt.Sprintf("Push version %d", i), meta)
		if err != nil {
			t.Fatalf("带元数据的提交失败: %v", err)
		}
	}

	versionHistory, err := gm.ListVersionHistory(0)
	if err != nil {
		t.Fatalf("获取版本历史失败: %v", err)
	}

	if len(versionHistory) != 3 {
		t.Errorf("期望3条版本历史，实际: %d", len(versionHistory))
	}

	for i, meta := range versionHistory {
		expectedGroup := fmt.Sprintf("group_%d", 3-i)
		if meta.TargetGroup != expectedGroup {
			t.Errorf("记录%d期望目标组'%s'，实际: '%s'", i, expectedGroup, meta.TargetGroup)
		}
		if meta.ConfigFile != "meta_test.conf" {
			t.Errorf("记录%d期望配置文件'meta_test.conf'，实际: '%s'", i, meta.ConfigFile)
		}
	}
}

func TestGitManager_GetTagMeta(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	testContent := []byte("tag meta test content")
	err := gm.WriteConfigFile("tagmeta.conf", testContent)
	if err != nil {
		t.Fatalf("写入失败: %v", err)
	}

	meta := &models.VersionSnapshotMeta{
		ConfigFile:  "tagmeta.conf",
		TargetGroup:  "tagged_group",
		Operator:     "tag_tester",
		ChangeType:   "rollback",
		ServerCount:  3,
	}
	commitHash, err := gm.CommitWithMeta("Tag with metadata", meta)
	if err != nil {
		t.Fatalf("提交失败: %v", err)
	}

	tagName := "v2026.05.05-test"
	err = gm.CreateTag(tagName, commitHash)
	if err != nil {
		t.Fatalf("创建标签失败: %v", err)
	}

	tagMeta, err := gm.GetTagMeta(tagName)
	if err != nil {
		t.Fatalf("获取标签元数据失败: %v", err)
	}

	if tagMeta.TargetGroup != "tagged_group" {
		t.Errorf("期望目标组'tagged_group'，实际: '%s'", tagMeta.TargetGroup)
	}
	if tagMeta.Operator != "tag_tester" {
		t.Errorf("期望操作者'tag_tester'，实际: '%s'", tagMeta.Operator)
	}
	if tagMeta.ChangeType != "rollback" {
		t.Errorf("期望变更类型'rollback'，实际: '%s'", tagMeta.ChangeType)
	}
}

func TestGitManager_GetDiff(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	initialContent := []byte("line1\nline2\nline3\n")
	err := gm.WriteConfigFile("diff_test.conf", initialContent)
	if err != nil {
		t.Fatalf("写入初始内容失败: %v", err)
	}
	_, err = gm.Commit("Initial for diff test")
	if err != nil {
		t.Fatalf("提交初始内容失败: %v", err)
	}

	diff, err := gm.GetDiff("diff_test.conf")
	if err != nil {
		t.Fatalf("获取差异失败: %v", err)
	}
	if diff != "" {
		t.Errorf("刚提交后期望无差异，实际: '%s'", diff)
	}

	modifiedContent := []byte("line1\nline2 modified\nline3\nline4 new\n")
	err = gm.WriteConfigFile("diff_test.conf", modifiedContent)
	if err != nil {
		t.Fatalf("写入修改后内容失败: %v", err)
	}

	diff, err = gm.GetDiff("diff_test.conf")
	if err != nil {
		t.Fatalf("获取差异失败: %v", err)
	}

	if !strings.Contains(diff, "modified") || !strings.Contains(diff, "new") {
		t.Logf("差异内容: %s", diff)
		t.Error("差异应包含'modified'或'new'")
	}
}

func TestGitManager_CleanCommit(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	_, err := gm.Commit("Try to commit nothing")
	if err == nil {
		t.Error("空提交应返回错误")
	}
	if !strings.Contains(err.Error(), "nothing to commit") {
		t.Errorf("期望错误包含'nothing to commit'，实际: %v", err)
	}
}

func TestGitManager_NonExistentTag(t *testing.T) {
	tempDir, gm := createTestRepo(t)
	defer cleanupTestRepo(t, tempDir)

	_, err := gm.GetTag("non_existent_tag")
	if err == nil {
		t.Error("获取不存在的标签应返回错误")
	}

	err = gm.CheckoutByTag("non_existent_tag")
	if err == nil {
		t.Error("检出不存在的标签应返回错误")
	}

	_, err = gm.ReadFileAtVersion("somefile.txt, some_tag")
	if err == nil {
		t.Error("读取不存在版本的不存在文件应返回错误")
	}
}

func TestParseMetaFromMessage(t *testing.T) {
	meta := &models.VersionSnapshotMeta{
		ConfigFile:  "test.conf",
		TargetGroup:  "test_group",
		Operator:     "operator_user",
		ChangeType:   "update",
		ServerCount:  10,
		ExecutedAt:   time.Now(),
	}

	metaJSON, _ := json.MarshalIndent(meta, "", "  ")
	message := fmt.Sprintf("Test commit message\n\n---METADATA---\n%s", string(metaJSON))

	parsed, err := parseMetaFromMessage(message)
	if err != nil {
		t.Fatalf("解析元数据失败: %v", err)
	}

	if parsed.ConfigFile != meta.ConfigFile {
		t.Errorf("期望ConfigFile '%s'，实际: '%s'", meta.ConfigFile, parsed.ConfigFile)
	}
	if parsed.TargetGroup != meta.TargetGroup {
		t.Errorf("期望TargetGroup '%s'，实际: '%s'", meta.TargetGroup, parsed.TargetGroup)
	}

	invalidMessage := "This message has no metadata"
	_, err = parseMetaFromMessage(invalidMessage)
	if err == nil {
		t.Error("无元数据的消息应返回错误")
	}

	badJSON := "Some message\n\n---METADATA---\n{invalid json"
	_, err = parseMetaFromMessage(badJSON)
	if err == nil {
		t.Error("无效JSON应返回错误")
	}
}
