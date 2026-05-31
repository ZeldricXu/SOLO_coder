package mpc

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"sync"
	"time"

	"github.com/apishield/apishield/internal/core/ports"
	"github.com/google/uuid"
)

type sessionState int

const (
	stateWaiting sessionState = iota
	stateRunning
	stateCompleted
	stateCanceled
)

type participantInput struct {
	encryptedData []byte
	nonce         []byte
	timestamp     time.Time
}

type mpcSession struct {
	id           string
	protocol     ports.MPCProtocol
	participants []string
	state        sessionState
	inputs       map[string]participantInput
	result       []byte
	createdAt    time.Time
	encryptionKey []byte
}

type mpcService struct {
	sessions map[string]*mpcSession
	mu       sync.RWMutex
}

func NewMPCService() ports.MPCService {
	return &mpcService{
		sessions: make(map[string]*mpcSession),
	}
}

func (s *mpcService) StartProtocol(ctx context.Context, protocol ports.MPCProtocol, participants []string) (string, error) {
	if err := s.validateProtocol(protocol); err != nil {
		return "", err
	}
	if len(participants) < 2 {
		return "", errors.New("at least 2 participants required")
	}

	sessionID := uuid.New().String()
	encryptionKey := make([]byte, 32)
	if _, err := rand.Read(encryptionKey); err != nil {
		return "", fmt.Errorf("failed to generate encryption key: %w", err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	s.sessions[sessionID] = &mpcSession{
		id:            sessionID,
		protocol:      protocol,
		participants:  participants,
		state:         stateWaiting,
		inputs:        make(map[string]participantInput),
		createdAt:     time.Now(),
		encryptionKey: encryptionKey,
	}

	return sessionID, nil
}

func (s *mpcService) SubmitInput(ctx context.Context, sessionID string, participant string, input []byte) error {
	s.mu.RLock()
	session, exists := s.sessions[sessionID]
	s.mu.RUnlock()

	if !exists {
		return errors.New("session not found")
	}
	if session.state == stateCanceled {
		return errors.New("session already canceled")
	}
	if session.state == stateCompleted {
		return errors.New("session already completed")
	}
	if !s.isParticipant(session, participant) {
		return errors.New("participant not in session")
	}

	encrypted, nonce, err := s.encryptInput(input, session.encryptionKey)
	if err != nil {
		return fmt.Errorf("failed to encrypt input: %w", err)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	session.inputs[participant] = participantInput{
		encryptedData: encrypted,
		nonce:         nonce,
		timestamp:     time.Now(),
	}

	if len(session.inputs) == len(session.participants) {
		session.state = stateRunning
		go s.executeProtocol(session)
	}

	return nil
}

func (s *mpcService) GetResult(ctx context.Context, sessionID string) ([]byte, error) {
	s.mu.RLock()
	session, exists := s.sessions[sessionID]
	s.mu.RUnlock()

	if !exists {
		return nil, errors.New("session not found")
	}
	if session.state == stateCanceled {
		return nil, errors.New("session canceled")
	}
	if session.state != stateCompleted {
		return nil, errors.New("computation not complete")
	}

	return session.result, nil
}

func (s *mpcService) CancelProtocol(ctx context.Context, sessionID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	session, exists := s.sessions[sessionID]
	if !exists {
		return errors.New("session not found")
	}

	session.state = stateCanceled
	return nil
}

func (s *mpcService) validateProtocol(protocol ports.MPCProtocol) error {
	switch protocol {
	case ports.ProtocolGarbledCircuit, ports.ProtocolSecretSharing, ports.ProtocolHomomorphicEncryption:
		return nil
	default:
		return fmt.Errorf("unsupported protocol: %s", protocol)
	}
}

func (s *mpcService) isParticipant(session *mpcSession, participant string) bool {
	for _, p := range session.participants {
		if p == participant {
			return true
		}
	}
	return false
}

func (s *mpcService) encryptInput(input []byte, key []byte) ([]byte, []byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, nil, err
	}

	nonce := make([]byte, gcm.NonceSize())
	if _, err = io.ReadFull(rand.Reader, nonce); err != nil {
		return nil, nil, err
	}

	encrypted := gcm.Seal(nil, nonce, input, nil)
	return encrypted, nonce, nil
}

func (s *mpcService) decryptInput(encrypted []byte, nonce []byte, key []byte) ([]byte, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}

	gcm, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}

	return gcm.Open(nil, nonce, encrypted, nil)
}

func (s *mpcService) executeProtocol(session *mpcSession) {
	var result []byte

	switch session.protocol {
	case ports.ProtocolGarbledCircuit:
		result = s.executeGarbledCircuit(session)
	case ports.ProtocolSecretSharing:
		result = s.executeSecretSharing(session)
	case ports.ProtocolHomomorphicEncryption:
		result = s.executeHomomorphicEncryption(session)
	}

	s.mu.Lock()
	defer s.mu.Unlock()

	if session.state != stateCanceled {
		session.result = result
		session.state = stateCompleted
	}
}

func (s *mpcService) executeGarbledCircuit(session *mpcSession) []byte {
	inputs := s.collectDecryptedInputs(session)
	hash := sha256.New()
	for _, in := range inputs {
		hash.Write(in)
	}
	return []byte(hex.EncodeToString(hash.Sum(nil)))
}

func (s *mpcService) executeSecretSharing(session *mpcSession) []byte {
	inputs := s.collectDecryptedInputs(session)
	var xorResult []byte
	for _, in := range inputs {
		if xorResult == nil {
			xorResult = make([]byte, len(in))
			copy(xorResult, in)
		} else {
			for i := range xorResult {
				if i < len(in) {
					xorResult[i] ^= in[i]
				}
			}
		}
	}
	return []byte(hex.EncodeToString(xorResult))
}

func (s *mpcService) executeHomomorphicEncryption(session *mpcSession) []byte {
	inputs := s.collectDecryptedInputs(session)
	var sum int
	for _, in := range inputs {
		for _, b := range in {
			sum += int(b)
		}
	}
	return []byte(fmt.Sprintf("%d", sum))
}

func (s *mpcService) collectDecryptedInputs(session *mpcSession) [][]byte {
	var inputs [][]byte
	for _, participant := range session.participants {
		if pi, ok := session.inputs[participant]; ok {
			decrypted, err := s.decryptInput(pi.encryptedData, pi.nonce, session.encryptionKey)
			if err == nil {
				inputs = append(inputs, decrypted)
			}
		}
	}
	return inputs
}
