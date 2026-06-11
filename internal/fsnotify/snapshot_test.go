package fsnotify

import (
	"sort"
	"sync"
	"testing"
	"time"
)

func makeInfo(path string, modTime time.Time, size int64, hash string) FileInfo {
	return FileInfo{
		Path:       path,
		ModTime:    modTime,
		Size:       size,
		Hash:       hash,
		IsMarkdown: true,
	}
}

func strSlicesEqual(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	sort.Strings(a)
	sort.Strings(b)
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

func TestSnapshot_UpdateRemoveContains(t *testing.T) {
	s := NewFileSnapshot("/vault")
	t1 := time.Now()

	if s.Contains("/vault/a.md") {
		t.Fatal("empty snapshot should not contain a.md")
	}
	if s.Count() != 0 {
		t.Fatalf("Count should be 0, got %d", s.Count())
	}

	infoA := makeInfo("/vault/a.md", t1, 100, "hash-a")
	s.Update("/vault/a.md", infoA)

	if !s.Contains("/vault/a.md") {
		t.Fatal("snapshot should contain a.md after Update")
	}
	if s.Count() != 1 {
		t.Fatalf("Count should be 1, got %d", s.Count())
	}

	got, ok := s.Get("/vault/a.md")
	if !ok {
		t.Fatal("Get should find a.md")
	}
	if got.Hash != "hash-a" || got.Size != 100 {
		t.Fatalf("Get returned wrong info: %+v", got)
	}

	_, ok = s.Get("/vault/nonexistent.md")
	if ok {
		t.Fatal("Get should return false for nonexistent")
	}

	infoB := makeInfo("/vault/b.md", t1, 200, "hash-b")
	s.Update("/vault/b.md", infoB)
	if s.Count() != 2 {
		t.Fatalf("Count should be 2, got %d", s.Count())
	}

	s.Remove("/vault/a.md")
	if s.Contains("/vault/a.md") {
		t.Fatal("a.md should be removed")
	}
	if s.Count() != 1 {
		t.Fatalf("Count should be 1 after remove, got %d", s.Count())
	}

	all := s.ListAll()
	if len(all) != 1 || all[0] != "/vault/b.md" {
		t.Fatalf("ListAll wrong: %v", all)
	}
}

func TestSnapshot_Compare_Added_Modified_Deleted(t *testing.T) {
	oldSnap := NewFileSnapshot("/vault")
	t1 := time.Now()
	t2 := t1.Add(1 * time.Hour)

	oldSnap.Update("/vault/a.md", makeInfo("/vault/a.md", t1, 100, "hash-a"))
	oldSnap.Update("/vault/b.md", makeInfo("/vault/b.md", t1, 200, "hash-b"))
	oldSnap.Update("/vault/c.md", makeInfo("/vault/c.md", t1, 300, "hash-c"))
	oldSnap.Update("/vault/d.md", makeInfo("/vault/d.md", t1, 400, "hash-d"))
	oldSnap.Update("/vault/e.md", makeInfo("/vault/e.md", t1, 500, "hash-e"))

	newSnap := NewFileSnapshot("/vault")
	newSnap.Update("/vault/a.md", makeInfo("/vault/a.md", t1, 100, "hash-a"))
	newSnap.Update("/vault/b.md", makeInfo("/vault/b.md", t2, 200, "hash-b-modified"))
	newSnap.Update("/vault/c.md", makeInfo("/vault/c.md", t1, 999, "hash-c"))
	newSnap.Update("/vault/d.md", makeInfo("/vault/d.md", t1, 400, "hash-d"))
	newSnap.Update("/vault/f.md", makeInfo("/vault/f.md", t2, 600, "hash-f"))
	newSnap.Update("/vault/g.md", makeInfo("/vault/g.md", t2, 700, "hash-g"))
	newSnap.Update("/vault/h.md", makeInfo("/vault/h.md", t2, 800, "hash-h"))

	diff := oldSnap.Compare(newSnap)

	expectedAdded := []string{"/vault/f.md", "/vault/g.md", "/vault/h.md"}
	if !strSlicesEqual(diff.Added, expectedAdded) {
		t.Fatalf("Added mismatch:\n  expected: %v\n  got:      %v", expectedAdded, diff.Added)
	}
	if len(diff.Added) != 3 {
		t.Fatalf("Added count should be 3, got %d", len(diff.Added))
	}

	expectedModified := []string{"/vault/b.md", "/vault/c.md"}
	if !strSlicesEqual(diff.Modified, expectedModified) {
		t.Fatalf("Modified mismatch:\n  expected: %v\n  got:      %v", expectedModified, diff.Modified)
	}
	if len(diff.Modified) != 2 {
		t.Fatalf("Modified count should be 2, got %d", len(diff.Modified))
	}

	expectedDeleted := []string{"/vault/e.md"}
	if !strSlicesEqual(diff.Deleted, expectedDeleted) {
		t.Fatalf("Deleted mismatch:\n  expected: %v\n  got:      %v", expectedDeleted, diff.Deleted)
	}
	if len(diff.Deleted) != 1 {
		t.Fatalf("Deleted count should be 1, got %d", len(diff.Deleted))
	}

	expectedUnchanged := []string{"/vault/a.md", "/vault/d.md"}
	if !strSlicesEqual(diff.Unchanged, expectedUnchanged) {
		t.Fatalf("Unchanged mismatch:\n  expected: %v\n  got:      %v", expectedUnchanged, diff.Unchanged)
	}
}

func TestSnapshot_Compare_Empty(t *testing.T) {
	s1 := NewFileSnapshot("/vault")
	s2 := NewFileSnapshot("/vault")

	diff := s1.Compare(s2)

	if len(diff.Added) != 0 {
		t.Fatalf("Added should be empty, got %v", diff.Added)
	}
	if len(diff.Modified) != 0 {
		t.Fatalf("Modified should be empty, got %v", diff.Modified)
	}
	if len(diff.Deleted) != 0 {
		t.Fatalf("Deleted should be empty, got %v", diff.Deleted)
	}
	if len(diff.Unchanged) != 0 {
		t.Fatalf("Unchanged should be empty, got %v", diff.Unchanged)
	}
}

func TestSnapshot_MergeChanges(t *testing.T) {
	base := NewFileSnapshot("/vault")
	t1 := time.Now()
	t2 := t1.Add(1 * time.Hour)

	base.Update("/vault/a.md", makeInfo("/vault/a.md", t1, 100, "hash-a"))
	base.Update("/vault/b.md", makeInfo("/vault/b.md", t1, 200, "hash-b"))
	base.Update("/vault/c.md", makeInfo("/vault/c.md", t1, 300, "hash-c"))

	diff := &SnapshotDiff{
		Added:    []string{"/vault/d.md"},
		Modified: []string{"/vault/b.md"},
		Deleted:  []string{"/vault/c.md"},
	}

	provider := func(path string) FileInfo {
		switch path {
		case "/vault/d.md":
			return makeInfo("/vault/d.md", t2, 400, "hash-d-new")
		case "/vault/b.md":
			return makeInfo("/vault/b.md", t2, 222, "hash-b-modified")
		default:
			return FileInfo{}
		}
	}

	merged := base.MergeChanges(diff, provider)

	if base.Count() != 3 {
		t.Fatalf("base should be unchanged (3 files), got %d", base.Count())
	}

	if merged.Count() != 3 {
		t.Fatalf("merged should have 3 files, got %d", merged.Count())
	}

	if !merged.Contains("/vault/a.md") {
		t.Fatal("merged should contain a.md (unchanged)")
	}

	if !merged.Contains("/vault/d.md") {
		t.Fatal("merged should contain d.md (added)")
	}

	infoD, _ := merged.Get("/vault/d.md")
	if infoD.Hash != "hash-d-new" || infoD.Size != 400 {
		t.Fatalf("d.md has wrong info: %+v", infoD)
	}

	infoB, _ := merged.Get("/vault/b.md")
	if infoB.Hash != "hash-b-modified" || infoB.Size != 222 {
		t.Fatalf("b.md should be modified, got: %+v", infoB)
	}

	if merged.Contains("/vault/c.md") {
		t.Fatal("merged should NOT contain c.md (deleted)")
	}
}

func TestSnapshot_MarshalRoundtrip(t *testing.T) {
	orig := NewFileSnapshot("/test/vault")
	t1 := time.Now()

	orig.Update("/test/vault/note1.md", makeInfo("/test/vault/note1.md", t1, 100, "hash-n1"))
	orig.Update("/test/vault/sub/note2.md", makeInfo("/test/vault/sub/note2.md", t1, 200, "hash-n2"))
	orig.Update("/test/vault/note3.md", makeInfo("/test/vault/note3.md", t1, 300, "hash-n3"))

	data, err := orig.Marshal()
	if err != nil {
		t.Fatalf("Marshal failed: %v", err)
	}

	restored, err := UnmarshalSnapshot(data)
	if err != nil {
		t.Fatalf("UnmarshalSnapshot failed: %v", err)
	}

	if restored.VaultPath != orig.VaultPath {
		t.Fatalf("VaultPath mismatch: %s vs %s", restored.VaultPath, orig.VaultPath)
	}

	if restored.Count() != orig.Count() {
		t.Fatalf("Count mismatch: %d vs %d", restored.Count(), orig.Count())
	}

	for _, p := range orig.ListAll() {
		if !restored.Contains(p) {
			t.Fatalf("restored missing path: %s", p)
		}
		origInfo, _ := orig.Get(p)
		restInfo, _ := restored.Get(p)
		if origInfo.Hash != restInfo.Hash {
			t.Fatalf("hash mismatch for %s: %s vs %s", p, origInfo.Hash, restInfo.Hash)
		}
		if origInfo.Size != restInfo.Size {
			t.Fatalf("size mismatch for %s: %d vs %d", p, origInfo.Size, restInfo.Size)
		}
		if !origInfo.ModTime.Equal(restInfo.ModTime) {
			t.Fatalf("modtime mismatch for %s: %v vs %v", p, origInfo.ModTime, restInfo.ModTime)
		}
	}
}

func TestSnapshot_ConcurrentSafe(t *testing.T) {
	s := NewFileSnapshot("/concurrent")
	t1 := time.Now()

	var wg sync.WaitGroup
	goroutines := 100
	perGoroutine := 50

	panicked := make(chan string, goroutines*2)

	for g := 0; g < goroutines; g++ {
		wg.Add(1)
		go func(gid int) {
			defer func() {
				if r := recover(); r != nil {
					panicked <- "panic detected"
				}
				wg.Done()
			}()

			for i := 0; i < perGoroutine; i++ {
				path := "/concurrent/" + string(rune('a'+gid%26)) + "_" + string(rune('a'+i%26)) + ".md"
				info := makeInfo(path, t1, int64(gid*1000+i), "hash-"+path)
				s.Update(path, info)

				s.Contains(path)
				s.Get(path)
				s.Count()
				_ = s.ListAll()

				if i%7 == 0 {
					s.Remove(path)
				}
			}
		}(g)
	}

	wg.Wait()

	select {
	case msg := <-panicked:
		t.Fatalf("Concurrent safety failed: %s", msg)
	default:
	}

	_ = s.Count()
	_ = s.ListAll()
}
