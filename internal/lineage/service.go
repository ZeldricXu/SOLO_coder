package lineage

import (
	"fmt"
	"sync"
	"time"

	"github.com/google/uuid"
	"streamsql/internal/common/logger"
)

type LineageService struct {
	dag        *LineageDAG
	extractors map[string]LineageExtractor
	lineages   map[string]*TableLineage
	mu         sync.RWMutex
}

func NewLineageService() *LineageService {
	svc := &LineageService{
		dag:        NewLineageDAG(),
		extractors: make(map[string]LineageExtractor),
		lineages:   make(map[string]*TableLineage),
	}

	sqlExtractor := NewSQLExtractor()
	sparkExtractor := NewSparkSQLExtractor()

	svc.extractors["sql"] = sqlExtractor
	svc.extractors["mysql"] = sqlExtractor
	svc.extractors["postgresql"] = sqlExtractor
	svc.extractors["sparksql"] = sparkExtractor
	svc.extractors["hive"] = sparkExtractor

	svc.initSampleLineage()

	logger.Sugar().Info("Lineage service initialized")
	return svc
}

func (s *LineageService) initSampleLineage() {
	sampleSQLs := []struct {
		sql     string
		sqlType string
	}{
		{
			sql:     "SELECT id, name, amount FROM orders WHERE status = 'active'",
			sqlType: "sql",
		},
		{
			sql:     "SELECT o.id, o.amount, c.name FROM orders o JOIN customers c ON o.customer_id = c.id",
			sqlType: "sql",
		},
	}

	for _, sample := range sampleSQLs {
		_, _ = s.ParseAndStore(sample.sql, sample.sqlType, map[string]interface{}{
			"source": "sample",
		})
	}
}

func (s *LineageService) ParseAndStore(sql string, sqlType string, metadata map[string]interface{}) (*TableLineage, error) {
	extractor, ok := s.extractors[sqlType]
	if !ok {
		return nil, fmt.Errorf("no extractor found for sql type: %s", sqlType)
	}

	lineage, err := extractor.Extract(sql)
	if err != nil {
		return nil, err
	}

	if metadata != nil {
		lineage.Metadata = metadata
	}

	s.mu.Lock()
	s.lineages[lineage.ID] = lineage
	s.mu.Unlock()

	s.updateDAG(lineage)

	logger.Sugar().Infof("Parsed and stored lineage: %s (operation: %s)", lineage.ID, lineage.OperationType)
	return lineage, nil
}

func (s *LineageService) updateDAG(lineage *TableLineage) {
	targetNode := &LineageNode{
		Name: lineage.TargetTable,
		Type: "table",
	}

	if lineage.TargetTable == "" {
		lineage.TargetTable = fmt.Sprintf("result_%s", lineage.ID[:8])
		targetNode.Name = lineage.TargetTable
	}

	targetNodeID, exists := s.dag.FindNodeByName(lineage.TargetTable)
	if !exists {
		targetNodeID = &LineageNode{ID: uuid.New().String(), Name: lineage.TargetTable, Type: "table"}
		s.dag.AddNode(targetNodeID)
	}

	for _, sourceTable := range lineage.SourceTables {
		sourceNodeID, exists := s.dag.FindNodeByName(sourceTable)
		if !exists {
			sourceNodeID = &LineageNode{ID: uuid.New().String(), Name: sourceTable, Type: "table"}
			s.dag.AddNode(sourceNodeID)
		}

		edge := &LineageEdge{
			SourceID: sourceNodeID.ID,
			TargetID: targetNodeID.ID,
			Relation: lineage.OperationType,
			Columns:  lineage.Columns,
		}
		s.dag.AddEdge(edge)
	}
}

func (s *LineageService) GetLineage(id string) (*TableLineage, error) {
	s.mu.RLock()
	defer s.mu.RUnlock()

	lineage, ok := s.lineages[id]
	if !ok {
		return nil, fmt.Errorf("lineage not found: %s", id)
	}
	return lineage, nil
}

func (s *LineageService) ListLineages() []*TableLineage {
	s.mu.RLock()
	defer s.mu.RUnlock()

	lineages := make([]*TableLineage, 0, len(s.lineages))
	for _, l := range s.lineages {
		lineages = append(lineages, l)
	}
	return lineages
}

func (s *LineageService) DeleteLineage(id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if _, ok := s.lineages[id]; !ok {
		return fmt.Errorf("lineage not found: %s", id)
	}

	delete(s.lineages, id)
	return nil
}

func (s *LineageService) SearchLineages(tableName string) []*TableLineage {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var results []*TableLineage
	for _, l := range s.lineages {
		if l.TargetTable == tableName {
			results = append(results, l)
			continue
		}
		for _, st := range l.SourceTables {
			if st == tableName {
				results = append(results, l)
				break
			}
		}
	}
	return results
}

func (s *LineageService) GetDAG() *LineageDAG {
	return s.dag
}

func (s *LineageService) GetUpstreamTables(tableName string) []*LineageNode {
	node, exists := s.dag.FindNodeByName(tableName)
	if !exists {
		return nil
	}
	return s.dag.GetAllUpstream(node.ID)
}

func (s *LineageService) GetDownstreamTables(tableName string) []*LineageNode {
	node, exists := s.dag.FindNodeByName(tableName)
	if !exists {
		return nil
	}
	return s.dag.GetAllDownstream(node.ID)
}

func (s *LineageService) GetTableLineage(tableName string) map[string]interface{} {
	node, exists := s.dag.FindNodeByName(tableName)
	if !exists {
		return nil
	}

	upstream := s.dag.GetAllUpstream(node.ID)
	downstream := s.dag.GetAllDownstream(node.ID)

	edges := make([]*LineageEdge, 0)
	for _, up := range upstream {
		edges = append(edges, s.dag.GetEdgesBetween(up.ID, node.ID)...)
	}
	for _, down := range downstream {
		edges = append(edges, s.dag.GetEdgesBetween(node.ID, down.ID)...)
	}

	return map[string]interface{}{
		"node":       node,
		"upstream":   upstream,
		"downstream": downstream,
		"edges":      edges,
	}
}

func (s *LineageService) GetStats() map[string]interface{} {
	s.mu.RLock()
	defer s.mu.RUnlock()

	nodes := s.dag.ListNodes()
	edges := s.dag.ListEdges()

	tableCount := 0
	for _, node := range nodes {
		if node.Type == "table" {
			tableCount++
		}
	}

	operationCounts := make(map[string]int)
	for _, l := range s.lineages {
		operationCounts[l.OperationType]++
	}

	return map[string]interface{}{
		"total_lineages":    len(s.lineages),
		"total_tables":      tableCount,
		"total_edges":       len(edges),
		"operation_counts":  operationCounts,
	}
}

func (s *LineageService) GetLineageByTimeRange(start, end time.Time) []*TableLineage {
	s.mu.RLock()
	defer s.mu.RUnlock()

	var results []*TableLineage
	for _, l := range s.lineages {
		if l.CreatedAt.After(start) && l.CreatedAt.Before(end) {
			results = append(results, l)
		}
	}
	return results
}

func (s *LineageService) AddExtractor(sqlType string, extractor LineageExtractor) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.extractors[sqlType] = extractor
}
