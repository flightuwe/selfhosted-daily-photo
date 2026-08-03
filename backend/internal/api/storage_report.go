package api

import (
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

type storageReport struct {
	FilesystemTotalBytes int64  `json:"filesystemTotalBytes"`
	FilesystemFreeBytes  int64  `json:"filesystemFreeBytes"`
	FilesystemUsedBytes  int64  `json:"filesystemUsedBytes"`
	UploadBytes          int64  `json:"uploadBytes"`
	OriginalBytes        int64  `json:"originalBytes"`
	RenditionBytes       int64  `json:"renditionBytes"`
	OtherUploadBytes     int64  `json:"otherUploadBytes"`
	DatabaseBytes        int64  `json:"databaseBytes"`
	DatabaseWalBytes     int64  `json:"databaseWalBytes"`
	DatabaseShmBytes     int64  `json:"databaseShmBytes"`
	BackendLogBytes      int64  `json:"backendLogBytes"`
	GatewayLogBytes      int64  `json:"gatewayLogBytes"`
	DockerBytesAvailable bool   `json:"dockerBytesAvailable"`
	DockerNote           string `json:"dockerNote"`
}

func pathSize(path string) int64 {
	var total int64
	_ = filepath.WalkDir(path, func(_ string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return nil
		}
		if info, err := entry.Info(); err == nil {
			total += info.Size()
		}
		return nil
	})
	return total
}

func fileSize(path string) int64 {
	info, err := os.Stat(path)
	if err != nil || info.IsDir() {
		return 0
	}
	return info.Size()
}

func (s *Server) storageReport() storageReport {
	report := storageReport{
		DockerNote: "Docker image and cache usage is intentionally measured by the host-side read-only report, not by the backend container.",
	}
	report.FilesystemTotalBytes, report.FilesystemFreeBytes = filesystemCapacity(s.Config.UploadDir)
	if report.FilesystemTotalBytes >= report.FilesystemFreeBytes {
		report.FilesystemUsedBytes = report.FilesystemTotalBytes - report.FilesystemFreeBytes
	}

	report.DatabaseBytes = fileSize(s.Config.DatabasePath)
	report.DatabaseWalBytes = fileSize(s.Config.DatabasePath + "-wal")
	report.DatabaseShmBytes = fileSize(s.Config.DatabasePath + "-shm")
	report.BackendLogBytes = pathSize(filepath.Dir(s.Config.ForensicBackendLogPath))
	report.GatewayLogBytes = pathSize(filepath.Dir(s.Config.ForensicGatewayLogPath))

	_ = filepath.WalkDir(s.Config.UploadDir, func(path string, entry fs.DirEntry, err error) error {
		if err != nil || entry.IsDir() {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			return nil
		}
		size := info.Size()
		report.UploadBytes += size
		rel, err := filepath.Rel(s.Config.UploadDir, path)
		if err != nil {
			report.OtherUploadBytes += size
			return nil
		}
		if strings.HasPrefix(filepath.ToSlash(rel), "renditions/") {
			report.RenditionBytes += size
		} else if strings.HasPrefix(filepath.ToSlash(rel), "photos/") {
			report.OriginalBytes += size
		} else {
			report.OtherUploadBytes += size
		}
		return nil
	})
	return report
}
