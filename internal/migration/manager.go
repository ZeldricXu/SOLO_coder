package migration

import (
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"time"

	"dbmigrator/pkg/models"
)

type DependencyNode struct {
	Migration    *models.Migration
	Dependencies []*DependencyNode
	Dependents   []*DependencyNode
}

type DependencyGraph struct {
	Nodes map[string]*DependencyNode
}

type Manager struct {
	migrationsDir string
	migrations    map[string]*models.Migration
	sorted        []*models.Migration
	graph         *DependencyGraph
}

var migrationFilePattern = regexp.MustCompile(`^(\d{8}_\d{6})_([^.]+)\.(up|down)\.sql$`)

func NewManager(migrationsDir string) (*Manager, error) {
	info, err := os.Stat(migrationsDir)
	if err != nil {
		if os.IsNotExist(err) {
			if err := os.MkdirAll(migrationsDir, 0755); err != nil {
				return nil, fmt.Errorf("failed to create migrations directory: %w", err)
			}
		} else {
			return nil, fmt.Errorf("failed to access migrations directory: %w", err)
		}
	} else if !info.IsDir() {
		return nil, fmt.Errorf("migrations path is not a directory: %s", migrationsDir)
	}

	m := &Manager{
		migrationsDir: migrationsDir,
		migrations:    make(map[string]*models.Migration),
	}

	if err := m.scanMigrations(); err != nil {
		return nil, err
	}

	return m, nil
}

func (m *Manager) scanMigrations() error {
	m.migrations = make(map[string]*models.Migration)

	entries, err := os.ReadDir(m.migrationsDir)
	if err != nil {
		return fmt.Errorf("failed to read migrations directory: %w", err)
	}

	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}

		filename := entry.Name()
		matches := migrationFilePattern.FindStringSubmatch(filename)
		if matches == nil || len(matches) != 4 {
			continue
		}

		version := matches[1]
		name := matches[2]
		scriptType := matches[3]

		migrationID := fmt.Sprintf("mig_%s_%s", version, name)
		migration, exists := m.migrations[migrationID]
		if !exists {
			migration = &models.Migration{
				MigrationID:  migrationID,
				Version:      version,
				Name:         name,
				Dependencies: []string{},
				CreatedAt:    time.Now(),
			}
			m.migrations[migrationID] = migration
		}

		filePath := filepath.Join(m.migrationsDir, filename)
		content, err := os.ReadFile(filePath)
		if err != nil {
			return fmt.Errorf("failed to read migration file %s: %w", filename, err)
		}

		if scriptType == "up" {
			migration.UpScript = string(content)
			migration.UpFilePath = filePath
		} else {
			migration.DownScript = string(content)
			migration.DownFilePath = filePath
		}

		m.parseDependencies(migration, string(content))
	}

	if err := m.buildDependencyGraph(); err != nil {
		return err
	}

	m.sortMigrations()
	return nil
}

func (m *Manager) parseDependencies(migration *models.Migration, content string) {
	lines := strings.Split(content, "\n")
	for _, line := range lines {
		line = strings.TrimSpace(line)
		if strings.HasPrefix(line, "--") || strings.HasPrefix(line, "/*") {
			if strings.Contains(line, "@depends") || strings.Contains(line, "@dependency") {
				parts := strings.Fields(line)
				for i, part := range parts {
					if (part == "@depends" || part == "@dependency") && i+1 < len(parts) {
						depID := parts[i+1]
						depID = strings.Trim(depID, ";:,'\"")
						if depID != "" {
							found := false
							for _, existing := range migration.Dependencies {
								if existing == depID {
									found = true
									break
								}
							}
							if !found {
								migration.Dependencies = append(migration.Dependencies, depID)
							}
						}
					}
				}
			}
		}
	}
}

func (m *Manager) buildDependencyGraph() error {
	graph := &DependencyGraph{
		Nodes: make(map[string]*DependencyNode),
	}

	for id, mig := range m.migrations {
		graph.Nodes[id] = &DependencyNode{
			Migration: mig,
		}
	}

	for id, node := range graph.Nodes {
		for _, depID := range node.Migration.Dependencies {
			depNode, exists := graph.Nodes[depID]
			if !exists {
				return fmt.Errorf("migration '%s' depends on '%s' which does not exist", id, depID)
			}

			node.Dependencies = append(node.Dependencies, depNode)
			depNode.Dependents = append(depNode.Dependents, node)
		}
	}

	if err := m.detectCyclicDependencies(graph); err != nil {
		return err
	}

	m.graph = graph
	return nil
}

func (m *Manager) detectCyclicDependencies(graph *DependencyGraph) error {
	visited := make(map[string]bool)
	recStack := make(map[string]bool)

	var dfs func(string) ([]string, bool)
	dfs = func(id string) ([]string, bool) {
		visited[id] = true
		recStack[id] = true

		node := graph.Nodes[id]
		for _, dep := range node.Dependencies {
			depID := dep.Migration.MigrationID
			if !visited[depID] {
				if cycle, found := dfs(depID); found {
					return append(cycle, id), true
				}
			} else if recStack[depID] {
				return []string{depID, id}, true
			}
		}

		recStack[id] = false
		return nil, false
	}

	for id := range graph.Nodes {
		if !visited[id] {
			if cycle, found := dfs(id); found {
				if len(cycle) >= 2 && cycle[0] == cycle[len(cycle)-1] {
					cycle = cycle[1:]
				}
				return fmt.Errorf("cyclic dependency detected: %v", cycle)
			}
		}
	}

	return nil
}

func (m *Manager) sortMigrations() {
	if m.graph == nil {
		migrations := make([]*models.Migration, 0, len(m.migrations))
		for _, mig := range m.migrations {
			migrations = append(migrations, mig)
		}
		sort.Slice(migrations, func(i, j int) bool {
			return migrations[i].Version < migrations[j].Version
		})
		m.sorted = migrations
		return
	}

	sorted, err := m.topologicalSort(m.graph)
	if err != nil {
		migrations := make([]*models.Migration, 0, len(m.migrations))
		for _, mig := range m.migrations {
			migrations = append(migrations, mig)
		}
		sort.Slice(migrations, func(i, j int) bool {
			return migrations[i].Version < migrations[j].Version
		})
		m.sorted = migrations
		return
	}

	m.sorted = sorted
}

func (m *Manager) topologicalSort(graph *DependencyGraph) ([]*models.Migration, error) {
	inDegree := make(map[string]int)
	for id := range graph.Nodes {
		inDegree[id] = 0
	}

	for _, node := range graph.Nodes {
		for _, dep := range node.Dependencies {
			inDegree[node.Migration.MigrationID]++
		}
	}

	queue := make([]*DependencyNode, 0)
	for id, degree := range inDegree {
		if degree == 0 {
			queue = append(queue, graph.Nodes[id])
		}
	}

	sort.Slice(queue, func(i, j int) bool {
		return queue[i].Migration.Version < queue[j].Migration.Version
	})

	result := make([]*models.Migration, 0)

	for len(queue) > 0 {
		node := queue[0]
		queue = queue[1:]

		result = append(result, node.Migration)

		for _, dependent := range node.Dependents {
			depID := dependent.Migration.MigrationID
			inDegree[depID]--
			if inDegree[depID] == 0 {
				queue = append(queue, dependent)

				sort.Slice(queue, func(i, j int) bool {
					return queue[i].Migration.Version < queue[j].Migration.Version
				})
			}
		}
	}

	if len(result) != len(graph.Nodes) {
		return nil, fmt.Errorf("graph has cycle, topological sort not possible")
	}

	return result, nil
}

func (m *Manager) GetMigrations() []*models.Migration {
	return m.sorted
}

func (m *Manager) GetMigrationByID(id string) (*models.Migration, bool) {
	mig, exists := m.migrations[id]
	return mig, exists
}

func (m *Manager) GetMigrationByVersion(version string) (*models.Migration, bool) {
	for _, mig := range m.sorted {
		if mig.Version == version {
			return mig, true
		}
	}
	return nil, false
}

func (m *Manager) GetPendingMigrations(executedIDs map[string]bool) []*models.Migration {
	pending := make([]*models.Migration, 0)
	for _, mig := range m.sorted {
		if !executedIDs[mig.MigrationID] {
			pending = append(pending, mig)
		}
	}
	return pending
}

func (m *Manager) GetMigrationsTopological(executedIDs map[string]bool) ([]*models.Migration, error) {
	if m.graph == nil {
		return m.GetPendingMigrations(executedIDs), nil
	}

	subGraph, err := m.buildSubGraph(executedIDs, false)
	if err != nil {
		return nil, err
	}

	sorted, err := m.topologicalSort(subGraph)
	if err != nil {
		return nil, err
	}

	return sorted, nil
}

func (m *Manager) GetMigrationsUpTo(targetVersion string) []*models.Migration {
	result := make([]*models.Migration, 0)
	for _, mig := range m.sorted {
		result = append(result, mig)
		if mig.Version == targetVersion {
			break
		}
	}
	return result
}

func (m *Manager) GetMigrationsUpToTopological(targetVersion string, executedIDs map[string]bool) ([]*models.Migration, error) {
	var targetMigration *models.Migration
	for _, mig := range m.sorted {
		if mig.Version == targetVersion {
			targetMigration = mig
			break
		}
	}

	if targetMigration == nil {
		return nil, fmt.Errorf("target version %s not found", targetVersion)
	}

	if m.graph == nil {
		result := make([]*models.Migration, 0)
		for _, mig := range m.sorted {
			if !executedIDs[mig.MigrationID] {
				result = append(result, mig)
			}
			if mig.Version == targetVersion {
				break
			}
		}
		return result, nil
	}

	targetIDs := m.collectDependencies(targetMigration.MigrationID)
	targetIDs[targetMigration.MigrationID] = true

	subGraph := &DependencyGraph{
		Nodes: make(map[string]*DependencyNode),
	}

	for id, node := range m.graph.Nodes {
		if targetIDs[id] && !executedIDs[id] {
			subGraph.Nodes[id] = &DependencyNode{
				Migration:    node.Migration,
				Dependencies: make([]*DependencyNode, 0),
				Dependents:   make([]*DependencyNode, 0),
			}
		}
	}

	for id, node := range m.graph.Nodes {
		if _, exists := subGraph.Nodes[id]; !exists {
			continue
		}
		for _, dep := range node.Dependencies {
			if _, exists := subGraph.Nodes[dep.Migration.MigrationID]; exists {
				subGraph.Nodes[id].Dependencies = append(subGraph.Nodes[id].Dependencies, subGraph.Nodes[dep.Migration.MigrationID])
				subGraph.Nodes[dep.Migration.MigrationID].Dependents = append(subGraph.Nodes[dep.Migration.MigrationID].Dependents, subGraph.Nodes[id])
			}
		}
	}

	sorted, err := m.topologicalSort(subGraph)
	if err != nil {
		return nil, err
	}

	return sorted, nil
}

func (m *Manager) collectDependencies(migrationID string) map[string]bool {
	result := make(map[string]bool)
	visited := make(map[string]bool)

	var collect func(string)
	collect = func(id string) {
		if visited[id] {
			return
		}
		visited[id] = true

		node, exists := m.graph.Nodes[id]
		if !exists {
			return
		}

		for _, dep := range node.Dependencies {
			collect(dep.Migration.MigrationID)
			result[dep.Migration.MigrationID] = true
		}
	}

	collect(migrationID)
	return result
}

func (m *Manager) buildSubGraph(executedIDs map[string]bool, includeExecuted bool) (*DependencyGraph, error) {
	subGraph := &DependencyGraph{
		Nodes: make(map[string]*DependencyNode),
	}

	for id, node := range m.graph.Nodes {
		shouldInclude := includeExecuted || !executedIDs[id]
		if shouldInclude {
			subGraph.Nodes[id] = &DependencyNode{
				Migration:    node.Migration,
				Dependencies: make([]*DependencyNode, 0),
				Dependents:   make([]*DependencyNode, 0),
			}
		}
	}

	for id, node := range m.graph.Nodes {
		if _, exists := subGraph.Nodes[id]; !exists {
			continue
		}
		for _, dep := range node.Dependencies {
			if _, exists := subGraph.Nodes[dep.Migration.MigrationID]; exists {
				subGraph.Nodes[id].Dependencies = append(subGraph.Nodes[id].Dependencies, subGraph.Nodes[dep.Migration.MigrationID])
				subGraph.Nodes[dep.Migration.MigrationID].Dependents = append(subGraph.Nodes[dep.Migration.MigrationID].Dependents, subGraph.Nodes[id])
			}
		}
	}

	return subGraph, nil
}

func (m *Manager) ResolveDependencies(migration *models.Migration) ([]*models.Migration, error) {
	if m.graph == nil {
		resolved := make([]*models.Migration, 0)
		visited := make(map[string]bool)
		resolving := make(map[string]bool)

		var resolve func(string) error
		resolve = func(migrationID string) error {
			if visited[migrationID] {
				return nil
			}
			if resolving[migrationID] {
				return fmt.Errorf("circular dependency detected involving migration: %s", migrationID)
			}

			mig, exists := m.migrations[migrationID]
			if !exists {
				return fmt.Errorf("dependency migration not found: %s", migrationID)
			}

			resolving[migrationID] = true

			for _, depID := range mig.Dependencies {
				if err := resolve(depID); err != nil {
					return err
				}
			}

			resolving[migrationID] = false
			visited[migrationID] = true
			resolved = append(resolved, mig)

			return nil
		}

		if err := resolve(migration.MigrationID); err != nil {
			return nil, err
		}

		return resolved, nil
	}

	deps := m.collectDependencies(migration.MigrationID)
	deps[migration.MigrationID] = true

	subGraph := &DependencyGraph{
		Nodes: make(map[string]*DependencyNode),
	}

	for id := range deps {
		if node, exists := m.graph.Nodes[id]; exists {
			subGraph.Nodes[id] = &DependencyNode{
				Migration:    node.Migration,
				Dependencies: make([]*DependencyNode, 0),
				Dependents:   make([]*DependencyNode, 0),
			}
		}
	}

	for id, node := range m.graph.Nodes {
		if _, exists := subGraph.Nodes[id]; !exists {
			continue
		}
		for _, dep := range node.Dependencies {
			if _, exists := subGraph.Nodes[dep.Migration.MigrationID]; exists {
				subGraph.Nodes[id].Dependencies = append(subGraph.Nodes[id].Dependencies, subGraph.Nodes[dep.Migration.MigrationID])
				subGraph.Nodes[dep.Migration.MigrationID].Dependents = append(subGraph.Nodes[dep.Migration.MigrationID].Dependents, subGraph.Nodes[id])
			}
		}
	}

	sorted, err := m.topologicalSort(subGraph)
	if err != nil {
		return nil, err
	}

	return sorted, nil
}

func (m *Manager) CreateMigration(name string) (string, string, error) {
	timestamp := time.Now().Format("20060102_150405")
	version := timestamp
	migrationID := fmt.Sprintf("mig_%s_%s", version, name)

	upFileName := fmt.Sprintf("%s_%s.up.sql", version, name)
	downFileName := fmt.Sprintf("%s_%s.down.sql", version, name)

	upFilePath := filepath.Join(m.migrationsDir, upFileName)
	downFilePath := filepath.Join(m.migrationsDir, downFileName)

	upTemplate := fmt.Sprintf(`-- Migration: %s
-- Created at: %s
-- Description: %s
--
-- To declare dependencies on other migrations, use:
--   -- @depends mig_20260504_134000_table_name
--   -- @depends mig_20260504_134001_another_table

-- Add your UP migration SQL here
-- Example: CREATE TABLE table_name (...);

`, migrationID, time.Now().Format(time.RFC3339), name)

	downTemplate := fmt.Sprintf(`-- Rollback: %s
-- Created at: %s
-- Description: Rollback for %s

-- Add your DOWN migration SQL here
-- Example: DROP TABLE IF EXISTS table_name;

`, migrationID, time.Now().Format(time.RFC3339), name)

	if err := os.WriteFile(upFilePath, []byte(upTemplate), 0644); err != nil {
		return "", "", fmt.Errorf("failed to create up migration file: %w", err)
	}

	if err := os.WriteFile(downFilePath, []byte(downTemplate), 0644); err != nil {
		os.Remove(upFilePath)
		return "", "", fmt.Errorf("failed to create down migration file: %w", err)
	}

	if err := m.scanMigrations(); err != nil {
		return "", "", err
	}

	return upFilePath, downFilePath, nil
}

func (m *Manager) Refresh() error {
	return m.scanMigrations()
}

func (m *Manager) GetDependencyGraph() *DependencyGraph {
	return m.graph
}

func (g *DependencyGraph) Print() {
	fmt.Println("Dependency Graph:")
	for id, node := range g.Nodes {
		fmt.Printf("  %s:\n", id)
		if len(node.Dependencies) > 0 {
			fmt.Printf("    Depends on: ")
			for i, dep := range node.Dependencies {
				if i > 0 {
					fmt.Print(", ")
				}
				fmt.Print(dep.Migration.MigrationID)
			}
			fmt.Println()
		}
		if len(node.Dependents) > 0 {
			fmt.Printf("    Required by: ")
			for i, dep := range node.Dependents {
				if i > 0 {
					fmt.Print(", ")
				}
				fmt.Print(dep.Migration.MigrationID)
			}
			fmt.Println()
		}
	}
}
