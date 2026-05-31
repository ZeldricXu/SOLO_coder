package flow

import (
	"errors"
	"fmt"
)

type NodeType string

const (
	NodeTypeStart    NodeType = "start"
	NodeTypeEnd      NodeType = "end"
	NodeTypeTask     NodeType = "task"
	NodeTypeDecision NodeType = "decision"
	NodeTypeApproval NodeType = "approval"
	NodeTypeNotify   NodeType = "notify"
)

type FlowState string

const (
	FlowStateDraft    FlowState = "draft"
	FlowStateActive   FlowState = "active"
	FlowStateArchived FlowState = "archived"
)

func (s FlowState) isValid() bool {
	switch s {
	case FlowStateDraft, FlowStateActive, FlowStateArchived:
		return true
	}
	return false
}

type Node struct {
	ID       string                 `json:"id"`
	Name     string                 `json:"name"`
	Type     NodeType               `json:"type"`
	Position Position               `json:"position"`
	Config   map[string]interface{} `json:"config"`
	NextNodes []string              `json:"next_nodes"`
}

type Position struct {
	X float64 `json:"x"`
	Y float64 `json:"y"`
}

type Connection struct {
	ID        string                 `json:"id"`
	FromNode  string                 `json:"from_node"`
	ToNode    string                 `json:"to_node"`
	Label     string                 `json:"label"`
	Condition map[string]interface{} `json:"condition"`
}

type FlowDefinition struct {
	ID          string       `json:"id"`
	Name        string       `json:"name"`
	Version     int          `json:"version"`
	State       FlowState    `json:"state"`
	Nodes       []Node       `json:"nodes"`
	Connections []Connection `json:"connections"`
	TenantID    string       `json:"tenant_id"`
}

type ValidationError struct {
	NodeID string `json:"node_id"`
	Field  string `json:"field"`
	Msg    string `json:"msg"`
}

func (e ValidationError) Error() string {
	return fmt.Sprintf("validation error on node %s, field %s: %s", e.NodeID, e.Field, e.Msg)
}

type FlowDesigner struct {
	flows map[string]*FlowDefinition
}

func NewFlowDesigner() *FlowDesigner {
	return &FlowDesigner{
		flows: make(map[string]*FlowDefinition),
	}
}

func (fd *FlowDesigner) CreateFlow(id, name, tenantID string) (*FlowDefinition, error) {
	if _, exists := fd.flows[id]; exists {
		return nil, fmt.Errorf("flow %s already exists", id)
	}
	flow := &FlowDefinition{
		ID:          id,
		Name:        name,
		Version:     1,
		State:       FlowStateDraft,
		Nodes:       []Node{},
		Connections: []Connection{},
		TenantID:    tenantID,
	}
	fd.flows[id] = flow
	return flow, nil
}

func (fd *FlowDesigner) GetFlow(id string) (*FlowDefinition, bool) {
	f, ok := fd.flows[id]
	return f, ok
}

func (fd *FlowDesigner) requireDraft(flowID string) (*FlowDefinition, error) {
	flow, ok := fd.flows[flowID]
	if !ok {
		return nil, errors.New("flow not found")
	}
	if flow.State != FlowStateDraft {
		return nil, fmt.Errorf("flow %s is in %s state; modifications only allowed in draft state", flowID, flow.State)
	}
	return flow, nil
}

func (fd *FlowDesigner) AddNode(flowID string, node Node) error {
	flow, err := fd.requireDraft(flowID)
	if err != nil {
		return err
	}
	for _, n := range flow.Nodes {
		if n.ID == node.ID {
			return fmt.Errorf("node %s already exists", node.ID)
		}
	}
	if node.Type == NodeTypeStart {
		for _, n := range flow.Nodes {
			if n.Type == NodeTypeStart {
				return fmt.Errorf("flow already has a start node (%s); only one start node allowed", n.ID)
			}
		}
	}
	flow.Nodes = append(flow.Nodes, node)
	return nil
}

func (fd *FlowDesigner) RemoveNode(flowID, nodeID string) error {
	flow, err := fd.requireDraft(flowID)
	if err != nil {
		return err
	}
	filtered := make([]Node, 0, len(flow.Nodes))
	for _, n := range flow.Nodes {
		if n.ID != nodeID {
			filtered = append(filtered, n)
		}
	}
	flow.Nodes = filtered
	filteredConn := make([]Connection, 0, len(flow.Connections))
	for _, c := range flow.Connections {
		if c.FromNode != nodeID && c.ToNode != nodeID {
			filteredConn = append(filteredConn, c)
		}
	}
	flow.Connections = filteredConn
	return nil
}

func (fd *FlowDesigner) UpdateNodeConfig(flowID, nodeID string, config map[string]interface{}) error {
	flow, err := fd.requireDraft(flowID)
	if err != nil {
		return err
	}
	for i := range flow.Nodes {
		if flow.Nodes[i].ID == nodeID {
			flow.Nodes[i].Config = config
			return nil
		}
	}
	return fmt.Errorf("node %s not found", nodeID)
}

func (fd *FlowDesigner) MoveNode(flowID, nodeID string, pos Position) error {
	flow, err := fd.requireDraft(flowID)
	if err != nil {
		return err
	}
	for i := range flow.Nodes {
		if flow.Nodes[i].ID == nodeID {
			flow.Nodes[i].Position = pos
			return nil
		}
	}
	return fmt.Errorf("node %s not found", nodeID)
}

func (fd *FlowDesigner) AddConnection(flowID string, conn Connection) error {
	flow, err := fd.requireDraft(flowID)
	if err != nil {
		return err
	}
	if conn.FromNode == conn.ToNode {
		return fmt.Errorf("self-loop connection from %s to itself is not allowed", conn.FromNode)
	}
	fromExists := false
	toExists := false
	for _, n := range flow.Nodes {
		if n.ID == conn.FromNode {
			fromExists = true
		}
		if n.ID == conn.ToNode {
			toExists = true
		}
	}
	if !fromExists {
		return fmt.Errorf("source node %s not found", conn.FromNode)
	}
	if !toExists {
		return fmt.Errorf("target node %s not found", conn.ToNode)
	}
	for _, c := range flow.Connections {
		if c.ID == conn.ID {
			return fmt.Errorf("connection %s already exists", conn.ID)
		}
		if c.FromNode == conn.FromNode && c.ToNode == conn.ToNode {
			return fmt.Errorf("duplicate connection from %s to %s already exists (id=%s)", conn.FromNode, conn.ToNode, c.ID)
		}
	}
	flow.Connections = append(flow.Connections, conn)
	return nil
}

func (fd *FlowDesigner) RemoveConnection(flowID, connID string) error {
	flow, err := fd.requireDraft(flowID)
	if err != nil {
		return err
	}
	filtered := make([]Connection, 0, len(flow.Connections))
	for _, c := range flow.Connections {
		if c.ID != connID {
			filtered = append(filtered, c)
		}
	}
	flow.Connections = filtered
	return nil
}

func (fd *FlowDesigner) ActivateFlow(flowID string) error {
	flow, ok := fd.flows[flowID]
	if !ok {
		return fmt.Errorf("flow %s not found", flowID)
	}
	if flow.State != FlowStateDraft {
		return fmt.Errorf("flow %s is in %s state; only draft flows can be activated", flowID, flow.State)
	}
	errs := fd.Validate(flowID)
	if len(errs) > 0 {
		return fmt.Errorf("flow %s has %d validation errors; cannot activate", flowID, len(errs))
	}
	flow.State = FlowStateActive
	return nil
}

func (fd *FlowDesigner) ArchiveFlow(flowID string) error {
	flow, ok := fd.flows[flowID]
	if !ok {
		return fmt.Errorf("flow %s not found", flowID)
	}
	if flow.State != FlowStateActive {
		return fmt.Errorf("flow %s is in %s state; only active flows can be archived", flowID, flow.State)
	}
	flow.State = FlowStateArchived
	return nil
}

func (fd *FlowDesigner) ReactivateFlow(flowID string) error {
	flow, ok := fd.flows[flowID]
	if !ok {
		return fmt.Errorf("flow %s not found", flowID)
	}
	if flow.State != FlowStateArchived {
		return fmt.Errorf("flow %s is in %s state; only archived flows can be reactivated", flowID, flow.State)
	}
	flow.State = FlowStateDraft
	return nil
}

func (fd *FlowDesigner) Validate(flowID string) []ValidationError {
	flow, ok := fd.flows[flowID]
	if !ok {
		return []ValidationError{{NodeID: "", Field: "flow", Msg: "flow not found"}}
	}
	var errs []ValidationError
	hasStart := false
	hasEnd := false
	startCount := 0
	nodeMap := make(map[string]*Node)
	for i := range flow.Nodes {
		n := &flow.Nodes[i]
		nodeMap[n.ID] = n
		if n.Type == NodeTypeStart {
			hasStart = true
			startCount++
		}
		if n.Type == NodeTypeEnd {
			hasEnd = true
		}
		if n.Name == "" {
			errs = append(errs, ValidationError{NodeID: n.ID, Field: "name", Msg: "node name is required"})
		}
	}
	if startCount > 1 {
		errs = append(errs, ValidationError{NodeID: "", Field: "start", Msg: fmt.Sprintf("flow must have exactly one start node, found %d", startCount)})
	}
	if !hasStart {
		errs = append(errs, ValidationError{NodeID: "", Field: "start", Msg: "flow must have a start node"})
	}
	if !hasEnd {
		errs = append(errs, ValidationError{NodeID: "", Field: "end", Msg: "flow must have an end node"})
	}
	inDegree := make(map[string]int)
	outDegree := make(map[string]int)
	for _, c := range flow.Connections {
		outDegree[c.FromNode]++
		inDegree[c.ToNode]++
		if _, ok := nodeMap[c.FromNode]; !ok {
			errs = append(errs, ValidationError{NodeID: c.FromNode, Field: "connection", Msg: "source node not found"})
		}
		if _, ok := nodeMap[c.ToNode]; !ok {
			errs = append(errs, ValidationError{NodeID: c.ToNode, Field: "connection", Msg: "target node not found"})
		}
	}
	for id, n := range nodeMap {
		switch n.Type {
		case NodeTypeStart:
			if inDegree[id] > 0 {
				errs = append(errs, ValidationError{NodeID: id, Field: "in_degree", Msg: "start node must not have incoming connections"})
			}
			if outDegree[id] == 0 {
				errs = append(errs, ValidationError{NodeID: id, Field: "out_degree", Msg: "start node must have at least one outgoing connection"})
			}
		case NodeTypeEnd:
			if outDegree[id] > 0 {
				errs = append(errs, ValidationError{NodeID: id, Field: "out_degree", Msg: "end node must not have outgoing connections"})
			}
			if inDegree[id] == 0 {
				errs = append(errs, ValidationError{NodeID: id, Field: "in_degree", Msg: "end node must have at least one incoming connection"})
			}
		case NodeTypeDecision:
			if outDegree[id] < 2 {
				errs = append(errs, ValidationError{NodeID: id, Field: "out_degree", Msg: "decision node must have at least 2 outgoing connections"})
			}
		case NodeTypeTask, NodeTypeApproval, NodeTypeNotify:
			if outDegree[id] == 0 {
				errs = append(errs, ValidationError{NodeID: id, Field: "out_degree", Msg: "node has no outgoing connections"})
			}
		}
	}
	visited := make(map[string]bool)
	recStack := make(map[string]bool)
	startID := findStartNode(flow)
	if startID != "" {
		fd.detectCycle(nodeMap, flow.Connections, startID, visited, recStack, &errs)
	}
	for id := range nodeMap {
		if !visited[id] {
			errs = append(errs, ValidationError{NodeID: id, Field: "reachability", Msg: "node is not reachable from start"})
		}
	}
	return errs
}

func findStartNode(flow *FlowDefinition) string {
	for _, n := range flow.Nodes {
		if n.Type == NodeTypeStart {
			return n.ID
		}
	}
	return ""
}

func (fd *FlowDesigner) detectCycle(nodeMap map[string]*Node, connections []Connection, current string, visited, recStack map[string]bool, errs *[]ValidationError) {
	visited[current] = true
	recStack[current] = true
	for _, c := range connections {
		if c.FromNode != current {
			continue
		}
		if recStack[c.ToNode] {
			*errs = append(*errs, ValidationError{
				NodeID: c.ToNode,
				Field:  "cycle",
				Msg:    fmt.Sprintf("cycle detected: %s -> %s forms a loop", c.FromNode, c.ToNode),
			})
			continue
		}
		if !visited[c.ToNode] {
			fd.detectCycle(nodeMap, connections, c.ToNode, visited, recStack, errs)
		}
	}
	recStack[current] = false
}

func (fd *FlowDesigner) SerializeFlow(flowID string) (map[string]interface{}, error) {
	flow, ok := fd.flows[flowID]
	if !ok {
		return nil, errors.New("flow not found")
	}
	return map[string]interface{}{
		"id":          flow.ID,
		"name":        flow.Name,
		"version":     flow.Version,
		"state":       flow.State,
		"tenant_id":   flow.TenantID,
		"nodes":       flow.Nodes,
		"connections": flow.Connections,
	}, nil
}

func (fd *FlowDesigner) DeleteFlow(flowID string) error {
	if _, ok := fd.flows[flowID]; !ok {
		return errors.New("flow not found")
	}
	delete(fd.flows, flowID)
	return nil
}

func (fd *FlowDesigner) CloneFlow(srcID, destID string) (*FlowDefinition, error) {
	src, ok := fd.flows[srcID]
	if !ok {
		return nil, errors.New("source flow not found")
	}
	if _, exists := fd.flows[destID]; exists {
		return nil, fmt.Errorf("destination flow %s already exists", destID)
	}
	cloned := *src
	cloned.ID = destID
	cloned.Version = 1
	cloned.State = FlowStateDraft
	cloned.Nodes = make([]Node, len(src.Nodes))
	copy(cloned.Nodes, src.Nodes)
	cloned.Connections = make([]Connection, len(src.Connections))
	copy(cloned.Connections, src.Connections)
	fd.flows[destID] = &cloned
	return &cloned, nil
}
