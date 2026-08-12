package models

import "time"

type User struct {
	ID                                  uint                 `gorm:"primaryKey" json:"id"`
	Username                            string               `gorm:"uniqueIndex;size:64;not null" json:"username"`
	PasswordHash                        string               `gorm:"not null" json:"-"`
	AuthVersion                         uint64               `gorm:"not null;default:1" json:"-"`
	Email                               string               `gorm:"size:254" json:"-"`
	EmailNormalized                     string               `gorm:"size:254;index" json:"-"`
	EmailVerifiedAt                     *time.Time           `gorm:"index" json:"-"`
	PendingEmail                        string               `gorm:"size:254" json:"-"`
	PendingEmailNormalized              string               `gorm:"size:254;index" json:"-"`
	PendingEmailRequestedAt             *time.Time           `json:"-"`
	IsAdmin                             bool                 `gorm:"default:false" json:"isAdmin"`
	FavoriteColor                       string               `gorm:"size:7;default:'#1F5FBF'" json:"favoriteColor"`
	ChatPushEnabled                     bool                 `gorm:"default:false" json:"chatPushEnabled"`
	PollPushEnabled                     bool                 `gorm:"default:false" json:"pollPushEnabled"`
	SpecialMomentPushEnabled            bool                 `gorm:"default:false" json:"specialMomentPushEnabled"`
	InviteRegistrationPushEnabled       bool                 `gorm:"default:false" json:"inviteRegistrationPushEnabled"`
	PhotoReactionPushEnabled            bool                 `gorm:"default:false" json:"photoReactionPushEnabled"`
	PhotoFotomojiPushEnabled            bool                 `gorm:"default:false" json:"photoFotomojiPushEnabled"`
	PhotoCommentPushEnabled             bool                 `gorm:"default:false" json:"photoCommentPushEnabled"`
	BookmarkedPhotoPushEnabled          bool                 `gorm:"default:false" json:"bookmarkedPhotoPushEnabled"`
	PostChangePushEnabled               bool                 `gorm:"default:false" json:"postChangePushEnabled"`
	AutoSubscribeInteractedPostsEnabled bool                 `gorm:"default:false" json:"autoSubscribeInteractedPostsEnabled"`
	OwnPostNumberInPushEnabled          bool                 `gorm:"default:false" json:"ownPostNumberInPushEnabled"`
	PostNumberInPushEnabled             bool                 `gorm:"default:false" json:"postNumberInPushEnabled"`
	YoloModeEnabled                     bool                 `gorm:"default:false" json:"yoloModeEnabled"`
	MediaDataMode                       string               `gorm:"size:16;default:'normal'" json:"mediaDataMode"`
	MediaFormatPreference               string               `gorm:"size:16;default:'auto'" json:"mediaFormatPreference"`
	AllowPhotoDownload                  bool                 `gorm:"default:false" json:"allowPhotoDownload"`
	AllowCommunityNsfwMarking           bool                 `gorm:"default:false" json:"allowCommunityNsfwMarking"`
	ShowNsfwByDefault                   bool                 `gorm:"default:false" json:"showNsfwByDefault"`
	CreativePostMode                    string               `gorm:"size:16;default:'none'" json:"creativePostMode"`
	LocationFeatureEnabled              bool                 `gorm:"default:false" json:"locationFeatureEnabled"`
	LocationShareDefaultEnabled         bool                 `gorm:"default:false" json:"locationShareDefaultEnabled"`
	AllowCommunityPostPromotion         bool                 `gorm:"default:false" json:"allowCommunityPostPromotion"`
	CommunityContributionPushEnabled    bool                 `gorm:"default:false" json:"communityContributionPushEnabled"`
	AvatarPath                          string               `gorm:"size:255" json:"avatarUrl"`
	Bio                                 string               `gorm:"size:280" json:"bio"`
	StatusText                          string               `gorm:"size:120" json:"statusText"`
	StatusEmoji                         string               `gorm:"size:16" json:"statusEmoji"`
	StatusExpiresAt                     *time.Time           `json:"statusExpiresAt"`
	ProfileVisible                      bool                 `gorm:"default:false;index" json:"profileVisible"`
	AvatarVisible                       bool                 `gorm:"default:false" json:"avatarVisible"`
	BioVisible                          bool                 `gorm:"default:false" json:"bioVisible"`
	StatusVisible                       bool                 `gorm:"default:false" json:"statusVisible"`
	QuietHoursEnabled                   bool                 `gorm:"default:false" json:"quietHoursEnabled"`
	QuietHoursStart                     string               `gorm:"size:5;default:'22:00'" json:"quietHoursStart"`
	QuietHoursEnd                       string               `gorm:"size:5;default:'07:00'" json:"quietHoursEnd"`
	HubTimelineClearedAt                *time.Time           `gorm:"index" json:"-"`
	HubTimelineLastViewedAt             *time.Time           `gorm:"index" json:"-"`
	DiagnosticsConsentGranted           bool                 `gorm:"default:false" json:"diagnosticsConsentGranted"`
	DiagnosticsConsentUpdatedAt         *time.Time           `json:"diagnosticsConsentUpdatedAt"`
	DiagnosticsConsentSource            string               `gorm:"size:32" json:"diagnosticsConsentSource"`
	DistributionProfileID               *uint                `gorm:"index" json:"distributionProfileId,omitempty"`
	DistributionProfile                 *DistributionProfile `gorm:"constraint:OnUpdate:CASCADE,OnDelete:RESTRICT;" json:"-"`
	CreatedAt                           time.Time            `json:"createdAt"`
}

type DistributionProfile struct {
	ID                        uint      `gorm:"primaryKey" json:"id"`
	Name                      string    `gorm:"size:120;not null" json:"name"`
	Enabled                   bool      `gorm:"not null;default:true" json:"enabled"`
	IsDefault                 bool      `gorm:"not null;default:false;index" json:"isDefault"`
	SourceMode                string    `gorm:"size:16;not null;default:'manifest'" json:"sourceMode"`
	Channel                   string    `gorm:"size:40;not null;default:'stable'" json:"channel"`
	ProjectURL                string    `gorm:"size:500" json:"projectUrl"`
	ReleaseIndexURL           string    `gorm:"size:500" json:"releaseIndexUrl"`
	ReleaseHistoryURL         string    `gorm:"size:500" json:"releaseHistoryUrl"`
	ReleasePageURL            string    `gorm:"size:500" json:"releasePageUrl"`
	DirectAPKURL              string    `gorm:"size:500" json:"directApkUrl"`
	DirectAPKVersionName      string    `gorm:"size:80" json:"directApkVersionName"`
	DirectAPKVersionCode      int64     `gorm:"default:0" json:"directApkVersionCode"`
	DirectAPKSHA256           string    `gorm:"size:64" json:"directApkSha256"`
	DirectAPKSizeBytes        *int64    `json:"directApkSizeBytes"`
	ExpectedPackageName       string    `gorm:"size:200;not null;default:'com.selfhosted.daily'" json:"expectedPackageName"`
	ExpectedSigningCertSHA256 string    `gorm:"size:64" json:"expectedSigningCertSha256"`
	MinSupportedVersionCode   *int64    `json:"minSupportedVersionCode"`
	AllowPrerelease           bool      `gorm:"not null;default:false" json:"allowPrerelease"`
	Revision                  int64     `gorm:"not null;default:1" json:"revision"`
	CreatedByUserID           *uint     `gorm:"index" json:"createdByUserId"`
	CreatedAt                 time.Time `json:"createdAt"`
	UpdatedAt                 time.Time `json:"updatedAt"`
}

type DistributionAuditEvent struct {
	ID             uint      `gorm:"primaryKey" json:"id"`
	ActorUserID    *uint     `gorm:"index" json:"actorUserId"`
	ActorUsername  string    `gorm:"size:64" json:"actorUsername"`
	Action         string    `gorm:"size:48;not null;index" json:"action"`
	ProfileID      *uint     `gorm:"index" json:"profileId"`
	TargetUserID   *uint     `gorm:"index" json:"targetUserId"`
	BeforeJSON     string    `gorm:"type:text" json:"beforeJson"`
	AfterJSON      string    `gorm:"type:text" json:"afterJson"`
	TestResultJSON string    `gorm:"type:text" json:"testResultJson"`
	ErrorClass     string    `gorm:"size:64" json:"errorClass"`
	CreatedAt      time.Time `gorm:"index;not null" json:"createdAt"`
}

func (DistributionAuditEvent) TableName() string {
	return "distribution_profile_audit"
}

// DistributionRollout is the singleton policy used to move authenticated
// clients through a bridge profile and back to the default stable profile.
// Profile references are deliberately validated in the API instead of being
// GORM relations, so adding this table can never rebuild the production users
// table during AutoMigrate.
type DistributionRollout struct {
	ID                 uint      `gorm:"primaryKey" json:"id"`
	Enabled            bool      `gorm:"not null;default:false" json:"enabled"`
	MigrationProfileID uint      `gorm:"index;not null;default:0" json:"migrationProfileId"`
	StableProfileID    uint      `gorm:"index;not null;default:0" json:"stableProfileId"`
	EntryVersionCode   int64     `gorm:"not null;default:0" json:"entryVersionCode"`
	StableVersionCode  int64     `gorm:"not null;default:0" json:"stableVersionCode"`
	Revision           int64     `gorm:"not null;default:1" json:"revision"`
	UpdatedByUserID    *uint     `gorm:"index" json:"updatedByUserId,omitempty"`
	CreatedAt          time.Time `json:"createdAt"`
	UpdatedAt          time.Time `json:"updatedAt"`
}

// DistributionClientState stores the last authenticated app version report
// separately from users. This keeps rollout observability additive and avoids
// a schema rewrite of the security-sensitive users table.
type DistributionClientState struct {
	UserID      uint      `gorm:"primaryKey" json:"userId"`
	VersionName string    `gorm:"size:80;not null;default:''" json:"versionName"`
	VersionCode int64     `gorm:"index;not null" json:"versionCode"`
	Phase       string    `gorm:"size:32;not null;index" json:"phase"`
	LastSeenAt  time.Time `gorm:"index;not null" json:"lastSeenAt"`
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}

type InviteCode struct {
	ID        uint       `gorm:"primaryKey" json:"id"`
	UserID    uint       `gorm:"index;index:idx_invite_user_active_unused_created,priority:1;not null" json:"userId"`
	User      User       `json:"user"`
	Code      string     `gorm:"uniqueIndex;size:24;not null" json:"code"`
	UsedByID  *uint      `gorm:"index;index:idx_invite_user_active_unused_created,priority:3" json:"usedById"`
	UsedAt    *time.Time `json:"usedAt"`
	Active    bool       `gorm:"default:true;index;index:idx_invite_user_active_unused_created,priority:2" json:"active"`
	CreatedAt time.Time  `gorm:"index:idx_invite_user_active_unused_created,priority:4" json:"createdAt"`
	UpdatedAt time.Time  `json:"updatedAt"`
}

type DeviceToken struct {
	ID         uint   `gorm:"primaryKey"`
	UserID     uint   `gorm:"index;not null"`
	Token      string `gorm:"uniqueIndex;size:255;not null"`
	DeviceName string `gorm:"size:120"`
	AppVersion string `gorm:"size:40"`
	CreatedAt  time.Time
}

type UserSession struct {
	ID               uint       `gorm:"primaryKey" json:"id"`
	SessionID        string     `gorm:"uniqueIndex;size:64;not null" json:"sessionId"`
	UserID           uint       `gorm:"index;not null" json:"userId"`
	RefreshTokenHash string     `gorm:"uniqueIndex;size:128;not null" json:"-"`
	DeviceID         string     `gorm:"size:120;index" json:"deviceId"`
	DeviceName       string     `gorm:"size:120" json:"deviceName"`
	LastUsedAt       time.Time  `gorm:"index;not null" json:"lastUsedAt"`
	ExpiresAt        *time.Time `gorm:"index" json:"expiresAt"`
	RevokedAt        *time.Time `gorm:"index" json:"revokedAt"`
	ReplacedByID     *uint      `gorm:"index" json:"replacedById"`
	CreatedAt        time.Time  `gorm:"index" json:"createdAt"`
	UpdatedAt        time.Time  `json:"updatedAt"`
}

type AppSettings struct {
	ID                               uint   `gorm:"primaryKey" json:"id"`
	PromptWindowStartHour            int    `gorm:"default:8" json:"promptWindowStartHour"`
	PromptWindowEndHour              int    `gorm:"default:20" json:"promptWindowEndHour"`
	UploadWindowMinutes              int    `gorm:"default:10" json:"uploadWindowMinutes"`
	FeedCommentPreviewLimit          int    `gorm:"default:10" json:"feedCommentPreviewLimit"`
	PromptNotificationText           string `gorm:"size:255;default:'Zeit fuer dein Daily Foto'" json:"promptNotificationText"`
	MaxUploadBytes                   int64  `gorm:"default:0" json:"maxUploadBytes"`
	ChatMessageMaxLength             int    `gorm:"default:5000" json:"chatMessageMaxLength"`
	ChatMessageUnlimited             bool   `gorm:"default:false" json:"chatMessageUnlimited"`
	PostMediaMaxCount                int    `gorm:"default:6" json:"postMediaMaxCount"`
	PostMediaUnlimited               bool   `gorm:"default:true" json:"postMediaUnlimited"`
	ChatCommandEnabled               bool   `gorm:"default:false" json:"chatCommandEnabled"`
	ChatCommandValue                 string `gorm:"size:64;default:'-moment'" json:"chatCommandValue"`
	ChatCommandTrigger               bool   `gorm:"default:true" json:"chatCommandTrigger"`
	ChatCommandSendPush              bool   `gorm:"default:true" json:"chatCommandSendPush"`
	ChatCommandPushText              string `gorm:"size:255;default:'Sondermoment von {user}! Jetzt 10 Minuten posten.'" json:"chatCommandPushText"`
	ChatCommandEchoChat              bool   `gorm:"default:true" json:"chatCommandEchoChat"`
	ChatCommandEchoText              string `gorm:"size:255;default:'Sondermoment wurde von {user} angefordert.'" json:"chatCommandEchoText"`
	PerformanceTrackingEnabled       bool   `gorm:"default:false" json:"performanceTrackingEnabled"`
	PerformanceTrackingWindowMinutes int    `gorm:"default:30" json:"performanceTrackingWindowMinutes"`
	PerformanceTrackingOneShot       bool   `gorm:"default:false" json:"performanceTrackingOneShot"`
	// MediaAVIFDisabled is an operator kill switch. The environment flag remains
	// the capability boundary; this durable flag makes enablement auditable.
	MediaAVIFDisabled        bool   `gorm:"default:false" json:"mediaAvifDisabled"`
	MediaAVIFAutoPaused      bool   `gorm:"default:false" json:"mediaAvifAutoPaused"`
	MediaAVIFAutoPauseReason string `gorm:"size:255" json:"mediaAvifAutoPauseReason"`
	// MediaDerivativeBackgroundPaused is a durable, global operator switch for
	// the best-effort historical rendition backlog. Visible requests ignore it.
	MediaDerivativeBackgroundPaused bool       `gorm:"default:false" json:"mediaDerivativeBackgroundPaused"`
	SchedulerAutoPaused             bool       `gorm:"default:false" json:"schedulerAutoPaused"`
	SchedulerAutoPauseReason        string     `gorm:"size:120" json:"schedulerAutoPauseReason"`
	SchedulerAutoPausedAt           *time.Time `json:"schedulerAutoPausedAt"`
	UserPromptRulesJSON             string     `gorm:"type:text" json:"userPromptRulesJson"`
	MigrationEnabled                bool       `gorm:"default:false" json:"migrationEnabled"`
	MigrationStartedAt              *time.Time `json:"migrationStartedAt"`
	MigrationUntil                  *time.Time `json:"migrationUntil"`
	MigrationAutoOffEnabled         bool       `gorm:"default:true" json:"migrationAutoOffEnabled"`
	MigrationTargetBaseURL          string     `gorm:"size:500" json:"migrationTargetBaseUrl"`
	MigrationDownloadURL            string     `gorm:"size:500" json:"migrationDownloadUrl"`
	MigrationPushTitle              string     `gorm:"size:255" json:"migrationPushTitle"`
	MigrationPushBody               string     `gorm:"size:500" json:"migrationPushBody"`
	MigrationScreenTitle            string     `gorm:"size:255" json:"migrationScreenTitle"`
	MigrationScreenBody             string     `gorm:"size:2000" json:"migrationScreenBody"`
	MigrationRequirePromptFirst     bool       `gorm:"default:true" json:"migrationRequirePromptFirst"`
	MigrationCallbackSecret         string     `gorm:"size:255" json:"migrationCallbackSecret"`
	MigrationExpectedSource         string     `gorm:"size:120" json:"migrationExpectedSource"`
	MigrationReportEnabled          bool       `gorm:"default:false" json:"migrationReportEnabled"`
	MigrationReportTarget           string     `gorm:"size:500" json:"migrationReportTarget"`
	MigrationReportSecret           string     `gorm:"size:255" json:"migrationReportSecret"`
	MigrationReportSource           string     `gorm:"size:500" json:"migrationReportSource"`
	MigrationBaselineUserCount      int64      `gorm:"default:0" json:"migrationBaselineUserCount"`
	CreatedAt                       time.Time  `json:"createdAt"`
	UpdatedAt                       time.Time  `json:"updatedAt"`
}

// EmailSettings is the singleton runtime SMTP configuration. SecretCiphertext
// is never serialized and is encrypted with the deployment-owned master key.
type EmailSettings struct {
	ID                 uint       `gorm:"primaryKey" json:"id"`
	Enabled            bool       `gorm:"default:false" json:"enabled"`
	Host               string     `gorm:"size:255" json:"host"`
	Port               int        `gorm:"default:587" json:"port"`
	TLSMode            string     `gorm:"size:24;default:'starttls'" json:"tlsMode"`
	AuthMode           string     `gorm:"size:24;default:'auto'" json:"authMode"`
	Username           string     `gorm:"size:254" json:"username"`
	SecretCiphertext   string     `gorm:"type:text" json:"-"`
	FromName           string     `gorm:"size:120" json:"fromName"`
	FromAddress        string     `gorm:"size:254" json:"fromAddress"`
	ReplyTo            string     `gorm:"size:254" json:"replyTo"`
	ActionBaseURL      string     `gorm:"size:500" json:"actionBaseUrl"`
	LastTestAt         *time.Time `json:"lastTestAt"`
	LastTestOK         bool       `json:"lastTestOk"`
	LastTestStage      string     `gorm:"size:32" json:"lastTestStage"`
	LastTestError      string     `gorm:"size:500" json:"lastTestError"`
	LastTestConfigHash string     `gorm:"size:64" json:"-"`
	LastDeliveryAt     *time.Time `json:"lastDeliveryAt"`
	LastDeliveryError  string     `gorm:"size:500" json:"lastDeliveryError"`
	CreatedAt          time.Time  `json:"createdAt"`
	UpdatedAt          time.Time  `json:"updatedAt"`
}

type EmailAction struct {
	ID                       uint       `gorm:"primaryKey" json:"id"`
	UserID                   uint       `gorm:"index;not null" json:"userId"`
	Purpose                  string     `gorm:"size:32;index;not null" json:"purpose"`
	TokenHash                string     `gorm:"size:64;uniqueIndex;not null" json:"-"`
	TokenCiphertext          string     `gorm:"type:text" json:"-"`
	PendingEmail             string     `gorm:"size:254" json:"-"`
	PendingEmailNormalized   string     `gorm:"size:254;index" json:"-"`
	NewsletterOptInRequested bool       `gorm:"default:false" json:"-"`
	ConsentVersion           string     `gorm:"size:64" json:"-"`
	Source                   string     `gorm:"size:64" json:"-"`
	AppVersion               string     `gorm:"size:40" json:"-"`
	RequestIPHash            string     `gorm:"size:64;index" json:"-"`
	SentAt                   *time.Time `gorm:"index" json:"sentAt"`
	ExpiresAt                *time.Time `gorm:"index" json:"expiresAt"`
	ConsumedAt               *time.Time `gorm:"index" json:"consumedAt"`
	InvalidatedAt            *time.Time `gorm:"index" json:"invalidatedAt"`
	CreatedAt                time.Time  `gorm:"index" json:"createdAt"`
	UpdatedAt                time.Time  `json:"updatedAt"`
}

type EmailDelivery struct {
	ID             uint       `gorm:"primaryKey" json:"id"`
	ActionID       *uint      `gorm:"index" json:"actionId"`
	UserID         uint       `gorm:"index" json:"userId"`
	Kind           string     `gorm:"size:40;index;not null" json:"kind"`
	Recipient      string     `gorm:"size:254;not null" json:"-"`
	Status         string     `gorm:"size:24;index;not null;default:'queued'" json:"status"`
	Attempts       int        `gorm:"default:0" json:"attempts"`
	NextAttemptAt  time.Time  `gorm:"index" json:"nextAttemptAt"`
	LockedBy       string     `gorm:"size:120;index" json:"lockedBy"`
	LockedUntil    *time.Time `gorm:"index" json:"lockedUntil"`
	LastStage      string     `gorm:"size:32" json:"lastStage"`
	SMTPResultCode int        `json:"smtpResultCode"`
	LastError      string     `gorm:"size:500" json:"lastError"`
	MessageID      string     `gorm:"size:255" json:"messageId"`
	SentAt         *time.Time `gorm:"index" json:"sentAt"`
	CreatedAt      time.Time  `gorm:"index" json:"createdAt"`
	UpdatedAt      time.Time  `json:"updatedAt"`
}

type EmailRateLimit struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	Scope       string    `gorm:"uniqueIndex:idx_email_rate_limit,priority:1;size:32;not null" json:"-"`
	KeyHash     string    `gorm:"uniqueIndex:idx_email_rate_limit,priority:2;size:64;not null" json:"-"`
	WindowStart time.Time `gorm:"uniqueIndex:idx_email_rate_limit,priority:3;not null" json:"-"`
	Count       int       `gorm:"not null;default:0" json:"-"`
	CreatedAt   time.Time `json:"-"`
	UpdatedAt   time.Time `json:"-"`
}

type NewsletterSubscription struct {
	ID              uint       `gorm:"primaryKey" json:"id"`
	UserID          uint       `gorm:"uniqueIndex;not null" json:"userId"`
	Email           string     `gorm:"size:254" json:"-"`
	EmailNormalized string     `gorm:"size:254;index" json:"-"`
	Status          string     `gorm:"size:24;index;not null;default:'unsubscribed'" json:"status"`
	ConsentVersion  string     `gorm:"size:64" json:"consentVersion"`
	Source          string     `gorm:"size:64" json:"source"`
	RequestedAt     *time.Time `json:"requestedAt"`
	ConfirmedAt     *time.Time `json:"confirmedAt"`
	RevokedAt       *time.Time `json:"revokedAt"`
	CreatedAt       time.Time  `json:"createdAt"`
	UpdatedAt       time.Time  `json:"updatedAt"`
}

type UserPromptState struct {
	ID               uint       `gorm:"primaryKey" json:"id"`
	UserID           uint       `gorm:"uniqueIndex:idx_user_prompt_state,priority:1;not null" json:"userId"`
	RuleID           string     `gorm:"uniqueIndex:idx_user_prompt_state,priority:2;size:80;not null" json:"ruleId"`
	LastShownVersion string     `gorm:"size:40" json:"lastShownVersion"`
	LastShownAt      *time.Time `gorm:"index" json:"lastShownAt"`
	LastEvent        string     `gorm:"size:24" json:"lastEvent"`
	AcceptedAt       *time.Time `json:"acceptedAt"`
	CompletedAt      *time.Time `json:"completedAt"`
	CreatedAt        time.Time  `json:"createdAt"`
	UpdatedAt        time.Time  `json:"updatedAt"`
}

type MigrationUserStatus struct {
	ID               uint       `gorm:"primaryKey" json:"id"`
	UserID           uint       `gorm:"uniqueIndex;not null" json:"userId"`
	Username         string     `gorm:"size:64;index" json:"username"`
	FirstSeenOnNewAt *time.Time `gorm:"index" json:"firstSeenOnNewAt"`
	LastSeenOnNewAt  *time.Time `gorm:"index" json:"lastSeenOnNewAt"`
	SourceInstance   string     `gorm:"size:255" json:"sourceInstance"`
	LastAppVersion   string     `gorm:"size:64" json:"lastAppVersion"`
	UpdatedAt        time.Time  `json:"updatedAt"`
	CreatedAt        time.Time  `json:"createdAt"`
}

type SchedulerLease struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	LeaseName   string    `gorm:"size:64;uniqueIndex;not null" json:"leaseName"`
	OwnerID     string    `gorm:"size:120;index;not null" json:"ownerId"`
	HeartbeatAt time.Time `gorm:"index;not null" json:"heartbeatAt"`
	ExpiresAt   time.Time `gorm:"index;not null" json:"expiresAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
	CreatedAt   time.Time `json:"createdAt"`
}

type DailyDispatch struct {
	ID             uint      `gorm:"primaryKey" json:"id"`
	Day            string    `gorm:"size:10;not null;uniqueIndex:idx_daily_dispatch_day_kind" json:"day"`
	Kind           string    `gorm:"size:32;not null;uniqueIndex:idx_daily_dispatch_day_kind" json:"kind"`
	RequestID      string    `gorm:"size:64;index" json:"requestId"`
	Source         string    `gorm:"size:32;index" json:"source"`
	ServerInstance string    `gorm:"size:120;index" json:"serverInstance"`
	Status         string    `gorm:"size:24;index;not null;default:'reserved'" json:"status"`
	SentCount      int64     `gorm:"default:0" json:"sentCount"`
	FailedCount    int64     `gorm:"default:0" json:"failedCount"`
	ErrorMessage   string    `gorm:"size:500" json:"errorMessage"`
	CreatedAt      time.Time `gorm:"index" json:"createdAt"`
	UpdatedAt      time.Time `json:"updatedAt"`
}

type DailyPrompt struct {
	ID             uint   `gorm:"primaryKey"`
	Day            string `gorm:"uniqueIndex;size:10;not null"`
	TriggeredAt    *time.Time
	UploadUntil    *time.Time
	TriggerSource  string `gorm:"size:32;default:'scheduler'"`
	RequestedByID  *uint
	RequestedBy    string `gorm:"size:64"`
	NotificationID string `gorm:"size:64"`
	CreatedAt      time.Time
	UpdatedAt      time.Time
}

type PromptPlan struct {
	ID        uint      `gorm:"primaryKey"`
	Day       string    `gorm:"uniqueIndex;size:10;not null"`
	PlannedAt time.Time `gorm:"not null"`
	IsManual  bool      `gorm:"default:false"`
	CreatedAt time.Time
	UpdatedAt time.Time
}

type DailyUserActivity struct {
	ID           uint      `gorm:"primaryKey"`
	Day          string    `gorm:"size:10;not null;uniqueIndex:idx_daily_user_activity_day_user"`
	UserID       uint      `gorm:"not null;uniqueIndex:idx_daily_user_activity_day_user"`
	User         User      `json:"user"`
	FirstSeenAt  time.Time `gorm:"not null"`
	LastSeenAt   time.Time `gorm:"not null"`
	RequestCount int       `gorm:"default:0"`
	CreatedAt    time.Time
	UpdatedAt    time.Time
}

// SyncRevision stores durable, monotonically increasing revisions for client
// synchronization scopes. Scope values are either "timeline" or "feed:<day>".
type SyncRevision struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	Scope     string    `gorm:"size:64;uniqueIndex;not null" json:"scope"`
	Revision  int64     `gorm:"not null;default:1" json:"revision"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type APIMinuteMetric struct {
	ID          uint      `gorm:"primaryKey"`
	Minute      time.Time `gorm:"not null;index:idx_api_minute_metric_bucket,unique"`
	Route       string    `gorm:"size:120;not null;index:idx_api_minute_metric_bucket,unique"`
	Method      string    `gorm:"size:8;not null;index:idx_api_minute_metric_bucket,unique"`
	StatusClass string    `gorm:"size:4;not null;index:idx_api_minute_metric_bucket,unique"`
	Count       int64     `gorm:"default:0"`
	P50Latency  float64   `gorm:"default:0"`
	P95Latency  float64   `gorm:"default:0"`
	P99Latency  float64   `gorm:"default:0"`
	MaxLatency  float64   `gorm:"default:0"`
	BytesIn     int64     `gorm:"default:0"`
	BytesOut    int64     `gorm:"default:0"`
	CreatedAt   time.Time
	UpdatedAt   time.Time
}

type SyncCapabilityMetric struct {
	ID         uint      `gorm:"primaryKey"`
	Minute     time.Time `gorm:"not null;uniqueIndex:idx_sync_capability_bucket,priority:1"`
	Surface    string    `gorm:"size:24;not null;uniqueIndex:idx_sync_capability_bucket,priority:2"`
	Mode       string    `gorm:"size:16;not null;uniqueIndex:idx_sync_capability_bucket,priority:3"`
	Outcome    string    `gorm:"size:16;not null;uniqueIndex:idx_sync_capability_bucket,priority:4"`
	AppVersion string    `gorm:"size:40;not null;uniqueIndex:idx_sync_capability_bucket,priority:5"`
	Requests   int64
	BytesOut   int64
	MaxLatency float64
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

// MediaDerivative is a disposable, reproducible rendition of an immutable
// uploaded source. SourcePath always points at the original upload; deleting a
// derivative never deletes or rewrites that source.
type MediaDerivative struct {
	ID              uint       `gorm:"primaryKey" json:"id"`
	SourcePath      string     `gorm:"size:255;not null;uniqueIndex:idx_media_derivative_source_variant,priority:1" json:"sourcePath"`
	Variant         string     `gorm:"size:32;not null;uniqueIndex:idx_media_derivative_source_variant,priority:2;index" json:"variant"`
	Purpose         string     `gorm:"size:16;not null;index" json:"purpose"`
	Format          string     `gorm:"size:8;not null;index" json:"format"`
	Width           int        `gorm:"not null" json:"width"`
	Quality         int        `gorm:"not null" json:"quality"`
	OutputPath      string     `gorm:"size:255;not null" json:"outputPath"`
	Status          string     `gorm:"size:16;not null;default:'queued';index" json:"status"`
	Priority        int        `gorm:"not null;default:0;index" json:"priority"`
	Attempts        int        `gorm:"not null;default:0" json:"attempts"`
	ByteSize        int64      `gorm:"not null;default:0" json:"byteSize"`
	LastError       string     `gorm:"size:500" json:"lastError"`
	NextAttemptAt   *time.Time `gorm:"index" json:"nextAttemptAt"`
	LastRequestedAt *time.Time `gorm:"index" json:"lastRequestedAt"`
	StartedAt       *time.Time `gorm:"index" json:"startedAt"`
	CompletedAt     *time.Time `json:"completedAt"`
	CreatedAt       time.Time  `gorm:"index" json:"createdAt"`
	UpdatedAt       time.Time  `json:"updatedAt"`
}

type MediaDeliveryMetric struct {
	ID        uint   `gorm:"primaryKey"`
	Day       string `gorm:"size:10;not null;uniqueIndex:idx_media_delivery_bucket,priority:1"`
	Format    string `gorm:"size:8;not null;uniqueIndex:idx_media_delivery_bucket,priority:2"`
	Status    int    `gorm:"not null;uniqueIndex:idx_media_delivery_bucket,priority:3"`
	Requests  int64
	BytesOut  int64
	CreatedAt time.Time
	UpdatedAt time.Time
}

type SystemMinuteMetric struct {
	ID                 uint      `gorm:"primaryKey"`
	Minute             time.Time `gorm:"not null;uniqueIndex"`
	MemAllocBytes      uint64
	MemSysBytes        uint64
	NumGoroutine       int
	GCPauseTotalMs     float64
	LastGCPauseMs      float64
	DBOpenConnections  int
	DBInUseConnections int
	DBIdleConnections  int
	DBWaitCount        int64
	DBWaitDurationMs   float64
	CreatedAt          time.Time
	UpdatedAt          time.Time
}

type DBQueryMinuteMetric struct {
	ID         uint      `gorm:"primaryKey"`
	Minute     time.Time `gorm:"not null;index:idx_db_query_minute_bucket,unique"`
	Route      string    `gorm:"size:120;not null;index:idx_db_query_minute_bucket,unique"`
	QueryGroup string    `gorm:"size:80;not null;index:idx_db_query_minute_bucket,unique"`
	Count      int64     `gorm:"default:0"`
	P50Ms      float64   `gorm:"default:0"`
	P95Ms      float64   `gorm:"default:0"`
	P99Ms      float64   `gorm:"default:0"`
	MaxMs      float64   `gorm:"default:0"`
	CreatedAt  time.Time
	UpdatedAt  time.Time
}

type DailySpikeEvent struct {
	ID            uint       `gorm:"primaryKey"`
	Day           string     `gorm:"size:10;index"`
	TriggerAt     time.Time  `gorm:"index"`
	WindowStart   time.Time  `gorm:"index"`
	WindowEnd     time.Time  `gorm:"index"`
	PushSent      int64      `gorm:"default:0"`
	UploadCount   int64      `gorm:"default:0"`
	FeedReadCount int64      `gorm:"default:0"`
	ErrorCount    int64      `gorm:"default:0"`
	P95PeakMs     float64    `gorm:"default:0"`
	FinalizedAt   *time.Time `gorm:"index"`
	CreatedAt     time.Time
	UpdatedAt     time.Time
}

type DailyTriggerAuditEvent struct {
	ID                  uint       `gorm:"primaryKey" json:"id"`
	Day                 string     `gorm:"size:10;index" json:"day"`
	OccurredAt          time.Time  `gorm:"index;not null" json:"occurredAt"`
	RequestID           string     `gorm:"size:64;index" json:"requestId"`
	Source              string     `gorm:"size:32;index" json:"source"`
	ActorUserID         *uint      `gorm:"index" json:"actorUserId"`
	ActorUsername       string     `gorm:"size:64" json:"actorUsername"`
	AttemptType         string     `gorm:"size:16;index" json:"attemptType"`
	Result              string     `gorm:"size:16;index" json:"result"`
	Reason              string     `gorm:"size:64;index" json:"reason"`
	BeforeTriggeredAt   *time.Time `json:"beforeTriggeredAt"`
	AfterTriggeredAt    *time.Time `json:"afterTriggeredAt"`
	BeforeTriggerSource string     `gorm:"size:32" json:"beforeTriggerSource"`
	AfterTriggerSource  string     `gorm:"size:32" json:"afterTriggerSource"`
	ErrorMessage        string     `gorm:"size:500" json:"errorMessage"`
	ServerInstance      string     `gorm:"size:120;index" json:"serverInstance"`
	MetaJSON            string     `gorm:"type:text" json:"metaJson"`
	CreatedAt           time.Time  `gorm:"index" json:"createdAt"`
}

type Photo struct {
	ID                       uint       `gorm:"primaryKey" json:"id"`
	UserID                   uint       `gorm:"index;not null" json:"userId"`
	User                     User       `json:"user"`
	Day                      string     `gorm:"index;index:idx_photo_day_created,priority:1;size:10;not null" json:"day"`
	PromptOnly               bool       `gorm:"default:false" json:"promptOnly"`
	MomentKind               string     `gorm:"size:16;index" json:"momentKind"`
	UploadClientID           string     `gorm:"size:64;index:idx_photo_user_upload_client" json:"uploadClientId"`
	FilePath                 string     `gorm:"size:255;not null" json:"filePath"`
	SecondPath               string     `gorm:"size:255" json:"secondPath"`
	PrimaryDigest            string     `gorm:"size:64;index" json:"primaryDigest"`
	SecondaryDigest          string     `gorm:"size:64" json:"secondaryDigest"`
	CapsulePreviewPath       string     `gorm:"size:255" json:"capsulePreviewPath"`
	CapsuleSecondPreviewPath string     `gorm:"size:255" json:"capsuleSecondPreviewPath"`
	Caption                  string     `gorm:"size:255" json:"caption"`
	CapsuleMode              string     `gorm:"size:16" json:"capsuleMode"`
	CapsuleVisibleAt         *time.Time `gorm:"index" json:"capsuleVisibleAt"`
	CapsulePrivate           bool       `gorm:"default:false" json:"capsulePrivate"`
	CapsuleGroupRemind       bool       `gorm:"default:false" json:"capsuleGroupRemind"`
	LocationShared           bool       `gorm:"default:false;index" json:"locationShared"`
	LocationLatitude         *float64   `json:"locationLatitude"`
	LocationLongitude        *float64   `json:"locationLongitude"`
	CommunityPost            bool       `gorm:"default:false;index" json:"communityPost"`
	CommunityActivatedAt     *time.Time `gorm:"index" json:"communityActivatedAt"`
	Nsfw                     bool       `gorm:"default:false;index" json:"nsfw"`
	NsfwMarkedByUserID       *uint      `gorm:"index" json:"nsfwMarkedByUserId"`
	NsfwMarkedAt             *time.Time `json:"nsfwMarkedAt"`
	PublicNumber             *string    `gorm:"size:9;uniqueIndex" json:"publicNumber"`
	CapturedAt               *time.Time `gorm:"index" json:"capturedAt"`
	CreatedAt                time.Time  `gorm:"index:idx_photo_day_created,priority:2" json:"createdAt"`
}

type PhotoAttachment struct {
	ID             uint       `gorm:"primaryKey" json:"id"`
	PhotoID        uint       `gorm:"not null;index;index:idx_photo_attachment_photo_sort,priority:1" json:"photoId"`
	UserID         uint       `gorm:"not null;default:0;index" json:"userId"`
	UploadClientID string     `gorm:"size:64;uniqueIndex:idx_attachment_photo_upload_client" json:"uploadClientId"`
	FilePath       string     `gorm:"size:255;not null" json:"filePath"`
	PreviewPath    string     `gorm:"size:255" json:"previewPath"`
	Digest         string     `gorm:"size:64;index" json:"digest"`
	SortOrder      int        `gorm:"not null;index:idx_photo_attachment_photo_sort,priority:2" json:"sortOrder"`
	CapturedAt     *time.Time `gorm:"index" json:"capturedAt"`
	CreatedAt      time.Time  `json:"createdAt"`
}

type PhotoBookmark struct {
	ID                     uint       `gorm:"primaryKey" json:"id"`
	UserID                 uint       `gorm:"not null;index:idx_photo_bookmark_user_photo,unique" json:"userId"`
	PhotoID                uint       `gorm:"not null;index:idx_photo_bookmark_user_photo,unique;index" json:"photoId"`
	Active                 bool       `gorm:"default:true;index" json:"active"`
	SubscriptionSource     string     `gorm:"size:24;default:'manual';index" json:"subscriptionSource"`
	LastActivityAt         *time.Time `gorm:"index" json:"lastActivityAt"`
	AutoExpiresAt          *time.Time `gorm:"index" json:"autoExpiresAt"`
	AutoResubscribeBlocked bool       `gorm:"default:false" json:"autoResubscribeBlocked"`
	CreatedAt              time.Time  `json:"createdAt"`
}

type PhotoMark struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	PhotoID   uint      `gorm:"not null;index:idx_photo_mark_user_photo,unique;index" json:"photoId"`
	UserID    uint      `gorm:"not null;index:idx_photo_mark_user_photo,unique;index" json:"userId"`
	User      User      `json:"user"`
	Color     string    `gorm:"size:7;not null" json:"color"`
	Surface   string    `gorm:"size:16;not null;default:'frame'" json:"surface"`
	CenterX   float64   `gorm:"not null" json:"centerX"`
	CenterY   float64   `gorm:"not null" json:"centerY"`
	RadiusX   float64   `gorm:"not null" json:"radiusX"`
	RadiusY   float64   `gorm:"not null" json:"radiusY"`
	Rotation  float64   `gorm:"not null" json:"rotation"`
	Seed      int64     `gorm:"not null" json:"seed"`
	Layer     int64     `gorm:"not null;index" json:"layer"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type PhotoPaint struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	PhotoID     uint      `gorm:"not null;index:idx_photo_paint_user_photo,unique;index" json:"photoId"`
	UserID      uint      `gorm:"not null;index:idx_photo_paint_user_photo,unique;index" json:"userId"`
	User        User      `json:"user"`
	Color       string    `gorm:"size:7;not null" json:"color"`
	Surface     string    `gorm:"size:16;not null;default:'frame'" json:"surface"`
	StrokeWidth float64   `gorm:"not null" json:"strokeWidth"`
	PathsJSON   string    `gorm:"type:text;not null" json:"pathsJson"`
	CreatedAt   time.Time `json:"createdAt"`
	UpdatedAt   time.Time `json:"updatedAt"`
}

type PhotoReaction struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	PhotoID   uint      `gorm:"index:idx_photo_user_reaction,unique;not null" json:"photoId"`
	UserID    uint      `gorm:"index:idx_photo_user_reaction,unique;not null" json:"userId"`
	Emoji     string    `gorm:"size:16;not null" json:"emoji"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type PhotoFotomoji struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	PhotoID   uint      `gorm:"index:idx_photo_user_fotomoji,unique;not null" json:"photoId"`
	UserID    uint      `gorm:"index:idx_photo_user_fotomoji,unique;not null" json:"userId"`
	User      User      `json:"user"`
	Emoji     string    `gorm:"size:16;not null" json:"emoji"`
	FilePath  string    `gorm:"size:255;not null" json:"filePath"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type UserFotomojiTemplate struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	UserID          uint      `gorm:"index:idx_user_fotomoji_template,unique;not null" json:"userId"`
	Emoji           string    `gorm:"size:16;index:idx_user_fotomoji_template,unique;not null" json:"emoji"`
	FilePath        string    `gorm:"size:255;not null" json:"filePath"`
	ActiveVersionID uint      `gorm:"index;default:0" json:"activeVersionId"`
	CreatedAt       time.Time `json:"createdAt"`
	UpdatedAt       time.Time `json:"updatedAt"`
}

type UserFotomojiTemplateVersion struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	UserID    uint      `gorm:"index:idx_user_fotomoji_template_versions_lookup,priority:1;not null" json:"userId"`
	Emoji     string    `gorm:"size:16;index:idx_user_fotomoji_template_versions_lookup,priority:2;not null" json:"emoji"`
	FilePath  string    `gorm:"size:255;not null" json:"filePath"`
	CreatedAt time.Time `json:"createdAt"`
}

type PhotoComment struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	PhotoID         uint      `gorm:"index;not null" json:"photoId"`
	UserID          uint      `gorm:"index;uniqueIndex:idx_photo_comment_user_client,priority:1;not null" json:"userId"`
	User            User      `json:"user"`
	Body            string    `gorm:"size:500;not null" json:"body"`
	ClientCommentID *string   `gorm:"size:64;uniqueIndex:idx_photo_comment_user_client,priority:2" json:"-"`
	CreatedAt       time.Time `json:"createdAt"`
}

type ChatMessage struct {
	ID                uint       `gorm:"primaryKey" json:"id"`
	UserID            uint       `gorm:"index;not null;index:idx_chat_msg_user_client,unique" json:"userId"`
	User              User       `json:"user"`
	Body              string     `gorm:"type:text;not null" json:"body"`
	Source            string     `gorm:"size:16;not null;default:'user';index" json:"source"`
	MessageType       string     `gorm:"size:16;not null;default:'text';index" json:"type"`
	PollQuestion      string     `gorm:"size:280" json:"pollQuestion"`
	PollAllowMultiple bool       `gorm:"default:false" json:"pollAllowMultiple"`
	PollClosedAt      *time.Time `json:"pollClosedAt"`
	ClientMessageID   *string    `gorm:"size:64;index:idx_chat_msg_user_client,unique" json:"-"`
	CreatedAt         time.Time  `json:"createdAt"`
}

type ChatPollOption struct {
	ID            uint      `gorm:"primaryKey" json:"id"`
	ChatMessageID uint      `gorm:"index;not null" json:"chatMessageId"`
	OptionText    string    `gorm:"size:120;not null" json:"optionText"`
	SortOrder     int       `gorm:"default:0;index" json:"sortOrder"`
	CreatedAt     time.Time `json:"createdAt"`
}

type ChatPollVote struct {
	ID            uint      `gorm:"primaryKey" json:"id"`
	ChatMessageID uint      `gorm:"index:idx_poll_vote_unique,unique;not null" json:"chatMessageId"`
	OptionID      uint      `gorm:"index:idx_poll_vote_unique,unique;index;not null" json:"optionId"`
	UserID        uint      `gorm:"index:idx_poll_vote_unique,unique;index;not null" json:"userId"`
	CreatedAt     time.Time `json:"createdAt"`
	UpdatedAt     time.Time `json:"updatedAt"`
}

type ChatCommand struct {
	ID             uint       `gorm:"primaryKey" json:"id"`
	Name           string     `gorm:"size:64;not null" json:"name"`
	Command        string     `gorm:"uniqueIndex;size:64;not null" json:"command"`
	Action         string     `gorm:"size:32;not null" json:"action"`
	Enabled        bool       `gorm:"default:true" json:"enabled"`
	RequireAdmin   bool       `gorm:"default:false" json:"requireAdmin"`
	SendPush       bool       `gorm:"default:false" json:"sendPush"`
	PostChat       bool       `gorm:"default:true" json:"postChat"`
	PushText       string     `gorm:"size:255" json:"pushText"`
	ResponseText   string     `gorm:"size:255" json:"responseText"`
	CooldownSecond int        `gorm:"default:0" json:"cooldownSecond"`
	LastUsedAt     *time.Time `json:"lastUsedAt"`
	CreatedAt      time.Time  `json:"createdAt"`
	UpdatedAt      time.Time  `json:"updatedAt"`
}

type SpecialMomentRequest struct {
	ID          uint      `gorm:"primaryKey" json:"id"`
	UserID      uint      `gorm:"index;not null" json:"userId"`
	User        User      `json:"user"`
	RequestedAt time.Time `gorm:"index;not null" json:"requestedAt"`
	CreatedAt   time.Time `json:"createdAt"`
}

type HubSystemEvent struct {
	ID         uint      `gorm:"primaryKey" json:"id"`
	EventType  string    `gorm:"size:48;index;not null" json:"eventType"`
	Scope      string    `gorm:"size:24;index;not null;default:'global'" json:"scope"`
	Title      string    `gorm:"size:160;not null" json:"title"`
	Body       string    `gorm:"size:500" json:"body"`
	TargetURL  string    `gorm:"size:500" json:"targetUrl"`
	MetaJSON   string    `gorm:"type:text" json:"metaJson"`
	OccurredAt time.Time `gorm:"index;not null" json:"occurredAt"`
	CreatedAt  time.Time `gorm:"index" json:"createdAt"`
	UpdatedAt  time.Time `json:"updatedAt"`
}

type ClientDebugLog struct {
	ID         uint      `gorm:"primaryKey" json:"id"`
	UserID     uint      `gorm:"index;not null" json:"userId"`
	User       User      `json:"user"`
	DeviceName string    `gorm:"size:120" json:"deviceName"`
	AppVersion string    `gorm:"size:40" json:"appVersion"`
	SessionID  string    `gorm:"size:64;index" json:"sessionId"`
	RequestID  string    `gorm:"size:64;index" json:"requestId"`
	Type       string    `gorm:"size:32;index;not null" json:"type"`
	Message    string    `gorm:"size:500;not null" json:"message"`
	Meta       string    `gorm:"size:4000" json:"meta"`
	CreatedAt  time.Time `gorm:"index" json:"createdAt"`
}

type UserReport struct {
	ID                uint      `gorm:"primaryKey" json:"id"`
	UserID            uint      `gorm:"index;not null" json:"userId"`
	User              User      `json:"user"`
	Type              string    `gorm:"size:16;index;not null" json:"type"`
	PhotoID           *uint     `gorm:"index" json:"photoId"`
	Photo             Photo     `json:"photo"`
	Body              string    `gorm:"size:1000;not null" json:"body"`
	Source            string    `gorm:"size:32;not null;default:'chat_prefix'" json:"source"`
	Status            string    `gorm:"size:16;index;not null;default:'open'" json:"status"`
	GithubIssueNumber *int      `json:"githubIssueNumber"`
	CreatedAt         time.Time `gorm:"index" json:"createdAt"`
	UpdatedAt         time.Time `json:"updatedAt"`
}
