package cache

import (
	"container/list"
	"fmt"
	"regexp"
	"sync"
	"time"

	"taskflow/pkg/models"
)

type CacheManager interface {
	Get(key string) (interface{}, bool)
	Set(key string, value interface{}, ttl time.Duration)
	SetWithTags(key string, value interface{}, ttl time.Duration, tags []string)
	Delete(key string) bool
	InvalidateTag(tag string)
	InvalidatePattern(pattern string)
	Clear()
	GetStats() models.CacheStats
}

type lruEntry struct {
	key       string
	value     interface{}
	expiresAt time.Time
	tags      []string
	hits      int
}

type LRUCache struct {
	maxSize    int
	defaultTTL time.Duration
	items      map[string]*list.Element
	order      *list.List
	tagIndex   map[string]map[*list.Element]bool
	totalHits  int
	mu         sync.RWMutex
	strategy   models.CacheStrategy
}

func NewLRUCache(maxSize int, defaultTTL time.Duration) *LRUCache {
	return &LRUCache{
		maxSize:    maxSize,
		defaultTTL: defaultTTL,
		items:      make(map[string]*list.Element),
		order:      list.New(),
		tagIndex:   make(map[string]map[*list.Element]bool),
		strategy:   models.CacheStrategyLRU,
	}
}

func (c *LRUCache) Get(key string) (interface{}, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	elem, exists := c.items[key]
	if !exists {
		return nil, false
	}

	entry := elem.Value.(*lruEntry)
	if !entry.expiresAt.IsZero() && time.Now().After(entry.expiresAt) {
		c.removeElement(elem)
		return nil, false
	}

	c.order.MoveToFront(elem)
	entry.hits++
	c.totalHits++

	return entry.value, true
}

func (c *LRUCache) Set(key string, value interface{}, ttl time.Duration) {
	c.SetWithTags(key, value, ttl, nil)
}

func (c *LRUCache) SetWithTags(key string, value interface{}, ttl time.Duration, tags []string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.items[key]; exists {
		c.removeFromTagIndex(elem)
		entry := elem.Value.(*lruEntry)
		entry.value = value
		entry.expiresAt = c.calculateExpiry(ttl)
		entry.tags = tags
		c.order.MoveToFront(elem)
		c.addToTagIndex(elem, tags)
		return
	}

	if c.order.Len() >= c.maxSize {
		c.evictOldest()
	}

	entry := &lruEntry{
		key:       key,
		value:     value,
		expiresAt: c.calculateExpiry(ttl),
		tags:      tags,
	}
	elem := c.order.PushFront(entry)
	c.items[key] = elem
	c.addToTagIndex(elem, tags)
}

func (c *LRUCache) Delete(key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	elem, exists := c.items[key]
	if !exists {
		return false
	}

	c.removeElement(elem)
	return true
}

func (c *LRUCache) InvalidateTag(tag string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elems, exists := c.tagIndex[tag]; exists {
		for elem := range elems {
			c.removeElement(elem)
		}
		delete(c.tagIndex, tag)
	}
}

func (c *LRUCache) InvalidatePattern(pattern string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	re, err := regexp.Compile(pattern)
	if err != nil {
		return
	}

	var toRemove []*list.Element
	for key, elem := range c.items {
		if re.MatchString(key) {
			toRemove = append(toRemove, elem)
		}
	}

	for _, elem := range toRemove {
		c.removeElement(elem)
	}
}

func (c *LRUCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.items = make(map[string]*list.Element)
	c.order.Init()
	c.tagIndex = make(map[string]map[*list.Element]bool)
	c.totalHits = 0
}

func (c *LRUCache) GetStats() models.CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return models.CacheStats{
		Size:       len(c.items),
		MaxSize:    c.maxSize,
		Strategy:   c.strategy,
		DefaultTTL: int(c.defaultTTL.Seconds()),
		TotalHits:  c.totalHits,
		TagsCount:  len(c.tagIndex),
	}
}

func (c *LRUCache) calculateExpiry(ttl time.Duration) time.Time {
	if ttl <= 0 {
		ttl = c.defaultTTL
	}
	if ttl <= 0 {
		return time.Time{}
	}
	return time.Now().Add(ttl)
}

func (c *LRUCache) evictOldest() {
	elem := c.order.Back()
	if elem != nil {
		c.removeElement(elem)
	}
}

func (c *LRUCache) removeElement(elem *list.Element) {
	entry := elem.Value.(*lruEntry)
	c.removeFromTagIndex(elem)
	c.order.Remove(elem)
	delete(c.items, entry.key)
}

func (c *LRUCache) addToTagIndex(elem *list.Element, tags []string) {
	for _, tag := range tags {
		if _, exists := c.tagIndex[tag]; !exists {
			c.tagIndex[tag] = make(map[*list.Element]bool)
		}
		c.tagIndex[tag][elem] = true
	}
}

func (c *LRUCache) removeFromTagIndex(elem *list.Element) {
	entry := elem.Value.(*lruEntry)
	for _, tag := range entry.tags {
		if elems, exists := c.tagIndex[tag]; exists {
			delete(elems, elem)
			if len(elems) == 0 {
				delete(c.tagIndex, tag)
			}
		}
	}
}

type LFUNode struct {
	key       string
	value     interface{}
	freq      int
	expiresAt time.Time
	tags      []string
}

type LFUCache struct {
	maxSize    int
	defaultTTL time.Duration
	items      map[string]*LFUNode
	freqMap    map[int]*list.List
	minFreq    int
	tagIndex   map[string]map[string]bool
	totalHits  int
	mu         sync.RWMutex
	strategy   models.CacheStrategy
}

func NewLFUCache(maxSize int, defaultTTL time.Duration) *LFUCache {
	return &LFUCache{
		maxSize:    maxSize,
		defaultTTL: defaultTTL,
		items:      make(map[string]*LFUNode),
		freqMap:    make(map[int]*list.List),
		tagIndex:   make(map[string]map[string]bool),
		strategy:   models.CacheStrategyLFU,
	}
}

func (c *LFUCache) Get(key string) (interface{}, bool) {
	c.mu.Lock()
	defer c.mu.Unlock()

	node, exists := c.items[key]
	if !exists {
		return nil, false
	}

	if !node.expiresAt.IsZero() && time.Now().After(node.expiresAt) {
		c.removeNode(node)
		return nil, false
	}

	c.incrementFrequency(node)
	c.totalHits++

	return node.value, true
}

func (c *LFUCache) Set(key string, value interface{}, ttl time.Duration) {
	c.SetWithTags(key, value, ttl, nil)
}

func (c *LFUCache) SetWithTags(key string, value interface{}, ttl time.Duration, tags []string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if node, exists := c.items[key]; exists {
		c.removeFromTagIndex(node)
		node.value = value
		node.expiresAt = c.calculateExpiry(ttl)
		node.tags = tags
		c.incrementFrequency(node)
		c.addToTagIndex(node, tags)
		return
	}

	if len(c.items) >= c.maxSize {
		c.evictLeastFrequent()
	}

	node := &LFUNode{
		key:       key,
		value:     value,
		freq:      1,
		expiresAt: c.calculateExpiry(ttl),
		tags:      tags,
	}
	c.items[key] = node
	c.addToFreqList(node)
	c.addToTagIndex(node, tags)
	c.minFreq = 1
}

func (c *LFUCache) Delete(key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	node, exists := c.items[key]
	if !exists {
		return false
	}

	c.removeNode(node)
	return true
}

func (c *LFUCache) InvalidateTag(tag string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if keys, exists := c.tagIndex[tag]; exists {
		for key := range keys {
			if node, exists := c.items[key]; exists {
				c.removeNode(node)
			}
		}
		delete(c.tagIndex, tag)
	}
}

func (c *LFUCache) InvalidatePattern(pattern string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	re, err := regexp.Compile(pattern)
	if err != nil {
		return
	}

	var toRemove []*LFUNode
	for key, node := range c.items {
		if re.MatchString(key) {
			toRemove = append(toRemove, node)
		}
	}

	for _, node := range toRemove {
		c.removeNode(node)
	}
}

func (c *LFUCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.items = make(map[string]*LFUNode)
	c.freqMap = make(map[int]*list.List)
	c.tagIndex = make(map[string]map[string]bool)
	c.totalHits = 0
	c.minFreq = 0
}

func (c *LFUCache) GetStats() models.CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return models.CacheStats{
		Size:       len(c.items),
		MaxSize:    c.maxSize,
		Strategy:   c.strategy,
		DefaultTTL: int(c.defaultTTL.Seconds()),
		TotalHits:  c.totalHits,
		TagsCount:  len(c.tagIndex),
	}
}

func (c *LFUCache) calculateExpiry(ttl time.Duration) time.Time {
	if ttl <= 0 {
		ttl = c.defaultTTL
	}
	if ttl <= 0 {
		return time.Time{}
	}
	return time.Now().Add(ttl)
}

func (c *LFUCache) incrementFrequency(node *LFUNode) {
	freqList := c.freqMap[node.freq]
	if freqList != nil {
		for e := freqList.Front(); e != nil; e = e.Next() {
			if e.Value.(*LFUNode) == node {
				freqList.Remove(e)
				break
			}
		}
		if freqList.Len() == 0 {
			delete(c.freqMap, node.freq)
			if node.freq == c.minFreq {
				c.minFreq++
			}
		}
	}

	node.freq++
	c.addToFreqList(node)
}

func (c *LFUCache) addToFreqList(node *LFUNode) {
	if _, exists := c.freqMap[node.freq]; !exists {
		c.freqMap[node.freq] = list.New()
	}
	c.freqMap[node.freq].PushFront(node)
}

func (c *LFUCache) evictLeastFrequent() {
	freqList, exists := c.freqMap[c.minFreq]
	if !exists || freqList.Len() == 0 {
		return
	}

	elem := freqList.Back()
	if elem != nil {
		node := elem.Value.(*LFUNode)
		c.removeNode(node)
	}
}

func (c *LFUCache) removeNode(node *LFUNode) {
	c.removeFromTagIndex(node)
	freqList := c.freqMap[node.freq]
	if freqList != nil {
		for e := freqList.Front(); e != nil; e = e.Next() {
			if e.Value.(*LFUNode) == node {
				freqList.Remove(e)
				break
			}
		}
		if freqList.Len() == 0 {
			delete(c.freqMap, node.freq)
			if node.freq == c.minFreq && len(c.items) > 1 {
				c.minFreq++
			}
		}
	}
	delete(c.items, node.key)
}

func (c *LFUCache) addToTagIndex(node *LFUNode, tags []string) {
	for _, tag := range tags {
		if _, exists := c.tagIndex[tag]; !exists {
			c.tagIndex[tag] = make(map[string]bool)
		}
		c.tagIndex[tag][node.key] = true
	}
}

func (c *LFUCache) removeFromTagIndex(node *LFUNode) {
	for _, tag := range node.tags {
		if keys, exists := c.tagIndex[tag]; exists {
			delete(keys, node.key)
			if len(keys) == 0 {
				delete(c.tagIndex, tag)
			}
		}
	}
}

type FIFOCache struct {
	maxSize    int
	defaultTTL time.Duration
	items      map[string]*list.Element
	order      *list.List
	tagIndex   map[string]map[*list.Element]bool
	totalHits  int
	mu         sync.RWMutex
	strategy   models.CacheStrategy
}

func NewFIFOCache(maxSize int, defaultTTL time.Duration) *FIFOCache {
	return &FIFOCache{
		maxSize:    maxSize,
		defaultTTL: defaultTTL,
		items:      make(map[string]*list.Element),
		order:      list.New(),
		tagIndex:   make(map[string]map[*list.Element]bool),
		strategy:   models.CacheStrategyFIFO,
	}
}

func (c *FIFOCache) Get(key string) (interface{}, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()

	elem, exists := c.items[key]
	if !exists {
		return nil, false
	}

	entry := elem.Value.(*lruEntry)
	if !entry.expiresAt.IsZero() && time.Now().After(entry.expiresAt) {
		return nil, false
	}

	entry.hits++
	c.totalHits++

	return entry.value, true
}

func (c *FIFOCache) Set(key string, value interface{}, ttl time.Duration) {
	c.SetWithTags(key, value, ttl, nil)
}

func (c *FIFOCache) SetWithTags(key string, value interface{}, ttl time.Duration, tags []string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elem, exists := c.items[key]; exists {
		c.removeFromTagIndex(elem)
		entry := elem.Value.(*lruEntry)
		entry.value = value
		entry.expiresAt = c.calculateExpiry(ttl)
		entry.tags = tags
		c.addToTagIndex(elem, tags)
		return
	}

	if c.order.Len() >= c.maxSize {
		c.evictFirst()
	}

	entry := &lruEntry{
		key:       key,
		value:     value,
		expiresAt: c.calculateExpiry(ttl),
		tags:      tags,
	}
	elem := c.order.PushBack(entry)
	c.items[key] = elem
	c.addToTagIndex(elem, tags)
}

func (c *FIFOCache) Delete(key string) bool {
	c.mu.Lock()
	defer c.mu.Unlock()

	elem, exists := c.items[key]
	if !exists {
		return false
	}

	c.removeElement(elem)
	return true
}

func (c *FIFOCache) InvalidateTag(tag string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	if elems, exists := c.tagIndex[tag]; exists {
		for elem := range elems {
			c.removeElement(elem)
		}
		delete(c.tagIndex, tag)
	}
}

func (c *FIFOCache) InvalidatePattern(pattern string) {
	c.mu.Lock()
	defer c.mu.Unlock()

	re, err := regexp.Compile(pattern)
	if err != nil {
		return
	}

	var toRemove []*list.Element
	for key, elem := range c.items {
		if re.MatchString(key) {
			toRemove = append(toRemove, elem)
		}
	}

	for _, elem := range toRemove {
		c.removeElement(elem)
	}
}

func (c *FIFOCache) Clear() {
	c.mu.Lock()
	defer c.mu.Unlock()

	c.items = make(map[string]*list.Element)
	c.order.Init()
	c.tagIndex = make(map[string]map[*list.Element]bool)
	c.totalHits = 0
}

func (c *FIFOCache) GetStats() models.CacheStats {
	c.mu.RLock()
	defer c.mu.RUnlock()

	return models.CacheStats{
		Size:       len(c.items),
		MaxSize:    c.maxSize,
		Strategy:   c.strategy,
		DefaultTTL: int(c.defaultTTL.Seconds()),
		TotalHits:  c.totalHits,
		TagsCount:  len(c.tagIndex),
	}
}

func (c *FIFOCache) calculateExpiry(ttl time.Duration) time.Time {
	if ttl <= 0 {
		ttl = c.defaultTTL
	}
	if ttl <= 0 {
		return time.Time{}
	}
	return time.Now().Add(ttl)
}

func (c *FIFOCache) evictFirst() {
	elem := c.order.Front()
	if elem != nil {
		c.removeElement(elem)
	}
}

func (c *FIFOCache) removeElement(elem *list.Element) {
	entry := elem.Value.(*lruEntry)
	c.removeFromTagIndex(elem)
	c.order.Remove(elem)
	delete(c.items, entry.key)
}

func (c *FIFOCache) addToTagIndex(elem *list.Element, tags []string) {
	for _, tag := range tags {
		if _, exists := c.tagIndex[tag]; !exists {
			c.tagIndex[tag] = make(map[*list.Element]bool)
		}
		c.tagIndex[tag][elem] = true
	}
}

func (c *FIFOCache) removeFromTagIndex(elem *list.Element) {
	entry := elem.Value.(*lruEntry)
	for _, tag := range entry.tags {
		if elems, exists := c.tagIndex[tag]; exists {
			delete(elems, elem)
			if len(elems) == 0 {
				delete(c.tagIndex, tag)
			}
		}
	}
}

type CacheManagerFactory struct{}

func (f *CacheManagerFactory) Create(strategy models.CacheStrategy, maxSize int, defaultTTL time.Duration) (CacheManager, error) {
	switch strategy {
	case models.CacheStrategyLRU:
		return NewLRUCache(maxSize, defaultTTL), nil
	case models.CacheStrategyLFU:
		return NewLFUCache(maxSize, defaultTTL), nil
	case models.CacheStrategyFIFO:
		return NewFIFOCache(maxSize, defaultTTL), nil
	case models.CacheStrategyTTL:
		return NewLRUCache(maxSize, defaultTTL), nil
	default:
		return nil, fmt.Errorf("unsupported cache strategy: %s", strategy)
	}
}

var (
	cacheInstance CacheManager
	cacheOnce     sync.Once
)

func GetCache() CacheManager {
	cacheOnce.Do(func() {
		factory := &CacheManagerFactory{}
		cacheInstance, _ = factory.Create(models.CacheStrategyLRU, 1000, time.Hour)
	})
	return cacheInstance
}

func SetCacheInstance(cache CacheManager) {
	cacheInstance = cache
	cacheOnce = sync.Once{}
}

func ResetCache() {
	cacheInstance = nil
	cacheOnce = sync.Once{}
}
