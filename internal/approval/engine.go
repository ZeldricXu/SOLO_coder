package approval

import (
	"errors"
	"fmt"
	"sync"
)

type ApprovalStrategy string

const (
	StrategyCountersign ApprovalStrategy = "countersign"
	StrategyOrsign      ApprovalStrategy = "or_sign"
	StrategySequential  ApprovalStrategy = "sequential"
	StrategyPercentage  ApprovalStrategy = "percentage"
)

type ConditionOperator string

const (
	OpEqual        ConditionOperator = "eq"
	OpNotEqual     ConditionOperator = "ne"
	OpGreaterThan  ConditionOperator = "gt"
	OpLessThan     ConditionOperator = "lt"
	OpContains     ConditionOperator = "contains"
	OpIn           ConditionOperator = "in"
)

type Condition struct {
	Field    string            `json:"field"`
	Operator ConditionOperator `json:"operator"`
	Value    interface{}       `json:"value"`
}

type Branch struct {
	ID         string      `json:"id"`
	Name       string      `json:"name"`
	Conditions []Condition `json:"conditions"`
	Strategy   ApprovalStrategy `json:"strategy"`
	Approvers  []Approver  `json:"approvers"`
	NextBranch string      `json:"next_branch,omitempty"`
	MinApproval int        `json:"min_approval,omitempty"`
	ApprovalPct float64    `json:"approval_pct,omitempty"`
}

type Approver struct {
	ID       string `json:"id"`
	Name     string `json:"name"`
	Role     string `json:"role"`
	Dept     string `json:"dept"`
	External bool   `json:"external"`
}

type ApprovalStatus string

const (
	ApprovalPending  ApprovalStatus = "pending"
	ApprovalApproved ApprovalStatus = "approved"
	ApprovalRejected ApprovalStatus = "rejected"
	ApprovalCancelled ApprovalStatus = "cancelled"
)

type ApprovalRecord struct {
	ApproverID string         `json:"approver_id"`
	Status     ApprovalStatus `json:"status"`
	Comment    string         `json:"comment"`
	Timestamp  string         `json:"timestamp"`
}

type ApprovalRequest struct {
	ID           string            `json:"id"`
	Title        string            `json:"title"`
	Description  string            `json:"description"`
	RequesterID  string            `json:"requester_id"`
	Context      map[string]interface{} `json:"context"`
	CurrentBranch string           `json:"current_branch"`
	Branches     []Branch          `json:"branches"`
	Records      []ApprovalRecord  `json:"records"`
	Status       ApprovalStatus    `json:"status"`
	TenantID     string            `json:"tenant_id"`
}

type ApprovalEngine struct {
	mu       sync.RWMutex
	requests map[string]*ApprovalRequest
	resolver DynamicApproverResolver
}

type DynamicApproverResolver func(role, dept string, context map[string]interface{}) ([]Approver, error)

func NewApprovalEngine(resolver DynamicApproverResolver) *ApprovalEngine {
	return &ApprovalEngine{
		requests: make(map[string]*ApprovalRequest),
		resolver: resolver,
	}
}

func (ae *ApprovalEngine) CreateRequest(id, title, description, requesterID, tenantID string, context map[string]interface{}, branches []Branch) (*ApprovalRequest, error) {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	if _, exists := ae.requests[id]; exists {
		return nil, fmt.Errorf("approval request %s already exists", id)
	}
	req := &ApprovalRequest{
		ID:          id,
		Title:       title,
		Description: description,
		RequesterID: requesterID,
		Context:     context,
		Branches:    branches,
		Records:     []ApprovalRecord{},
		Status:      ApprovalPending,
		TenantID:    tenantID,
	}
	if err := ae.resolveApprovers(req); err != nil {
		return nil, err
	}
	if len(branches) > 0 {
		matched := ae.matchBranch(req)
		if matched != nil {
			req.CurrentBranch = matched.ID
		} else {
			req.CurrentBranch = branches[0].ID
		}
	}
	ae.requests[id] = req
	return req, nil
}

func (ae *ApprovalEngine) resolveApprovers(req *ApprovalRequest) error {
	for i := range req.Branches {
		branch := &req.Branches[i]
		var resolvedApprovers []Approver
		for _, a := range branch.Approvers {
			if a.External && ae.resolver != nil {
				dynamic, err := ae.resolver(a.Role, a.Dept, req.Context)
				if err != nil {
					return fmt.Errorf("failed to resolve approver %s: %w", a.ID, err)
				}
				resolvedApprovers = append(resolvedApprovers, dynamic...)
			} else {
				resolvedApprovers = append(resolvedApprovers, a)
			}
		}
		branch.Approvers = resolvedApprovers
	}
	return nil
}

func (ae *ApprovalEngine) matchBranch(req *ApprovalRequest) *Branch {
	for i := range req.Branches {
		branch := &req.Branches[i]
		if len(branch.Conditions) == 0 {
			return branch
		}
		allMatch := true
		for _, cond := range branch.Conditions {
			if !ae.evaluateCondition(cond, req.Context) {
				allMatch = false
				break
			}
		}
		if allMatch {
			return branch
		}
	}
	return nil
}

func (ae *ApprovalEngine) evaluateCondition(cond Condition, context map[string]interface{}) bool {
	val, ok := context[cond.Field]
	if !ok {
		return false
	}
	switch cond.Operator {
	case OpEqual:
		return fmt.Sprintf("%v", val) == fmt.Sprintf("%v", cond.Value)
	case OpNotEqual:
		return fmt.Sprintf("%v", val) != fmt.Sprintf("%v", cond.Value)
	case OpGreaterThan:
		v1, ok1 := toFloat(val)
		v2, ok2 := toFloat(cond.Value)
		return ok1 && ok2 && v1 > v2
	case OpLessThan:
		v1, ok1 := toFloat(val)
		v2, ok2 := toFloat(cond.Value)
		return ok1 && ok2 && v1 < v2
	case OpContains:
		return fmt.Sprintf("%v", val) == fmt.Sprintf("%v", cond.Value) ||
			containsVal(val, cond.Value)
	case OpIn:
		return inList(val, cond.Value)
	default:
		return false
	}
}

func toFloat(v interface{}) (float64, bool) {
	switch val := v.(type) {
	case float64:
		return val, true
	case float32:
		return float64(val), true
	case int:
		return float64(val), true
	case int64:
		return float64(val), true
	default:
		return 0, false
	}
}

func containsVal(val, target interface{}) bool {
	s1 := fmt.Sprintf("%v", val)
	s2 := fmt.Sprintf("%v", target)
	for i := 0; i <= len(s1)-len(s2); i++ {
		if s1[i:i+len(s2)] == s2 {
			return true
		}
	}
	return false
}

func inList(val, list interface{}) bool {
	s := fmt.Sprintf("%v", val)
	listStr := fmt.Sprintf("%v", list)
	return s == listStr
}

func (ae *ApprovalEngine) GetCurrentBranch(req *ApprovalRequest) *Branch {
	for i := range req.Branches {
		if req.Branches[i].ID == req.CurrentBranch {
			return &req.Branches[i]
		}
	}
	return nil
}

func (ae *ApprovalEngine) Approve(requestID, approverID, comment string) error {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	req, ok := ae.requests[requestID]
	if !ok {
		return fmt.Errorf("request %s not found", requestID)
	}
	if req.Status != ApprovalPending {
		return errors.New("request is not in pending state")
	}
	branch := ae.GetCurrentBranch(req)
	if branch == nil {
		return errors.New("no active branch found")
	}
	approverValid := false
	for _, a := range branch.Approvers {
		if a.ID == approverID {
			approverValid = true
			break
		}
	}
	if !approverValid {
		return fmt.Errorf("approver %s is not authorized for this branch", approverID)
	}
	for i, r := range req.Records {
		if r.ApproverID == approverID {
			if r.Status != ApprovalPending {
				return errors.New("approver has already acted on this request")
			}
			req.Records[i].Status = ApprovalApproved
			req.Records[i].Comment = comment
			req.Records[i].Timestamp = fmt.Sprintf("%d", currentTimestamp())
			return ae.checkBranchCompletion(req)
		}
	}
	req.Records = append(req.Records, ApprovalRecord{
		ApproverID: approverID,
		Status:     ApprovalApproved,
		Comment:    comment,
		Timestamp:  fmt.Sprintf("%d", currentTimestamp()),
	})
	return ae.checkBranchCompletion(req)
}

func (ae *ApprovalEngine) Reject(requestID, approverID, comment string) error {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	req, ok := ae.requests[requestID]
	if !ok {
		return fmt.Errorf("request %s not found", requestID)
	}
	if req.Status != ApprovalPending {
		return errors.New("request is not in pending state")
	}
	branch := ae.GetCurrentBranch(req)
	if branch == nil {
		return errors.New("no active branch found")
	}
	if branch.Strategy == StrategyCountersign {
		req.Status = ApprovalRejected
		req.Records = append(req.Records, ApprovalRecord{
			ApproverID: approverID,
			Status:     ApprovalRejected,
			Comment:    comment,
			Timestamp:  fmt.Sprintf("%d", currentTimestamp()),
		})
		return nil
	}
	req.Records = append(req.Records, ApprovalRecord{
		ApproverID: approverID,
		Status:     ApprovalRejected,
		Comment:    comment,
		Timestamp:  fmt.Sprintf("%d", currentTimestamp()),
	})
	return ae.checkBranchCompletion(req)
}

func (ae *ApprovalEngine) checkBranchCompletion(req *ApprovalRequest) error {
	branch := ae.GetCurrentBranch(req)
	if branch == nil {
		return nil
	}
	approvedCount := 0
	rejectedCount := 0
	pendingCount := 0
	for _, r := range req.Records {
		switch r.Status {
		case ApprovalApproved:
			approvedCount++
		case ApprovalRejected:
			rejectedCount++
		case ApprovalPending:
			pendingCount++
		}
	}
	switch branch.Strategy {
	case StrategyOrsign:
		if approvedCount >= 1 {
			return ae.advanceBranch(req)
		}
		if rejectedCount == len(branch.Approvers) {
			req.Status = ApprovalRejected
		}
	case StrategyCountersign:
		minApproval := branch.MinApproval
		if minApproval == 0 {
			minApproval = len(branch.Approvers)
		}
		if approvedCount >= minApproval {
			return ae.advanceBranch(req)
		}
		if rejectedCount > 0 {
			req.Status = ApprovalRejected
		}
	case StrategySequential:
		if approvedCount >= len(branch.Approvers) {
			return ae.advanceBranch(req)
		}
	case StrategyPercentage:
		pct := branch.ApprovalPct
		if pct <= 0 {
			pct = 100
		}
		if len(branch.Approvers) > 0 {
			currentPct := float64(approvedCount) / float64(len(branch.Approvers)) * 100
			if currentPct >= pct {
				return ae.advanceBranch(req)
			}
		}
	}
	if pendingCount == 0 && approvedCount < len(branch.Approvers) {
		pendingCount = len(branch.Approvers) - approvedCount - rejectedCount
		if pendingCount <= 0 {
			req.Status = ApprovalRejected
		}
	}
	return nil
}

func (ae *ApprovalEngine) advanceBranch(req *ApprovalRequest) error {
	branch := ae.GetCurrentBranch(req)
	if branch == nil {
		req.Status = ApprovalApproved
		return nil
	}
	if branch.NextBranch != "" {
		req.CurrentBranch = branch.NextBranch
		return nil
	}
	req.Status = ApprovalApproved
	return nil
}

func (ae *ApprovalEngine) GetRequest(id string) (*ApprovalRequest, error) {
	ae.mu.RLock()
	defer ae.mu.RUnlock()
	req, ok := ae.requests[id]
	if !ok {
		return nil, fmt.Errorf("request %s not found", id)
	}
	return req, nil
}

func (ae *ApprovalEngine) CancelRequest(id string) error {
	ae.mu.Lock()
	defer ae.mu.Unlock()
	req, ok := ae.requests[id]
	if !ok {
		return fmt.Errorf("request %s not found", id)
	}
	if req.Status != ApprovalPending {
		return errors.New("only pending requests can be cancelled")
	}
	req.Status = ApprovalCancelled
	return nil
}

func (ae *ApprovalEngine) GetPendingApprovals(approverID string) []*ApprovalRequest {
	ae.mu.RLock()
	defer ae.mu.RUnlock()
	var result []*ApprovalRequest
	for _, req := range ae.requests {
		if req.Status != ApprovalPending {
			continue
		}
		hasApproved := false
		for _, r := range req.Records {
			if r.ApproverID == approverID && r.Status != ApprovalPending {
				hasApproved = true
				break
			}
		}
		if hasApproved {
			continue
		}
		branch := ae.GetCurrentBranch(req)
		if branch == nil {
			continue
		}
		for _, a := range branch.Approvers {
			if a.ID == approverID {
				result = append(result, req)
				break
			}
		}
	}
	return result
}

func (r *ApprovalRequest) Format() string {
	result := fmt.Sprintf("Approval[%s] %s | Status: %s | Branch: %s\n",
		r.ID, r.Title, r.Status, r.CurrentBranch)
	result += fmt.Sprintf("  Requester: %s | Records: %d\n", r.RequesterID, len(r.Records))
	for _, rec := range r.Records {
		result += fmt.Sprintf("    %s: %s (%s)\n", rec.ApproverID, rec.Status, rec.Comment)
	}
	return result
}

func currentTimestamp() int64 {
	return 0
}
