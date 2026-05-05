package sync

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

type SSHExecutor interface {
	ExecuteCommand(cmd string) (string, string, error)
	BackupRemoteConfig(remotePath string) (string, error)
	TransferFile(localPath string, remotePath string) error
	TransferContent(content []byte, remotePath string) error
	ReloadService(command string) error
	Close() error
}

type RealSSHExecutor struct {
	client *ssh.Client
}

func NewRealSSHExecutor(client *ssh.Client) *RealSSHExecutor {
	return &RealSSHExecutor{client: client}
}

func (r *RealSSHExecutor) ExecuteCommand(cmd string) (string, string, error) {
	session, err := r.client.NewSession()
	if err != nil {
		return "", "", fmt.Errorf("failed to create session: %w", err)
	}
	defer session.Close()

	var stdout, stderr bytes.Buffer
	session.Stdout = &stdout
	session.Stderr = &stderr

	err = session.Run(cmd)
	if err != nil {
		return stdout.String(), stderr.String(), fmt.Errorf("command failed: %w", err)
	}

	return stdout.String(), stderr.String(), nil
}

func (r *RealSSHExecutor) BackupRemoteConfig(remotePath string) (string, error) {
	timestamp := time.Now().Format("20060102_150405")
	backupPath := fmt.Sprintf("%s.backup.%s", remotePath, timestamp)

	backupCmd := fmt.Sprintf("cp %s %s", remotePath, backupPath)
	_, _, err := r.ExecuteCommand(backupCmd)
	if err != nil {
		return "", fmt.Errorf("failed to backup config: %w", err)
	}

	return backupPath, nil
}

func (r *RealSSHExecutor) TransferFile(localPath string, remotePath string) error {
	session, err := r.client.NewSession()
	if err != nil {
		return fmt.Errorf("failed to create session: %w", err)
	}
	defer session.Close()

	file, err := os.Open(localPath)
	if err != nil {
		return fmt.Errorf("failed to open local file: %w", err)
	}
	defer file.Close()

	fileInfo, err := file.Stat()
	if err != nil {
		return fmt.Errorf("failed to get file info: %w", err)
	}

	remoteDir := filepath.Dir(remotePath)
	remoteBase := filepath.Base(remotePath)

	scpCmd := fmt.Sprintf("scp -t %s", remoteDir)

	stdin, err := session.StdinPipe()
	if err != nil {
		return fmt.Errorf("failed to create stdin pipe: %w", err)
	}
	defer stdin.Close()

	if err := session.Start(scpCmd); err != nil {
		return fmt.Errorf("failed to start scp: %w", err)
	}

	fmt.Fprintf(stdin, "C0644 %d %s\n", fileInfo.Size(), remoteBase)

	_, err = io.Copy(stdin, file)
	if err != nil {
		return fmt.Errorf("failed to transfer file: %w", err)
	}

	fmt.Fprintf(stdin, "\x00")
	stdin.Close()

	if err := session.Wait(); err != nil {
		return fmt.Errorf("scp transfer failed: %w", err)
	}

	return nil
}

func (r *RealSSHExecutor) TransferContent(content []byte, remotePath string) error {
	tempFile, err := os.CreateTemp("", "configsync-*")
	if err != nil {
		return fmt.Errorf("failed to create temp file: %w", err)
	}
	defer os.Remove(tempFile.Name())

	if _, err := tempFile.Write(content); err != nil {
		tempFile.Close()
		return fmt.Errorf("failed to write temp file: %w", err)
	}
	tempFile.Close()

	return r.TransferFile(tempFile.Name(), remotePath)
}

func (r *RealSSHExecutor) ReloadService(command string) error {
	if command == "" {
		return nil
	}

	_, stderr, err := r.ExecuteCommand(command)
	if err != nil {
		if stderr != "" {
			return fmt.Errorf("reload command failed: %s - %w", stderr, err)
		}
		return fmt.Errorf("reload command failed: %w", err)
	}

	return nil
}

func (r *RealSSHExecutor) Close() error {
	if r.client != nil {
		return r.client.Close()
	}
	return nil
}

type MockSSHExecutor struct {
	mu                sync.Mutex
	commandResults    map[string]*CommandResult
	configContents    map[string][]byte
	backupPaths       map[string]string
	transferHistory   []TransferRecord
	reloadCalled      bool
	lastReloadCommand string
	shouldFail        bool
	failAfterTransfer bool
	relayCount        int
}

type CommandResult struct {
	Stdout string
	Stderr string
	Err    error
}

type TransferRecord struct {
	LocalPath  string
	RemotePath string
	Content    []byte
}

func NewMockSSHExecutor() *MockSSHExecutor {
	return &MockSSHExecutor{
		commandResults:  make(map[string]*CommandResult),
		configContents:  make(map[string][]byte),
		backupPaths:     make(map[string]string),
		transferHistory: make([]TransferRecord, 0),
	}
}

func (m *MockSSHExecutor) SetCommandResult(cmdPattern string, result *CommandResult) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.commandResults[cmdPattern] = result
}

func (m *MockSSHExecutor) SetConfigContent(remotePath string, content []byte) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.configContents[remotePath] = content
}

func (m *MockSSHExecutor) GetConfigContent(remotePath string) []byte {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.configContents[remotePath]
}

func (m *MockSSHExecutor) SetShouldFail(shouldFail bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.shouldFail = shouldFail
}

func (m *MockSSHExecutor) SetFailAfterTransfer(fail bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.failAfterTransfer = fail
}

func (m *MockSSHExecutor) ExecuteCommand(cmd string) (string, string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.shouldFail {
		return "", "mock failure", fmt.Errorf("mock error")
	}

	for pattern, result := range m.commandResults {
		if strings.Contains(cmd, pattern) {
			return result.Stdout, result.Stderr, result.Err
		}
	}

	if strings.Contains(cmd, "cat ") {
		remotePath := strings.TrimPrefix(strings.TrimSpace(cmd), "cat ")
		if content, exists := m.configContents[remotePath]; exists {
			return string(content), "", nil
		}
		return "", "file not found", fmt.Errorf("file not found: %s", remotePath)
	}

	return "mock success", "", nil
}

func (m *MockSSHExecutor) BackupRemoteConfig(remotePath string) (string, error) {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.shouldFail {
		return "", fmt.Errorf("mock backup error")
	}

	timestamp := time.Now().Format("20060102_150405")
	backupPath := fmt.Sprintf("%s.backup.%s", remotePath, timestamp)
	m.backupPaths[remotePath] = backupPath

	if content, exists := m.configContents[remotePath]; exists {
		m.configContents[backupPath] = content
	}

	return backupPath, nil
}

func (m *MockSSHExecutor) TransferFile(localPath string, remotePath string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.shouldFail && !m.failAfterTransfer {
		return fmt.Errorf("mock transfer error")
	}

	content, err := os.ReadFile(localPath)
	if err != nil {
		return fmt.Errorf("failed to read local file: %w", err)
	}

	m.configContents[remotePath] = content
	m.transferHistory = append(m.transferHistory, TransferRecord{
		LocalPath:  localPath,
		RemotePath: remotePath,
		Content:    content,
	})

	if m.failAfterTransfer {
		return fmt.Errorf("mock error after transfer")
	}

	return nil
}

func (m *MockSSHExecutor) TransferContent(content []byte, remotePath string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	if m.shouldFail && !m.failAfterTransfer {
		return fmt.Errorf("mock transfer error")
	}

	m.configContents[remotePath] = content
	m.transferHistory = append(m.transferHistory, TransferRecord{
		RemotePath: remotePath,
		Content:    content,
	})

	if m.failAfterTransfer {
		return fmt.Errorf("mock error after transfer")
	}

	return nil
}

func (m *MockSSHExecutor) ReloadService(command string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	m.reloadCalled = true
	m.lastReloadCommand = command
	m.relayCount++

	if m.shouldFail {
		return fmt.Errorf("mock reload error")
	}

	return nil
}

func (m *MockSSHExecutor) IsReloadCalled() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.reloadCalled
}

func (m *MockSSHExecutor) GetLastReloadCommand() string {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.lastReloadCommand
}

func (m *MockSSHExecutor) GetTransferHistory() []TransferRecord {
	m.mu.Lock()
	defer m.mu.Unlock()
	history := make([]TransferRecord, len(m.transferHistory))
	copy(history, m.transferHistory)
	return history
}

func (m *MockSSHExecutor) Close() error {
	return nil
}

func (m *MockSSHExecutor) Reset() {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.configContents = make(map[string][]byte)
	m.backupPaths = make(map[string]string)
	m.transferHistory = make([]TransferRecord, 0)
	m.reloadCalled = false
	m.lastReloadCommand = ""
	m.shouldFail = false
	m.failAfterTransfer = false
	m.relayCount = 0
}

type ExecutorFactory interface {
	Create(client *ssh.Client) SSHExecutor
}

type RealExecutorFactory struct{}

func (f *RealExecutorFactory) Create(client *ssh.Client) SSHExecutor {
	return NewRealSSHExecutor(client)
}

type MockExecutorFactory struct {
	mock *MockSSHExecutor
}

func NewMockExecutorFactory() *MockExecutorFactory {
	return &MockExecutorFactory{
		mock: NewMockSSHExecutor(),
	}
}

func (f *MockExecutorFactory) Create(client *ssh.Client) SSHExecutor {
	return f.mock
}

func (f *MockExecutorFactory) GetMock() *MockSSHExecutor {
	return f.mock
}
