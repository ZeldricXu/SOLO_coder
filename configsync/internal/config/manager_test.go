package config

import (
	"bytes"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func createTestConfigManager(t *testing.T) (string, *ConfigManager) {
	tempDir, err := os.MkdirTemp("", "configsync-config-test-*")
	if err != nil {
		t.Fatalf("创建临时目录失败: %v", err)
	}

	cm, err := NewConfigManager(tempDir)
	if err != nil {
		os.RemoveAll(tempDir)
		t.Fatalf("创建ConfigManager失败: %v", err)
	}

	return tempDir, cm
}

func cleanupTestConfig(t *testing.T, path string) {
	if err := os.RemoveAll(path); err != nil {
		t.Logf("清理临时目录失败: %v", err)
	}
}

func TestConfigManager_NewConfigManager(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	if cm == nil {
		t.Fatal("ConfigManager不应为nil")
	}

	if cm.GetRepoPath() != tempDir {
		t.Errorf("期望仓库路径 '%s'，实际: '%s'", tempDir, cm.GetRepoPath())
	}
}

func TestConfigManager_WriteAndReadConfig(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	testContent := []byte("test configuration content\nwith multiple lines")
	fileName := "test.conf"

	err := cm.WriteConfig(fileName, testContent)
	if err != nil {
		t.Fatalf("写入配置失败: %v", err)
	}

	readContent, err := cm.ReadConfig(fileName)
	if err != nil {
		t.Fatalf("读取配置失败: %v", err)
	}

	if !bytes.Equal(readContent, testContent) {
		t.Errorf("内容不匹配。期望: '%s'，实际: '%s'", string(testContent), string(readContent))
	}

	expectedPath := filepath.Join(tempDir, fileName)
	if cm.GetConfigPath(fileName) != expectedPath {
		t.Errorf("期望配置路径 '%s'，实际: '%s'", expectedPath, cm.GetConfigPath(fileName))
	}
}

func TestConfigManager_ReadNonExistentConfig(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	_, err := cm.ReadConfig("non_existent.conf")
	if err == nil {
		t.Error("读取不存在的配置应返回错误")
	}

	if !errors.Is(err, ErrConfigNotFound) {
		t.Errorf("期望返回ErrConfigNotFound，实际: %v", err)
	}
}

func TestConfigManager_SaveConfigVersion(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "versioned.conf"
	contentV1 := []byte("version 1")

	err := cm.WriteConfig(fileName, contentV1)
	if err != nil {
		t.Fatalf("写入v1失败: %v", err)
	}

	versionTag, err := cm.SaveConfigVersion(fileName, "Version 1 commit")
	if err != nil {
		t.Fatalf("保存版本失败: %v", err)
	}

	if versionTag == "" {
		t.Error("版本标签不应为空")
	}

	if !strings.HasPrefix(versionTag, "v") {
		t.Errorf("版本标签应以'v'开头，实际: '%s'", versionTag)
	}

	versions, err := cm.ListVersions()
	if err != nil {
		t.Fatalf("列出版本失败: %v", err)
	}

	if len(versions) != 1 {
		t.Errorf("期望1个版本，实际: %d", len(versions))
	}

	if versions[0] != versionTag {
		t.Errorf("期望版本标签 '%s'，实际: '%s'", versionTag, versions[0])
	}
}

func TestConfigManager_RollbackToVersion(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "rollback_test.conf"

	contentV1 := []byte("original version 1")
	err := cm.WriteConfig(fileName, contentV1)
	if err != nil {
		t.Fatalf("写入v1失败: %v", err)
	}
	v1Tag, err := cm.SaveConfigVersion(fileName, "Version 1")
	if err != nil {
		t.Fatalf("保存v1失败: %v", err)
	}

	contentV2 := []byte("modified version 2")
	err = cm.WriteConfig(fileName, contentV2)
	if err != nil {
		t.Fatalf("写入v2失败: %v", err)
	}
	_, err = cm.SaveConfigVersion(fileName, "Version 2")
	if err != nil {
		t.Fatalf("保存v2失败: %v", err)
	}

	currentContent, err := cm.ReadConfig(fileName)
	if err != nil {
		t.Fatalf("读取当前内容失败: %v", err)
	}
	if string(currentContent) != string(contentV2) {
		t.Error("当前应为v2内容")
	}

	err = cm.RollbackToVersion(fileName, v1Tag)
	if err != nil {
		t.Fatalf("回滚失败: %v", err)
	}

	rolledBackContent, err := cm.ReadConfig(fileName)
	if err != nil {
		t.Fatalf("读取回滚后内容失败: %v", err)
	}
	if string(rolledBackContent) != string(contentV1) {
		t.Errorf("回滚后期望v1内容，实际: '%s'", string(rolledBackContent))
	}
}

func TestConfigManager_ReadConfigAtVersion(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "read_version.conf"

	contentV1 := []byte("first version")
	err := cm.WriteConfig(fileName, contentV1)
	if err != nil {
		t.Fatalf("写入v1失败: %v", err)
	}
	v1Tag, err := cm.SaveConfigVersion(fileName, "Version 1")
	if err != nil {
		t.Fatalf("保存v1失败: %v", err)
	}

	contentV2 := []byte("second version updated")
	err = cm.WriteConfig(fileName, contentV2)
	if err != nil {
		t.Fatalf("写入v2失败: %v", err)
	}
	_, err = cm.SaveConfigVersion(fileName, "Version 2")
	if err != nil {
		t.Fatalf("保存v2失败: %v", err)
	}

	readV1, err := cm.ReadConfigAtVersion(fileName, v1Tag)
	if err != nil {
		t.Fatalf("读取v1版本失败: %v", err)
	}
	if string(readV1) != string(contentV1) {
		t.Errorf("期望v1内容 '%s'，实际: '%s'", string(contentV1), string(readV1))
	}
}

func TestConfigManager_GetDiff(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "diff_test.conf"

	initialContent := []byte("line1\nline2\nline3")
	err := cm.WriteConfig(fileName, initialContent)
	if err != nil {
		t.Fatalf("写入初始内容失败: %v", err)
	}
	_, err = cm.SaveConfigVersion(fileName, "Initial version")
	if err != nil {
		t.Fatalf("保存初始版本失败: %v", err)
	}

	diff, err := cm.GetDiff(fileName)
	if err != nil {
		t.Fatalf("获取差异失败: %v", err)
	}
	if diff != "" {
		t.Errorf("刚提交后期望无差异，实际: '%s'", diff)
	}

	modifiedContent := []byte("line1\nline2 modified\nline3\nline4 added")
	err = cm.WriteConfig(fileName, modifiedContent)
	if err != nil {
		t.Fatalf("写入修改后内容失败: %v", err)
	}

	diff, err = cm.GetDiff(fileName)
	if err != nil {
		t.Fatalf("获取差异失败: %v", err)
	}

	t.Logf("差异内容: %s", diff)
}

func TestConfigManager_CreatePushSnapshotWithMeta(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "snapshot_meta.conf"
	testContent := []byte("snapshot test content")

	err := cm.WriteConfig(fileName, testContent)
	if err != nil {
		t.Fatalf("写入内容失败: %v", err)
	}

	targetGroup := "production_servers"
	operator := "admin_user"
	serverCount := 8
	changeType := "update"

	versionTag, meta, err := cm.CreatePushSnapshotWithMeta(
		fileName,
		targetGroup,
		operator,
		serverCount,
		changeType,
	)
	if err != nil {
		t.Fatalf("创建带元数据的快照失败: %v", err)
	}

	if versionTag == "" {
		t.Error("版本标签不应为空")
	}

	if meta == nil {
		t.Fatal("元数据不应为nil")
	}

	if meta.ConfigFile != fileName {
		t.Errorf("期望ConfigFile '%s'，实际: '%s'", fileName, meta.ConfigFile)
	}
	if meta.TargetGroup != targetGroup {
		t.Errorf("期望TargetGroup '%s'，实际: '%s'", targetGroup, meta.TargetGroup)
	}
	if meta.Operator != operator {
		t.Errorf("期望Operator '%s'，实际: '%s'", operator, meta.Operator)
	}
	if meta.ServerCount != serverCount {
		t.Errorf("期望ServerCount %d，实际: %d", serverCount, meta.ServerCount)
	}
	if meta.ChangeType != changeType {
		t.Errorf("期望ChangeType '%s'，实际: '%s'", changeType, meta.ChangeType)
	}
	if meta.VersionTag != versionTag {
		t.Errorf("期望VersionTag '%s'，实际: '%s'", versionTag, meta.VersionTag)
	}
	if meta.CommitHash == "" {
		t.Error("CommitHash不应为空")
	}
	if meta.ExecutedAt.IsZero() {
		t.Error("ExecutedAt不应为零")
	}

	retrievedMeta, err := cm.GetVersionMeta(versionTag)
	if err != nil {
		t.Fatalf("获取版本元数据失败: %v", err)
	}

	if retrievedMeta.TargetGroup != targetGroup {
		t.Errorf("获取的元数据中期望TargetGroup '%s'，实际: '%s'", targetGroup, retrievedMeta.TargetGroup)
	}
}

func TestConfigManager_ListVersionMetaHistory(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "history_meta.conf"

	for i := 1; i <= 3; i++ {
		content := []byte("version content")
		err := cm.WriteConfig(fileName, content)
		if err != nil {
			t.Fatalf("写入版本%d失败: %v", i, err)
		}

		_, _, err = cm.CreatePushSnapshotWithMeta(
			fileName,
			"test_group",
			"tester",
			i,
			"update",
		)
		if err != nil {
			t.Fatalf("创建快照%d失败: %v", i, err)
		}
	}

	history, err := cm.ListVersionMetaHistory(0)
	if err != nil {
		t.Fatalf("获取版本历史失败: %v", err)
	}

	if len(history) != 3 {
		t.Errorf("期望3条历史记录，实际: %d", len(history))
	}

	limitedHistory, err := cm.ListVersionMetaHistory(2)
	if err != nil {
		t.Fatalf("获取限制版本历史失败: %v", err)
	}

	if len(limitedHistory) != 2 {
		t.Errorf("期望2条历史记录，实际: %d", len(limitedHistory))
	}
}

func TestConfigManager_ImportExport(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	sourceDir, err := os.MkdirTemp("", "import-test-*")
	if err != nil {
		t.Fatalf("创建导入源目录失败: %v", err)
	}
	defer os.RemoveAll(sourceDir)

	importContent := []byte("imported configuration content")
	sourceFile := filepath.Join(sourceDir, "import.conf")
	err = os.WriteFile(sourceFile, importContent, 0644)
	if err != nil {
		t.Fatalf("写入源文件失败: %v", err)
	}

	targetName := "imported_config.conf"
	err = cm.ImportConfig(sourceFile, targetName)
	if err != nil {
		t.Fatalf("导入配置失败: %v", err)
	}

	readContent, err := cm.ReadConfig(targetName)
	if err != nil {
		t.Fatalf("读取导入的配置失败: %v", err)
	}
	if !bytes.Equal(readContent, importContent) {
		t.Errorf("导入的内容不匹配")
	}

	exportDir, err := os.MkdirTemp("", "export-test-*")
	if err != nil {
		t.Fatalf("创建导出目标目录失败: %v", err)
	}
	defer os.RemoveAll(exportDir)

	exportPath := filepath.Join(exportDir, "exported", "exported.conf")
	err = cm.ExportConfig(targetName, exportPath)
	if err != nil {
		t.Fatalf("导出配置失败: %v", err)
	}

	exportedContent, err := os.ReadFile(exportPath)
	if err != nil {
		t.Fatalf("读取导出的文件失败: %v", err)
	}
	if !bytes.Equal(exportedContent, importContent) {
		t.Error("导出的内容不匹配")
	}

	err = cm.ImportConfig("/non/existent/file", "target")
	if err == nil {
		t.Error("导入不存在的文件应返回错误")
	}
	if !errors.Is(err, ErrConfigNotFound) {
		t.Errorf("期望返回ErrConfigNotFound，实际: %v", err)
	}
}

func TestConfigManager_ListConfigFiles(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	configFiles := []string{
		"nginx.conf",
		"redis.conf",
		"app/config.yaml",
	}

	for _, fileName := range configFiles {
		fullPath := filepath.Join(tempDir, fileName)
		dir := filepath.Dir(fullPath)
		if err := os.MkdirAll(dir, 0755); err != nil {
			t.Fatalf("创建目录失败: %v", err)
		}
		if err := os.WriteFile(fullPath, []byte("test"), 0644); err != nil {
			t.Fatalf("写入文件失败: %v", err)
		}
	}

	listedFiles, err := cm.ListConfigFiles()
	if err != nil {
		t.Fatalf("列出配置文件失败: %v", err)
	}

	if len(listedFiles) != 3 {
		t.Errorf("期望3个配置文件，实际: %d", len(listedFiles))
	}

	for _, expected := range configFiles {
		found := false
		for _, listed := range listedFiles {
			if listed == expected {
				found = true
				break
			}
		}
		if !found {
			t.Errorf("期望找到文件 '%s'，但未找到", expected)
		}
	}
}

func TestConfigManager_GetChangeSummary(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	oldContent := []byte("line1\nline2\nline3")
	newContent := []byte("line1\nline2 modified\nline3\nline4\nline5")

	summary := cm.GetChangeSummary(oldContent, newContent)
	t.Logf("变更摘要: %s", summary)

	if !strings.Contains(summary, "+") || !strings.Contains(summary, "lines") {
		t.Error("变更摘要格式不正确")
	}

	emptyContent := []byte("")
	summary = cm.GetChangeSummary(emptyContent, emptyContent)
	if !strings.Contains(summary, "+0") || !strings.Contains(summary, "-0") {
		t.Errorf("空内容的摘要应为'+0 lines, -0 lines'，实际: '%s'", summary)
	}
}

func TestConfigManager_CreatePushSnapshot(t *testing.T) {
	tempDir, cm := createTestConfigManager(t)
	defer cleanupTestConfig(t, tempDir)

	fileName := "push_snapshot.conf"
	testContent := []byte("push test content")

	err := cm.WriteConfig(fileName, testContent)
	if err != nil {
		t.Fatalf("写入内容失败: %v", err)
	}

	targetGroup := "test_servers"
	versionTag, err := cm.CreatePushSnapshot(fileName, targetGroup)
	if err != nil {
		t.Fatalf("创建推送快照失败: %v", err)
	}

	if versionTag == "" {
		t.Error("版本标签不应为空")
	}

	versions, err := cm.ListVersions()
	if err != nil {
		t.Fatalf("列出版本失败: %v", err)
	}

	if len(versions) != 1 {
		t.Errorf("期望1个版本，实际: %d", len(versions))
	}
}

func TestGenerateVersionTag(t *testing.T) {
	tag1 := generateVersionTag()
	if tag1 == "" {
		t.Error("版本标签不应为空")
	}
	if !strings.HasPrefix(tag1, "v") {
		t.Errorf("版本标签应以'v'开头，实际: '%s'", tag1)
	}
}

func TestCountLines(t *testing.T) {
	tests := []struct {
		name     string
		input    []byte
		expected int
	}{
		{"空内容", []byte(""), 0},
		{"单行无换行", []byte("single line"), 1},
		{"单行带换行", []byte("single line\n"), 1},
		{"三行", []byte("line1\nline2\nline3"), 3},
		{"三行带结尾换行", []byte("line1\nline2\nline3\n"), 3},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			result := countLines(tt.input)
			if result != tt.expected {
				t.Errorf("期望%d行，实际: %d", tt.expected, result)
			}
		})
	}
}
