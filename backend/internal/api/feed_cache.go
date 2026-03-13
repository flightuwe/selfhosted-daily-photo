package api

import (
	"sync"
	"time"

	"github.com/gin-gonic/gin"
)

type feedCacheEntry struct {
	Data      gin.H
	ExpiresAt time.Time
}

type FeedDayCache struct {
	mu    sync.Mutex
	ttl   time.Duration
	items map[string]feedCacheEntry
}

func NewFeedDayCache(ttl time.Duration) *FeedDayCache {
	if ttl <= 0 {
		ttl = 10 * time.Second
	}
	return &FeedDayCache{
		ttl:   ttl,
		items: make(map[string]feedCacheEntry),
	}
}

func (c *FeedDayCache) key(userID uint, day string) string {
	return day + "|" + intToString(int(userID))
}

func (c *FeedDayCache) Get(userID uint, day string, now time.Time) (gin.H, bool) {
	if c == nil {
		return nil, false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	key := c.key(userID, day)
	entry, ok := c.items[key]
	if !ok {
		return nil, false
	}
	if now.After(entry.ExpiresAt) {
		delete(c.items, key)
		return nil, false
	}
	return cloneGinMap(entry.Data), true
}

func (c *FeedDayCache) Put(userID uint, day string, payload gin.H, now time.Time) {
	if c == nil || payload == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	c.items[c.key(userID, day)] = feedCacheEntry{
		Data:      cloneGinMap(payload),
		ExpiresAt: now.Add(c.ttl),
	}
}

func (c *FeedDayCache) InvalidateDay(day string) {
	if c == nil {
		return
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	for key := range c.items {
		if len(key) >= len(day)+1 && key[:len(day)] == day && key[len(day)] == '|' {
			delete(c.items, key)
		}
	}
}

func cloneGinMap(in gin.H) gin.H {
	out := make(gin.H, len(in))
	for k, v := range in {
		out[k] = v
	}
	return out
}

type FeedPollLimiter struct {
	mu      sync.Mutex
	maxHits int
	window  time.Duration
	hits    map[string][]time.Time
}

func NewFeedPollLimiter(maxHits int, window time.Duration) *FeedPollLimiter {
	if maxHits < 1 {
		maxHits = 30
	}
	if window <= 0 {
		window = 30 * time.Second
	}
	return &FeedPollLimiter{
		maxHits: maxHits,
		window:  window,
		hits:    make(map[string][]time.Time),
	}
}

func (l *FeedPollLimiter) Allow(userID uint, now time.Time) (bool, int) {
	if l == nil {
		return true, 0
	}
	key := intToString(int(userID))
	l.mu.Lock()
	defer l.mu.Unlock()
	series := l.hits[key]
	cutoff := now.Add(-l.window)
	dst := series[:0]
	for _, ts := range series {
		if ts.After(cutoff) {
			dst = append(dst, ts)
		}
	}
	series = dst
	if len(series) >= l.maxHits {
		retry := int(l.window.Seconds())
		if retry < 1 {
			retry = 1
		}
		l.hits[key] = series
		return false, retry
	}
	series = append(series, now)
	l.hits[key] = series
	return true, 0
}

func intToString(v int) string {
	if v == 0 {
		return "0"
	}
	neg := v < 0
	if neg {
		v = -v
	}
	buf := [20]byte{}
	i := len(buf)
	for v > 0 {
		i--
		buf[i] = byte('0' + v%10)
		v /= 10
	}
	if neg {
		i--
		buf[i] = '-'
	}
	return string(buf[i:])
}
