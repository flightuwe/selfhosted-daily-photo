package api

import (
	"testing"
	"time"

	"github.com/gin-gonic/gin"
	"gorm.io/gorm"
)

func TestMonitorDropsBestEffortPersistenceInsteadOfBlockingRequests(t *testing.T) {
	monitor := &Monitor{
		DB:               &gorm.DB{},
		persistenceQueue: make(chan func(), 1),
	}
	monitor.persistenceQueue <- func() {}

	done := make(chan struct{})
	go func() {
		monitor.enqueuePersistence(func() {})
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(100 * time.Millisecond):
		t.Fatal("best-effort metric enqueue blocked behind a full persistence queue")
	}
	if monitor.persistenceDropped != 1 {
		t.Fatalf("dropped persistence metrics = %d, want 1", monitor.persistenceDropped)
	}
}

func TestPerformanceCacheAvoidsRepeatedAnalysisBuild(t *testing.T) {
	server := &Server{}
	calls := 0
	build := func() (gin.H, error) {
		calls++
		return gin.H{"calls": calls}, nil
	}
	first, err := server.performanceCached("overview", time.Minute, build)
	if err != nil {
		t.Fatal(err)
	}
	second, err := server.performanceCached("overview", time.Minute, build)
	if err != nil {
		t.Fatal(err)
	}
	if calls != 1 || first["calls"] != 1 || second["calls"] != 1 {
		t.Fatalf("cache did not reuse analysis payload: calls=%d first=%v second=%v", calls, first, second)
	}
}
