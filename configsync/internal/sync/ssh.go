package sync

import (
	"bytes"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"golang.org/x/crypto/ssh"

	"configsync/internal/models"
	"configsync/internal/pool"
)

type SSHClient struct {
	client *ssh.Client
	server *models.Server
}

type PushResult struct {
	ServerID   string
	Host       string
	Success    bool
	Error      string
	Reloaded   bool
	ReloadError string
}

type SyncService struct {
	connPool *pool.ConnectionPool
}

func NewSyncService(p *pool.ConnectionPool) *SyncService {
	return &SyncService{
		connPool: p,
	}
}

func NewSyncServiceDefault() *SyncService {
	return &SyncService{
		connPool: pool.NewConnectionPool(),
	}
}

func (s *SyncService) Close() {
	if s.connPool != nil {
		s.connPool.Close()
	}
}

func (s *SyncService) PushConfigToServer(server *models.Server, configContent []byte, reload bool, reloadCommand string) PushResult {
	result := PushResult{
		ServerID: server.ServerID,
		Host:     server.Host,
		Success:  false,
	}

	client, err := s.connPool.Get(server)
	if err != nil {
		result.Error = fmt.Sprintf("SSH connection failed: %v", err)
		return result
	}

	sshClient := &SSHClient{
		client: client,
		server: server,
	}

	_, err = sshClient.BackupRemoteConfig(server.ConfigPath)
	if err != nil {
		s.connPool.ForceClose(server)
		result.Error = fmt.Sprintf("backup failed: %v", err)
		return result
	}

	if err := sshClient.TransferContent(configContent, server.ConfigPath); err != nil {
		s.connPool.ForceClose(server)
		result.Error = fmt.Sprintf("file transfer failed: %v", err)
		return result
	}

	if reload && reloadCommand != "" {
		result.Reloaded = true
		if err := sshClient.ReloadService(reloadCommand); err != nil {
			result.ReloadError = fmt.Sprintf("reload warning: %v", err)
		}
	}

	s.connPool.Release(server)
	result.Success = true
	return result
}

func (s *SyncService) ValidateSSHConnection(server *models.Server) error {
	client, err := s.connPool.Get(server)
	if err != nil {
		return err
	}
	s.connPool.Release(server)
	return nil
}

func (s *SyncService) GetRemoteConfig(server *models.Server) ([]byte, error) {
	client, err := s.connPool.Get(server)
	if err != nil {
		return nil, err
	}

	sshClient := &SSHClient{
		client: client,
		server: server,
	}

	stdout, stderr, err := sshClient.ExecuteCommand(fmt.Sprintf("cat %s", server.ConfigPath))
	if err != nil {
		s.connPool.ForceClose(server)
		if stderr != "" {
			return nil, fmt.Errorf("%s: %w", stderr, err)
		}
		return nil, err
	}

	s.connPool.Release(server)
	return []byte(stdout), nil
}

func (s *SyncService) DiffLocalRemote(server *models.Server, localContent []byte) (string, error) {
	remoteContent, err := s.GetRemoteConfig(server)
	if err != nil {
		return "", fmt.Errorf("failed to get remote config: %w", err)
	}

	localLines := strings.Split(string(localContent), "\n")
	remoteLines := strings.Split(string(remoteContent), "\n")

	var diffOutput strings.Builder

	maxLen := len(localLines)
	if len(remoteLines) > maxLen {
		maxLen = len(remoteLines)
	}

	for i := 0; i < maxLen; i++ {
		var localLine, remoteLine string
		localHas := i < len(localLines)
		remoteHas := i < len(remoteLines)

		if localHas {
			localLine = localLines[i]
		}
		if remoteHas {
			remoteLine = remoteLines[i]
		}

		if localHas && remoteHas && localLine == remoteLine {
			diffOutput.WriteString(fmt.Sprintf("  %s\n", localLine))
		} else {
			if localHas {
				diffOutput.WriteString(fmt.Sprintf("+ %s\n", localLine))
			}
			if remoteHas && localLine != remoteLine {
				diffOutput.WriteString(fmt.Sprintf("- %s\n", remoteLine))
			}
		}
	}

	return diffOutput.String(), nil
}

func NewSSHClientWithPool(server *models.Server, p *pool.ConnectionPool) (*SSHClient, error) {
	client, err := p.Get(server)
	if err != nil {
		return nil, err
	}

	return &SSHClient{
		client: client,
		server: server,
	}, nil
}

func (sc *SSHClient) Release(p *pool.ConnectionPool) {
	if p != nil && sc.server != nil {
		p.Release(sc.server)
	}
}

func (sc *SSHClient) Close() error {
	if sc.client != nil {
		return sc.client.Close()
	}
	return nil
}

func (sc *SSHClient) ExecuteCommand(cmd string) (string, string, error) {
	session, err := sc.client.NewSession()
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

func (sc *SSHClient) BackupRemoteConfig(remotePath string) (string, error) {
	timestamp := time.Now().Format("20060102_150405")
	backupPath := fmt.Sprintf("%s.backup.%s", remotePath, timestamp)

	backupCmd := fmt.Sprintf("cp %s %s", remotePath, backupPath)
	_, _, err := sc.ExecuteCommand(backupCmd)
	if err != nil {
		return "", fmt.Errorf("failed to backup config: %w", err)
	}

	return backupPath, nil
}

func (sc *SSHClient) TransferFile(localPath string, remotePath string) error {
	session, err := sc.client.NewSession()
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

func (sc *SSHClient) TransferContent(content []byte, remotePath string) error {
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

	return sc.TransferFile(tempFile.Name(), remotePath)
}

func (sc *SSHClient) ReloadService(command string) error {
	if command == "" {
		return nil
	}

	_, stderr, err := sc.ExecuteCommand(command)
	if err != nil {
		if stderr != "" {
			return fmt.Errorf("reload command failed: %s - %w", stderr, err)
		}
		return fmt.Errorf("reload command failed: %w", err)
	}

	return nil
}

func PushConfigToServer(server *models.Server, configContent []byte, reload bool, reloadCommand string) PushResult {
	syncService := NewSyncServiceDefault()
	defer syncService.Close()
	return syncService.PushConfigToServer(server, configContent, reload, reloadCommand)
}

func ValidateSSHConnection(server *models.Server) error {
	syncService := NewSyncServiceDefault()
	defer syncService.Close()
	return syncService.ValidateSSHConnection(server)
}

func GetRemoteConfig(server *models.Server) ([]byte, error) {
	syncService := NewSyncServiceDefault()
	defer syncService.Close()
	return syncService.GetRemoteConfig(server)
}

func DiffLocalRemote(server *models.Server, localContent []byte) (string, error) {
	syncService := NewSyncServiceDefault()
	defer syncService.Close()
	return syncService.DiffLocalRemote(server, localContent)
}
