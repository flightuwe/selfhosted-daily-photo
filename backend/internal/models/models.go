package models

import "time"

type User struct {
	ID                            uint       `gorm:"primaryKey" json:"id"`
	Username                      string     `gorm:"uniqueIndex;size:64;not null" json:"username"`
	PasswordHash                  string     `gorm:"not null" json:"-"`
	IsAdmin                       bool       `gorm:"default:false" json:"isAdmin"`
	FavoriteColor                 string     `gorm:"size:7;default:'#1F5FBF'" json:"favoriteColor"`
	ChatPushEnabled               bool       `gorm:"default:false" json:"chatPushEnabled"`
	InviteRegistrationPushEnabled bool       `gorm:"default:false" json:"inviteRegistrationPushEnabled"`
	PhotoReactionPushEnabled      bool       `gorm:"default:false" json:"photoReactionPushEnabled"`
	PhotoCommentPushEnabled       bool       `gorm:"default:false" json:"photoCommentPushEnabled"`
	AllowPhotoDownload            bool       `gorm:"default:false" json:"allowPhotoDownload"`
	AvatarPath                    string     `gorm:"size:255" json:"avatarUrl"`
	Bio                           string     `gorm:"size:280" json:"bio"`
	StatusText                    string     `gorm:"size:120" json:"statusText"`
	StatusEmoji                   string     `gorm:"size:16" json:"statusEmoji"`
	StatusExpiresAt               *time.Time `json:"statusExpiresAt"`
	ProfileVisible                bool       `gorm:"default:false" json:"profileVisible"`
	AvatarVisible                 bool       `gorm:"default:false" json:"avatarVisible"`
	BioVisible                    bool       `gorm:"default:false" json:"bioVisible"`
	StatusVisible                 bool       `gorm:"default:false" json:"statusVisible"`
	QuietHoursEnabled             bool       `gorm:"default:false" json:"quietHoursEnabled"`
	QuietHoursStart               string     `gorm:"size:5;default:'22:00'" json:"quietHoursStart"`
	QuietHoursEnd                 string     `gorm:"size:5;default:'07:00'" json:"quietHoursEnd"`
	CreatedAt                     time.Time  `json:"createdAt"`
}

type InviteCode struct {
	ID        uint       `gorm:"primaryKey" json:"id"`
	UserID    uint       `gorm:"index;not null" json:"userId"`
	User      User       `json:"user"`
	Code      string     `gorm:"uniqueIndex;size:24;not null" json:"code"`
	UsedByID  *uint      `gorm:"index" json:"usedById"`
	UsedAt    *time.Time `json:"usedAt"`
	Active    bool       `gorm:"default:true;index" json:"active"`
	CreatedAt time.Time  `json:"createdAt"`
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

type AppSettings struct {
	ID                      uint      `gorm:"primaryKey" json:"id"`
	PromptWindowStartHour   int       `gorm:"default:8" json:"promptWindowStartHour"`
	PromptWindowEndHour     int       `gorm:"default:20" json:"promptWindowEndHour"`
	UploadWindowMinutes     int       `gorm:"default:10" json:"uploadWindowMinutes"`
	FeedCommentPreviewLimit int       `gorm:"default:10" json:"feedCommentPreviewLimit"`
	PromptNotificationText  string    `gorm:"size:255;default:'Zeit fuer dein Daily Foto'" json:"promptNotificationText"`
	MaxUploadBytes          int64     `gorm:"default:0" json:"maxUploadBytes"`
	ChatCommandEnabled      bool      `gorm:"default:false" json:"chatCommandEnabled"`
	ChatCommandValue        string    `gorm:"size:64;default:'-moment'" json:"chatCommandValue"`
	ChatCommandTrigger      bool      `gorm:"default:true" json:"chatCommandTrigger"`
	ChatCommandSendPush     bool      `gorm:"default:true" json:"chatCommandSendPush"`
	ChatCommandPushText     string    `gorm:"size:255;default:'{user} hat einen Moment angefordert. Jetzt 10 Minuten posten.'" json:"chatCommandPushText"`
	ChatCommandEchoChat     bool      `gorm:"default:true" json:"chatCommandEchoChat"`
	ChatCommandEchoText     string    `gorm:"size:255;default:'Moment wurde von {user} angefordert.'" json:"chatCommandEchoText"`
	CreatedAt               time.Time `json:"createdAt"`
	UpdatedAt               time.Time `json:"updatedAt"`
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

type Photo struct {
	ID                 uint       `gorm:"primaryKey" json:"id"`
	UserID             uint       `gorm:"index;not null" json:"userId"`
	User               User       `json:"user"`
	Day                string     `gorm:"index;size:10;not null" json:"day"`
	PromptOnly         bool       `gorm:"default:false" json:"promptOnly"`
	FilePath           string     `gorm:"size:255;not null" json:"filePath"`
	SecondPath         string     `gorm:"size:255" json:"secondPath"`
	CapsulePreviewPath string     `gorm:"size:255" json:"capsulePreviewPath"`
	CapsuleSecondPreviewPath string `gorm:"size:255" json:"capsuleSecondPreviewPath"`
	Caption            string     `gorm:"size:255" json:"caption"`
	CapsuleMode        string     `gorm:"size:16" json:"capsuleMode"`
	CapsuleVisibleAt   *time.Time `json:"capsuleVisibleAt"`
	CapsulePrivate     bool       `gorm:"default:false" json:"capsulePrivate"`
	CapsuleGroupRemind bool       `gorm:"default:false" json:"capsuleGroupRemind"`
	CreatedAt          time.Time  `json:"createdAt"`
}

type PhotoReaction struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	PhotoID   uint      `gorm:"index:idx_photo_user_reaction,unique;not null" json:"photoId"`
	UserID    uint      `gorm:"index:idx_photo_user_reaction,unique;not null" json:"userId"`
	Emoji     string    `gorm:"size:16;not null" json:"emoji"`
	CreatedAt time.Time `json:"createdAt"`
	UpdatedAt time.Time `json:"updatedAt"`
}

type PhotoComment struct {
	ID        uint      `gorm:"primaryKey" json:"id"`
	PhotoID   uint      `gorm:"index;not null" json:"photoId"`
	UserID    uint      `gorm:"index;not null" json:"userId"`
	User      User      `json:"user"`
	Body      string    `gorm:"size:500;not null" json:"body"`
	CreatedAt time.Time `json:"createdAt"`
}

type ChatMessage struct {
	ID              uint      `gorm:"primaryKey" json:"id"`
	UserID          uint      `gorm:"index;not null;index:idx_chat_msg_user_client,unique" json:"userId"`
	User            User      `json:"user"`
	Body            string    `gorm:"size:500;not null" json:"body"`
	Source          string    `gorm:"size:16;not null;default:'user';index" json:"source"`
	ClientMessageID *string   `gorm:"size:64;index:idx_chat_msg_user_client,unique" json:"-"`
	CreatedAt       time.Time `json:"createdAt"`
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

type ClientDebugLog struct {
	ID         uint      `gorm:"primaryKey" json:"id"`
	UserID     uint      `gorm:"index;not null" json:"userId"`
	User       User      `json:"user"`
	DeviceName string    `gorm:"size:120" json:"deviceName"`
	AppVersion string    `gorm:"size:40" json:"appVersion"`
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
	Body              string    `gorm:"size:1000;not null" json:"body"`
	Source            string    `gorm:"size:32;not null;default:'chat_prefix'" json:"source"`
	Status            string    `gorm:"size:16;index;not null;default:'open'" json:"status"`
	GithubIssueNumber *int      `json:"githubIssueNumber"`
	CreatedAt         time.Time `gorm:"index" json:"createdAt"`
	UpdatedAt         time.Time `json:"updatedAt"`
}
