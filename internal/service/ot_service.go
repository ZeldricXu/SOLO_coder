package service

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"sync"
	"time"

	"github.com/enterprise/knowledgebase/internal/config"
	"github.com/enterprise/knowledgebase/internal/database"
	"github.com/enterprise/knowledgebase/internal/ot"
	"github.com/enterprise/knowledgebase/internal/repository"
	"github.com/google/uuid"
)

type OTService struct {
	redisClient  *database.RedisClient
	docRepo      *repository.DocumentRepository
	cfg          config.OTConfig
	docSessions  map[string]*DocSession
	sessionMu    sync.RWMutex
}

type DocSession struct {
	DocID         uuid.UUID
	TenantID      uuid.UUID
	CurrentVer    int
	OpsBuffer     []ot.OperationEnvelope
	LastFlush     time.Time
	Mu            sync.Mutex
	ConnectedUser map[string]*ConnectedUser
}

type ConnectedUser struct {
	UserID    uuid.UUID
	ClientID  string
	LastSeen  time.Time
	LastVer   int
}

func NewOTService(
	redisClient *database.RedisClient,
	docRepo *repository.DocumentRepository,
	cfg config.OTConfig,
) *OTService {
	svc := &OTService{
		redisClient: redisClient,
		docRepo:     docRepo,
		cfg:         cfg,
		docSessions: make(map[string]*DocSession),
	}
	go svc.flushLoop()
	return svc
}

func (s *OTService) sessionKey(tenantID, docID uuid.UUID) string {
	return fmt.Sprintf("ot:session:%s:%s", tenantID, docID)
}

func (s *OTService) opsKey(tenantID, docID uuid.UUID) string {
	return fmt.Sprintf("ot:ops:%s:%s", tenantID, docID)
}

func (s *OTService) presenceKey(tenantID, docID uuid.UUID) string {
	return fmt.Sprintf("ot:presence:%s:%s", tenantID, docID)
}

type SubmitOpsRequest struct {
	TenantID    uuid.UUID
	DocID       uuid.UUID
	UserID      uuid.UUID
	ClientID    string
	BaseVersion int
	Operations  []map[string]interface{}
	Timestamp   int64
}

type SubmitOpsResponse struct {
	NewVersion   int                  `json:"new_version"`
	Transformed  []ot.OperationList   `json:"transformed,omitempty"`
	ServerOps    []ot.OperationEnvelope `json:"server_ops,omitempty"`
	Ack          bool                 `json:"ack"`
}

func (s *OTService) SubmitOps(ctx context.Context, req *SubmitOpsRequest) (*SubmitOpsResponse, error) {
	session, err := s.getOrCreateSession(ctx, req.TenantID, req.DocID)
	if err != nil {
		return nil, err
	}

	session.Mu.Lock()
	defer session.Mu.Unlock()

	clientOps, err := ot.ProseMirrorStepsToOperations(req.Operations)
	if err != nil {
		return nil, fmt.Errorf("convert ops: %w", err)
	}
	clientOps = ot.Normalize(clientOps)
	if len(clientOps) == 0 {
		return &SubmitOpsResponse{
			NewVersion: session.CurrentVer,
			Ack:        true,
		}, nil
	}

	missingOps := session.CurrentVer - req.BaseVersion
	if missingOps > s.cfg.MaxVersionGap {
		return nil, errors.New("version gap too large, please reload document")
	}

	if missingOps > 0 {
		history := make([]ot.OperationList, 0, missingOps)
		startIdx := len(session.OpsBuffer) - missingOps
		if startIdx < 0 {
			startIdx = 0
		}
		for _, env := range session.OpsBuffer[startIdx:] {
			history = append(history, env.Operations.Operations)
		}
		transformed, err := ot.TransformAgainstServer(clientOps, history)
		if err != nil {
			return nil, fmt.Errorf("transform ops: %w", err)
		}
		clientOps = transformed
	}

	envelope := ot.OperationEnvelope{
		Version:    int64(session.CurrentVer + 1),
		Operations: ot.Step{Operations: clientOps, DocumentID: req.DocID.String(), ClientID: req.ClientID},
		UserID:     req.UserID.String(),
		Timestamp:  req.Timestamp,
		ID:         uuid.New().String(),
	}

	session.OpsBuffer = append(session.OpsBuffer, envelope)
	session.CurrentVer++

	resp := &SubmitOpsResponse{
		NewVersion: session.CurrentVer,
		Ack:        true,
	}

	if missingOps > 0 {
		startIdx := len(session.OpsBuffer) - missingOps - 1
		if startIdx < 0 {
			startIdx = 0
		}
		for i := startIdx; i < len(session.OpsBuffer)-1; i++ {
			resp.ServerOps = append(resp.ServerOps, session.OpsBuffer[i])
		}
	}

	if user, ok := session.ConnectedUser[req.ClientID]; ok {
		user.LastSeen = time.Now()
		user.LastVer = session.CurrentVer
	}

	if len(session.OpsBuffer) >= s.cfg.BufferSize ||
		time.Since(session.LastFlush) > time.Duration(s.cfg.FlushInterval)*time.Millisecond {
		_ = s.flushToDB(ctx, session)
	}

	s.broadcastPresence(ctx, session, req.UserID, req.ClientID)

	return resp, nil
}

func (s *OTService) GetDocumentState(ctx context.Context, tenantID, docID uuid.UUID) (int, []ot.OperationEnvelope, error) {
	session, err := s.getOrCreateSession(ctx, tenantID, docID)
	if err != nil {
		return 0, nil, err
	}

	session.Mu.Lock()
	defer session.Mu.Unlock()

	ops := make([]ot.OperationEnvelope, len(session.OpsBuffer))
	copy(ops, session.OpsBuffer)

	return session.CurrentVer, ops, nil
}

func (s *OTService) ConnectUser(ctx context.Context, tenantID, docID, userID uuid.UUID, clientID string) (int, error) {
	session, err := s.getOrCreateSession(ctx, tenantID, docID)
	if err != nil {
		return 0, err
	}

	session.Mu.Lock()
	defer session.Mu.Unlock()

	session.ConnectedUser[clientID] = &ConnectedUser{
		UserID:   userID,
		ClientID: clientID,
		LastSeen: time.Now(),
		LastVer:  session.CurrentVer,
	}

	s.broadcastPresence(ctx, session, userID, clientID)

	return session.CurrentVer, nil
}

func (s *OTService) DisconnectUser(ctx context.Context, tenantID, docID uuid.UUID, clientID string) {
	s.sessionMu.RLock()
	key := s.sessionKey(tenantID, docID)
	session, ok := s.docSessions[key]
	s.sessionMu.RUnlock()
	if !ok {
		return
	}

	session.Mu.Lock()
	delete(session.ConnectedUser, clientID)
	remaining := len(session.ConnectedUser)
	session.Mu.Unlock()

	if remaining == 0 {
		session.Mu.Lock()
		if len(session.OpsBuffer) > 0 {
			_ = s.flushToDB(ctx, session)
		}
		session.Mu.Unlock()
	}
}

func (s *OTService) GetPresence(ctx context.Context, tenantID, docID uuid.UUID) ([]map[string]interface{}, error) {
	session, err := s.getOrCreateSession(ctx, tenantID, docID)
	if err != nil {
		return nil, err
	}

	session.Mu.Lock()
	defer session.Mu.Unlock()

	users := make([]map[string]interface{}, 0, len(session.ConnectedUser))
	for _, u := range session.ConnectedUser {
		users = append(users, map[string]interface{}{
			"user_id":   u.UserID.String(),
			"client_id": u.ClientID,
			"version":   u.LastVer,
			"last_seen": u.LastSeen.Unix(),
		})
	}
	return users, nil
}

func (s *OTService) getOrCreateSession(ctx context.Context, tenantID, docID uuid.UUID) (*DocSession, error) {
	key := s.sessionKey(tenantID, docID)

	s.sessionMu.RLock()
	session, ok := s.docSessions[key]
	s.sessionMu.RUnlock()
	if ok {
		return session, nil
	}

	s.sessionMu.Lock()
	defer s.sessionMu.Unlock()

	if session, ok := s.docSessions[key]; ok {
		return session, nil
	}

	doc, err := s.docRepo.GetByID(ctx, docID)
	if err != nil {
		return nil, err
	}

	session = &DocSession{
		DocID:         docID,
		TenantID:      tenantID,
		CurrentVer:    doc.CurrentVersion,
		OpsBuffer:     make([]ot.OperationEnvelope, 0, s.cfg.BufferSize),
		LastFlush:     time.Now(),
		ConnectedUser: make(map[string]*ConnectedUser),
	}

	s.docSessions[key] = session
	return session, nil
}

func (s *OTService) flushToDB(ctx context.Context, session *DocSession) error {
	if len(session.OpsBuffer) == 0 {
		return nil
	}

	doc, err := s.docRepo.GetByID(ctx, session.DocID)
	if err != nil {
		return err
	}

	var combinedOps ot.OperationList
	for _, env := range session.OpsBuffer {
		if len(combinedOps) == 0 {
			combinedOps = env.Operations.Operations
		} else {
			composed, err := ot.Compose(combinedOps, env.Operations.Operations)
			if err != nil {
				_ = err
				continue
			}
			combinedOps = composed
		}
	}

	_ = combinedOps
	_ = doc

	session.OpsBuffer = session.OpsBuffer[:0]
	session.LastFlush = time.Now()

	opsJSON, _ := json.Marshal(session.OpsBuffer)
	_ = s.redisClient.HSet(ctx, s.opsKey(session.TenantID, session.DocID),
		"last_flush", time.Now().Unix())
	_ = s.redisClient.HSet(ctx, s.opsKey(session.TenantID, session.DocID),
		"current_ver", session.CurrentVer)
	_ = opsJSON

	return nil
}

func (s *OTService) flushLoop() {
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	for range ticker.C {
		s.sessionMu.RLock()
		sessions := make([]*DocSession, 0, len(s.docSessions))
		for _, s := range s.docSessions {
			sessions = append(sessions, s)
		}
		s.sessionMu.RUnlock()

		for _, session := range sessions {
			session.Mu.Lock()
			idle := len(session.ConnectedUser) == 0
			needFlush := len(session.OpsBuffer) > 0 &&
				(time.Since(session.LastFlush) > time.Duration(s.cfg.FlushInterval)*2*time.Millisecond || idle)
			session.Mu.Unlock()

			if needFlush {
				ctx := context.Background()
				session.Mu.Lock()
				_ = s.flushToDB(ctx, session)
				session.Mu.Unlock()
			}
		}
	}
}

func (s *OTService) broadcastPresence(ctx context.Context, session *DocSession, userID uuid.UUID, clientID string) {
	presence := make([]map[string]interface{}, 0, len(session.ConnectedUser))
	for cid, u := range session.ConnectedUser {
		presence = append(presence, map[string]interface{}{
			"user_id":   u.UserID.String(),
			"client_id": cid,
			"last_seen": u.LastSeen.Unix(),
		})
	}
	data, _ := json.Marshal(presence)
	channel := fmt.Sprintf("ot:presence_channel:%s:%s", session.TenantID, session.DocID)
	_ = s.redisClient.Publish(ctx, channel, string(data))
	_ = userID
	_ = clientID
}
