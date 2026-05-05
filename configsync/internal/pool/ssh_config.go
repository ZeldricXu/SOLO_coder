package pool

import (
	"fmt"
	"os"
	"time"

	"golang.org/x/crypto/ssh"

	"configsync/internal/models"
)

func createSSHConfig(server *models.Server) (*ssh.ClientConfig, error) {
	var authMethods []ssh.AuthMethod

	if server.KeyFile != "" {
		signer, err := loadPrivateKey(server.KeyFile)
		if err != nil {
			return nil, fmt.Errorf("failed to load private key: %w", err)
		}
		authMethods = append(authMethods, ssh.PublicKeys(signer))
	}

	return &ssh.ClientConfig{
		User:            server.User,
		Auth:            authMethods,
		HostKeyCallback: ssh.InsecureIgnoreHostKey(),
		Timeout:         10 * time.Second,
	}, nil
}

func loadPrivateKey(keyPath string) (ssh.Signer, error) {
	keyBytes, err := os.ReadFile(keyPath)
	if err != nil {
		return nil, fmt.Errorf("failed to read private key file: %w", err)
	}

	signer, err := ssh.ParsePrivateKey(keyBytes)
	if err != nil {
		return nil, fmt.Errorf("failed to parse private key: %w", err)
	}

	return signer, nil
}
