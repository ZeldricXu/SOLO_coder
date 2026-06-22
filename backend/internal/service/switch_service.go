package service

import (
	"context"
	"encoding/json"
	"errors"
	"time"

	"github.com/featureflag/platform/internal/dao"
	"github.com/featureflag/platform/internal/engine"
	"github.com/featureflag/platform/internal/model"
	"github.com/featureflag/platform/pkg/logger"
	"github.com/featureflag/platform/pkg/utils"
)

type SwitchService struct {
	switchDAO     *dao.SwitchDAO
	strategyDAO   *dao.StrategyDAO
	conditionDAO  *dao.ConditionDAO
	engine        *engine.StrategyEngine
	historyDAO    *dao.HistoryDAO
	kafkaProducer *KafkaProducer
}

func NewSwitchService() *SwitchService {
	return &SwitchService{
		switchDAO:    dao.NewSwitchDAO(),
		strategyDAO:  dao.NewStrategyDAO(),
		conditionDAO: dao.NewConditionDAO(),
		engine:       engine.NewStrategyEngine(),
		historyDAO:   dao.NewHistoryDAO(),
	}
}

func (s *SwitchService) SetKafkaProducer(p *KafkaProducer) {
	s.kafkaProducer = p
}

func (s *SwitchService) Create(ctx context.Context, req *model.CreateSwitchRequest, operator string) (*model.Switch, error) {
	exists, err := s.switchDAO.Exists(ctx, req.Key)
	if err != nil {
		return nil, err
	}
	if exists {
		return nil, errors.New("switch key already exists")
	}

	sw := &model.Switch{
		ID:                  utils.GenerateUUID(),
		Key:                 req.Key,
		Name:                req.Name,
		Description:         req.Description,
		Type:                req.Type,
		Scope:               req.Scope,
		ServiceID:           req.ServiceID,
		Owner:               req.Owner,
		Status:              model.StatusDraft,
		Enabled:             false,
		BooleanValue:        req.BooleanValue,
		PercentageValue:     req.PercentageValue,
		Environment:         req.Environment,
		TenantID:            req.TenantID,
		RequireApproval:     req.RequireApproval,
		AutoRollbackEnabled: req.AutoRollbackEnabled,
		AutoRollbackThreshold: req.AutoRollbackThreshold,
		CreatedBy:           operator,
		CreatedAt:           time.Now(),
		UpdatedAt:           time.Now(),
	}

	err = s.switchDAO.Create(ctx, sw)
	if err != nil {
		return nil, err
	}

	s.sendEvent(ctx, model.EventSwitchCreated, sw, operator)
	return sw, nil
}

func (s *SwitchService) Update(ctx context.Context, id string, req *model.UpdateSwitchRequest, operator string) (*model.Switch, error) {
	sw, err := s.switchDAO.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if sw == nil {
		return nil, errors.New("switch not found")
	}

	oldValue := s.toJSONB(sw)

	if req.Name != "" {
		sw.Name = req.Name
	}
	if req.Description != "" {
		sw.Description = req.Description
	}
	if req.Type != "" {
		sw.Type = req.Type
	}
	if req.Scope != "" {
		sw.Scope = req.Scope
	}
	if req.ServiceID != "" {
		sw.ServiceID = req.ServiceID
	}
	if req.Owner != "" {
		sw.Owner = req.Owner
	}
	if req.Environment != "" {
		sw.Environment = req.Environment
	}
	if req.TenantID != "" {
		sw.TenantID = req.TenantID
	}
	sw.RequireApproval = req.RequireApproval
	sw.AutoRollbackEnabled = req.AutoRollbackEnabled
	if req.AutoRollbackThreshold > 0 {
		sw.AutoRollbackThreshold = req.AutoRollbackThreshold
	}

	err = s.switchDAO.Update(ctx, sw)
	if err != nil {
		return nil, err
	}

	newValue := s.toJSONB(sw)
	s.historyDAO.Create(ctx, &model.SwitchHistory{
		ID:           utils.GenerateUUID(),
		SwitchID:     id,
		EventType:    model.EventSwitchUpdated,
		OldValue:     oldValue,
		NewValue:     newValue,
		OperatorUser: operator,
		Remark:       "Update switch",
		CreatedAt:    time.Now(),
	})
	s.sendEvent(ctx, model.EventSwitchUpdated, sw, operator)

	return sw, nil
}

func (s *SwitchService) Delete(ctx context.Context, id string, operator string) error {
	sw, err := s.switchDAO.GetByID(ctx, id)
	if err != nil {
		return err
	}
	if sw == nil {
		return errors.New("switch not found")
	}

	err = s.switchDAO.Delete(ctx, id, operator)
	if err != nil {
		return err
	}

	s.sendEvent(ctx, model.EventSwitchDeleted, sw, operator)
	return nil
}

func (s *SwitchService) GetByID(ctx context.Context, id string) (*model.Switch, error) {
	sw, err := s.switchDAO.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if sw != nil {
		strategies, err := s.strategyDAO.GetBySwitchID(ctx, id)
		if err != nil {
			logger.Warnf("get strategies error: %v", err)
		} else {
			for _, st := range strategies {
				conditions, _ := s.conditionDAO.GetByStrategyID(ctx, st.ID)
				st.Conditions = conditions
			}
			sw.Strategies = strategies
		}
	}
	return sw, nil
}

func (s *SwitchService) GetByKey(ctx context.Context, key string) (*model.Switch, error) {
	sw, err := s.switchDAO.GetByKey(ctx, key)
	if err != nil {
		return nil, err
	}
	if sw != nil {
		strategies, err := s.strategyDAO.GetBySwitchID(ctx, sw.ID)
		if err != nil {
			logger.Warnf("get strategies error: %v", err)
		} else {
			for _, st := range strategies {
				conditions, _ := s.conditionDAO.GetByStrategyID(ctx, st.ID)
				st.Conditions = conditions
			}
			sw.Strategies = strategies
		}
	}
	return sw, nil
}

func (s *SwitchService) List(ctx context.Context, req *model.ListRequest) (*model.ListResponse, error) {
	switches, total, err := s.switchDAO.List(ctx, req)
	if err != nil {
		return nil, err
	}

	return &model.ListResponse{
		Data: switches,
		Pagination: model.Pagination{
			Page:     req.Page,
			PageSize: req.PageSize,
			Total:    total,
		},
	}, nil
}

func (s *SwitchService) Enable(ctx context.Context, id string, operator string) (*model.Switch, error) {
	sw, err := s.switchDAO.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if sw == nil {
		return nil, errors.New("switch not found")
	}

	if sw.RequireApproval && sw.Status != model.StatusPendingApproval {
		return nil, errors.New("switch requires approval")
	}

	oldValue := s.toJSONB(sw)

	sw.Enabled = true
	sw.Status = model.StatusActive
	err = s.switchDAO.UpdateStatus(ctx, id, model.StatusActive, true, operator)
	if err != nil {
		return nil, err
	}

	newValue := s.toJSONB(sw)
	s.historyDAO.Create(ctx, &model.SwitchHistory{
		ID:           utils.GenerateUUID(),
		SwitchID:     id,
		EventType:    model.EventSwitchEnabled,
		OldValue:     oldValue,
		NewValue:     newValue,
		OperatorUser: operator,
		Remark:       "Enable switch",
		CreatedAt:    time.Now(),
	})
	s.sendEvent(ctx, model.EventSwitchEnabled, sw, operator)

	return sw, nil
}

func (s *SwitchService) Disable(ctx context.Context, id string, operator string) (*model.Switch, error) {
	sw, err := s.switchDAO.GetByID(ctx, id)
	if err != nil {
		return nil, err
	}
	if sw == nil {
		return nil, errors.New("switch not found")
	}

	oldValue := s.toJSONB(sw)

	sw.Enabled = false
	sw.Status = model.StatusInactive
	err = s.switchDAO.UpdateStatus(ctx, id, model.StatusInactive, false, operator)
	if err != nil {
		return nil, err
	}

	newValue := s.toJSONB(sw)
	s.historyDAO.Create(ctx, &model.SwitchHistory{
		ID:           utils.GenerateUUID(),
		SwitchID:     id,
		EventType:    model.EventSwitchDisabled,
		OldValue:     oldValue,
		NewValue:     newValue,
		OperatorUser: operator,
		Remark:       "Disable switch",
		CreatedAt:    time.Now(),
	})
	s.sendEvent(ctx, model.EventSwitchDisabled, sw, operator)

	return sw, nil
}

func (s *SwitchService) BatchEnableByService(ctx context.Context, serviceID string, operator string) (int64, error) {
	count, err := s.switchDAO.BatchUpdateStatusByService(ctx, serviceID, model.StatusActive, true, operator)
	if err != nil {
		return 0, err
	}
	logger.Infof("batch enabled %d switches for service %s by %s", count, serviceID, operator)
	return count, nil
}

func (s *SwitchService) BatchDisableByService(ctx context.Context, serviceID string, operator string) (int64, error) {
	count, err := s.switchDAO.BatchUpdateStatusByService(ctx, serviceID, model.StatusInactive, false, operator)
	if err != nil {
		return 0, err
	}
	logger.Infof("batch disabled %d switches for service %s by %s", count, serviceID, operator)
	return count, nil
}

func (s *SwitchService) BatchEnable(ctx context.Context, ids []string, operator string) (int64, error) {
	count, err := s.switchDAO.BatchUpdateStatus(ctx, ids, model.StatusActive, true, operator)
	if err != nil {
		return 0, err
	}
	logger.Infof("batch enabled %d switches by %s", count, operator)
	return count, nil
}

func (s *SwitchService) BatchDisable(ctx context.Context, ids []string, operator string) (int64, error) {
	count, err := s.switchDAO.BatchUpdateStatus(ctx, ids, model.StatusInactive, false, operator)
	if err != nil {
		return 0, err
	}
	logger.Infof("batch disabled %d switches by %s", count, operator)
	return count, nil
}

func (s *SwitchService) Evaluate(ctx context.Context, key string, evalCtx *model.EvaluationContext) (*model.EvaluationResult, error) {
	sw, err := s.switchDAO.GetByKey(ctx, key)
	if err != nil {
		return nil, err
	}
	if sw == nil {
		return &model.EvaluationResult{
			SwitchKey: key,
			Enabled:   false,
			Reason:    "switch_not_found",
		}, nil
	}

	strategies, err := s.strategyDAO.GetBySwitchID(ctx, sw.ID)
	if err != nil {
		logger.Warnf("get strategies error: %v", err)
		strategies = make([]*model.Strategy, 0)
	}

	for _, st := range strategies {
		conditions, _ := s.conditionDAO.GetByStrategyID(ctx, st.ID)
		st.Conditions = conditions
	}

	return s.engine.Evaluate(sw, evalCtx, strategies), nil
}

func (s *SwitchService) BatchEvaluate(ctx context.Context, evalCtx *model.EvaluationContext) (map[string]*model.EvaluationResult, error) {
	switches, err := s.switchDAO.GetAllEnabled(ctx)
	if err != nil {
		return nil, err
	}

	allStrategies, err := s.strategyDAO.GetAllWithConditions(ctx)
	if err != nil {
		logger.Warnf("get all strategies error: %v", err)
		allStrategies = make(map[string][]*model.Strategy)
	}

	return s.engine.BatchEvaluate(switches, evalCtx, allStrategies), nil
}

func (s *SwitchService) GetSDKConfig(ctx context.Context, version int64) (*model.SDKConfigResponse, error) {
	switches, err := s.switchDAO.GetAllEnabled(ctx)
	if err != nil {
		return nil, err
	}

	allStrategies, err := s.strategyDAO.GetAllWithConditions(ctx)
	if err != nil {
		logger.Warnf("get all strategies error: %v", err)
		allStrategies = make(map[string][]*model.Strategy)
	}

	snapshots := make([]*model.SwitchSnapshot, 0, len(switches))
	for _, sw := range switches {
		snapshot := &model.SwitchSnapshot{
			Key:             sw.Key,
			Type:            sw.Type,
			Enabled:         sw.Enabled,
			BooleanValue:    sw.BooleanValue,
			PercentageValue: sw.PercentageValue,
			UpdatedAt:       sw.UpdatedAt,
		}

		strategies := allStrategies[sw.ID]
		if len(strategies) > 0 {
			snapshot.Strategies = make([]*model.StrategySnapshot, 0, len(strategies))
			for _, st := range strategies {
				stSnapshot := &model.StrategySnapshot{
					ID:         st.ID,
					Operator:   st.Operator,
					Priority:   st.Priority,
					Conditions: make([]*model.ConditionSnapshot, 0, len(st.Conditions)),
				}
				for _, cond := range st.Conditions {
					stSnapshot.Conditions = append(stSnapshot.Conditions, &model.ConditionSnapshot{
						Field:    cond.Field,
						Operator: cond.Operator,
						Values:   cond.Values,
					})
				}
				snapshot.Strategies = append(snapshot.Strategies, stSnapshot)
			}
		}

		snapshots = append(snapshots, snapshot)
	}

	return &model.SDKConfigResponse{
		Version:   time.Now().Unix(),
		Switches:  snapshots,
		UpdatedAt: time.Now(),
	}, nil
}

func (s *SwitchService) SaveStrategies(ctx context.Context, switchID string, strategies []*model.Strategy, operator string) error {
	_, err := s.switchDAO.GetByID(ctx, switchID)
	if err != nil {
		return err
	}

	err = s.strategyDAO.DeleteBySwitchID(ctx, switchID)
	if err != nil {
		return err
	}

	for _, st := range strategies {
		st.ID = utils.GenerateUUID()
		st.SwitchID = switchID
		err = s.strategyDAO.Create(ctx, st)
		if err != nil {
			return err
		}

		if len(st.Conditions) > 0 {
			for _, cond := range st.Conditions {
				cond.ID = utils.GenerateUUID()
				cond.StrategyID = st.ID
			}
			err = s.conditionDAO.BatchCreate(ctx, st.Conditions)
			if err != nil {
				return err
			}
		}
	}

	s.sendEvent(ctx, model.EventStrategyUpdated, &model.Switch{ID: switchID}, operator)
	return nil
}

func (s *SwitchService) GetHistory(ctx context.Context, switchID string, page, pageSize int) (*model.ListResponse, error) {
	histories, total, err := s.historyDAO.ListBySwitchID(ctx, switchID, page, pageSize)
	if err != nil {
		return nil, err
	}
	return &model.ListResponse{
		Data: histories,
		Pagination: model.Pagination{
			Page:     page,
			PageSize: pageSize,
			Total:    total,
		},
	}, nil
}

func (s *SwitchService) toJSONB(v interface{}) *model.JSONB {
	b, err := json.Marshal(v)
	if err != nil {
		return nil
	}
	var j model.JSONB
	json.Unmarshal(b, &j)
	return &j
}

func (s *SwitchService) sendEvent(ctx context.Context, eventType model.EventType, sw *model.Switch, operator string) {
	if s.kafkaProducer == nil {
		return
	}

	event := &model.ChangeEvent{
		EventType: eventType,
		SwitchID:  sw.ID,
		SwitchKey: sw.Key,
		Operator:  operator,
		Timestamp: time.Now(),
		Data:      sw,
	}
	s.kafkaProducer.SendEvent(event)
}
