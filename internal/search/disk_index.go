package search

import (
	"bytes"
	"container/list"
	"encoding/binary"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
)

const (
	walThreshold   = 1024 * 1024
	cacheCapacity  = 10000
	termsFileName  = "terms.dat"
	postingsFileName = "postings.dat"
	walFileName    = "wal.dat"
	docFileName    = "docs.dat"

	walOpUpsert = byte(1)
	walOpDelete = byte(2)
)

type TermIndexEntry struct {
	Offset  int64
	Length  int32
	DocFreq int32
}

type MemNotePosting struct {
	TermData map[string]MemTermPosting
}

type MemTermPosting struct {
	Frequency int
	Positions []int
}

type PostingRecord struct {
	NoteID    uint
	Frequency int
	Positions []int
}

type cacheEntry struct {
	term     string
	postings []PostingRecord
}

type LRUCache struct {
	capacity int
	items    map[string]*list.Element
	order    list.List
	mu       sync.Mutex
}

func NewLRUCache(capacity int) *LRUCache {
	return &LRUCache{
		capacity: capacity,
		items:    make(map[string]*list.Element),
	}
}

func (c *LRUCache) Get(term string) ([]PostingRecord, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	elem, ok := c.items[term]
	if !ok {
		return nil, false
	}
	c.order.MoveToBack(elem)
	return elem.Value.(*cacheEntry).postings, true
}

func (c *LRUCache) Put(term string, postings []PostingRecord) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, ok := c.items[term]; ok {
		c.order.MoveToBack(elem)
		elem.Value.(*cacheEntry).postings = postings
		return
	}

	if c.order.Len() >= c.capacity {
		front := c.order.Front()
		if front != nil {
			oldEntry := front.Value.(*cacheEntry)
			delete(c.items, oldEntry.term)
			c.order.Remove(front)
		}
	}

	entry := &cacheEntry{term: term, postings: postings}
	elem := c.order.PushBack(entry)
	c.items[term] = elem
}

func (c *LRUCache) Delete(term string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, ok := c.items[term]; ok {
		delete(c.items, term)
		c.order.Remove(elem)
	}
}

func (c *LRUCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.items = make(map[string]*list.Element)
	c.order.Init()
}

type DiskInvertedIndex struct {
	basePath string

	termsFile    *os.File
	postingsFile *os.File
	walFile      *os.File
	docFile      *os.File

	memTable     map[uint]*MemNotePosting
	memTableSize int64
	deletedNotes map[uint]bool
	termsIndex   map[string]TermIndexEntry
	cache        *LRUCache
	docLengths   map[uint]int
	totalDocs    int

	mu    sync.RWMutex
	dirty bool
}

func NewDiskInvertedIndex(basePath string) (*DiskInvertedIndex, error) {
	if err := os.MkdirAll(basePath, 0755); err != nil {
		return nil, fmt.Errorf("mkdir base path: %w", err)
	}

	dii := &DiskInvertedIndex{
		basePath:     basePath,
		memTable:     make(map[uint]*MemNotePosting),
		deletedNotes: make(map[uint]bool),
		termsIndex:   make(map[string]TermIndexEntry),
		cache:        NewLRUCache(cacheCapacity),
		docLengths:   make(map[uint]int),
	}

	var err error
	dii.termsFile, err = os.OpenFile(filepath.Join(basePath, termsFileName), os.O_RDWR|os.O_CREATE, 0644)
	if err != nil {
		return nil, fmt.Errorf("open terms file: %w", err)
	}

	dii.postingsFile, err = os.OpenFile(filepath.Join(basePath, postingsFileName), os.O_RDWR|os.O_CREATE, 0644)
	if err != nil {
		dii.Close()
		return nil, fmt.Errorf("open postings file: %w", err)
	}

	dii.walFile, err = os.OpenFile(filepath.Join(basePath, walFileName), os.O_RDWR|os.O_CREATE|os.O_APPEND, 0644)
	if err != nil {
		dii.Close()
		return nil, fmt.Errorf("open wal file: %w", err)
	}

	dii.docFile, err = os.OpenFile(filepath.Join(basePath, docFileName), os.O_RDWR|os.O_CREATE, 0644)
	if err != nil {
		dii.Close()
		return nil, fmt.Errorf("open docs file: %w", err)
	}

	if err := dii.loadTermsIndex(); err != nil {
		dii.Close()
		return nil, fmt.Errorf("load terms index: %w", err)
	}

	if err := dii.loadDocLengths(); err != nil {
		dii.Close()
		return nil, fmt.Errorf("load doc lengths: %w", err)
	}

	if err := dii.replayWAL(); err != nil {
		dii.Close()
		return nil, fmt.Errorf("replay wal: %w", err)
	}

	walInfo, _ := dii.walFile.Stat()
	if walInfo != nil && walInfo.Size() >= walThreshold {
		if err := dii.compact(); err != nil {
			dii.Close()
			return nil, fmt.Errorf("initial compact: %w", err)
		}
	}

	return dii, nil
}

func (dii *DiskInvertedIndex) loadTermsIndex() error {
	info, err := dii.termsFile.Stat()
	if err != nil {
		return err
	}
	if info.Size() == 0 {
		return nil
	}

	if _, err := dii.termsFile.Seek(0, io.SeekStart); err != nil {
		return err
	}

	data, err := io.ReadAll(dii.termsFile)
	if err != nil {
		return err
	}

	buf := bytes.NewReader(data)
	for buf.Len() > 0 {
		var termLen int32
		if err := binary.Read(buf, binary.LittleEndian, &termLen); err != nil {
			if err == io.EOF {
				break
			}
			return err
		}

		termBytes := make([]byte, termLen)
		if _, err := buf.Read(termBytes); err != nil {
			return err
		}
		term := string(termBytes)

		var entry TermIndexEntry
		if err := binary.Read(buf, binary.LittleEndian, &entry.Offset); err != nil {
			return err
		}
		if err := binary.Read(buf, binary.LittleEndian, &entry.Length); err != nil {
			return err
		}
		if err := binary.Read(buf, binary.LittleEndian, &entry.DocFreq); err != nil {
			return err
		}

		dii.termsIndex[term] = entry
	}

	return nil
}

func (dii *DiskInvertedIndex) saveTermsIndex() error {
	if _, err := dii.termsFile.Seek(0, io.SeekStart); err != nil {
		return err
	}
	if err := dii.termsFile.Truncate(0); err != nil {
		return err
	}

	terms := make([]string, 0, len(dii.termsIndex))
	for t := range dii.termsIndex {
		terms = append(terms, t)
	}
	sort.Strings(terms)

	var buf bytes.Buffer
	for _, term := range terms {
		entry := dii.termsIndex[term]
		termBytes := []byte(term)
		termLen := int32(len(termBytes))

		if err := binary.Write(&buf, binary.LittleEndian, termLen); err != nil {
			return err
		}
		if _, err := buf.Write(termBytes); err != nil {
			return err
		}
		if err := binary.Write(&buf, binary.LittleEndian, entry.Offset); err != nil {
			return err
		}
		if err := binary.Write(&buf, binary.LittleEndian, entry.Length); err != nil {
			return err
		}
		if err := binary.Write(&buf, binary.LittleEndian, entry.DocFreq); err != nil {
			return err
		}
	}

	_, err := dii.termsFile.Write(buf.Bytes())
	return err
}

func (dii *DiskInvertedIndex) loadDocLengths() error {
	info, err := dii.docFile.Stat()
	if err != nil {
		return err
	}
	if info.Size() == 0 {
		return nil
	}

	if _, err := dii.docFile.Seek(0, io.SeekStart); err != nil {
		return err
	}

	data, err := io.ReadAll(dii.docFile)
	if err != nil {
		return err
	}

	buf := bytes.NewReader(data)
	dii.totalDocs = 0
	for buf.Len() > 0 {
		var noteID uint32
		var docLen int32
		if err := binary.Read(buf, binary.LittleEndian, &noteID); err != nil {
			if err == io.EOF {
				break
			}
			return err
		}
		if err := binary.Read(buf, binary.LittleEndian, &docLen); err != nil {
			return err
		}
		dii.docLengths[uint(noteID)] = int(docLen)
		dii.totalDocs++
	}

	return nil
}

func (dii *DiskInvertedIndex) saveDocLengths() error {
	if _, err := dii.docFile.Seek(0, io.SeekStart); err != nil {
		return err
	}
	if err := dii.docFile.Truncate(0); err != nil {
		return err
	}

	var buf bytes.Buffer
	ids := make([]uint, 0, len(dii.docLengths))
	for id := range dii.docLengths {
		ids = append(ids, id)
	}
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })

	dii.totalDocs = 0
	for _, id := range ids {
		if dii.deletedNotes[id] {
			continue
		}
		if err := binary.Write(&buf, binary.LittleEndian, uint32(id)); err != nil {
			return err
		}
		if err := binary.Write(&buf, binary.LittleEndian, int32(dii.docLengths[id])); err != nil {
			return err
		}
		dii.totalDocs++
	}

	_, err := dii.docFile.Write(buf.Bytes())
	return err
}

func (dii *DiskInvertedIndex) replayWAL() error {
	info, err := dii.walFile.Stat()
	if err != nil {
		return err
	}
	if info.Size() == 0 {
		return nil
	}

	if _, err := dii.walFile.Seek(0, io.SeekStart); err != nil {
		return err
	}

	data, err := io.ReadAll(dii.walFile)
	if err != nil {
		return err
	}

	buf := bytes.NewReader(data)
	for buf.Len() > 0 {
		var op byte
		if err := binary.Read(buf, binary.LittleEndian, &op); err != nil {
			if err == io.EOF {
				break
			}
			return err
		}

		switch op {
		case walOpUpsert:
			if err := dii.replayWALUpsert(buf); err != nil {
				return fmt.Errorf("replay wal upsert: %w", err)
			}
		case walOpDelete:
			if err := dii.replayWALDelete(buf); err != nil {
				return fmt.Errorf("replay wal delete: %w", err)
			}
		default:
			return fmt.Errorf("unknown wal op: %d", op)
		}
	}

	return nil
}

func (dii *DiskInvertedIndex) replayWALUpsert(buf *bytes.Reader) error {
	var noteID uint32
	var docLen int32
	if err := binary.Read(buf, binary.LittleEndian, &noteID); err != nil {
		return err
	}
	if err := binary.Read(buf, binary.LittleEndian, &docLen); err != nil {
		return err
	}

	mp := &MemNotePosting{TermData: make(map[string]MemTermPosting)}

	var termCount int32
	if err := binary.Read(buf, binary.LittleEndian, &termCount); err != nil {
		return err
	}

	for i := int32(0); i < termCount; i++ {
		var termLen int32
		if err := binary.Read(buf, binary.LittleEndian, &termLen); err != nil {
			return err
		}
		termBytes := make([]byte, termLen)
		if _, err := buf.Read(termBytes); err != nil {
			return err
		}
		term := string(termBytes)

		var freq int32
		if err := binary.Read(buf, binary.LittleEndian, &freq); err != nil {
			return err
		}

		var posCount int32
		if err := binary.Read(buf, binary.LittleEndian, &posCount); err != nil {
			return err
		}
		positions := make([]int, posCount)
		for j := int32(0); j < posCount; j++ {
			var pos int32
			if err := binary.Read(buf, binary.LittleEndian, &pos); err != nil {
				return err
			}
			positions[j] = int(pos)
		}

		mp.TermData[term] = MemTermPosting{
			Frequency: int(freq),
			Positions: positions,
		}
	}

	dii.memTable[uint(noteID)] = mp
	delete(dii.deletedNotes, uint(noteID))
	dii.docLengths[uint(noteID)] = int(docLen)

	return nil
}

func (dii *DiskInvertedIndex) replayWALDelete(buf *bytes.Reader) error {
	var noteID uint32
	if err := binary.Read(buf, binary.LittleEndian, &noteID); err != nil {
		return err
	}
	id := uint(noteID)
	delete(dii.memTable, id)
	dii.deletedNotes[id] = true
	return nil
}

func (dii *DiskInvertedIndex) writeWALUpsert(noteID uint, docLen int, mp *MemNotePosting) error {
	var buf bytes.Buffer
	if err := binary.Write(&buf, binary.LittleEndian, walOpUpsert); err != nil {
		return err
	}
	if err := binary.Write(&buf, binary.LittleEndian, uint32(noteID)); err != nil {
		return err
	}
	if err := binary.Write(&buf, binary.LittleEndian, int32(docLen)); err != nil {
		return err
	}

	termCount := int32(len(mp.TermData))
	if err := binary.Write(&buf, binary.LittleEndian, termCount); err != nil {
		return err
	}

	terms := make([]string, 0, len(mp.TermData))
	for t := range mp.TermData {
		terms = append(terms, t)
	}
	sort.Strings(terms)

	for _, term := range terms {
		tp := mp.TermData[term]
		termBytes := []byte(term)
		termLen := int32(len(termBytes))
		if err := binary.Write(&buf, binary.LittleEndian, termLen); err != nil {
			return err
		}
		if _, err := buf.Write(termBytes); err != nil {
			return err
		}
		if err := binary.Write(&buf, binary.LittleEndian, int32(tp.Frequency)); err != nil {
			return err
		}
		posCount := int32(len(tp.Positions))
		if err := binary.Write(&buf, binary.LittleEndian, posCount); err != nil {
			return err
		}
		for _, p := range tp.Positions {
			if err := binary.Write(&buf, binary.LittleEndian, int32(p)); err != nil {
				return err
			}
		}
	}

	n, err := dii.walFile.Write(buf.Bytes())
	dii.memTableSize += int64(n)
	dii.dirty = true
	return err
}

func (dii *DiskInvertedIndex) writeWALDelete(noteID uint) error {
	var buf bytes.Buffer
	if err := binary.Write(&buf, binary.LittleEndian, walOpDelete); err != nil {
		return err
	}
	if err := binary.Write(&buf, binary.LittleEndian, uint32(noteID)); err != nil {
		return err
	}
	n, err := dii.walFile.Write(buf.Bytes())
	dii.memTableSize += int64(n)
	dii.dirty = true
	return err
}

func (dii *DiskInvertedIndex) encodePostings(postings []PostingRecord) []byte {
	var buf bytes.Buffer
	for _, p := range postings {
		binary.Write(&buf, binary.LittleEndian, uint32(p.NoteID))
		binary.Write(&buf, binary.LittleEndian, uint32(p.Frequency))
		binary.Write(&buf, binary.LittleEndian, uint32(len(p.Positions)))
		for _, pos := range p.Positions {
			binary.Write(&buf, binary.LittleEndian, uint32(pos))
		}
	}
	return buf.Bytes()
}

func (dii *DiskInvertedIndex) decodePostings(data []byte) ([]PostingRecord, error) {
	buf := bytes.NewReader(data)
	var postings []PostingRecord

	for buf.Len() > 0 {
		var noteID, freq, posCount uint32
		if err := binary.Read(buf, binary.LittleEndian, &noteID); err != nil {
			if err == io.EOF {
				break
			}
			return nil, err
		}
		if err := binary.Read(buf, binary.LittleEndian, &freq); err != nil {
			return nil, err
		}
		if err := binary.Read(buf, binary.LittleEndian, &posCount); err != nil {
			return nil, err
		}

		positions := make([]int, posCount)
		for j := uint32(0); j < posCount; j++ {
			var pos uint32
			if err := binary.Read(buf, binary.LittleEndian, &pos); err != nil {
				return nil, err
			}
			positions[j] = int(pos)
		}

		postings = append(postings, PostingRecord{
			NoteID:    uint(noteID),
			Frequency: int(freq),
			Positions: positions,
		})
	}

	return postings, nil
}

func (dii *DiskInvertedIndex) IndexNote(noteID uint, termPostings map[string]MemTermPosting) error {
	dii.mu.Lock()
	defer dii.mu.Unlock()

	docLen := 0
	for _, tp := range termPostings {
		docLen += tp.Frequency
	}

	mp := &MemNotePosting{TermData: make(map[string]MemTermPosting)}
	for t, tp := range termPostings {
		mp.TermData[t] = MemTermPosting{
			Frequency: tp.Frequency,
			Positions: append([]int{}, tp.Positions...),
		}
	}

	if err := dii.writeWALUpsert(noteID, docLen, mp); err != nil {
		return fmt.Errorf("write wal: %w", err)
	}

	dii.memTable[noteID] = mp
	delete(dii.deletedNotes, noteID)
	dii.docLengths[noteID] = docLen

	for t := range termPostings {
		dii.cache.Delete(t)
	}

	if dii.memTableSize >= walThreshold {
		if err := dii.compact(); err != nil {
			return fmt.Errorf("compact: %w", err)
		}
	}

	return nil
}

func (dii *DiskInvertedIndex) DeleteNote(noteID uint) error {
	dii.mu.Lock()
	defer dii.mu.Unlock()

	if err := dii.writeWALDelete(noteID); err != nil {
		return fmt.Errorf("write wal: %w", err)
	}

	delete(dii.memTable, noteID)
	dii.deletedNotes[noteID] = true

	dii.cache.Clear()

	if dii.memTableSize >= walThreshold {
		if err := dii.compact(); err != nil {
			return fmt.Errorf("compact: %w", err)
		}
	}

	return nil
}

func (dii *DiskInvertedIndex) GetPostings(term string) ([]PostingRecord, error) {
	lowerTerm := strings.ToLower(term)

	dii.mu.RLock()
	if postings, ok := dii.cache.Get(lowerTerm); ok {
		result := make([]PostingRecord, 0, len(postings))
		for _, p := range postings {
			if !dii.deletedNotes[p.NoteID] {
				result = append(result, p)
			}
		}
		dii.mu.RUnlock()
		return result, nil
	}

	memPostings := make(map[uint]PostingRecord)
	for noteID, mp := range dii.memTable {
		if tp, ok := mp.TermData[lowerTerm]; ok {
			memPostings[noteID] = PostingRecord{
				NoteID:    noteID,
				Frequency: tp.Frequency,
				Positions: tp.Positions,
			}
		}
	}
	dii.mu.RUnlock()

	var diskPostings []PostingRecord
	entry, hasDisk := func() (TermIndexEntry, bool) {
		dii.mu.RLock()
		defer dii.mu.RUnlock()
		e, ok := dii.termsIndex[lowerTerm]
		return e, ok
	}()

	if hasDisk {
		data := make([]byte, entry.Length)
		dii.mu.RLock()
		_, readErr := dii.postingsFile.ReadAt(data, entry.Offset)
		dii.mu.RUnlock()
		if readErr != nil {
			return nil, fmt.Errorf("read postings: %w", readErr)
		}

		var decodeErr error
		diskPostings, decodeErr = dii.decodePostings(data)
		if decodeErr != nil {
			return nil, fmt.Errorf("decode postings: %w", decodeErr)
		}
	}

	combined := make(map[uint]PostingRecord)
	for _, p := range diskPostings {
		combined[p.NoteID] = p
	}
	for noteID, p := range memPostings {
		combined[noteID] = p
	}

	result := make([]PostingRecord, 0, len(combined))
	dii.mu.RLock()
	for _, p := range combined {
		if !dii.deletedNotes[p.NoteID] {
			result = append(result, p)
		}
	}
	dii.mu.RUnlock()

	sort.Slice(result, func(i, j int) bool { return result[i].NoteID < result[j].NoteID })

	dii.mu.Lock()
	dii.cache.Put(lowerTerm, result)
	dii.mu.Unlock()

	return result, nil
}

func (dii *DiskInvertedIndex) GetDocLength(noteID uint) int {
	dii.mu.RLock()
	defer dii.mu.RUnlock()
	return dii.docLengths[noteID]
}

func (dii *DiskInvertedIndex) GetDocLengths() (map[uint]int, error) {
	dii.mu.RLock()
	defer dii.mu.RUnlock()

	result := make(map[uint]int, len(dii.docLengths))
	for id, l := range dii.docLengths {
		if !dii.deletedNotes[id] {
			result[id] = l
		}
	}
	return result, nil
}

func (dii *DiskInvertedIndex) GetTotalDocCount() int {
	dii.mu.RLock()
	defer dii.mu.RUnlock()

	count := 0
	for id := range dii.docLengths {
		if !dii.deletedNotes[id] {
			count++
		}
	}
	return count
}

func (dii *DiskInvertedIndex) GetDocFrequency(term string) (int, error) {
	postings, err := dii.GetPostings(term)
	if err != nil {
		return 0, err
	}
	return len(postings), nil
}

func (dii *DiskInvertedIndex) GetAllTerms() ([]string, error) {
	dii.mu.RLock()
	defer dii.mu.RUnlock()

	termSet := make(map[string]bool)
	for t := range dii.termsIndex {
		termSet[t] = true
	}
	for _, mp := range dii.memTable {
		for t := range mp.TermData {
			termSet[t] = true
		}
	}

	result := make([]string, 0, len(termSet))
	for t := range termSet {
		result = append(result, t)
	}
	sort.Strings(result)
	return result, nil
}

func (dii *DiskInvertedIndex) GetNoteTerms(noteID uint) (map[string]int, error) {
	dii.mu.RLock()

	if dii.deletedNotes[noteID] {
		dii.mu.RUnlock()
		return make(map[string]int), nil
	}

	if mp, ok := dii.memTable[noteID]; ok {
		result := make(map[string]int, len(mp.TermData))
		for t, tp := range mp.TermData {
			result[t] = tp.Frequency
		}
		dii.mu.RUnlock()
		return result, nil
	}

	termEntries := make(map[string]TermIndexEntry)
	for t, e := range dii.termsIndex {
		termEntries[t] = e
	}
	postingsFile := dii.postingsFile
	dii.mu.RUnlock()

	result := make(map[string]int)
	for term, entry := range termEntries {
		data := make([]byte, entry.Length)
		_, err := postingsFile.ReadAt(data, entry.Offset)
		if err != nil {
			continue
		}

		postings, err := dii.decodePostings(data)
		if err != nil {
			continue
		}

		for _, p := range postings {
			if p.NoteID == noteID {
				result[term] = p.Frequency
				break
			}
		}
	}

	return result, nil
}

func (dii *DiskInvertedIndex) Flush() error {
	dii.mu.Lock()
	defer dii.mu.Unlock()
	return dii.compact()
}

func (dii *DiskInvertedIndex) Close() error {
	dii.mu.Lock()
	defer dii.mu.Unlock()

	if dii.dirty {
		_ = dii.compact()
	}

	var errs []error
	if dii.termsFile != nil {
		if err := dii.termsFile.Close(); err != nil {
			errs = append(errs, err)
		}
	}
	if dii.postingsFile != nil {
		if err := dii.postingsFile.Close(); err != nil {
			errs = append(errs, err)
		}
	}
	if dii.walFile != nil {
		if err := dii.walFile.Close(); err != nil {
			errs = append(errs, err)
		}
	}
	if dii.docFile != nil {
		if err := dii.docFile.Close(); err != nil {
			errs = append(errs, err)
		}
	}

	if len(errs) > 0 {
		return fmt.Errorf("close errors: %v", errs)
	}
	return nil
}

func (dii *DiskInvertedIndex) compact() error {
	termPostings := make(map[string][]PostingRecord)

	for term, entry := range dii.termsIndex {
		data := make([]byte, entry.Length)
		if _, err := dii.postingsFile.ReadAt(data, entry.Offset); err != nil {
			continue
		}
		postings, err := dii.decodePostings(data)
		if err != nil {
			continue
		}
		for _, p := range postings {
			if !dii.deletedNotes[p.NoteID] {
				if _, ok := dii.memTable[p.NoteID]; !ok {
					termPostings[term] = append(termPostings[term], p)
				}
			}
		}
	}

	for noteID, mp := range dii.memTable {
		if dii.deletedNotes[noteID] {
			continue
		}
		for term, tp := range mp.TermData {
			termPostings[term] = append(termPostings[term], PostingRecord{
				NoteID:    noteID,
				Frequency: tp.Frequency,
				Positions: tp.Positions,
			})
		}
	}

	if _, err := dii.postingsFile.Seek(0, io.SeekStart); err != nil {
		return err
	}
	if err := dii.postingsFile.Truncate(0); err != nil {
		return err
	}

	dii.termsIndex = make(map[string]TermIndexEntry)

	terms := make([]string, 0, len(termPostings))
	for t := range termPostings {
		terms = append(terms, t)
	}
	sort.Strings(terms)

	var offset int64
	for _, term := range terms {
		postings := termPostings[term]
		sort.Slice(postings, func(i, j int) bool { return postings[i].NoteID < postings[j].NoteID })

		filtered := postings[:0]
		for _, p := range postings {
			if !dii.deletedNotes[p.NoteID] {
				filtered = append(filtered, p)
			}
		}
		postings = filtered

		if len(postings) == 0 {
			continue
		}

		data := dii.encodePostings(postings)
		n, err := dii.postingsFile.Write(data)
		if err != nil {
			return fmt.Errorf("write postings: %w", err)
		}

		dii.termsIndex[term] = TermIndexEntry{
			Offset:  offset,
			Length:  int32(n),
			DocFreq: int32(len(postings)),
		}
		offset += int64(n)
	}

	if err := dii.saveTermsIndex(); err != nil {
		return fmt.Errorf("save terms index: %w", err)
	}

	if err := dii.saveDocLengths(); err != nil {
		return fmt.Errorf("save doc lengths: %w", err)
	}

	if _, err := dii.walFile.Seek(0, io.SeekStart); err != nil {
		return err
	}
	if err := dii.walFile.Truncate(0); err != nil {
		return err
	}

	dii.memTable = make(map[uint]*MemNotePosting)
	dii.deletedNotes = make(map[uint]bool)
	dii.memTableSize = 0
	dii.cache.Clear()
	dii.dirty = false

	return nil
}
