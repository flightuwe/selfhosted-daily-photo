package api

import (
	"context"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/yosho/selfhosted-bereal/backend/internal/config"
	"github.com/yosho/selfhosted-bereal/backend/internal/models"
)

func TestValidateDistributionURLSyntax(t *testing.T) {
	tests := []struct {
		name      string
		url       string
		machine   bool
		allowHTTP bool
		wantClass string
	}{
		{name: "valid https", url: "https://example.org/releases/index.json", machine: true},
		{name: "http blocked", url: "http://example.org/index.json", machine: true, wantClass: "insecure_http"},
		{name: "http deployment override", url: "http://example.org/index.json", machine: true, allowHTTP: true},
		{name: "credentials blocked", url: "https://user:pass@example.org/index.json", machine: true, wantClass: "url_credentials"},
		{name: "fragment blocked for machine", url: "https://example.org/index.json#latest", machine: true, wantClass: "url_fragment"},
		{name: "human fragment allowed", url: "https://example.org/project#releases"},
		{name: "signed query blocked", url: "https://example.org/app.apk?X-Amz-Signature=secret", machine: true, wantClass: "signed_or_credential_url"},
		{name: "ftp blocked", url: "ftp://example.org/app.apk", machine: true, wantClass: "invalid_scheme"},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			_, err := validateDistributionURLSyntax(tc.url, tc.machine, tc.allowHTTP)
			if tc.wantClass == "" {
				if err != nil {
					t.Fatalf("unexpected error: %v", err)
				}
				return
			}
			if err == nil || distributionErrorClass(err) != tc.wantClass {
				t.Fatalf("error = %v class=%q, want %q", err, distributionErrorClass(err), tc.wantClass)
			}
		})
	}
	_, err := validateDistributionURLSyntax("https://example.org/"+strings.Repeat("a", 600), true, false)
	if err == nil || distributionErrorClass(err) != "url_too_long" {
		t.Fatalf("long URL error = %v", err)
	}
}

func TestDistributionAddressPolicyBlocksPrivateAndMappedAddresses(t *testing.T) {
	blocked := []string{
		"127.0.0.1", "10.1.2.3", "169.254.10.2", "224.0.0.1", "0.0.0.0",
		"::1", "fe80::1", "fc00::1", "ff02::1", "::ffff:127.0.0.1",
	}
	for _, raw := range blocked {
		t.Run(raw, func(t *testing.T) {
			if err := validateDistributionAddresses("blocked.example", []net.IPAddr{{IP: net.ParseIP(raw)}}, newDistributionNetworkPolicy(nil)); err == nil || distributionErrorClass(err) != "private_target" {
				t.Fatalf("address %s was not blocked: %v", raw, err)
			}
		})
	}
	addresses := []net.IPAddr{{IP: net.ParseIP("93.184.216.34")}, {IP: net.ParseIP("127.0.0.1")}}
	if err := validateDistributionAddresses("rebinding.example", addresses, newDistributionNetworkPolicy(nil)); err == nil {
		t.Fatal("mixed public/private DNS answers were accepted")
	}
	if err := validateDistributionAddresses("internal.example", []net.IPAddr{{IP: net.ParseIP("10.20.10.30")}}, newDistributionNetworkPolicy([]string{"internal.example"})); err != nil {
		t.Fatalf("exact allowlisted host rejected: %v", err)
	}
	if err := validateDistributionAddresses("other.example", []net.IPAddr{{IP: net.ParseIP("10.20.10.30")}}, newDistributionNetworkPolicy([]string{"10.20.10.0/24"})); err != nil {
		t.Fatalf("CIDR allowlisted address rejected: %v", err)
	}
}

func TestDistributionRedirectPolicyRejectsDowngrade(t *testing.T) {
	client := newDistributionHTTPClient(config.Config{})
	previous, _ := http.NewRequest(http.MethodGet, "https://example.org/index.json", nil)
	next, _ := http.NewRequest(http.MethodGet, "http://example.org/index.json", nil)
	err := client.CheckRedirect(next, []*http.Request{previous})
	if err == nil || distributionErrorClass(err) != "redirect_downgrade" {
		t.Fatalf("downgrade error = %v", err)
	}
}

func TestDistributionManifestTestSuccessAndFailures(t *testing.T) {
	tests := []struct {
		name      string
		handler   http.HandlerFunc
		maxBytes  int64
		wantOK    bool
		wantClass string
	}{
		{
			name: "valid",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"schemaVersion":1,"latest":"1.2.3","releases":[{"version":"1.2.3"}]}`))
			},
			wantOK: true,
		},
		{
			name: "invalid schema",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"schemaVersion":2,"latest":"1.2.3","releases":[]}`))
			},
			wantClass: "invalid_schema",
		},
		{
			name: "oversized",
			handler: func(w http.ResponseWriter, _ *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				_, _ = w.Write([]byte(`{"schemaVersion":1,"latest":"1.2.3","releases":["` + strings.Repeat("x", 512) + `"]}`))
			},
			maxBytes:  64,
			wantClass: "manifest_too_large",
		},
	}
	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			server := httptest.NewServer(tc.handler)
			defer server.Close()
			cfg := config.Config{
				AllowInsecureDistributionURLs:    true,
				DistributionPrivateHostAllowlist: []string{"127.0.0.1"},
				DistributionManifestMaxBytes:     tc.maxBytes,
			}
			profile := models.DistributionProfile{
				Name: "Test", Enabled: true, SourceMode: "manifest", Channel: "stable",
				ReleaseIndexURL: server.URL, ExpectedPackageName: "com.selfhosted.daily",
			}
			result := testDistributionProfile(context.Background(), cfg, profile)
			if result.Success != tc.wantOK || result.ErrorClass != tc.wantClass {
				t.Fatalf("result = %+v", result)
			}
		})
	}
}

func TestDistributionManifestTestHonorsContextTimeout(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		time.Sleep(150 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"schemaVersion":1,"latest":"1.0.0","releases":[{}]}`))
	}))
	defer server.Close()
	cfg := config.Config{
		AllowInsecureDistributionURLs:    true,
		DistributionPrivateHostAllowlist: []string{"127.0.0.1"},
		DistributionManifestMaxBytes:     1024,
	}
	profile := models.DistributionProfile{
		Name: "Timeout", Enabled: true, SourceMode: "manifest", Channel: "stable",
		ReleaseIndexURL: server.URL, ExpectedPackageName: "com.selfhosted.daily",
	}
	ctx, cancel := context.WithTimeout(context.Background(), 20*time.Millisecond)
	defer cancel()
	result := testDistributionProfile(ctx, cfg, profile)
	if result.Success || result.ErrorClass != "timeout" {
		t.Fatalf("timeout result = %+v", result)
	}
}

func TestDistributionRedirectToPrivateAddressIsRejected(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, request *http.Request) {
		http.Redirect(w, request, "http://127.0.0.2/private-index.json", http.StatusFound)
	}))
	defer server.Close()
	cfg := config.Config{
		AllowInsecureDistributionURLs:    true,
		DistributionPrivateHostAllowlist: []string{"127.0.0.1"},
		DistributionManifestMaxBytes:     1024,
	}
	profile := models.DistributionProfile{
		Name: "Redirect", Enabled: true, SourceMode: "manifest", Channel: "stable",
		ReleaseIndexURL: server.URL, ExpectedPackageName: "com.selfhosted.daily",
	}
	result := testDistributionProfile(context.Background(), cfg, profile)
	if result.Success || result.ErrorClass != "private_target" {
		t.Fatalf("redirect result = %+v", result)
	}
}

func TestDistributionAuditSnapshotRedactsQueryValues(t *testing.T) {
	profile := models.DistributionProfile{
		ProjectURL:      "https://example.org/project?view=full",
		ReleaseIndexURL: "https://example.org/index.json?channel=stable",
	}
	snapshot := distributionProfileAuditSnapshot(profile)
	encoded := marshalDistributionAudit(snapshot)
	if strings.Contains(encoded, "view=full") || strings.Contains(encoded, "channel=stable") {
		t.Fatalf("audit contains unredacted query values: %s", encoded)
	}
	if !strings.Contains(encoded, "redacted") {
		t.Fatalf("audit does not show redaction marker: %s", encoded)
	}
}
