package api

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

type mediaDeliveryAggregate struct {
	requests int64
	bytes    int64
}

// ingestRenditionGatewayLog consumes only new combined-log lines. It stores no
// request path; only day, format, status and byte aggregates leave the parser.
func (s *Server) ingestRenditionGatewayLog() error {
	logPath := strings.TrimSpace(s.Config.ForensicGatewayLogPath)
	offsetPath := strings.TrimSpace(s.Config.RenditionLogOffsetPath)
	if logPath == "" || offsetPath == "" {
		return nil
	}
	file, err := os.Open(logPath)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return err
	}
	offset := int64(0)
	if raw, readErr := os.ReadFile(offsetPath); readErr == nil {
		offset, _ = strconv.ParseInt(strings.TrimSpace(string(raw)), 10, 64)
	}
	if offset < 0 || offset > info.Size() {
		offset = 0
	}
	if _, err := file.Seek(offset, io.SeekStart); err != nil {
		return err
	}
	aggregates := map[string]*mediaDeliveryAggregate{}
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 64*1024), 1024*1024)
	for scanner.Scan() {
		day, format, status, bytes, ok := parseRenditionAccessLine(scanner.Text())
		if !ok {
			continue
		}
		key := fmt.Sprintf("%s|%s|%d", day, format, status)
		bucket := aggregates[key]
		if bucket == nil {
			bucket = &mediaDeliveryAggregate{}
			aggregates[key] = bucket
		}
		bucket.requests++
		bucket.bytes += bytes
	}
	if err := scanner.Err(); err != nil {
		return err
	}
	for key, bucket := range aggregates {
		parts := strings.Split(key, "|")
		status, _ := strconv.Atoi(parts[2])
		row := models.MediaDeliveryMetric{Day: parts[0], Format: parts[1], Status: status, Requests: bucket.requests, BytesOut: bucket.bytes}
		if err := s.DB.Clauses(clause.OnConflict{
			Columns: []clause.Column{{Name: "day"}, {Name: "format"}, {Name: "status"}},
			DoUpdates: clause.Assignments(map[string]any{
				"requests": gorm.Expr("requests + ?", row.Requests), "bytes_out": gorm.Expr("bytes_out + ?", row.BytesOut), "updated_at": time.Now(),
			}),
		}).Create(&row).Error; err != nil {
			return err
		}
	}
	position, err := file.Seek(0, io.SeekCurrent)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(offsetPath), 0o755); err != nil {
		return err
	}
	temp, err := os.CreateTemp(filepath.Dir(offsetPath), ".rendition-offset-*")
	if err != nil {
		return err
	}
	tempName := temp.Name()
	defer os.Remove(tempName)
	if _, err := fmt.Fprint(temp, position); err != nil {
		_ = temp.Close()
		return err
	}
	if err := temp.Close(); err != nil {
		return err
	}
	return os.Rename(tempName, offsetPath)
}

func parseRenditionAccessLine(line string) (string, string, int, int64, bool) {
	requestStart := strings.Index(line, "\"")
	if requestStart < 0 {
		return "", "", 0, 0, false
	}
	requestEnd := strings.Index(line[requestStart+1:], "\"")
	if requestEnd < 0 {
		return "", "", 0, 0, false
	}
	requestEnd += requestStart + 1
	requestParts := strings.Fields(line[requestStart+1 : requestEnd])
	if len(requestParts) < 2 || requestParts[0] != "GET" || !strings.HasPrefix(requestParts[1], "/uploads/renditions/") {
		return "", "", 0, 0, false
	}
	format := strings.ToLower(strings.TrimPrefix(filepath.Ext(strings.SplitN(requestParts[1], "?", 2)[0]), "."))
	if format == "jpg" {
		format = "jpeg"
	}
	if format != "jpeg" && format != "webp" && format != "avif" {
		return "", "", 0, 0, false
	}
	tail := strings.Fields(line[requestEnd+1:])
	if len(tail) < 2 {
		return "", "", 0, 0, false
	}
	status, err := strconv.Atoi(tail[0])
	if err != nil {
		return "", "", 0, 0, false
	}
	bytes, _ := strconv.ParseInt(tail[1], 10, 64)
	day := time.Now().UTC().Format("2006-01-02")
	if open := strings.Index(line, "["); open >= 0 {
		if close := strings.Index(line[open:], "]"); close > 0 {
			raw := strings.Fields(line[open+1 : open+close])[0]
			if parsed, parseErr := time.Parse("02/Jan/2006:15:04:05", raw); parseErr == nil {
				day = parsed.Format("2006-01-02")
			}
		}
	}
	return day, format, status, maxInt64(0, bytes), true
}

func (s *Server) mediaDeliveryStats() gin.H {
	_ = s.ingestRenditionGatewayLog()
	cutoff := time.Now().UTC().Add(-7 * 24 * time.Hour).Format("2006-01-02")
	var rows []struct {
		Format   string
		Requests int64
		BytesOut int64
	}
	_ = s.DB.Model(&models.MediaDeliveryMetric{}).Select("format, SUM(requests) AS requests, SUM(bytes_out) AS bytes_out").
		Where("day >= ? AND status >= 200 AND status < 400", cutoff).Group("format").Scan(&rows).Error
	out := gin.H{}
	for _, row := range rows {
		out[row.Format] = gin.H{"requests": row.Requests, "bytes": row.BytesOut}
	}
	return out
}
