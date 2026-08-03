package api

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"image"
	"image/jpeg"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
	"golang.org/x/image/draw"
	"gorm.io/gorm"
	"gorm.io/gorm/clause"
)

const (
	mediaDerivativeQueued  = "queued"
	mediaDerivativeRunning = "running"
	mediaDerivativeReady   = "ready"
	mediaDerivativeFailed  = "failed"
	mediaDerivativeEvicted = "evicted"
)

type mediaVariantSpec struct {
	Name     string
	Purpose  string
	Format   string
	Width    int
	Quality  int
	Ext      string
	Priority int
}

var baseMediaVariants = []mediaVariantSpec{
	{Name: "preview-webp-320", Purpose: "preview", Format: "webp", Width: 320, Quality: 65, Ext: ".webp", Priority: 90},
	{Name: "preview-jpeg-320", Purpose: "preview", Format: "jpeg", Width: 320, Quality: 72, Ext: ".jpg", Priority: 80},
	{Name: "feed-webp-720", Purpose: "feed", Format: "webp", Width: 720, Quality: 75, Ext: ".webp", Priority: 70},
	{Name: "feed-jpeg-720", Purpose: "feed", Format: "jpeg", Width: 720, Quality: 80, Ext: ".jpg", Priority: 60},
}

var avifMediaVariants = []mediaVariantSpec{
	{Name: "preview-avif-320", Purpose: "preview", Format: "avif", Width: 320, Quality: 50, Ext: ".avif", Priority: 55},
	{Name: "feed-avif-720", Purpose: "feed", Format: "avif", Width: 720, Quality: 55, Ext: ".avif", Priority: 50},
}

func (s *Server) mediaVariantSpecs() []mediaVariantSpec {
	items := append([]mediaVariantSpec{}, baseMediaVariants...)
	if s.Config.MediaAVIFEnabled {
		items = append(items, avifMediaVariants...)
	}
	return items
}

func (s *Server) mediaRenditionsJSON(sourcePath string) []gin.H {
	if s == nil || s.DB == nil || !s.Config.MediaRenditionsEnabled || strings.TrimSpace(sourcePath) == "" {
		return []gin.H{}
	}
	clean := filepath.ToSlash(filepath.Clean(strings.TrimSpace(sourcePath)))
	if strings.HasPrefix(clean, "../") || filepath.IsAbs(clean) {
		return []gin.H{}
	}
	var rows []models.MediaDerivative
	if err := s.DB.Where("source_path = ?", clean).Order("width asc, format asc").Find(&rows).Error; err != nil {
		return []gin.H{}
	}
	if len(rows) < len(s.mediaVariantSpecs()) {
		s.enqueueMediaDerivatives(clean, 0, true)
		_ = s.DB.Where("source_path = ?", clean).Order("width asc, format asc").Find(&rows).Error
	} else {
		now := time.Now().UTC()
		_ = s.DB.Model(&models.MediaDerivative{}).Where("source_path = ?", clean).Update("last_requested_at", now).Error
	}
	out := make([]gin.H, 0, len(rows))
	for _, row := range rows {
		if row.Status != mediaDerivativeReady {
			continue
		}
		if row.Format == "avif" && !s.Config.MediaAVIFEnabled {
			continue
		}
		out = append(out, gin.H{
			"purpose":  row.Purpose,
			"format":   row.Format,
			"width":    row.Width,
			"url":      fmt.Sprintf("%s/uploads/%s", s.Config.PublicBaseURL, filepath.ToSlash(row.OutputPath)),
			"byteSize": row.ByteSize,
		})
	}
	return out
}

func (s *Server) enqueueMediaDerivatives(clean string, priorityAdjustment int, requested bool) {
	now := time.Now().UTC()
	for _, spec := range s.mediaVariantSpecs() {
		output := mediaDerivativeRelativePath(clean, spec)
		priority := maxInt(1, spec.Priority+priorityAdjustment)
		row := models.MediaDerivative{
			SourcePath: clean, Variant: spec.Name, Purpose: spec.Purpose, Format: spec.Format,
			Width: spec.Width, Quality: spec.Quality, OutputPath: output,
			Status: mediaDerivativeQueued, Priority: priority,
		}
		if requested {
			row.LastRequestedAt = &now
		}
		_ = s.DB.Clauses(clause.OnConflict{DoNothing: true}).Create(&row).Error
		updates := map[string]any{
			"priority": gorm.Expr("CASE WHEN priority < ? THEN ? ELSE priority END", priority, priority),
			"status":   gorm.Expr("CASE WHEN status = ? THEN ? ELSE status END", mediaDerivativeEvicted, mediaDerivativeQueued),
		}
		if requested {
			updates["last_requested_at"] = now
		}
		_ = s.DB.Model(&models.MediaDerivative{}).Where("source_path = ? AND variant = ?", clean, spec.Name).Updates(updates).Error
	}
}

func mediaDerivativeRelativePath(source string, spec mediaVariantSpec) string {
	hash := sha256.Sum256([]byte(filepath.ToSlash(source)))
	key := hex.EncodeToString(hash[:])
	return filepath.ToSlash(filepath.Join("renditions", key[:2], key[2:4], key+"_"+spec.Name+spec.Ext))
}

func (s *Server) RunMediaDerivativeLoop(ctx context.Context, interval time.Duration) {
	if s == nil || s.DB == nil || !s.Config.MediaRenditionsEnabled {
		return
	}
	if interval < 5*time.Second {
		interval = 5 * time.Second
	}
	_ = s.recoverInterruptedMediaDerivatives()
	_ = s.enqueueRecentMediaDerivativeBackfill()
	_ = s.enqueueOlderMediaDerivativeBatch(50)
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	cleanupTicker := time.NewTicker(time.Hour)
	defer cleanupTicker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
			if err := s.processOneMediaDerivative(ctx); err != nil && !errors.Is(err, gorm.ErrRecordNotFound) {
				log.Printf("media derivative worker: %v", err)
			}
		case <-cleanupTicker.C:
			_ = s.enqueueOlderMediaDerivativeBatch(50)
			if err := s.cleanupMediaDerivatives(); err != nil {
				log.Printf("media derivative cleanup: %v", err)
			}
		}
	}
}

func (s *Server) enqueueRecentMediaDerivativeBackfill() error {
	cutoff := time.Now().UTC().Add(-30 * 24 * time.Hour)
	var photos []models.Photo
	if err := s.DB.Select("id", "file_path", "second_path").Where("created_at >= ?", cutoff).Order("created_at desc").Find(&photos).Error; err != nil {
		return err
	}
	for _, photo := range photos {
		for _, source := range []string{photo.FilePath, photo.SecondPath} {
			if clean := strings.TrimSpace(source); clean != "" {
				s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(clean)), -35, false)
			}
		}
	}
	var attachments []models.PhotoAttachment
	if err := s.DB.Select("photo_attachments.file_path").Joins("JOIN photos ON photos.id = photo_attachments.photo_id").
		Where("photos.created_at >= ?", cutoff).Order("photos.created_at desc").Find(&attachments).Error; err != nil {
		return err
	}
	for _, attachment := range attachments {
		s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(attachment.FilePath)), -35, false)
	}
	return nil
}

func (s *Server) enqueueOlderMediaDerivativeBatch(limit int) error {
	if limit <= 0 {
		limit = 50
	}
	var paths []string
	err := s.DB.Model(&models.Photo{}).Distinct("file_path").
		Where("file_path <> '' AND NOT EXISTS (SELECT 1 FROM media_derivatives WHERE media_derivatives.source_path = photos.file_path)").
		Order("created_at desc").Limit(limit).Pluck("file_path", &paths).Error
	if err != nil {
		return err
	}
	for _, source := range paths {
		s.enqueueMediaDerivatives(filepath.ToSlash(filepath.Clean(source)), -55, false)
	}
	return nil
}

func (s *Server) recoverInterruptedMediaDerivatives() error {
	return s.DB.Model(&models.MediaDerivative{}).Where("status = ?", mediaDerivativeRunning).
		Updates(map[string]any{"status": mediaDerivativeQueued, "next_attempt_at": nil}).Error
}

func (s *Server) processOneMediaDerivative(parent context.Context) error {
	now := time.Now().UTC()
	var row models.MediaDerivative
	err := s.DB.Where("status = ? AND (next_attempt_at IS NULL OR next_attempt_at <= ?)", mediaDerivativeQueued, now).
		Order("priority desc, created_at asc, id asc").First(&row).Error
	if err != nil {
		return err
	}
	claimed := s.DB.Model(&models.MediaDerivative{}).Where("id = ? AND status = ?", row.ID, mediaDerivativeQueued).
		Updates(map[string]any{"status": mediaDerivativeRunning, "attempts": gorm.Expr("attempts + 1"), "last_error": ""})
	if claimed.Error != nil {
		return claimed.Error
	}
	if claimed.RowsAffected != 1 {
		return nil
	}
	row.Attempts++
	ctx, cancel := context.WithTimeout(parent, 90*time.Second)
	defer cancel()
	byteSize, encodeErr := s.encodeMediaDerivative(ctx, row)
	completed := time.Now().UTC()
	if encodeErr == nil {
		if err := s.DB.Model(&models.MediaDerivative{}).Where("id = ?", row.ID).Updates(map[string]any{
			"status": mediaDerivativeReady, "byte_size": byteSize, "completed_at": completed,
			"next_attempt_at": nil, "last_error": "",
		}).Error; err != nil {
			return err
		}
		if row.Variant == "feed-jpeg-720" || row.Format == "avif" {
			s.invalidateDerivativeSource(row.SourcePath)
		}
		return nil
	}
	message := encodeErr.Error()
	if len(message) > 500 {
		message = message[:500]
	}
	if row.Attempts >= 4 {
		return s.DB.Model(&models.MediaDerivative{}).Where("id = ?", row.ID).
			Updates(map[string]any{"status": mediaDerivativeFailed, "last_error": message, "next_attempt_at": nil}).Error
	}
	retryAt := completed.Add(time.Duration(1<<minInt(row.Attempts, 6)) * time.Minute)
	return s.DB.Model(&models.MediaDerivative{}).Where("id = ?", row.ID).
		Updates(map[string]any{"status": mediaDerivativeQueued, "last_error": message, "next_attempt_at": retryAt}).Error
}

func (s *Server) invalidateDerivativeSource(sourcePath string) {
	var photo models.Photo
	err := s.DB.Select("day").Where("file_path = ? OR second_path = ?", sourcePath, sourcePath).First(&photo).Error
	if errors.Is(err, gorm.ErrRecordNotFound) {
		err = s.DB.Model(&models.Photo{}).Select("photos.day").
			Joins("JOIN photo_attachments ON photo_attachments.photo_id = photos.id").
			Where("photo_attachments.file_path = ?", sourcePath).First(&photo).Error
	}
	if err == nil && strings.TrimSpace(photo.Day) != "" {
		s.invalidateFeedDayCache(photo.Day)
	}
}

func (s *Server) encodeMediaDerivative(ctx context.Context, row models.MediaDerivative) (int64, error) {
	source, err := s.secureUploadFile(row.SourcePath)
	if err != nil {
		return 0, err
	}
	output, err := s.secureDerivativeOutput(row.OutputPath)
	if err != nil {
		return 0, err
	}
	if err := os.MkdirAll(filepath.Dir(output), 0o755); err != nil {
		return 0, err
	}
	sourceFile, err := os.Open(source)
	if err != nil {
		return 0, err
	}
	img, _, decodeErr := image.Decode(sourceFile)
	_ = sourceFile.Close()
	if decodeErr != nil {
		return 0, decodeErr
	}
	resized := resizeForDerivative(img, row.Width)
	intermediate, err := os.CreateTemp(filepath.Dir(output), ".daily-rendition-source-*.jpg")
	if err != nil {
		return 0, err
	}
	intermediatePath := intermediate.Name()
	defer os.Remove(intermediatePath)
	if err := jpeg.Encode(intermediate, resized, &jpeg.Options{Quality: maxInt(row.Quality, 90)}); err != nil {
		_ = intermediate.Close()
		return 0, err
	}
	if err := intermediate.Close(); err != nil {
		return 0, err
	}
	tempOutput, err := os.CreateTemp(filepath.Dir(output), ".daily-rendition-output-*"+filepath.Ext(output))
	if err != nil {
		return 0, err
	}
	tempPath := tempOutput.Name()
	_ = tempOutput.Close()
	_ = os.Remove(tempPath)
	defer os.Remove(tempPath)
	switch row.Format {
	case "jpeg":
		out, createErr := os.OpenFile(tempPath, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o644)
		if createErr != nil {
			return 0, createErr
		}
		encodeErr := jpeg.Encode(out, resized, &jpeg.Options{Quality: row.Quality})
		closeErr := out.Close()
		if encodeErr != nil {
			return 0, encodeErr
		}
		if closeErr != nil {
			return 0, closeErr
		}
	case "webp":
		if err := runDerivativeCommand(ctx, "cwebp", "-quiet", "-q", strconv.Itoa(row.Quality), intermediatePath, "-o", tempPath); err != nil {
			return 0, err
		}
	case "avif":
		if !s.Config.MediaAVIFEnabled {
			return 0, errors.New("avif disabled")
		}
		if err := runDerivativeCommand(ctx, "avifenc", "-q", strconv.Itoa(row.Quality), "-s", "6", "-j", "1", intermediatePath, tempPath); err != nil {
			return 0, err
		}
	default:
		return 0, fmt.Errorf("unsupported derivative format %q", row.Format)
	}
	info, err := os.Stat(tempPath)
	if err != nil || info.Size() <= 0 {
		return 0, fmt.Errorf("empty derivative output: %w", err)
	}
	if err := os.Rename(tempPath, output); err != nil {
		return 0, err
	}
	return info.Size(), nil
}

func runDerivativeCommand(ctx context.Context, binary string, args ...string) error {
	path, err := exec.LookPath(binary)
	if err != nil {
		return fmt.Errorf("%s unavailable: %w", binary, err)
	}
	command := exec.CommandContext(ctx, path, args...)
	output, err := command.CombinedOutput()
	if err != nil {
		message := strings.TrimSpace(string(output))
		if len(message) > 300 {
			message = message[:300]
		}
		return fmt.Errorf("%s failed: %w: %s", binary, err, message)
	}
	return nil
}

func resizeForDerivative(src image.Image, targetWidth int) image.Image {
	bounds := src.Bounds()
	width := maxInt(1, bounds.Dx())
	height := maxInt(1, bounds.Dy())
	if targetWidth <= 0 || width <= targetWidth {
		return src
	}
	targetHeight := maxInt(1, int(float64(height)*float64(targetWidth)/float64(width)))
	dst := image.NewRGBA(image.Rect(0, 0, targetWidth, targetHeight))
	draw.CatmullRom.Scale(dst, dst.Bounds(), src, bounds, draw.Over, nil)
	return dst
}

func (s *Server) secureUploadFile(relative string) (string, error) {
	root, err := filepath.Abs(s.Config.UploadDir)
	if err != nil {
		return "", err
	}
	clean := filepath.Clean(strings.TrimSpace(relative))
	if clean == "." || filepath.IsAbs(clean) || strings.HasPrefix(clean, ".."+string(filepath.Separator)) {
		return "", errors.New("unsafe source path")
	}
	full := filepath.Join(root, clean)
	resolved, err := filepath.EvalSymlinks(full)
	if err != nil {
		return "", err
	}
	if !pathWithinRoot(root, resolved) {
		return "", errors.New("source escapes upload root")
	}
	info, err := os.Stat(resolved)
	if err != nil || !info.Mode().IsRegular() {
		return "", errors.New("source is not a regular file")
	}
	return resolved, nil
}

func (s *Server) secureDerivativeOutput(relative string) (string, error) {
	root, err := filepath.Abs(s.Config.UploadDir)
	if err != nil {
		return "", err
	}
	clean := filepath.Clean(strings.TrimSpace(relative))
	if filepath.IsAbs(clean) || !strings.HasPrefix(filepath.ToSlash(clean), "renditions/") {
		return "", errors.New("unsafe derivative path")
	}
	full := filepath.Join(root, clean)
	if !pathWithinRoot(root, full) {
		return "", errors.New("derivative escapes upload root")
	}
	parent := filepath.Dir(full)
	relParent, err := filepath.Rel(root, parent)
	if err != nil {
		return "", err
	}
	cursor := root
	for _, part := range strings.Split(relParent, string(filepath.Separator)) {
		if part == "" || part == "." {
			continue
		}
		cursor = filepath.Join(cursor, part)
		info, statErr := os.Lstat(cursor)
		if os.IsNotExist(statErr) {
			break
		}
		if statErr != nil {
			return "", statErr
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return "", errors.New("derivative path contains symlink")
		}
	}
	return full, nil
}

func pathWithinRoot(root, candidate string) bool {
	rel, err := filepath.Rel(root, candidate)
	return err == nil && rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator)) && !filepath.IsAbs(rel)
}

func (s *Server) cleanupMediaDerivatives() error {
	root := filepath.Join(s.Config.UploadDir, "renditions")
	_ = filepath.Walk(root, func(path string, info os.FileInfo, err error) error {
		if err == nil && info != nil && !info.IsDir() && strings.Contains(info.Name(), ".daily-rendition-") && time.Since(info.ModTime()) > 24*time.Hour {
			_ = os.Remove(path)
		}
		return nil
	})
	maxBytes := s.Config.MediaDerivativeMaxBytes
	if maxBytes <= 0 {
		return nil
	}
	var total int64
	if err := s.DB.Model(&models.MediaDerivative{}).Where("status = ?", mediaDerivativeReady).Select("COALESCE(SUM(byte_size),0)").Scan(&total).Error; err != nil {
		return err
	}
	target := maxBytes * 9 / 10
	if total <= target {
		return nil
	}
	cutoff := time.Now().UTC().Add(-7 * 24 * time.Hour)
	var rows []models.MediaDerivative
	if err := s.DB.Where("status = ? AND created_at < ? AND (last_requested_at IS NULL OR last_requested_at < ?)", mediaDerivativeReady, cutoff, cutoff).
		Order("COALESCE(last_requested_at, created_at) asc").Find(&rows).Error; err != nil {
		return err
	}
	for _, row := range rows {
		if total <= target {
			break
		}
		full, pathErr := s.secureDerivativeOutput(row.OutputPath)
		if pathErr != nil {
			continue
		}
		_ = os.Remove(full)
		if err := s.DB.Model(&models.MediaDerivative{}).Where("id = ?", row.ID).Updates(map[string]any{
			"status": mediaDerivativeEvicted, "byte_size": 0, "completed_at": nil,
		}).Error; err != nil {
			return err
		}
		total -= row.ByteSize
	}
	return nil
}

func (s *Server) mediaDerivativeStats() gin.H {
	stats := gin.H{"queued": int64(0), "running": int64(0), "ready": int64(0), "failed": int64(0), "bytes": int64(0)}
	var rows []struct {
		Status string
		Count  int64
		Bytes  int64
	}
	_ = s.DB.Model(&models.MediaDerivative{}).Select("status, COUNT(*) AS count, COALESCE(SUM(byte_size),0) AS bytes").Group("status").Scan(&rows).Error
	for _, row := range rows {
		stats[row.Status] = row.Count
		if row.Status == mediaDerivativeReady {
			stats["bytes"] = row.Bytes
		}
	}
	stats["enabled"] = s.Config.MediaRenditionsEnabled
	stats["avifEnabled"] = s.Config.MediaAVIFEnabled
	stats["maxBytes"] = s.Config.MediaDerivativeMaxBytes
	stats["deliveriesSevenDays"] = s.mediaDeliveryStats()
	return stats
}
