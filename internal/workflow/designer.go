package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/datamigration/platform/internal/logger"
	"github.com/datamigration/platform/pkg/models"
	"github.com/google/uuid"
	"go.uber.org/zap"
	"gorm.io/gorm"
)

type NodeType string

const (
	NodeStart        NodeType = "start"
	NodeEnd          NodeType = "end"
	NodeTask         NodeType = "task"
	NodeApproval     NodeType = "approval"
	NodeCondition    NodeType = "condition"
	NodeParallel     NodeType = "parallel"
	NodeSubworkflow  NodeType = "subworkflow"
	NodeNotification NodeType = "notification"
)

type EdgeRule string

const (
	EdgeRuleDefault EdgeRule = "default"
	EdgeRuleTrue    EdgeRule = "true"
	EdgeRuleFalse   EdgeRule = "false"
	EdgeRuleExpr    EdgeRule = "expression"
)

type NodeConfig struct {
	Type        NodeType              `json:"type"`
	Name        string                `json:"name"`
	Description string                `json:"description,omitempty"`
	Config      map[string]interface{} `json:"config,omitempty"`
	Position    *Position             `json:"position,omitempty"`
	Metadata    map[string]string     `json:"metadata,omitempty"`
}

type Position struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
}

type EdgeConfig struct {
	ID          string   `json:"id"`
	Source      string   `json:"source"`
	Target      string   `json:"target"`
	Rule        EdgeRule `json:"rule"`
	Expression  string   `json:"expression,omitempty"`
	Label       string   `json:"label,omitempty"`
}

type ValidationError struct {
	NodeID string
	EdgeID string
	Field  string
	Reason string
}

func (e *ValidationError) Error() string {
	if e.NodeID != "" {
		return fmt.Sprintf("node %s: %s - %s", e.NodeID, e.Field, e.Reason)
	}
	if e.EdgeID != "" {
		return fmt.Sprintf("edge %s: %s - %s", e.EdgeID, e.Field, e.Reason)
	}
	return fmt.Sprintf("%s: %s", e.Field, e.Reason)
}

type Designer struct {
	db *gorm.DB
}

func NewDesigner(db *gorm.DB) *Designer {
	return &Designer{db: db}
}

func (d *Designer) CreateDefinition(ctx context.Context, tenantID, name, description string, nodes []*NodeConfig, edges []*EdgeConfig, config map[string]interface{}) (*models.WorkflowDefinition, error) {
	nodesBytes, err := json.Marshal(nodes)
	if err != nil {
		return nil, err
	}
	edgesBytes, err := json.Marshal(edges)
	if err != nil {
		return nil, err
	}
	configBytes, _ := json.Marshal(config)

	wf := &models.WorkflowDefinition{
		ID:          fmt.Sprintf("wf_%s", uuid.New().String()[:8]),
		Name:        name,
		Description: description,
		Version:     1,
		Nodes:       nodesBytes,
		Edges:       edgesBytes,
		Config:      configBytes,
		Enabled:     true,
		CreatedAt:   time.Now(),
		UpdatedAt:   time.Now(),
		TenantID:    tenantID,
	}

	if err := d.db.WithContext(ctx).Create(wf).Error; err != nil {
		logger.Error("failed to create workflow definition", zap.Error(err))
		return nil, err
	}

	return wf, nil
}

func (d *Designer) ValidateWorkflow(nodes []*NodeConfig, edges []*EdgeConfig) []*ValidationError {
	var errors []*ValidationError

	if len(nodes) == 0 {
		errors = append(errors, &ValidationError{
			Field:  "nodes",
			Reason: "workflow must have at least one node",
		})
		return errors
	}

	nodeMap := make(map[string]*NodeConfig)
	for _, node := range nodes {
		nodeMap[node.Name] = node
	}

	var startCount, endCount int
	for name, node := range nodeMap {
		if node.Type == NodeStart {
			startCount++
		}
		if node.Type == NodeEnd {
			endCount++
		}

		if node.Name == "" {
			errors = append(errors, &ValidationError{
				NodeID: name,
				Field:  "name",
				Reason: "node name cannot be empty",
			})
		}

		if node.Config == nil {
			node.Config = make(map[string]interface{})
		}

		switch node.Type {
		case NodeApproval:
			if _, ok := node.Config["approvers"]; !ok {
				errors = append(errors, &ValidationError{
					NodeID: name,
					Field:  "config.approvers",
					Reason: "approval node requires approvers configuration",
				})
			}
		case NodeCondition:
			if _, ok := node.Config["expression"]; !ok {
				errors = append(errors, &ValidationError{
					NodeID: name,
					Field:  "config.expression",
					Reason: "condition node requires expression",
				})
			}
		}
	}

	if startCount != 1 {
		errors = append(errors, &ValidationError{
			Field:  "nodes",
			Reason: fmt.Sprintf("workflow must have exactly one start node, got %d", startCount),
		})
	}

	if endCount == 0 {
		errors = append(errors, &ValidationError{
			Field:  "nodes",
			Reason: "workflow must have at least one end node",
		})
	}

	for _, edge := range edges {
		if edge.Source == "" {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "source",
				Reason: "edge source cannot be empty",
			})
		}
		if edge.Target == "" {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "target",
				Reason: "edge target cannot be empty",
			})
		}
		if edge.Source == edge.Target {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "source/target",
				Reason: "edge cannot connect node to itself",
			})
		}

		if _, ok := nodeMap[edge.Source]; !ok && edge.Source != "" {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "source",
				Reason: fmt.Sprintf("source node '%s' does not exist", edge.Source),
			})
		}
		if _, ok := nodeMap[edge.Target]; !ok && edge.Target != "" {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "target",
				Reason: fmt.Sprintf("target node '%s' does not exist", edge.Target),
			})
		}

		sourceNode, ok := nodeMap[edge.Source]
		if ok && sourceNode.Type == NodeEnd {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "source",
				Reason: "end node cannot have outgoing edges",
			})
		}

		targetNode, ok := nodeMap[edge.Target]
		if ok && targetNode.Type == NodeStart {
			errors = append(errors, &ValidationError{
				EdgeID: edge.ID,
				Field:  "target",
				Reason: "start node cannot have incoming edges",
			})
		}

		if sourceNode != nil && sourceNode.Type == NodeCondition {
			if edge.Rule != EdgeRuleTrue && edge.Rule != EdgeRuleFalse && edge.Rule != EdgeRuleExpr {
				errors = append(errors, &ValidationError{
					EdgeID: edge.ID,
					Field:  "rule",
					Reason: "condition node edges must have true/false/expression rule",
				})
			}
		}
	}

	if d.hasCycle(nodes, edges) {
		errors = append(errors, &ValidationError{
			Field:  "workflow",
			Reason: "workflow contains cycle",
		})
	}

	return errors
}

func (d *Designer) hasCycle(nodes []*NodeConfig, edges []*EdgeConfig) bool {
	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	adj := make(map[string][]string)
	for _, edge := range edges {
		adj[edge.Source] = append(adj[edge.Source], edge.Target)
	}

	var dfs func(string) bool
	dfs = func(node string) bool {
		visited[node] = true
		recStack[node] = true

		for _, neighbor := range adj[node] {
			if !visited[neighbor] {
				if dfs(neighbor) {
					return true
				}
			} else if recStack[neighbor] {
				return true
			}
		}

		recStack[node] = false
		return false
	}

	for _, node := range nodes {
		if !visited[node.Name] {
			if dfs(node.Name) {
				return true
			}
		}
	}

	return false
}

func (d *Designer) UpdateDefinition(ctx context.Context, definitionID string, updates map[string]interface{}) error {
	updates["updated_at"] = time.Now()

	definition := &models.WorkflowDefinition{}
	if err := d.db.WithContext(ctx).Where("id = ?", definitionID).First(definition).Error; err != nil {
		return err
	}

	updates["version"] = definition.Version + 1

	return d.db.WithContext(ctx).Model(&models.WorkflowDefinition{}).
		Where("id = ?", definitionID).
		Updates(updates).Error
}

func (d *Designer) GetDefinition(ctx context.Context, definitionID string) (*models.WorkflowDefinition, error) {
	var wf models.WorkflowDefinition
	if err := d.db.WithContext(ctx).Where("id = ?", definitionID).First(&wf).Error; err != nil {
		return nil, err
	}
	return &wf, nil
}

func (d *Designer) ListDefinitions(ctx context.Context, tenantID string, page, pageSize int) ([]*models.WorkflowDefinition, int64, error) {
	var definitions []*models.WorkflowDefinition
	var total int64

	query := d.db.WithContext(ctx).Model(&models.WorkflowDefinition{}).Where("tenant_id = ?", tenantID)

	if err := query.Count(&total).Error; err != nil {
		return nil, 0, err
	}

	if page <= 0 {
		page = 1
	}
	if pageSize <= 0 {
		pageSize = 20
	}

	offset := (page - 1) * pageSize
	if err := query.Order("created_at DESC").Offset(offset).Limit(pageSize).Find(&definitions).Error; err != nil {
		return nil, 0, err
	}

	return definitions, total, nil
}

func (d *Designer) StartInstance(ctx context.Context, tenantID, definitionID string, payload map[string]interface{}) (*models.WorkflowInstance, error) {
	def, err := d.GetDefinition(ctx, definitionID)
	if err != nil {
		return nil, err
	}

	if !def.Enabled {
		return nil, errors.New("workflow definition is disabled")
	}

	var nodes []*NodeConfig
	if err := json.Unmarshal(def.Nodes, &nodes); err != nil {
		return nil, err
	}

	startNode := d.findStartNode(nodes)
	if startNode == nil {
		return nil, errors.New("start node not found")
	}

	payloadBytes, _ := json.Marshal(payload)
	contextBytes, _ := json.Marshal(map[string]interface{}{
		"started_by": "system",
		"version":    def.Version,
	})

	instance := &models.WorkflowInstance{
		ID:             fmt.Sprintf("wfi_%s", uuid.New().String()[:8]),
		DefinitionID:   definitionID,
		CurrentNodeID:  startNode.Name,
		Status:         "running",
		Payload:        payloadBytes,
		Context:        contextBytes,
		StartedAt:      time.Now(),
		LastActivityAt: time.Now(),
		TenantID:       tenantID,
	}

	if err := d.db.WithContext(ctx).Create(instance).Error; err != nil {
		logger.Error("failed to create workflow instance", zap.Error(err))
		return nil, err
	}

	return instance, nil
}

func (d *Designer) findStartNode(nodes []*NodeConfig) *NodeConfig {
	for _, n := range nodes {
		if n.Type == NodeStart {
			return n
		}
	}
	return nil
}

func (d *Designer) AdvanceInstance(ctx context.Context, instanceID string, decision map[string]interface{}) error {
	var instance models.WorkflowInstance
	if err := d.db.WithContext(ctx).Where("id = ?", instanceID).First(&instance).Error; err != nil {
		return err
	}

	if instance.Status != "running" {
		return errors.New("workflow instance is not running")
	}

	var def models.WorkflowDefinition
	if err := d.db.WithContext(ctx).Where("id = ?", instance.DefinitionID).First(&def).Error; err != nil {
		return err
	}

	var nodes []*NodeConfig
	var edges []*EdgeConfig
	if err := json.Unmarshal(def.Nodes, &nodes); err != nil {
		return err
	}
	if err := json.Unmarshal(def.Edges, &edges); err != nil {
		return err
	}

	currentNode := d.findNode(nodes, instance.CurrentNodeID)
	if currentNode == nil {
		return errors.New("current node not found")
	}

	nextNode, err := d.determineNextNode(currentNode, edges, decision)
	if err != nil {
		return err
	}

	now := time.Now()
	updates := map[string]interface{}{
		"last_activity_at": now,
	}

	if nextNode != nil {
		updates["current_node_id"] = nextNode.Name
		if nextNode.Type == NodeEnd {
			updates["status"] = "completed"
			updates["completed_at"] = &now
		}
	}

	return d.db.WithContext(ctx).Model(&instance).Updates(updates).Error
}

func (d *Designer) findNode(nodes []*NodeConfig, name string) *NodeConfig {
	for _, n := range nodes {
		if n.Name == name {
			return n
		}
	}
	return nil
}

func (d *Designer) determineNextNode(current *NodeConfig, edges []*EdgeConfig, decision map[string]interface{}) (*NodeConfig, error) {
	var outgoing []*EdgeConfig
	for _, e := range edges {
		if e.Source == current.Name {
			outgoing = append(outgoing, e)
		}
	}

	if len(outgoing) == 0 {
		return nil, nil
	}

	if current.Type == NodeCondition {
		decisionValue, _ := decision["result"].(bool)
		rule := EdgeRuleTrue
		if !decisionValue {
			rule = EdgeRuleFalse
		}
		for _, e := range outgoing {
			if e.Rule == rule {
				return &NodeConfig{Name: e.Target}, nil
			}
		}
	}

	for _, e := range outgoing {
		if e.Rule == EdgeRuleDefault || e.Rule == "" {
			return &NodeConfig{Name: e.Target}, nil
		}
	}

	if len(outgoing) > 0 {
		return &NodeConfig{Name: outgoing[0].Target}, nil
	}

	return nil, nil
}

func (d *Designer) GetInstance(ctx context.Context, instanceID string) (*models.WorkflowInstance, error) {
	var instance models.WorkflowInstance
	if err := d.db.WithContext(ctx).Where("id = ?", instanceID).First(&instance).Error; err != nil {
		return nil, err
	}
	return &instance, nil
}

func (d *Designer) GetActiveInstances(ctx context.Context, tenantID string) ([]*models.WorkflowInstance, error) {
	var instances []*models.WorkflowInstance
	if err := d.db.WithContext(ctx).
		Where("tenant_id = ? AND status = ?", tenantID, "running").
		Order("started_at DESC").
		Find(&instances).Error; err != nil {
		return nil, err
	}
	return instances, nil
}

func (d *Designer) ParseNodeID(input string) string {
	return strings.TrimSpace(input)
}
