package graph

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/solocoder/knowledgebase/internal/config"
	"github.com/solocoder/knowledgebase/internal/db"
	"github.com/solocoder/knowledgebase/internal/models"
)

func NewTestConfig(t *testing.T) *config.Config {
	t.Helper()
	tmpDir := t.TempDir()
	cfg := config.Default()
	cfg.VaultPath = filepath.Join(tmpDir, "vault")
	cfg.DBPath = filepath.Join(tmpDir, "test.db")
	cfg.Graph.NodeMinSize = 10
	cfg.Graph.NodeMaxSize = 50
	return cfg
}

func NewTestDatabase(t *testing.T) (*db.Database, *config.Config) {
	t.Helper()
	cfg := NewTestConfig(t)
	database, err := db.New(cfg)
	if err != nil {
		t.Fatalf("failed to create test database: %v", err)
	}
	t.Cleanup(func() {
		database.Close()
		os.Remove(cfg.DBPath)
	})
	return database, cfg
}

func runWithTimeout(t *testing.T, timeout time.Duration, fn func()) {
	t.Helper()
	done := make(chan struct{})
	go func() {
		defer close(done)
		fn()
	}()
	select {
	case <-done:
	case <-time.After(timeout):
		t.Fatalf("test timed out after %v", timeout)
	}
}

func saveTestNote(t *testing.T, database *db.Database, path, title string, tags []string) *models.Note {
	t.Helper()
	note := &models.Note{
		Path:      path,
		Title:     title,
		Hash:      "hash_" + path,
		WordCount: 100,
	}
	for _, tagName := range tags {
		note.Tags = append(note.Tags, models.Tag{Name: tagName})
	}
	if err := database.SaveNote(note); err != nil {
		t.Fatalf("failed to save note %s: %v", title, err)
	}
	return note
}

type linkSpec struct {
	SourceID   uint
	TargetID   uint
	SourcePath string
	TargetPath string
}

func saveTestLinksBatch(t *testing.T, database *db.Database, specs []linkSpec) {
	t.Helper()
	sourceLinks := make(map[uint][]models.Link)
	for _, spec := range specs {
		link := models.Link{
			SourceID:   spec.SourceID,
			TargetID:   spec.TargetID,
			SourcePath: spec.SourcePath,
			TargetPath: spec.TargetPath,
			AnchorText: "link",
			LineNum:    1,
		}
		sourceLinks[spec.SourceID] = append(sourceLinks[spec.SourceID], link)
	}
	for sourceID, links := range sourceLinks {
		if err := database.SaveLinks(sourceID, links); err != nil {
			t.Fatalf("failed to save links for source %d: %v", sourceID, err)
		}
	}
}

func TestGraph_BuildFromNotes(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	a := saveTestNote(t, database, "/notes/A.md", "Note A", nil)
	b := saveTestNote(t, database, "/notes/B.md", "Note B", nil)
	c := saveTestNote(t, database, "/notes/C.md", "Note C", nil)
	d := saveTestNote(t, database, "/notes/D.md", "Note D", nil)
	e := saveTestNote(t, database, "/notes/E.md", "Note E", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: a.ID, TargetID: b.ID, SourcePath: a.Path, TargetPath: b.Path},
		{SourceID: b.ID, TargetID: c.ID, SourcePath: b.Path, TargetPath: c.Path},
		{SourceID: a.ID, TargetID: c.ID, SourcePath: a.Path, TargetPath: c.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	if g.GetNodeCount() != 5 {
		t.Errorf("expected 5 nodes, got %d", g.GetNodeCount())
	}
	if g.GetEdgeCount() != 3 {
		t.Errorf("expected 3 edges, got %d", g.GetEdgeCount())
	}

	nodeA := g.Nodes[a.ID]
	if nodeA == nil {
		t.Fatal("node A not found")
	}
	if nodeA.OutDegree != 2 {
		t.Errorf("node A OutDegree: expected 2, got %d", nodeA.OutDegree)
	}
	if nodeA.InDegree != 0 {
		t.Errorf("node A InDegree: expected 0, got %d", nodeA.InDegree)
	}

	nodeB := g.Nodes[b.ID]
	if nodeB == nil {
		t.Fatal("node B not found")
	}
	if nodeB.InDegree != 1 {
		t.Errorf("node B InDegree: expected 1, got %d", nodeB.InDegree)
	}
	if nodeB.OutDegree != 1 {
		t.Errorf("node B OutDegree: expected 1, got %d", nodeB.OutDegree)
	}

	nodeC := g.Nodes[c.ID]
	if nodeC == nil {
		t.Fatal("node C not found")
	}
	if nodeC.InDegree != 2 {
		t.Errorf("node C InDegree: expected 2, got %d", nodeC.InDegree)
	}
	if nodeC.OutDegree != 0 {
		t.Errorf("node C OutDegree: expected 0, got %d", nodeC.OutDegree)
	}

	nodeD := g.Nodes[d.ID]
	if nodeD == nil {
		t.Fatal("node D not found")
	}
	if nodeD.InDegree != 0 || nodeD.OutDegree != 0 {
		t.Errorf("node D degrees: expected both 0, got In=%d Out=%d", nodeD.InDegree, nodeD.OutDegree)
	}

	nodeE := g.Nodes[e.ID]
	if nodeE == nil {
		t.Fatal("node E not found")
	}
	if nodeE.InDegree != 0 || nodeE.OutDegree != 0 {
		t.Errorf("node E degrees: expected both 0, got In=%d Out=%d", nodeE.InDegree, nodeE.OutDegree)
	}
}

func TestGraph_NodeSizeReflectsInDegree(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	center := saveTestNote(t, database, "/notes/center.md", "Center", nil)

	var leaves []*models.Note
	var linkSpecs []linkSpec
	for i := 1; i <= 9; i++ {
		leaf := saveTestNote(t, database, "/notes/leaf_"+string(rune('0'+i))+".md", "Leaf"+string(rune('0'+i)), nil)
		leaves = append(leaves, leaf)
		linkSpecs = append(linkSpecs, linkSpec{
			SourceID:   leaf.ID,
			TargetID:   center.ID,
			SourcePath: leaf.Path,
			TargetPath: center.Path,
		})
	}
	saveTestLinksBatch(t, database, linkSpecs)

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}
	g.UpdateNodeSizes()

	centerNode := g.Nodes[center.ID]
	if centerNode == nil {
		t.Fatal("center node not found")
	}
	if centerNode.InDegree != 9 {
		t.Errorf("center InDegree: expected 9, got %d", centerNode.InDegree)
	}

	for i, leaf := range leaves {
		leafNode := g.Nodes[leaf.ID]
		if leafNode == nil {
			t.Fatalf("leaf %d node not found", i)
		}
		if centerNode.Size <= leafNode.Size {
			t.Errorf("center Size (%d) should be > leaf%d Size (%d)", centerNode.Size, i, leafNode.Size)
		}
	}
}

func TestGraph_OrphanDetection(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	notes := make([]*models.Note, 8)
	for i := 0; i < 8; i++ {
		notes[i] = saveTestNote(t, database, "/notes/note"+string(rune('A'+i))+".md", "Note"+string(rune('A'+i)), nil)
	}

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: notes[0].ID, TargetID: notes[1].ID, SourcePath: notes[0].Path, TargetPath: notes[1].Path},
		{SourceID: notes[1].ID, TargetID: notes[2].ID, SourcePath: notes[1].Path, TargetPath: notes[2].Path},
		{SourceID: notes[3].ID, TargetID: notes[4].ID, SourcePath: notes[3].Path, TargetPath: notes[4].Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	orphans := g.GetOrphanNodes()
	if len(orphans) != 3 {
		t.Errorf("expected 3 orphans, got %d", len(orphans))
	}

	orphanIDs := make(map[uint]bool)
	for _, orphan := range orphans {
		orphanIDs[orphan.ID] = true
		if !orphan.IsOrphan {
			t.Errorf("orphan node %d should have IsOrphan=true", orphan.ID)
		}
	}

	expectedOrphans := []uint{notes[5].ID, notes[6].ID, notes[7].ID}
	for _, id := range expectedOrphans {
		if !orphanIDs[id] {
			t.Errorf("expected note %d to be orphan", id)
		}
	}
}

func TestGraph_NoteAddition(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	n1 := saveTestNote(t, database, "/notes/n1.md", "N1", nil)
	n2 := saveTestNote(t, database, "/notes/n2.md", "N2", nil)
	_ = saveTestNote(t, database, "/notes/n3.md", "N3", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: n1.ID, TargetID: n2.ID, SourcePath: n1.Path, TargetPath: n2.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	initialNodes := g.GetNodeCount()
	initialEdges := g.GetEdgeCount()

	if initialNodes != 3 {
		t.Errorf("expected 3 initial nodes, got %d", initialNodes)
	}
	if initialEdges != 1 {
		t.Errorf("expected 1 initial edge, got %d", initialEdges)
	}

	n4 := saveTestNote(t, database, "/notes/n4.md", "N4", nil)

	existingLinks := make(map[uint][]models.Link)
	allLinks, _ := database.GetLinks()
	for _, l := range allLinks {
		existingLinks[l.SourceID] = append(existingLinks[l.SourceID], l)
	}
	existingLinks[n4.ID] = append(existingLinks[n4.ID], models.Link{
		SourceID: n4.ID, TargetID: n1.ID, SourcePath: n4.Path, TargetPath: n1.Path, AnchorText: "link", LineNum: 1,
	})
	existingLinks[n2.ID] = append(existingLinks[n2.ID], models.Link{
		SourceID: n2.ID, TargetID: n4.ID, SourcePath: n2.Path, TargetPath: n4.Path, AnchorText: "link", LineNum: 2,
	})
	for sourceID, links := range existingLinks {
		if err := database.SaveLinks(sourceID, links); err != nil {
			t.Fatalf("failed to save links for source %d: %v", sourceID, err)
		}
	}

	g2 := New(cfg)
	if err := g2.BuildFromDB(database); err != nil {
		t.Fatalf("second BuildFromDB failed: %v", err)
	}

	if g2.GetNodeCount() != initialNodes+1 {
		t.Errorf("expected %d nodes after addition, got %d", initialNodes+1, g2.GetNodeCount())
	}
	if g2.GetEdgeCount() != initialEdges+2 {
		t.Errorf("expected %d edges after addition, got %d", initialEdges+2, g2.GetEdgeCount())
	}

	nodeN4 := g2.Nodes[n4.ID]
	if nodeN4 == nil {
		t.Fatal("new node N4 not found")
	}
	if nodeN4.InDegree != 1 {
		t.Errorf("N4 InDegree: expected 1, got %d", nodeN4.InDegree)
	}
	if nodeN4.OutDegree != 1 {
		t.Errorf("N4 OutDegree: expected 1, got %d", nodeN4.OutDegree)
	}

	nodeN1 := g2.Nodes[n1.ID]
	if nodeN1.InDegree != 1 {
		t.Errorf("N1 InDegree should be updated to 1, got %d", nodeN1.InDegree)
	}

	nodeN2 := g2.Nodes[n2.ID]
	if nodeN2.OutDegree != 1 {
		t.Errorf("N2 OutDegree should be updated to 1, got %d", nodeN2.OutDegree)
	}
}

func TestGraph_NoteDeletion(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	a := saveTestNote(t, database, "/notes/a.md", "A", nil)
	b := saveTestNote(t, database, "/notes/b.md", "B", nil)
	c := saveTestNote(t, database, "/notes/c.md", "C", nil)
	d := saveTestNote(t, database, "/notes/d.md", "D", nil)
	e := saveTestNote(t, database, "/notes/e.md", "E", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: a.ID, TargetID: b.ID, SourcePath: a.Path, TargetPath: b.Path},
		{SourceID: a.ID, TargetID: c.ID, SourcePath: a.Path, TargetPath: c.Path},
		{SourceID: a.ID, TargetID: d.ID, SourcePath: a.Path, TargetPath: d.Path},
		{SourceID: e.ID, TargetID: a.ID, SourcePath: e.Path, TargetPath: a.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	if g.Nodes[a.ID].OutDegree != 3 {
		t.Fatalf("precondition: A should have OutDegree=3, got %d", g.Nodes[a.ID].OutDegree)
	}

	if err := database.DeleteNote(a.Path); err != nil {
		t.Fatalf("failed to delete note A: %v", err)
	}

	g2 := New(cfg)
	if err := g2.BuildFromDB(database); err != nil {
		t.Fatalf("second BuildFromDB failed: %v", err)
	}

	if _, exists := g2.Nodes[a.ID]; exists {
		t.Error("deleted node A should not exist in graph")
	}

	sourceEdgesFromA := 0
	targetEdgesToA := 0
	for _, edge := range g2.Edges {
		if edge.Source == a.ID {
			sourceEdgesFromA++
		}
		if edge.Target == a.ID {
			targetEdgesToA++
		}
	}
	if sourceEdgesFromA != 0 {
		t.Errorf("edges with A as source should be removed, found %d", sourceEdgesFromA)
	}
	if targetEdgesToA != 0 {
		t.Errorf("edges with A as target should be removed, found %d", targetEdgesToA)
	}

	nodeB := g2.Nodes[b.ID]
	if nodeB == nil {
		t.Fatal("node B should still exist")
	}
	if nodeB.InDegree != 0 {
		t.Errorf("node B InDegree should be 0 (was 1 from A), got %d", nodeB.InDegree)
	}

	nodeC := g2.Nodes[c.ID]
	if nodeC == nil {
		t.Fatal("node C should still exist")
	}
	if nodeC.InDegree != 0 {
		t.Errorf("node C InDegree should be 0 (was 1 from A), got %d", nodeC.InDegree)
	}

	nodeD := g2.Nodes[d.ID]
	if nodeD == nil {
		t.Fatal("node D should still exist")
	}
	if nodeD.InDegree != 0 {
		t.Errorf("node D InDegree should be 0 (was 1 from A), got %d", nodeD.InDegree)
	}

	nodeE := g2.Nodes[e.ID]
	if nodeE == nil {
		t.Fatal("node E should still exist")
	}
	if nodeE.OutDegree != 0 {
		t.Errorf("node E OutDegree should be 0 (was 1 to A), got %d", nodeE.OutDegree)
	}
}

func TestGraph_SelfReference(t *testing.T) {
	runWithTimeout(t, 2*time.Second, func() {
		database, cfg := NewTestDatabase(t)

		a := saveTestNote(t, database, "/notes/A.md", "Note A", nil)

		saveTestLinksBatch(t, database, []linkSpec{
			{SourceID: a.ID, TargetID: a.ID, SourcePath: a.Path, TargetPath: a.Path},
		})

		g := New(cfg)
		if err := g.BuildFromDB(database); err != nil {
			t.Fatalf("BuildFromDB failed: %v", err)
		}

		if _, exists := g.Nodes[a.ID]; !exists {
			t.Error("self-referencing node should exist")
		}

		selfEdges := 0
		for _, edge := range g.Edges {
			if edge.Source == a.ID && edge.Target == a.ID {
				selfEdges++
			}
		}
		if selfEdges == 0 {
			t.Error("self-referencing edge (Source==Target) should be recorded")
		}

		neighbors := g.GetNeighbors(a.ID, 2)
		if len(neighbors) > 10 {
			t.Errorf("GetNeighbors returned too many nodes (%d), possible infinite loop", len(neighbors))
		}

		path := g.ShortestPath(a.ID, a.ID)
		if path == nil {
			t.Error("ShortestPath(A,A) should not return nil")
		} else if len(path) != 1 || path[0] != a.ID {
			t.Errorf("ShortestPath(A,A) = %v, expected [%d]", path, a.ID)
		}
	})
}

func TestGraph_CycleReference(t *testing.T) {
	runWithTimeout(t, 2*time.Second, func() {
		database, cfg := NewTestDatabase(t)

		a := saveTestNote(t, database, "/notes/A.md", "A", nil)
		b := saveTestNote(t, database, "/notes/B.md", "B", nil)
		c := saveTestNote(t, database, "/notes/C.md", "C", nil)

		saveTestLinksBatch(t, database, []linkSpec{
			{SourceID: a.ID, TargetID: b.ID, SourcePath: a.Path, TargetPath: b.Path},
			{SourceID: b.ID, TargetID: c.ID, SourcePath: b.Path, TargetPath: c.Path},
			{SourceID: c.ID, TargetID: a.ID, SourcePath: c.Path, TargetPath: a.Path},
		})

		g := New(cfg)
		if err := g.BuildFromDB(database); err != nil {
			t.Fatalf("BuildFromDB failed: %v", err)
		}

		path := g.ShortestPath(a.ID, c.ID)
		if path == nil {
			t.Fatal("ShortestPath(A,C) should not return nil in cycle graph")
		}
		if path[0] != a.ID {
			t.Errorf("path should start with A (%d), got %d", a.ID, path[0])
		}
		if path[len(path)-1] != c.ID {
			t.Errorf("path should end with C (%d), got %d", c.ID, path[len(path)-1])
		}
		visited := make(map[uint]bool)
		duplicate := false
		for _, id := range path {
			if visited[id] {
				duplicate = true
				break
			}
			visited[id] = true
		}
		if duplicate {
			t.Errorf("ShortestPath(A,C) should not have duplicate nodes (BFS visited marking failed), path=%v", path)
		}
		if len(path) < 2 {
			t.Errorf("ShortestPath(A,C) path length = %d, expected at least 2 (2 nodes, 1 edge)", len(path))
		}
	})
}

func TestGraph_FilterByTags(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	n1 := saveTestNote(t, database, "/notes/n1.md", "N1", []string{"go", "test"})
	n2 := saveTestNote(t, database, "/notes/n2.md", "N2", []string{"go", "prod"})
	n3 := saveTestNote(t, database, "/notes/n3.md", "N3", []string{"python", "test"})
	n4 := saveTestNote(t, database, "/notes/n4.md", "N4", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: n1.ID, TargetID: n2.ID, SourcePath: n1.Path, TargetPath: n2.Path},
		{SourceID: n3.ID, TargetID: n4.ID, SourcePath: n3.Path, TargetPath: n4.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	filtered := g.Filter(FilterOptions{
		Tags:     []string{"go"},
		TagLogic: "any",
	})

	if filtered.GetNodeCount() != 2 {
		t.Errorf("expected 2 nodes with 'go' tag, got %d", filtered.GetNodeCount())
	}

	if _, exists := filtered.Nodes[n1.ID]; !exists {
		t.Error("n1 (with 'go') should be in filtered result")
	}
	if _, exists := filtered.Nodes[n2.ID]; !exists {
		t.Error("n2 (with 'go') should be in filtered result")
	}
	if _, exists := filtered.Nodes[n3.ID]; exists {
		t.Error("n3 (without 'go') should NOT be in filtered result")
	}
	if _, exists := filtered.Nodes[n4.ID]; exists {
		t.Error("n4 (without any tag) should NOT be in filtered result")
	}

	if filtered.GetEdgeCount() != 1 {
		t.Errorf("expected 1 edge in filtered graph, got %d", filtered.GetEdgeCount())
	}
}

func TestGraph_FilterOrphans(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	n1 := saveTestNote(t, database, "/notes/n1.md", "N1", nil)
	n2 := saveTestNote(t, database, "/notes/n2.md", "N2", nil)
	_ = saveTestNote(t, database, "/notes/n3.md", "N3", nil)
	_ = saveTestNote(t, database, "/notes/n4.md", "N4", nil)
	_ = saveTestNote(t, database, "/notes/n5.md", "N5", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: n1.ID, TargetID: n2.ID, SourcePath: n1.Path, TargetPath: n2.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	orphansBefore := len(g.GetOrphanNodes())
	if orphansBefore != 3 {
		t.Errorf("precondition: expected 3 orphans, got %d", orphansBefore)
	}

	filtered := g.Filter(FilterOptions{OnlyOrphans: true})

	if filtered.GetNodeCount() != orphansBefore {
		t.Errorf("Filter(OnlyOrphans=true) node count = %d, expected %d", filtered.GetNodeCount(), orphansBefore)
	}

	for id, node := range filtered.Nodes {
		if !node.IsOrphan {
			t.Errorf("filtered node %d should be orphan", id)
		}
	}
}

func TestGraph_FilterDegreeMin(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	n1 := saveTestNote(t, database, "/notes/n1.md", "N1", nil)
	n2 := saveTestNote(t, database, "/notes/n2.md", "N2", nil)
	n3 := saveTestNote(t, database, "/notes/n3.md", "N3", nil)
	n4 := saveTestNote(t, database, "/notes/n4.md", "N4", nil)
	n5 := saveTestNote(t, database, "/notes/n5.md", "N5", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: n1.ID, TargetID: n2.ID, SourcePath: n1.Path, TargetPath: n2.Path},
		{SourceID: n2.ID, TargetID: n3.ID, SourcePath: n2.Path, TargetPath: n3.Path},
		{SourceID: n4.ID, TargetID: n2.ID, SourcePath: n4.Path, TargetPath: n2.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	filtered := g.Filter(FilterOptions{MinDegree: 2})

	for id, node := range filtered.Nodes {
		totalDegree := node.InDegree + node.OutDegree
		if totalDegree < 2 {
			t.Errorf("filtered node %d has total degree %d, expected >= 2", id, totalDegree)
		}
	}

	if _, exists := filtered.Nodes[n2.ID]; !exists {
		t.Error("n2 (In=2, Out=1, total=3) should pass MinDegree=2 filter")
	}
	if _, exists := filtered.Nodes[n5.ID]; exists {
		t.Error("n5 (total degree 0) should NOT pass MinDegree=2 filter")
	}
}

func TestGraph_ShortestPath(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	a := saveTestNote(t, database, "/notes/A.md", "A", nil)
	b := saveTestNote(t, database, "/notes/B.md", "B", nil)
	c := saveTestNote(t, database, "/notes/C.md", "C", nil)
	d := saveTestNote(t, database, "/notes/D.md", "D", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: a.ID, TargetID: b.ID, SourcePath: a.Path, TargetPath: b.Path},
		{SourceID: b.ID, TargetID: c.ID, SourcePath: b.Path, TargetPath: c.Path},
		{SourceID: c.ID, TargetID: d.ID, SourcePath: c.Path, TargetPath: d.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	path := g.ShortestPath(a.ID, d.ID)
	if path == nil {
		t.Fatal("ShortestPath(A,D) should not return nil")
	}
	if len(path) != 4 {
		t.Fatalf("ShortestPath(A,D) length = %d, expected 4", len(path))
	}
	expected := []uint{a.ID, b.ID, c.ID, d.ID}
	for i, id := range expected {
		if path[i] != id {
			t.Errorf("path[%d] = %d, expected %d", i, path[i], id)
		}
	}
}

func TestGraph_NoPath(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	a := saveTestNote(t, database, "/notes/A.md", "A", nil)
	b := saveTestNote(t, database, "/notes/B.md", "B", nil)
	c := saveTestNote(t, database, "/notes/C.md", "C", nil)
	d := saveTestNote(t, database, "/notes/D.md", "D", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: a.ID, TargetID: b.ID, SourcePath: a.Path, TargetPath: b.Path},
		{SourceID: c.ID, TargetID: d.ID, SourcePath: c.Path, TargetPath: d.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	path := g.ShortestPath(a.ID, c.ID)
	if path != nil {
		t.Errorf("ShortestPath between disconnected components should return nil, got %v", path)
	}

	path2 := g.ShortestPath(b.ID, d.ID)
	if path2 != nil {
		t.Errorf("ShortestPath(B,D) should return nil, got %v", path2)
	}
}

func TestGraph_GetNeighbors(t *testing.T) {
	database, cfg := NewTestDatabase(t)

	a := saveTestNote(t, database, "/notes/A.md", "A", nil)
	b := saveTestNote(t, database, "/notes/B.md", "B", nil)
	c := saveTestNote(t, database, "/notes/C.md", "C", nil)
	d := saveTestNote(t, database, "/notes/D.md", "D", nil)
	e := saveTestNote(t, database, "/notes/E.md", "E", nil)

	saveTestLinksBatch(t, database, []linkSpec{
		{SourceID: a.ID, TargetID: b.ID, SourcePath: a.Path, TargetPath: b.Path},
		{SourceID: a.ID, TargetID: c.ID, SourcePath: a.Path, TargetPath: c.Path},
		{SourceID: b.ID, TargetID: d.ID, SourcePath: b.Path, TargetPath: d.Path},
		{SourceID: c.ID, TargetID: e.ID, SourcePath: c.Path, TargetPath: e.Path},
	})

	g := New(cfg)
	if err := g.BuildFromDB(database); err != nil {
		t.Fatalf("BuildFromDB failed: %v", err)
	}

	neighbors1 := g.GetNeighbors(a.ID, 1)
	if len(neighbors1) != 2 {
		t.Errorf("1-hop neighbors of A: expected 2, got %d", len(neighbors1))
	}
	hop1IDs := make(map[uint]bool)
	for _, n := range neighbors1 {
		hop1IDs[n.ID] = true
	}
	if !hop1IDs[b.ID] || !hop1IDs[c.ID] {
		t.Errorf("1-hop neighbors should include B and C, got IDs: %v", hop1IDs)
	}

	neighbors2 := g.GetNeighbors(a.ID, 2)
	if len(neighbors2) != 4 {
		t.Errorf("2-hop neighbors of A: expected 4, got %d", len(neighbors2))
	}
	hop2IDs := make(map[uint]bool)
	for _, n := range neighbors2 {
		hop2IDs[n.ID] = true
	}
	expectedHop2 := []uint{b.ID, c.ID, d.ID, e.ID}
	for _, id := range expectedHop2 {
		if !hop2IDs[id] {
			t.Errorf("2-hop neighbors should include node %d", id)
		}
	}
	if hop2IDs[a.ID] {
		t.Error("2-hop neighbors should NOT include A itself")
	}
}
