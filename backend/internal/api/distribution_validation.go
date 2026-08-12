package api

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"mime"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

const distributionURLMaxLength = 500

var (
	distributionSHA256Pattern  = regexp.MustCompile(`^[0-9a-f]{64}$`)
	distributionChannelPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,40}$`)
	distributionPackagePattern = regexp.MustCompile(`^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$`)
)

type distributionValidationError struct {
	Class   string
	Message string
}

func (e *distributionValidationError) Error() string {
	return e.Message
}

func distributionError(class string, message string) error {
	return &distributionValidationError{Class: class, Message: message}
}

func distributionErrorClass(err error) string {
	var validationErr *distributionValidationError
	if errors.As(err, &validationErr) {
		return validationErr.Class
	}
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return "timeout"
	}
	var netErr net.Error
	if errors.As(err, &netErr) && netErr.Timeout() {
		return "timeout"
	}
	return "network_error"
}

func normalizeDistributionProfile(profile *models.DistributionProfile) {
	profile.Name = strings.TrimSpace(profile.Name)
	profile.SourceMode = strings.ToLower(strings.TrimSpace(profile.SourceMode))
	profile.Channel = strings.ToLower(strings.TrimSpace(profile.Channel))
	profile.ProjectURL = strings.TrimSpace(profile.ProjectURL)
	profile.ReleaseIndexURL = strings.TrimSpace(profile.ReleaseIndexURL)
	profile.ReleaseHistoryURL = strings.TrimSpace(profile.ReleaseHistoryURL)
	profile.ReleasePageURL = strings.TrimSpace(profile.ReleasePageURL)
	profile.DirectAPKURL = strings.TrimSpace(profile.DirectAPKURL)
	profile.DirectAPKVersionName = strings.TrimSpace(profile.DirectAPKVersionName)
	profile.DirectAPKSHA256 = strings.ToLower(strings.TrimSpace(profile.DirectAPKSHA256))
	profile.ExpectedPackageName = strings.TrimSpace(profile.ExpectedPackageName)
	profile.ExpectedSigningCertSHA256 = strings.ToLower(strings.TrimSpace(profile.ExpectedSigningCertSHA256))
}

func validateDistributionProfile(profile *models.DistributionProfile, cfg config.Config) error {
	normalizeDistributionProfile(profile)
	if profile.Name == "" || len(profile.Name) > 120 {
		return distributionError("invalid_name", "name must be between 1 and 120 characters")
	}
	if !distributionChannelPattern.MatchString(profile.Channel) {
		return distributionError("invalid_channel", "channel must use letters, numbers, dot, underscore or dash")
	}
	if !distributionPackagePattern.MatchString(profile.ExpectedPackageName) || len(profile.ExpectedPackageName) > 200 {
		return distributionError("invalid_package_name", "expected package name is invalid")
	}
	if profile.ExpectedSigningCertSHA256 != "" && !distributionSHA256Pattern.MatchString(profile.ExpectedSigningCertSHA256) {
		return distributionError("invalid_signing_fingerprint", "expected signing certificate SHA-256 must be 64 lowercase hex characters")
	}
	if profile.DirectAPKSHA256 != "" && !distributionSHA256Pattern.MatchString(profile.DirectAPKSHA256) {
		return distributionError("invalid_apk_hash", "direct APK SHA-256 must be 64 lowercase hex characters")
	}
	if profile.DirectAPKSizeBytes != nil && *profile.DirectAPKSizeBytes <= 0 {
		return distributionError("invalid_apk_size", "direct APK size must be greater than zero")
	}
	if profile.DirectAPKSizeBytes != nil && cfg.DistributionAPKMaxBytes > 0 && *profile.DirectAPKSizeBytes > cfg.DistributionAPKMaxBytes {
		return distributionError("apk_too_large", "direct APK size exceeds the deployment limit")
	}
	if profile.MinSupportedVersionCode != nil && *profile.MinSupportedVersionCode < 1 {
		return distributionError("invalid_min_version", "minimum supported version code must be positive")
	}

	urlFields := []struct {
		name       string
		value      *string
		machineURL bool
		required   bool
	}{
		{name: "projectUrl", value: &profile.ProjectURL},
		{name: "releaseIndexUrl", value: &profile.ReleaseIndexURL, machineURL: true, required: profile.SourceMode == "manifest"},
		{name: "releaseHistoryUrl", value: &profile.ReleaseHistoryURL, machineURL: true},
		{name: "releasePageUrl", value: &profile.ReleasePageURL},
		{name: "directApkUrl", value: &profile.DirectAPKURL, machineURL: true, required: profile.SourceMode == "direct"},
	}
	for _, field := range urlFields {
		if strings.TrimSpace(*field.value) == "" {
			if field.required {
				return distributionError("missing_url", field.name+" is required for this source mode")
			}
			continue
		}
		normalized, err := validateDistributionURLSyntax(*field.value, field.machineURL, cfg.AllowInsecureDistributionURLs)
		if err != nil {
			return distributionError(distributionErrorClass(err), field.name+": "+err.Error())
		}
		*field.value = normalized
	}

	switch profile.SourceMode {
	case "manifest":
		if !profile.Enabled && profile.IsDefault {
			return distributionError("default_disabled", "the default profile must be enabled")
		}
	case "direct":
		if profile.DirectAPKVersionName == "" || profile.DirectAPKVersionCode < 1 || profile.DirectAPKSHA256 == "" {
			return distributionError("incomplete_direct_apk", "direct mode requires version name, positive version code and SHA-256")
		}
		if !profile.Enabled && profile.IsDefault {
			return distributionError("default_disabled", "the default profile must be enabled")
		}
	case "disabled":
		profile.Enabled = false
		if profile.IsDefault {
			return distributionError("default_disabled", "a disabled profile cannot be the default")
		}
	default:
		return distributionError("invalid_source_mode", "sourceMode must be manifest, direct or disabled")
	}
	return nil
}

func validateDistributionURLSyntax(raw string, machineReadable bool, allowInsecureHTTP bool) (string, error) {
	clean := strings.TrimSpace(raw)
	if clean == "" {
		return "", distributionError("invalid_url", "URL is empty")
	}
	if len(clean) > distributionURLMaxLength {
		return "", distributionError("url_too_long", "URL exceeds 500 characters")
	}
	parsed, err := url.Parse(clean)
	if err != nil || !parsed.IsAbs() || parsed.Hostname() == "" {
		return "", distributionError("invalid_url", "URL must be absolute and include a host")
	}
	if parsed.User != nil {
		return "", distributionError("url_credentials", "URL user information is not allowed")
	}
	scheme := strings.ToLower(parsed.Scheme)
	if scheme != "https" && !(scheme == "http" && allowInsecureHTTP) {
		if scheme == "http" {
			return "", distributionError("insecure_http", "HTTP distribution URLs are disabled by deployment policy")
		}
		return "", distributionError("invalid_scheme", "only HTTPS distribution URLs are allowed")
	}
	if machineReadable && parsed.Fragment != "" {
		return "", distributionError("url_fragment", "machine-readable URLs cannot contain fragments")
	}
	if parsed.RawQuery != "" || parsed.ForceQuery {
		return "", distributionError("url_query_not_allowed", "distribution URLs must be stable, public and query-free")
	}
	parsed.Scheme = scheme
	parsed.Host = strings.ToLower(parsed.Host)
	return parsed.String(), nil
}

type distributionNetworkPolicy struct {
	exactHosts map[string]struct{}
	prefixes   []netip.Prefix
}

func newDistributionNetworkPolicy(entries []string) distributionNetworkPolicy {
	policy := distributionNetworkPolicy{exactHosts: make(map[string]struct{})}
	for _, raw := range entries {
		entry := strings.TrimSpace(strings.ToLower(raw))
		if entry == "" {
			continue
		}
		if prefix, err := netip.ParsePrefix(entry); err == nil {
			policy.prefixes = append(policy.prefixes, prefix)
			continue
		}
		policy.exactHosts[strings.TrimSuffix(entry, ".")] = struct{}{}
	}
	return policy
}

func (p distributionNetworkPolicy) hostAllowed(host string) bool {
	_, ok := p.exactHosts[strings.TrimSuffix(strings.ToLower(strings.TrimSpace(host)), ".")]
	return ok
}

func (p distributionNetworkPolicy) addressAllowed(address netip.Addr) bool {
	address = address.Unmap()
	for _, prefix := range p.prefixes {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

func validateDistributionAddresses(host string, addresses []net.IPAddr, policy distributionNetworkPolicy) error {
	if len(addresses) == 0 {
		return distributionError("dns_empty", "target host resolved to no addresses")
	}
	allowHost := policy.hostAllowed(host)
	for _, resolved := range addresses {
		address, ok := netip.AddrFromSlice(resolved.IP)
		if !ok {
			return distributionError("invalid_address", "target resolved to an invalid address")
		}
		address = address.Unmap()
		if isBlockedDistributionAddress(address) && !allowHost && !policy.addressAllowed(address) {
			return distributionError("private_target", "target resolves to a private or reserved address")
		}
	}
	return nil
}

func isBlockedDistributionAddress(address netip.Addr) bool {
	address = address.Unmap()
	if !address.IsValid() || address.IsUnspecified() || address.IsLoopback() || address.IsMulticast() || address.IsLinkLocalUnicast() || address.IsLinkLocalMulticast() || address.IsPrivate() {
		return true
	}
	blocked := []netip.Prefix{
		netip.MustParsePrefix("0.0.0.0/8"),
		netip.MustParsePrefix("100.64.0.0/10"),
		netip.MustParsePrefix("192.0.0.0/24"),
		netip.MustParsePrefix("192.0.2.0/24"),
		netip.MustParsePrefix("198.18.0.0/15"),
		netip.MustParsePrefix("198.51.100.0/24"),
		netip.MustParsePrefix("203.0.113.0/24"),
		netip.MustParsePrefix("240.0.0.0/4"),
		netip.MustParsePrefix("2001:db8::/32"),
	}
	for _, prefix := range blocked {
		if prefix.Contains(address) {
			return true
		}
	}
	return false
}

type distributionTestResult struct {
	Success         bool     `json:"success"`
	FinalHost       string   `json:"finalHost"`
	HTTPStatusClass string   `json:"httpStatusClass"`
	LatencyMS       int64    `json:"latencyMs"`
	SchemaVersion   int      `json:"schemaVersion,omitempty"`
	DetectedVersion string   `json:"detectedVersion,omitempty"`
	DetectedSize    *int64   `json:"detectedSize,omitempty"`
	Warnings        []string `json:"warnings"`
	ErrorClass      string   `json:"errorClass,omitempty"`
}

func testDistributionProfile(ctx context.Context, cfg config.Config, profile models.DistributionProfile) distributionTestResult {
	started := time.Now()
	result := distributionTestResult{Warnings: []string{}}
	if err := validateDistributionProfile(&profile, cfg); err != nil {
		result.ErrorClass = distributionErrorClass(err)
		result.LatencyMS = time.Since(started).Milliseconds()
		return result
	}
	if profile.SourceMode == "disabled" || !profile.Enabled {
		result.Success = true
		result.Warnings = append(result.Warnings, "distribution is disabled")
		result.LatencyMS = time.Since(started).Milliseconds()
		return result
	}

	target := profile.ReleaseIndexURL
	manifest := true
	if profile.SourceMode == "direct" {
		target = profile.DirectAPKURL
		manifest = false
	}
	client := newDistributionHTTPClient(cfg)
	var err error
	if manifest {
		err = testDistributionManifest(ctx, client, cfg, target, &result)
	} else {
		err = testDistributionDirectAPK(ctx, client, cfg, target, &result)
	}
	result.LatencyMS = time.Since(started).Milliseconds()
	if err != nil {
		result.ErrorClass = distributionErrorClass(err)
		return result
	}
	result.Success = true
	return result
}

func newDistributionHTTPClient(cfg config.Config) *http.Client {
	policy := newDistributionNetworkPolicy(cfg.DistributionPrivateHostAllowlist)
	dialer := &net.Dialer{Timeout: 4 * time.Second, KeepAlive: 15 * time.Second}
	transport := &http.Transport{
		Proxy:                 nil,
		TLSClientConfig:       &tls.Config{MinVersion: tls.VersionTLS12},
		TLSHandshakeTimeout:   4 * time.Second,
		ResponseHeaderTimeout: 5 * time.Second,
		DisableKeepAlives:     true,
	}
	transport.DialContext = func(ctx context.Context, network string, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, distributionError("invalid_address", "target address is invalid")
		}
		addresses, err := net.DefaultResolver.LookupIPAddr(ctx, host)
		if err != nil {
			return nil, distributionError("dns_failure", "target host could not be resolved")
		}
		if err := validateDistributionAddresses(host, addresses, policy); err != nil {
			return nil, err
		}
		selected := addresses[0]
		return dialer.DialContext(ctx, network, net.JoinHostPort(selected.IP.String(), port))
	}
	return &http.Client{
		Transport: transport,
		Timeout:   10 * time.Second,
		CheckRedirect: func(req *http.Request, via []*http.Request) error {
			if len(via) > 3 {
				return distributionError("redirect_limit", "too many redirects")
			}
			if len(via) > 0 && strings.EqualFold(via[len(via)-1].URL.Scheme, "https") && strings.EqualFold(req.URL.Scheme, "http") {
				return distributionError("redirect_downgrade", "HTTPS to HTTP redirect is not allowed")
			}
			if _, err := validateDistributionURLSyntax(req.URL.String(), true, cfg.AllowInsecureDistributionURLs); err != nil {
				return err
			}
			return nil
		},
	}
}

func testDistributionManifest(ctx context.Context, client *http.Client, cfg config.Config, target string, result *distributionTestResult) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target, nil)
	if err != nil {
		return distributionError("invalid_url", "manifest request could not be created")
	}
	req.Header.Set("Accept", "application/json")
	response, err := client.Do(req)
	if err != nil {
		return err
	}
	defer response.Body.Close()
	result.FinalHost = response.Request.URL.Hostname()
	result.HTTPStatusClass = strconv.Itoa(response.StatusCode/100) + "xx"
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return distributionError("http_status", "manifest endpoint returned a non-success status")
	}
	mediaType, _, err := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if err != nil || (mediaType != "application/json" && !strings.HasSuffix(mediaType, "+json")) {
		return distributionError("content_type", "manifest endpoint did not return JSON")
	}
	limit := cfg.DistributionManifestMaxBytes
	if limit <= 0 {
		limit = 1024 * 1024
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, limit+1))
	if err != nil {
		return distributionError("response_read", "manifest response could not be read")
	}
	if int64(len(body)) > limit {
		return distributionError("manifest_too_large", "manifest exceeds the deployment size limit")
	}
	var root map[string]any
	decoder := json.NewDecoder(strings.NewReader(string(body)))
	decoder.UseNumber()
	if err := decoder.Decode(&root); err != nil {
		return distributionError("invalid_schema", "manifest is not valid JSON")
	}
	schema, ok := distributionSchemaVersion(root["schemaVersion"])
	if !ok || schema != 1 {
		return distributionError("invalid_schema", "manifest schemaVersion must be 1")
	}
	latest, _ := root["latest"].(string)
	latest = strings.TrimSpace(strings.TrimPrefix(latest, "v"))
	releases, ok := root["releases"].([]any)
	if !ok || len(releases) == 0 || latest == "" {
		return distributionError("invalid_schema", "manifest must contain latest and releases")
	}
	result.SchemaVersion = schema
	result.DetectedVersion = latest
	return nil
}

func distributionSchemaVersion(raw any) (int, bool) {
	switch value := raw.(type) {
	case json.Number:
		n, err := strconv.Atoi(value.String())
		return n, err == nil
	case float64:
		return int(value), value == float64(int(value))
	case string:
		n, err := strconv.Atoi(strings.TrimSpace(value))
		return n, err == nil
	default:
		return 0, false
	}
}

func testDistributionDirectAPK(ctx context.Context, client *http.Client, cfg config.Config, target string, result *distributionTestResult) error {
	response, err := distributionAPKMetadataRequest(ctx, client, http.MethodHead, target)
	if err != nil {
		return err
	}
	if response.StatusCode == http.StatusMethodNotAllowed || response.StatusCode == http.StatusNotImplemented {
		response.Body.Close()
		response, err = distributionAPKMetadataRequest(ctx, client, http.MethodGet, target)
		if err != nil {
			return err
		}
	}
	defer response.Body.Close()
	result.FinalHost = response.Request.URL.Hostname()
	result.HTTPStatusClass = strconv.Itoa(response.StatusCode/100) + "xx"
	if response.StatusCode < 200 || response.StatusCode >= 300 {
		return distributionError("http_status", "APK endpoint returned a non-success status")
	}
	mediaType, _, _ := mime.ParseMediaType(response.Header.Get("Content-Type"))
	if mediaType != "application/vnd.android.package-archive" && mediaType != "application/octet-stream" {
		result.Warnings = append(result.Warnings, "APK content type is not specific")
	}
	if response.ContentLength > 0 {
		size := response.ContentLength
		result.DetectedSize = &size
		if cfg.DistributionAPKMaxBytes > 0 && size > cfg.DistributionAPKMaxBytes {
			return distributionError("apk_too_large", "APK exceeds the deployment size limit")
		}
	}
	return nil
}

func distributionAPKMetadataRequest(ctx context.Context, client *http.Client, method string, target string) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, method, target, nil)
	if err != nil {
		return nil, distributionError("invalid_url", "APK request could not be created")
	}
	req.Header.Set("Accept", "application/vnd.android.package-archive, application/octet-stream")
	if method == http.MethodGet {
		req.Header.Set("Range", "bytes=0-0")
	}
	return client.Do(req)
}

func redactDistributionURL(raw string) string {
	parsed, err := url.Parse(strings.TrimSpace(raw))
	if err != nil {
		return ""
	}
	if parsed.RawQuery != "" {
		parsed.RawQuery = "redacted"
	}
	parsed.Fragment = ""
	parsed.User = nil
	return parsed.String()
}

func distributionProfileAuditSnapshot(profile models.DistributionProfile) map[string]any {
	return map[string]any{
		"id": profile.ID, "name": profile.Name, "enabled": profile.Enabled, "isDefault": profile.IsDefault,
		"sourceMode": profile.SourceMode, "channel": profile.Channel,
		"projectUrl": redactDistributionURL(profile.ProjectURL), "releaseIndexUrl": redactDistributionURL(profile.ReleaseIndexURL),
		"releaseHistoryUrl": redactDistributionURL(profile.ReleaseHistoryURL), "releasePageUrl": redactDistributionURL(profile.ReleasePageURL),
		"directApkUrl": redactDistributionURL(profile.DirectAPKURL), "directApkVersionName": profile.DirectAPKVersionName,
		"directApkVersionCode": profile.DirectAPKVersionCode, "directApkSha256": profile.DirectAPKSHA256,
		"directApkSizeBytes": profile.DirectAPKSizeBytes, "expectedPackageName": profile.ExpectedPackageName,
		"expectedSigningCertSha256": profile.ExpectedSigningCertSHA256, "minSupportedVersionCode": profile.MinSupportedVersionCode,
		"allowPrerelease": profile.AllowPrerelease,
	}
}

func marshalDistributionAudit(value any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}
