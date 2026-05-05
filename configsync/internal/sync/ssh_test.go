package sync

import (
	"bytes"
	"strings"
	"testing"

	"configsync/internal/models"
)

var (
	testPushServer = &models.Server{
		ServerID:   "test-server-1",
		Host:       "192.168.1.100",
		Port:       22,
		User:       "deploy",
		ConfigPath: "/etc/nginx/nginx.conf",
	}
)

func createMockTestSetup() (*MockSSHExecutor, *TestSyncService) {
	mock := NewMockSSHExecutor()
	service := NewTestSyncService(mock, testPushServer)
	return mock, service
}

func TestTestSyncService_PushConfig_Success(t *testing.T) {
	mock, service := createMockTestSetup()

	testContent := []byte("server {\n    listen 80;\n    server_name test.com;\n}")

	result := service.PushConfig(testContent, true, "systemctl reload nginx")

	if !result.Success {
		t.Errorf("推送应成功，但返回失败: %s", result.Error)
	}

	if !result.Reloaded {
		t.Error("重载应被调用")
	}

	if !mock.IsReloadCalled() {
		t.Error("mock的重载方法应被调用")
	}

	if mock.GetLastReloadCommand() != "systemctl reload nginx" {
		t.Errorf("期望重载命令'systemctl reload nginx'，实际: '%s'", mock.GetLastReloadCommand())
	}

	storedContent := mock.GetConfigContent(testPushServer.ConfigPath)
	if !bytes.Equal(storedContent, testContent) {
		t.Errorf("存储的内容不匹配。期望: '%s'，实际: '%s'", string(testContent), string(storedContent))
	}

	history := mock.GetTransferHistory()
	if len(history) != 1 {
		t.Errorf("期望1次传输记录，实际: %d", len(history))
	}
}

func TestTestSyncService_PushConfig_NoReload(t *testing.T) {
	mock, service := createMockTestSetup()

	testContent := []byte("config content")

	result := service.PushConfig(testContent, false, "")

	if !result.Success {
		t.Errorf("推送应成功，但返回失败: %s", result.Error)
	}

	if result.Reloaded {
		t.Error("不应调用重载")
	}

	if mock.IsReloadCalled() {
		t.Error("mock的重载方法不应被调用")
	}
}

func TestTestSyncService_PushConfig_BackupFailure(t *testing.T) {
	mock, service := createMockTestSetup()

	mock.SetShouldFail(true)

	testContent := []byte("test config")
	result := service.PushConfig(testContent, false, "")

	if result.Success {
		t.Error("备份失败时推送应失败")
	}

	if !strings.Contains(result.Error, "backup failed") {
		t.Errorf("错误信息应包含'backup failed'，实际: '%s'", result.Error)
	}

	history := mock.GetTransferHistory()
	if len(history) > 0 {
		t.Error("备份失败时不应进行传输")
	}
}

func TestTestSyncService_PushConfig_TransferFailure(t *testing.T) {
	mock, service := createMockTestSetup()

	mock.SetFailAfterTransfer(true)

	testContent := []byte("test config")
	result := service.PushConfig(testContent, false, "")

	if result.Success {
		t.Error("传输失败时推送应失败")
	}

	if !strings.Contains(result.Error, "file transfer failed") {
		t.Errorf("错误信息应包含'file transfer failed'，实际: '%s'", result.Error)
	}
}

func TestTestSyncService_PushConfig_ReloadFailure(t *testing.T) {
	mock, service := createMockTestSetup()

	mock.SetCommandResult("systemctl", &CommandResult{
		Stdout: "",
		Stderr: "service not found",
		Err:    nil,
	})

	testContent := []byte("test config")
	result := service.PushConfig(testContent, true, "systemctl reload nginx")

	if !result.Success {
		t.Error("重载警告不应导致推送失败")
	}

	if !result.Reloaded {
		t.Error("重载应被标记为已调用")
	}

	if result.ReloadError == "" {
		t.Error("重载错误应被记录")
	}
}

func TestTestSyncService_GetRemoteConfig(t *testing.T) {
	mock, service := createMockTestSetup()

	remoteContent := []byte("remote nginx config\nline2\nline3")
	mock.SetConfigContent(testPushServer.ConfigPath, remoteContent)

	retrievedContent, err := service.GetRemoteConfig()
	if err != nil {
		t.Fatalf("获取远程配置失败: %v", err)
	}

	if !bytes.Equal(retrievedContent, remoteContent) {
		t.Errorf("内容不匹配。期望: '%s'，实际: '%s'", string(remoteContent), string(retrievedContent))
	}
}

func TestTestSyncService_GetRemoteConfig_FileNotFound(t *testing.T) {
	mock, service := createMockTestSetup()

	mock.SetCommandResult("cat", &CommandResult{
		Stdout: "",
		Stderr: "No such file or directory",
		Err:    nil,
	})

	_, err := service.GetRemoteConfig()
	if err == nil {
		t.Error("获取不存在的文件应返回错误")
	}
}

func TestTestSyncService_DiffLocalRemote(t *testing.T) {
	mock, service := createMockTestSetup()

	remoteContent := []byte("line1\nline2\nline3")
	mock.SetConfigContent(testPushServer.ConfigPath, remoteContent)

	localContent := []byte("line1\nline2 modified\nline3\nline4 added")

	diff, err := service.DiffLocalRemote(localContent)
	if err != nil {
		t.Fatalf("比对差异失败: %v", err)
	}

	t.Logf("差异输出:\n%s", diff)

	if !strings.Contains(diff, "+") || !strings.Contains(diff, "-") {
		t.Error("差异输出应包含'+'或'-'标记")
	}
}

func TestTestSyncService_DiffLocalRemote_Identical(t *testing.T) {
	mock, service := createMockTestSetup()

	testContent := []byte("identical content\nline2")
	mock.SetConfigContent(testPushServer.ConfigPath, testContent)

	diff, err := service.DiffLocalRemote(testContent)
	if err != nil {
		t.Fatalf("比对差异失败: %v", err)
	}

	t.Logf("差异输出:\n%s", diff)

	if strings.Contains(diff, "+") || strings.Contains(diff, "-") {
		t.Error("相同内容的差异不应包含'+'或'-'标记")
	}
}

func TestMockSSHExecutor_Reset(t *testing.T) {
	mock := NewMockSSHExecutor()

	mock.SetConfigContent("/test/path", []byte("test"))
	mock.SetShouldFail(true)
	mock.TransferContent([]byte("content"), "/another/path")
	mock.ReloadService("reload cmd")

	mock.Reset()

	if len(mock.GetTransferHistory()) != 0 {
		t.Error("重置后传输历史应为空")
	}
	if mock.IsReloadCalled() {
		t.Error("重置后重载标记应为false")
	}
	if mock.GetConfigContent("/test/path") != nil {
		t.Error("重置后配置内容应为空")
	}
}

func TestMockSSHExecutor_MultipleTransfers(t *testing.T) {
	mock := NewMockSSHExecutor()

	contents := [][]byte{
		[]byte("first content"),
		[]byte("second content"),
		[]byte("third content"),
	}

	paths := []string{
		"/path1",
		"/path2",
		"/path3",
	}

	for i, content := range contents {
		err := mock.TransferContent(content, paths[i])
		if err != nil {
			t.Fatalf("传输%d失败: %v", i+1, err)
		}
	}

	history := mock.GetTransferHistory()
	if len(history) != 3 {
		t.Errorf("期望3次传输记录，实际: %d", len(history))
	}

	for i, path := range paths {
		stored := mock.GetConfigContent(path)
		if !bytes.Equal(stored, contents[i]) {
			t.Errorf("路径%s的内容不匹配", path)
		}
	}
}

func TestMockSSHExecutor_CommandResultPatternMatching(t *testing.T) {
	mock := NewMockSSHExecutor()

	mock.SetCommandResult("systemctl reload", &CommandResult{
		Stdout: "Reloaded",
		Stderr: "",
		Err:    nil,
	})

	mock.SetCommandResult("cat", &CommandResult{
		Stdout: "file content",
		Stderr: "",
		Err:    nil,
	})

	stdout, stderr, err := mock.ExecuteCommand("systemctl reload nginx")
	if err != nil {
		t.Errorf("systemctl命令应成功: %v", err)
	}
	if stdout != "Reloaded" {
		t.Errorf("期望输出'Reloaded'，实际: '%s'", stdout)
	}

	stdout, stderr, err = mock.ExecuteCommand("cat /etc/config")
	if err != nil {
		t.Errorf("cat命令应成功: %v", err)
	}
	if stdout != "file content" {
		t.Errorf("期望输出'file content'，实际: '%s'", stdout)
	}
}

func TestTestSyncService_FullPushFlow(t *testing.T) {
	mock, service := createMockTestSetup()

	initialRemoteContent := []byte("old config\nline2")
	mock.SetConfigContent(testPushServer.ConfigPath, initialRemoteContent)

	diff, err := service.DiffLocalRemote([]byte("new config"))
	if err != nil {
		t.Fatalf("初始差异比对失败: %v", err)
	}
	t.Logf("初始差异:\n%s", diff)

	newContent := []byte("new config\nupdated line2\nnew line3")
	result := service.PushConfig(newContent, true, "service nginx reload")

	if !result.Success {
		t.Errorf("推送应成功: %s", result.Error)
	}

	if !result.Reloaded {
		t.Error("重载应被调用")
	}

	storedContent := mock.GetConfigContent(testPushServer.ConfigPath)
	if !bytes.Equal(storedContent, newContent) {
		t.Error("存储的内容应与推送的内容匹配")
	}

	history := mock.GetTransferHistory()
	if len(history) != 1 {
		t.Errorf("期望1次传输，实际: %d", len(history))
	}

	t.Logf("推送结果: 成功=%v, 重载=%v, 重载错误=%s",
		result.Success, result.Reloaded, result.ReloadError)
}

func TestMockSSHExecutor_Close(t *testing.T) {
	mock := NewMockSSHExecutor()

	err := mock.Close()
	if err != nil {
		t.Errorf("关闭mock应返回nil，实际: %v", err)
	}
}

func TestMockSSHExecutor_SetConfigContent(t *testing.T) {
	mock := NewMockSSHExecutor()

	testPath := "/test/config.conf"
	testContent := []byte("test configuration")

	mock.SetConfigContent(testPath, testContent)

	retrieved := mock.GetConfigContent(testPath)
	if !bytes.Equal(retrieved, testContent) {
		t.Error("获取的内容应与设置的内容匹配")
	}

	nonExistent := mock.GetConfigContent("/non/existent/path")
	if nonExistent != nil {
		t.Error("不存在的路径应返回nil")
	}
}

func TestMockSSHExecutor_ReloadCallTracking(t *testing.T) {
	mock := NewMockSSHExecutor()

	if mock.IsReloadCalled() {
		t.Error("初始状态不应有重载调用")
	}

	command1 := "systemctl reload nginx"
	err := mock.ReloadService(command1)
	if err != nil {
		t.Errorf("重载应成功: %v", err)
	}

	if !mock.IsReloadCalled() {
		t.Error("调用后应标记为重载已调用")
	}

	if mock.GetLastReloadCommand() != command1 {
		t.Errorf("期望命令 '%s'，实际: '%s'", command1, mock.GetLastReloadCommand())
	}

	command2 := "service apache2 reload"
	err = mock.ReloadService(command2)
	if err != nil {
		t.Errorf("第二次重载应成功: %v", err)
	}

	if mock.GetLastReloadCommand() != command2 {
		t.Errorf("第二次调用后期望命令 '%s'，实际: '%s'", command2, mock.GetLastReloadCommand())
	}
}
