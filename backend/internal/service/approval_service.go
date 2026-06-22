package service

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/featureflag/platform/internal/dao"
	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
	"github.com/featureflag/platform/pkg/utils"
)

type ApprovalService struct {
	approvalDAO *dao.ApprovalDAO
	switchDAO   *dao.SwitchDAO
	historyDAO  *dao.HistoryDAO
	switchService *SwitchService
	kafkaProducer *KafkaProducer
}

func NewApprovalService() *ApprovalService {
	return &ApprovalService{
		approvalDAO: dao.NewApprovalDAO(),
		switchDAO:   dao.NewSwitchDAO(),
		historyDAO:  dao.NewHistoryDAO(),
	}
}

func (s *ApprovalService) SetSwitchService(ss *SwitchService) {
	s.switchService = ss
}

func (s *ApprovalService) SetKafkaProducer(p *KafkaProducer) {
	s.kafkaProducer = p
}

func (s *ApprovalService) CreateRequest(ctx context.Context, req *model.ApprovalRequest, requester string) (*model.Approval, error) {
	sw, err := s.switchDAO.GetByID(ctx, req.SwitchID)
	if err != nil {
		return nil, err
	}
	if sw == nil {
		return nil, errors.New("switch not found")
	}

	changeContent := make(model.JSONB)
	changeContent["target_enabled"] = req.TargetEnabled
	changeContent["switch_key"] = sw.Key
	changeContent["switch_name"] = sw.Name

	approval := &model.Approval{
		ID:            utils.GenerateUUID(),
		SwitchID:      req.SwitchID,
		Title:         req.Title,
		Description:   req.Description,
		Requester:     requester,
		Approver:      req.Approver,
		Status:        model.ApprovalPending,
		ChangeContent: changeContent,
		CreatedAt:     time.Now(),
		UpdatedAt:     time.Now(),
	}

	err = s.approvalDAO.Create(ctx, approval)
	if err != nil {
		return nil, err
	}

	err = s.switchDAO.UpdateStatus(ctx, req.SwitchID, model.StatusPendingApproval, sw.Enabled, requester)
	if err != nil {
		logger.Warnf("update switch status to pending approval error: %v", err)
	}

	s.sendEvent(ctx, model.EventApprovalRequested, approval, requester)
	return approval, nil
}

func (s *ApprovalService) Approve(ctx context.Context, approvalID string, operator string) (*model.Approval, error) {
	approval, err := s.approvalDAO.GetByID(ctx, approvalID)
	if err != nil {
		return nil, err
	}
	if approval == nil {
		return nil, errors.New("approval not found")
	}
	if approval.Status != model.ApprovalPending {
		return nil, errors.New("approval already processed")
	}

	now := time.Now()
	approval.Status = model.ApprovalApproved
	approval.ApprovedAt = &now

	err = s.approvalDAO.Update(ctx, approval)
	if err != nil {
		return nil, err
	}

	targetEnabled, _ := approval.ChangeContent["target_enabled"].(bool)
	status := model.StatusInactive
	if targetEnabled {
		status = model.StatusActive
	}

	_, err = s.switchService.Enable(ctx, approval.SwitchID, operator)
	if err != nil {
		logger.Errorf("enable switch after approval error: %v", err)
	} else {
		_ = s.switchDAO.UpdateStatus(ctx, approval.SwitchID, status, targetEnabled, operator)
	}

	s.sendEvent(ctx, model.EventApprovalApproved, approval, operator)
	return approval, nil
}

func (s *ApprovalService) Reject(ctx context.Context, approvalID string, reason string, operator string) (*model.Approval, error) {
	approval, err := s.approvalDAO.GetByID(ctx, approvalID)
	if err != nil {
		return nil, err
	}
	if approval == nil {
		return nil, errors.New("approval not found")
	}
	if approval.Status != model.ApprovalPending {
		return nil, errors.New("approval already processed")
	}

	now := time.Now()
	approval.Status = model.ApprovalRejected
	approval.RejectedAt = &now
	approval.RejectReason = reason

	err = s.approvalDAO.Update(ctx, approval)
	if err != nil {
		return nil, err
	}

	sw, _ := s.switchDAO.GetByID(ctx, approval.SwitchID)
	if sw != nil {
		_ = s.switchDAO.UpdateStatus(ctx, approval.SwitchID, model.StatusDraft, sw.Enabled, operator)
	}

	s.sendEvent(ctx, model.EventApprovalRejected, approval, operator)
	return approval, nil
}

func (s *ApprovalService) GetByID(ctx context.Context, id string) (*model.Approval, error) {
	return s.approvalDAO.GetByID(ctx, id)
}

func (s *ApprovalService) List(ctx context.Context, status, requester, approver string, page, pageSize int) (*model.ListResponse, error) {
	approvals, total, err := s.approvalDAO.List(ctx, status, requester, approver, page, pageSize)
	if err != nil {
		return nil, err
	}
	return &model.ListResponse{
		Data: approvals,
		Pagination: model.Pagination{
			Page:     page,
			PageSize: pageSize,
			Total:    total,
		},
	}, nil
}

func (s *ApprovalService) sendEvent(ctx context.Context, eventType model.EventType, approval *model.Approval, operator string) {
	if s.kafkaProducer == nil {
		return
	}

	event := &model.ChangeEvent{
		EventType: eventType,
		SwitchID:  approval.SwitchID,
		SwitchKey: approval.SwitchKey,
		Operator:  operator,
		Timestamp: time.Now(),
		Data:      approval,
	}
	s.kafkaProducer.SendEvent(event)
}

func (s *ApprovalService) toJSONB(v interface{}) *model.JSONB {
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var j model.JSONB
	json.Unmarshal(b, &j)
	return &j
}
