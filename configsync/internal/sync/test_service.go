package sync

import (
	"fmt"
	"strings"

	"configsync/internal/models"
)

type TestSyncService struct {
	executor SSHExecutor
	server   *models.Server
}

func NewTestSyncService(executor SSHExecutor, server *models.Server) *TestSyncService {
	return &TestSyncService{
		executor: executor,
		server:   server,
	}
}

func (s *TestSyncService) PushConfig(configContent []byte, reload bool, reloadCommand string) PushResult {
	result := PushResult{
		ServerID: s.server.ServerID,
		Host:     s.server.Host,
		Success:  false,
	}

	_, err := s.executor.BackupRemoteConfig(s.server.ConfigPath)
	if err != nil {
		result.Error = fmt.Sprintf("backup failed: %v", err)
		return result
	}

	if err := s.executor.TransferContent(configContent, s.server.ConfigPath); err != nil {
		result.Error = fmt.Sprintf("file transfer failed: %v", err)
		return result
	}

	if reload && reloadCommand != "" {
		result.Reloaded = true
		if err := s.executor.ReloadService(reloadCommand); err != nil {
			result.ReloadError = fmt.Sprintf("reload warning: %v", err)
		}
	}

	result.Success = true
	return result
}

func (s *TestSyncService) GetRemoteConfig() ([]byte, error) {
	stdout, stderr, err := s.executor.ExecuteCommand(fmt.Sprintf("cat %s", s.server.ConfigPath))
	if err != nil {
		if stderr != "" {
			return nil, fmt.Errorf("%s: %w", stderr, err)
		}
		return nil, err
	}
	return []byte(stdout), nil
}

func (s *TestSyncService) DiffLocalRemote(localContent []byte) (string, error) {
	remoteContent, err := s.GetRemoteConfig()
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
