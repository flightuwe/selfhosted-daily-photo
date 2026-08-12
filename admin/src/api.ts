import type {
  DistributionAuditItem,
  DistributionProfile,
  DistributionProfilesResponse,
  DistributionTestResult,
} from "./distribution/distributionTypes";

export type AuthResponse = {
  token: string;
  user: { id: number; username: string; isAdmin: boolean };
};

export type UserPromptRule = {
  id: string;
  action?: "diagnostics_consent" | "add_recovery_email";
  enabled: boolean;
  triggerType: "app_version" | "app_start" | "time_based";
  title: string;
  body: string;
  confirmLabel: string;
  declineLabel: string;
  cooldownHours: number;
  priority: number;
};

export type Settings = {
  promptWindowStartHour: number;
  promptWindowEndHour: number;
  uploadWindowMinutes: number;
  feedCommentPreviewLimit: number;
  performanceTrackingEnabled: boolean;
  performanceTrackingWindowMinutes: number;
  performanceTrackingOneShot: boolean;
  promptNotificationText: string;
  maxUploadBytes: number;
  chatMessageMaxLength: number;
  chatMessageUnlimited: boolean;
  postMediaMaxCount: number;
  postMediaUnlimited: boolean;
  chatCommandEnabled: boolean;
  chatCommandValue: string;
  chatCommandTrigger: boolean;
  chatCommandSendPush: boolean;
  chatCommandPushText: string;
  chatCommandEchoChat: boolean;
  chatCommandEchoText: string;
  userPromptRules: UserPromptRule[];
  migrationEnabled?: boolean;
  migrationStartedAt?: string | null;
  migrationUntil?: string | null;
  migrationAutoOffEnabled?: boolean;
  migrationTargetBaseUrl?: string;
  migrationDownloadUrl?: string;
  migrationPushTitle?: string;
  migrationPushBody?: string;
  migrationScreenTitle?: string;
  migrationScreenBody?: string;
  migrationRequirePromptFirst?: boolean;
  migrationExpectedSource?: string;
  migrationBaselineUserCount?: number;
};

export type AdminMediaRenditions = {
  runtimeAvailable: boolean;
  avifEnabled: boolean;
  operatorDisabled: boolean;
	backgroundPaused?: boolean;
	backgroundPolicy?: { paused?: boolean; idle?: boolean; allowed?: boolean; nextEligibleAt?: string; nightWindow?: string; daytimeIntervalSeconds?: number };
	queueTelemetry?: {
		pending?: number; paused?: number;
		running?: { id?: number; sourcePath?: string; variant?: string; format?: string; startedAt?: string; ageSeconds?: number };
		lastCompleted?: { id?: number; sourcePath?: string; variant?: string; format?: string; completedAt?: string; byteSize?: number; durationMs?: number };
		throughput?: { completedLastHour?: number; completedLast24Hours?: number };
		eta?: { policyCapacityPerNight?: number; conservativeNights?: number; daytimeCapacityMax?: number; basis?: string };
	};
  autoPaused?: boolean;
  autoPauseReason?: string;
  renditions: Record<string, any>;
  recentConversions: Array<{ id: number; sourcePath: string; variant: string; purpose: string; format: string; width: number; status: string; byteSize: number; attempts: number; lastError?: string; createdAt: string; updatedAt: string; completedAt?: string | null; lastRequestedAt?: string | null; photoId?: number; day?: string; postCreatedAt?: string; userId?: number; username?: string; postFound?: boolean }>;
  viewerPreferences: Array<{ preference: string; users: number }>;
  serverNow: string;
};

export type AdminStats = {
  users: number;
  photos: number;
  devices: number;
  prompts: number;
  totalImages: number;
  runningDays: number;
  storageBytes: number;
  diagnosticsConsentUsers?: number;
  diagnosticsConsentRate?: number;
};

export type AdminSearchScope =
  "users" | "reports" | "commands" | "history" | "posts";

export type AdminSearchResult = {
  type: AdminSearchScope;
  id: string;
  label: string;
  meta?: string;
  target: {
    tab: "users" | "reports" | "commands" | "history" | "feed";
    day?: string;
  };
};

export type AdminUser = {
  id: number;
  username: string;
  isAdmin: boolean;
  createdAt: string;
  invitedById?: number;
  invitedBy?: string;
  invitedAt?: string;
  photoCount: number;
  deviceCount: number;
  deviceNames?: string[];
  deviceDetails?: Array<{ name: string; appVersion?: string }>;
  lastAppVersion?: string;
  lastError?: string;
  lastErrorAt?: string;
  lastProfileOkAt?: string;
  emailMasked?: string;
  emailVerifiedAt?: string | null;
  emailPending?: boolean;
  pendingEmailMasked?: string;
  newsletterStatus?: "pending" | "subscribed" | "unsubscribed";
  newsletterConfirmedAt?: string | null;
  distributionProfileId?: number | null;
};

export type AdminUserEmailDetails = {
  email: string;
  emailVerifiedAt?: string | null;
  pendingEmail: string;
  pendingEmailRequestedAt?: string | null;
  newsletterStatus: "pending" | "subscribed" | "unsubscribed";
  newsletterConfirmedAt?: string | null;
};

export type AdminEmailSettings = {
  enabled: boolean;
  provider?: "custom" | "posteo";
  host: string;
  port: number;
  tlsMode: "starttls" | "implicit";
  authMode: "auto" | "plain" | "login";
  username: string;
  password?: string;
  passwordConfigured: boolean;
  clearPassword?: boolean;
  fromName: string;
  fromAddress: string;
  replyTo: string;
  actionBaseUrl: string;
  lastTestAt?: string | null;
  lastTestOk?: boolean;
  lastTestStage?: string;
  lastTestError?: string;
  lastDeliveryAt?: string | null;
  lastDeliveryError?: string;
};

export type AdminEmailStatus = {
  queueLength: number;
  failedJobs: number;
  deliveryEnabled: boolean;
  recentFailures?: Array<{ id: number; kind: string; status: string; attempts: number; lastStage: string; smtpResultCode: number; lastError: string; updatedAt: string }>;
};

export type AdminUserAccessToken = {
  userId: number;
  username: string;
  isAdmin: boolean;
  token: string;
  expiresAt?: string | null;
};

export type AdminTriggerPromptResponse = {
  prompt?: any;
  settings?: any;
  mode?: "broadcast_all" | "silent" | "targeted_users";
  targetUsers?: number[];
  devices?: number;
  provider?: string;
  sentTo?: number;
  failed?: number;
  invalidRemoved?: number;
  notificationErr?: string;
};

export type DebugLogItem = {
  id: number;
  createdAt: string;
  type: string;
  message: string;
  meta?: string;
  appVersion?: string;
  deviceName?: string;
  sessionId?: string;
  requestId?: string;
  user: { id: number; username: string };
};

export type DebugLogsResponse = {
  items: DebugLogItem[];
  sinceHours: number;
  since: string;
  serverNow: string;
};

export type DebugLogSummaryItem = {
  count: number;
  firstSeenAt: string;
  lastSeenAt: string;
  sampleMessage: string;
  sampleMeta?: string;
  failureFamily?: string;
  topTransport?: string;
  deviceName?: string;
  signature: string;
  user: { id: number; username: string };
};

export type DebugLogSummaryResponse = {
  items: DebugLogSummaryItem[];
  sinceHours: number;
  since: string;
  serverNow: string;
};

export type UploadTimelineItem = {
  id: number;
  timelineId: string;
  createdAt: string;
  type: string;
  stage: string;
  source: "direct" | "queue" | string;
  message: string;
  meta?: string;
  appVersion?: string;
  deviceName?: string;
  sessionId?: string;
  requestId?: string;
  responseRequestId?: string;
  uploadClientId?: string;
  queueItemId?: string;
  kind?: string;
  failureClass?: string;
  failureFamily?: string;
  securityFailureClass?: string;
  networkStateClass?: string;
  retrySuppressedReason?: string;
  userAdviceShown?: boolean | null;
  networkKind?: string;
  aggregateCount?: number;
  attempt?: number;
  bytesTotal?: number;
  bytesSent?: number;
  durationMs?: number;
  pingMs?: number;
  pingFailure?: string;
  capturedAt?: string;
  queuedAt?: string;
  firstSeenAt?: string;
  lastSeenAt?: string;
  httpCode?: number;
  network?: {
    activeNetwork?: boolean | null;
    internet?: boolean | null;
    validated?: boolean | null;
    metered?: boolean | null;
    stable?: boolean | null;
    transport?: string;
    downKbps?: number | null;
    upKbps?: number | null;
  };
  user: { id: number; username: string };
};

export type UploadTimelineResponse = {
  items: UploadTimelineItem[];
  sinceHours: number;
  since: string;
  serverNow: string;
  summary: {
    total: number;
    uniqueUploads: number;
    failedCount: number;
    waitingForNetworkCount: number;
    liveCount: number;
  };
};

export type AdminReportItem = {
  id: number;
  type: "bug" | "idea" | "post";
  body: string;
  source: string;
  status: "open" | "in_review" | "done" | "rejected";
  githubIssueNumber?: number | null;
  createdAt: string;
  updatedAt: string;
  user: { id: number; username: string; favoriteColor?: string };
  photoId?: number;
  photo?: FeedPhoto;
  photoUser?: { id: number; username: string; favoriteColor?: string };
};

export type FeedPhotoMediaItem = {
  id?: string;
  url: string;
  previewUrl?: string;
  sourceKind?: string;
  capturedAt?: string;
};

export type FeedPhoto = {
  id: number;
  day: string;
  promptOnly: boolean;
  caption?: string;
  url: string;
  secondUrl?: string;
  media?: FeedPhotoMediaItem[];
  mediaCount?: number;
  nsfw?: boolean;
  nsfwMarkedByUserId?: number | null;
  nsfwMarkedAt?: string | null;
  createdAt: string;
  publicNumber?: string;
  locationShared?: boolean;
  locationDisplay?: string;
  locationMapsUrl?: string;
};

export type FeedItem = {
  isLate: boolean;
  triggerSource?: string;
  requestedByUser?: string;
  momentKind?: "daily" | "special" | string;
  photo: FeedPhoto;
  user: { id: number; username: string };
};

export type MonthlyRecap = {
  month: string;
  monthLabel: string;
  yourMoments: number;
  mostReliableUser?: {
    id: number;
    username: string;
    favoriteColor?: string;
    count: number;
  };
  topSpontaneous: Array<{
    day: string;
    userId: number;
    username: string;
    minutesAfterTrigger: number;
    createdAt: string;
  }>;
};

export type AdminFeedResponse = {
  items: FeedItem[];
  monthRecap?: MonthlyRecap | null;
};

export type AdminLocationItem = {
  photoId: number;
  day: string;
  createdAt: string;
  locationLatitude: number;
  locationLongitude: number;
  locationDisplay: string;
  locationMapsUrl: string;
  user: { id: number; username: string; favoriteColor?: string };
  photo: FeedPhoto;
};

export type ChatItem = {
  id: number;
  body: string;
  createdAt: string;
  user: { id: number; username: string };
};

export type AdminPollVoter = {
  userId: number;
  username: string;
  favoriteColor?: string;
  votedAt: string;
};

export type AdminPollOption = {
  id: number;
  text: string;
  sortOrder: number;
  votes: number;
  voters: AdminPollVoter[];
};

export type AdminPollItem = {
  id: number;
  question: string;
  allowMultiSelect: boolean;
  isClosed: boolean;
  closedAt?: string | null;
  createdAt: string;
  source: string;
  body?: string;
  totalVotes: number;
  totalVoters: number;
  creator: {
    id: number;
    username: string;
    favoriteColor?: string;
  };
  options: AdminPollOption[];
};

export type AdminPollResponse = {
  items: AdminPollItem[];
  count: number;
  limit: number;
};

export type ChatSendResult = {
  id?: number;
  body?: string;
  source?: string;
  command?: boolean;
  report?: boolean;
  reportId?: number;
  reportType?: string;
  reportStatus?: string;
  message?: string;
};

export type CalendarItem = {
  day: string;
  plannedAt: string;
  isManual: boolean;
  source: "auto" | "manual";
  triggeredAt?: string | null;
  uploadUntil?: string | null;
  triggerSource?: string;
  requestedByUser?: string;
  momentKind?: "daily" | "special" | string;
};

export type AdminHistoryUserActivity = {
  userId: number;
  username: string;
  firstSeenAt?: string | null;
  lastSeenAt?: string | null;
  requestCount: number;
  posted: boolean;
  postedPrompt: boolean;
  postedExtra: boolean;
};

export type AdminHistoryAnalytics = {
  promptPhotoRatio: number;
  extraPhotoRatio: number;
  capsulePhotoRatio: number;
  promptUserRatio: number;
  extraUserRatio: number;
  avgRequestsPerOnline: number;
  triggerDelayMinutes: number;
  onTimeTrigger: boolean;
  hasTriggerPerformance: boolean;
  totalRequests: number;
};

export type AdminHistoryDay = {
  day: string;
  plannedAt?: string | null;
  triggeredAt?: string | null;
  uploadUntil?: string | null;
  source: "auto" | "manual";
  triggerSource?: string;
  requestedByUser?: string;
  momentKind?: "daily" | "special" | string;
  onlineUsersCount?: number | null;
  postedUsersCount: number;
  dailyMomentUsersCount: number;
  extraUsersCount: number;
  photoCount: number;
  dailyMomentPhotoCount: number;
  extraPhotoCount: number;
  timeCapsuleCount: number;
  privateCapsuleCount: number;
  commentCount: number;
  reactionCount: number;
  chatMessageCount: number;
  debugErrorCount?: number;
  debugConnectivityCount?: number;
  debugCancelledCount?: number;
  debugServerCount?: number;
  debugCrashCount?: number;
  debugClientCount?: number;
  triggerAttemptCount?: number;
  triggerBlockedCount?: number;
  triggerFailedCount?: number;
  multipleTriggerAlert?: boolean;
  dailyTriggerAttemptCount?: number;
  dailyTriggerBlockedCount?: number;
  dailyTriggerFailedCount?: number;
  dailyTriggeredCount?: number;
  specialTriggerAttemptCount?: number;
  specialTriggerBlockedCount?: number;
  specialTriggerFailedCount?: number;
  specialTriggeredCount?: number;
  dailyTriggeredAt?: string | null;
  specialTriggeredAt?: string | null;
  dailyPending?: boolean;
  dailyMultipleTriggerAlert?: boolean;
  specialMultipleTriggerAlert?: boolean;
  onlineTrackingAvailable: boolean;
  userActivity?: AdminHistoryUserActivity[] | null;
  analytics?: AdminHistoryAnalytics;
};

export type AdminTriggerAuditItem = {
  id: number;
  day: string;
  occurredAt: string;
  requestId?: string;
  source: string;
  actorUserId?: number | null;
  actorUsername?: string;
  attemptType: "scheduler" | "admin" | "chat" | "special" | "reset" | string;
  result: "triggered" | "blocked" | "failed" | string;
  reason: string;
  beforeTriggeredAt?: string | null;
  afterTriggeredAt?: string | null;
  beforeTriggerSource?: string;
  afterTriggerSource?: string;
  errorMessage?: string;
  serverInstance?: string;
  metaJson?: string;
};

export type AdminTriggerAuditResponse = {
  items: AdminTriggerAuditItem[];
  from: string;
  to: string;
  count: number;
  limit: number;
};

export type AdminTriggerAuditSummary = {
  days: number;
  from: string;
  to: string;
  summary: {
    attempts: number;
    triggered: number;
    blocked: number;
    failed: number;
    dbLocked: number;
    duplicateAttempts: number;
    multipleAttemptDays: number;
    duplicateAttemptsDaily?: number;
    duplicateAttemptsSpecial?: number;
    multipleAttemptDaysDaily?: number;
    multipleAttemptDaysSpecial?: number;
    dailyAttempts?: number;
    dailyTriggered?: number;
    dailyBlocked?: number;
    dailyFailed?: number;
    specialAttempts?: number;
    specialTriggered?: number;
    specialBlocked?: number;
    specialFailed?: number;
    blockedRate: number;
    failedRate: number;
  };
  byDay: Array<{
    day: string;
    attempts: number;
    triggered: number;
    blocked: number;
    failed: number;
    dbLocked: number;
  }>;
  bySource: Array<{ source: string; count: number }>;
};

export type AdminIncidentExportStatus = {
  meta: {
    schemaVersion: string;
    generatedAt: string;
    serverVersion: string;
    timezone: string;
    requestId: string;
    from: string;
    to: string;
    includeGateway: boolean;
  };
  status: {
    duplicateAttempts: number;
    multipleAttemptDays: number;
    duplicateAttemptsDaily?: number;
    duplicateAttemptsSpecial?: number;
    multipleAttemptDaysDaily?: number;
    multipleAttemptDaysSpecial?: number;
    dailyAttempts?: number;
    dailyTriggered?: number;
    dailyBlocked?: number;
    dailyFailed?: number;
    specialAttempts?: number;
    specialTriggered?: number;
    specialBlocked?: number;
    specialFailed?: number;
    dailyPending?: boolean;
    lastTriggerSource?: string;
    gatewayLogAvailable: boolean;
    backendLogAvailable: boolean;
  };
  collectionWarnings: string[];
};

export type AdminTriggerRuntimeLease = {
  ownerId?: string;
  heartbeatAt?: string;
  expiresAt?: string;
  isExpired?: boolean;
  isOwner?: boolean;
};

export type AdminTriggerRuntimeState = {
  serverInstance?: string;
  leaseName?: string;
  leaseTimeoutSec?: number;
  lastTickAt?: string;
  lastTickResult?: string;
  tickRunning?: boolean;
  autoPaused?: boolean;
  autoPauseReason?: string;
  autoPausedAt?: string | null;
  lease?: AdminTriggerRuntimeLease;
};

export type AdminTriggerRuntimeResponse = {
  serverNow: string;
  windowMinutes: number;
  runtime: AdminTriggerRuntimeState;
  recent: {
    attempts: number;
    blocked: number;
    failed: number;
    dbLocked: number;
    duplicateToday?: number;
    blockRate?: number;
    byReason?: Record<string, number>;
    byReasonRate?: Record<string, number>;
  };
  slo?: {
    evaluatedAt?: string;
    windowMinutes?: number;
    status?: "ok" | "breach";
    thresholds?: {
      duplicateToday?: number;
      blockRate?: number;
      dbLocked?: number;
    };
    violations?: Array<{
      id: string;
      severity: "low" | "medium" | "high" | string;
      threshold: number;
      observed: number;
      unit: string;
    }>;
  };
  dispatch: {
    kind: string;
    last?: {
      day?: string;
      kind?: string;
      source?: string;
      status?: string;
      sentCount?: number;
      failedCount?: number;
      errorMessage?: string;
      serverInstance?: string;
      updatedAt?: string;
    };
  };
};

export type AdminHistoryLeaderboardEntry = {
  userId: number;
  username: string;
  postedDays: number;
  promptDays: number;
  extraDays: number;
  onlineDays?: number;
  reliabilityScore?: number;
  extraBiasScore?: number;
  participation7d?: number;
  participation30d?: number;
  participationDelta?: number;
};

export type AdminHistoryAnomaly = {
  day: string;
  severity: "low" | "medium" | "high";
  reason: string;
  details?: string;
};

export type AdminHistoryTimeSeriesPoint = {
  day: string;
  onlineUsers: number;
  postedUsers: number;
  dailyMomentUsers: number;
  extraUsers: number;
  photoCount: number;
  dailyMomentPhotos: number;
  extraPhotos: number;
  capsulePhotos: number;
  debugErrors: number;
  triggerDelayMin: number;
  onTimeTrigger: boolean;
};

export type AdminHistoryConversionPoint = {
  day: string;
  onlineUsers: number;
  postedUsers: number;
  dailyMomentUsers: number;
  extraUsers: number;
};

export type AdminHistoryDistribution = {
  photoMix: {
    promptRatio: number;
    extraRatio: number;
    capsuleRatio: number;
  };
  userMix: {
    promptRatio: number;
    extraRatio: number;
  };
  rawTotals: {
    photos: number;
    dailyMomentPhotos: number;
    extraPhotos: number;
    capsulePhotos: number;
    postedUsersSum: number;
    onlineUsersSum: number;
  };
};

export type AdminHistoryReliability = {
  daysAnalyzed: number;
  daysWithPosts: number;
  daysWithTriggerPerformance: number;
  onTimeTriggerDays: number;
  onTimeTriggerRate: number;
  avgAbsoluteTriggerDelayMinutes: number;
  debugErrorIndicators: number;
  errorIndicatorRatePerDay: number;
  connectivityIndicators?: number;
  cancelledIndicators?: number;
  avgPostedUsersPerDay: number;
  avgOnlineUsersPerDay: number;
  avgRequestsPerOnlineUser: number;
};

export type AdminHistoryCohortEntry = {
  userId: number;
  username: string;
  postedDays: number;
  promptDays: number;
  extraDays: number;
  participation7d: number;
  participation30d: number;
  participationDelta: number;
};

export type AdminHistoryResponse = {
  items: AdminHistoryDay[];
  days: number;
  offset: number;
  excludeEmpty?: boolean;
  debugLogSample?: { loaded: number; total: number; limit: number; truncated: boolean };
  onlineTrackingSince?: string;
  leaderboard?: {
    reliableTop?: AdminHistoryLeaderboardEntry[];
    extraHeavyTop?: AdminHistoryLeaderboardEntry[];
  };
  timeseries?: AdminHistoryTimeSeriesPoint[];
  distribution?: AdminHistoryDistribution;
  conversion?: AdminHistoryConversionPoint[];
  reliability?: AdminHistoryReliability;
  cohorts?: AdminHistoryCohortEntry[];
  anomalies?: AdminHistoryAnomaly[];
};

export type AdminTimeCapsuleItem = {
  photoId: number;
  day: string;
  capsuleMode?: string;
  capsuledAt: string;
  unlocksAt?: string | null;
  previewUrl: string;
  secondPreviewUrl?: string;
  user: { id: number; username: string; favoriteColor?: string };
};

export type AdminFotomojiItem = {
  id: number;
  emoji: string;
  url: string;
  createdAt: string;
  updatedAt?: string;
  user: { id: number; username: string; favoriteColor?: string };
  photo: {
    id: number;
    day?: string;
    user?: { id: number; username: string; favoriteColor?: string };
  };
};

export type AdminFotomojiTemplateVersion = {
  id: number;
  url: string;
  filePath: string;
  createdAt: string;
  isActive: boolean;
  postUsageCount: number;
};

export type AdminFotomojiTemplateHistoryEmoji = {
  emoji: string;
  activeVersion?: AdminFotomojiTemplateVersion | null;
  versions: AdminFotomojiTemplateVersion[];
};

export type AdminFotomojiTemplateHistoryUser = {
  user: { id: number; username: string; favoriteColor?: string };
  emojis: AdminFotomojiTemplateHistoryEmoji[];
};

export type AdminFotomojiHistoryResponse = {
  items: AdminFotomojiTemplateHistoryUser[];
  userCount: number;
  versionCount: number;
  filters?: { userId?: number; emoji?: string; from?: string; to?: string };
};

export type ChatCommand = {
  id: number;
  name: string;
  command: string;
  action:
    "trigger_moment" | "clear_chat" | "broadcast_push" | "send_chat_message";
  enabled: boolean;
  requireAdmin: boolean;
  sendPush: boolean;
  postChat: boolean;
  pushText: string;
  responseText: string;
  cooldownSecond: number;
  lastUsedAt?: string | null;
  createdAt: string;
  updatedAt: string;
};

export type SystemComponent = {
  name: string;
  ok: boolean;
  message: string;
};

export type SystemHealth = {
  ok: boolean;
  version: string;
  provider: string;
  time: string;
  uploadSizeBytes: number;
  storage?: {
    filesystemTotalBytes: number;
    filesystemFreeBytes: number;
    filesystemUsedBytes: number;
    uploadBytes: number;
    originalBytes: number;
    renditionBytes: number;
    otherUploadBytes: number;
    databaseBytes: number;
    databaseWalBytes: number;
    databaseShmBytes: number;
    backendLogBytes: number;
    gatewayLogBytes: number;
    dockerBytesAvailable: boolean;
    dockerNote?: string;
  };
  latestPrompt?: {
    day?: string;
    triggeredAt?: string | null;
    uploadUntil?: string | null;
    triggerSource?: string;
    requestedByUser?: string;
  };
  components: SystemComponent[];
  metrics?: {
    startedAt?: string;
    uptimeSec?: number;
    requestsTotal?: number;
    errorsTotal?: number;
    errors4xx?: number;
    errors5xx?: number;
    errorRatePercent?: number;
    p95LatencyMs?: number;
    recentRequestsCnt?: number;
    push?: {
      sent?: number;
      failed?: number;
      invalidTokens?: number;
      errors?: number;
    };
  };
};

export type AdminPerformanceBucket = {
  bucketStart: string;
  requests: number;
  errors: number;
  errors4xx: number;
  errors5xx: number;
  p95Ms: number;
  p99Ms: number;
  maxMs: number;
  bytesIn: number;
  bytesOut: number;
};

export type AdminPerformanceSystemBucket = {
  bucketStart: string;
  memAllocBytes: number;
  memSysBytes: number;
  numGoroutine: number;
  lastGCPauseMs: number;
  dbOpenConnections: number;
  dbInUseConnections: number;
  dbIdleConnections: number;
  dbWaitCount: number;
  dbWaitDurationMs: number;
};

export type AdminPerformanceDbHotspot = {
  route: string;
  queryGroup: string;
  count: number;
  p95PeakMs: number;
  p99PeakMs: number;
  maxPeakMs: number;
};

export type AdminPerformanceSloViolation = {
  id: string;
  severity: "low" | "medium" | "high";
  threshold: number;
  observed: number;
  unit: "ms" | "ratio";
};

export type AdminPerformanceSloState = {
  evaluatedAt: string;
  windowMinutes: number;
  status: "ok" | "breach";
  metrics: {
    feedP95PeakMs: number;
    global5xxRate: number;
    uploadErrorRate: number;
    feed4xxRate: number;
    requestsTotal: number;
  };
  thresholds: {
    feedP95PeakMs: number;
    global5xxRate: number;
    uploadErrorRate: number;
    feed4xxRate: number;
  };
  violations: AdminPerformanceSloViolation[];
};

export type AdminPerformanceOverview = {
  schemaVersion?: string;
  from: string;
  to: string;
  bucket: "1m" | "5m";
  items: AdminPerformanceBucket[];
  system?: AdminPerformanceSystemBucket[];
  dbHotspots?: AdminPerformanceDbHotspot[];
  errorClasses?: AdminPerformanceErrorClass[];
  slo?: AdminPerformanceSloState;
  dataSync?: {
    feed?: AdminDataSyncEndpoint;
    timeline?: AdminDataSyncEndpoint;
    calendar?: AdminDataSyncEndpoint;
    hubBootstrap?: AdminDataSyncEndpoint;
    uploads?: AdminDataSyncEndpoint & { clientRetrySignals?: number };
    renditions?: {
      queued?: number;
      running?: number;
      ready?: number;
      failed?: number;
      bytes?: number;
      maxBytes?: number;
      enabled?: boolean;
      avifEnabled?: boolean;
      deliveriesSevenDays?: Record<string, { requests?: number; bytes?: number }>;
    };
    legacyRequestRate?: number;
    capabilities?: Array<{ surface: string; mode: string; outcome: string; appVersion: string; requests: number; responseBytes: number; maxLatencyMs: number }>;
  };
  summary?: {
    requests: number;
    errors: number;
    p95Peak: number;
    p99Peak: number;
    throttleCount?: number;
    throttleRate?: number;
  };
};

export type AdminDataSyncEndpoint = {
  requests: number;
  notModified?: number;
  notModifiedRate?: number;
  responseBytes: number;
  p95PeakMs: number;
};

export type AdminPerformanceErrorClass = {
  errorClass: string;
  count: number;
  ratio: number;
};

export type AdminPerformanceRouteHotspot = {
  route: string;
  method: string;
  requests: number;
  errors: number;
  errors4xx: number;
  errors5xx: number;
  errorRate: number;
  p95PeakMs: number;
  p99PeakMs: number;
  maxPeakMs: number;
};

export type AdminPerformanceRoutesResponse = {
  from: string;
  to: string;
  top: number;
  items: AdminPerformanceRouteHotspot[];
};

export type AdminPerformanceSpikeWindow = {
  id: number;
  day: string;
  triggerAt: string;
  windowStart: string;
  windowEnd: string;
  pushSent: number;
  uploadCount: number;
  feedReadCount: number;
  errorCount: number;
  p95PeakMs: number;
  finalizedAt?: string | null;
};

export type AdminPerformanceSpikesResponse = {
  days: number;
  items: AdminPerformanceSpikeWindow[];
};

export type AdminPerformanceTrackingState = {
  enabled: boolean;
  windowMinutes: number;
  oneShot?: boolean;
  activeSpike?: AdminPerformanceSpikeWindow | null;
  latestSpike?: AdminPerformanceSpikeWindow | null;
  serverNow?: string;
};

export type AdminMigrationInfo = {
  enabled: boolean;
  startedAt?: string | null;
  until?: string | null;
  autoOffEnabled: boolean;
  autoOffReason?: string;
  targetBaseUrl?: string;
  downloadUrl?: string;
  pushTitle?: string;
  pushBody?: string;
  screenTitle?: string;
  screenBody?: string;
  requirePromptFirst: boolean;
  baselineUserCount: number;
  migratedUserCount: number;
  migrationRatio: number;
  remainingSeconds: number;
  callbackExpectedSource?: string;
  callbackSecretConfigured?: boolean;
  reportEnabled?: boolean;
  reportTarget?: string;
  reportSource?: string;
  reportSecretConfigured?: boolean;
};

export type AdminMigrationResponse = {
  migration: AdminMigrationInfo;
};

export type AdminMigrationLinkExportResponse = {
  instanceRole: "old" | "new";
  token: string;
  expiresAt: string;
  instanceBase: string;
  hints?: { pasteTokenOn?: string };
};

export type AdminMigrationLinkImportResponse = {
  ok: boolean;
  imported: "old" | "new";
  remoteUrl: string;
  migration: AdminMigrationInfo;
};

const apiBase = import.meta.env.VITE_API_BASE || "/api";

const settingsDefaults: Settings = {
  promptWindowStartHour: 8,
  promptWindowEndHour: 20,
  uploadWindowMinutes: 10,
  feedCommentPreviewLimit: 10,
  performanceTrackingEnabled: false,
  performanceTrackingWindowMinutes: 30,
  performanceTrackingOneShot: false,
  promptNotificationText: "Zeit fuer dein Daily Foto",
  maxUploadBytes: 0,
  chatMessageMaxLength: 5000,
  chatMessageUnlimited: false,
  postMediaMaxCount: 6,
  postMediaUnlimited: true,
  chatCommandEnabled: false,
  chatCommandValue: "-moment",
  chatCommandTrigger: true,
  chatCommandSendPush: true,
  chatCommandPushText: "Sondermoment von {user}! Jetzt 10 Minuten posten.",
  chatCommandEchoChat: true,
  chatCommandEchoText: "Sondermoment wurde von {user} angefordert.",
  migrationEnabled: false,
  migrationStartedAt: null,
  migrationUntil: null,
  migrationAutoOffEnabled: true,
  migrationTargetBaseUrl: "",
  migrationDownloadUrl: "",
  migrationPushTitle: "Daily umgezogen",
  migrationPushBody:
    "Bitte aktualisiere Daily und verbinde dich mit dem neuen Server.",
  migrationScreenTitle: "Daily ist umgezogen",
  migrationScreenBody:
    "Diese Instanz ist im Migrationsmodus. Bitte installiere die aktuelle App-Version und trage den neuen Server ein.",
  migrationRequirePromptFirst: true,
  migrationExpectedSource: "",
  migrationBaselineUserCount: 0,
  userPromptRules: [
    {
      id: "diagnostics_consent_v1",
      action: "diagnostics_consent",
      enabled: true,
      triggerType: "app_version",
      title: "Diagnose & Performance teilen?",
      body: "Wenn du zustimmst, sendet die App bei Problemen und Ladezeiten technische Diagnosedaten. Das hilft uns, Fehler und Engpaesse schneller zu finden. Du kannst das jederzeit im Profil widerrufen.",
      confirmLabel: "Zustimmen",
      declineLabel: "Nicht teilen",
      cooldownHours: 0,
      priority: 10,
    },
  ],
};

async function parse<T>(res: Response): Promise<T> {
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: "Request failed" }));
    throw new Error(body.error || "Request failed");
  }
  return res.json();
}

function normalizeSettings(raw: any): Settings {
  const rawRules = Array.isArray(raw?.userPromptRules)
    ? raw.userPromptRules
    : [];
  const normalizedRules: UserPromptRule[] =
    rawRules.length > 0
      ? rawRules.map((rule: any, idx: number) => ({
          id: String(rule?.id ?? `rule_${idx + 1}`),
          action: rule?.action ? String(rule.action) as UserPromptRule["action"] : undefined,
          enabled: Boolean(rule?.enabled ?? true),
          triggerType: String(
            rule?.triggerType ?? "app_version",
          ) as UserPromptRule["triggerType"],
          title: String(rule?.title ?? ""),
          body: String(rule?.body ?? ""),
          confirmLabel: String(rule?.confirmLabel ?? "Zustimmen"),
          declineLabel: String(rule?.declineLabel ?? "Nicht teilen"),
          cooldownHours: Number(rule?.cooldownHours ?? 0),
          priority: Number(rule?.priority ?? 0),
        }))
      : settingsDefaults.userPromptRules;
  return {
    promptWindowStartHour: Number(
      raw?.promptWindowStartHour ??
        raw?.PromptWindowStartHour ??
        settingsDefaults.promptWindowStartHour,
    ),
    promptWindowEndHour: Number(
      raw?.promptWindowEndHour ??
        raw?.PromptWindowEndHour ??
        settingsDefaults.promptWindowEndHour,
    ),
    uploadWindowMinutes: Number(
      raw?.uploadWindowMinutes ??
        raw?.UploadWindowMinutes ??
        settingsDefaults.uploadWindowMinutes,
    ),
    feedCommentPreviewLimit: Number(
      raw?.feedCommentPreviewLimit ??
        raw?.FeedCommentPreviewLimit ??
        settingsDefaults.feedCommentPreviewLimit,
    ),
    performanceTrackingEnabled: Boolean(
      raw?.performanceTrackingEnabled ??
      raw?.PerformanceTrackingEnabled ??
      settingsDefaults.performanceTrackingEnabled,
    ),
    performanceTrackingWindowMinutes: Number(
      raw?.performanceTrackingWindowMinutes ??
        raw?.PerformanceTrackingWindowMinutes ??
        settingsDefaults.performanceTrackingWindowMinutes,
    ),
    performanceTrackingOneShot: Boolean(
      raw?.performanceTrackingOneShot ??
      raw?.PerformanceTrackingOneShot ??
      settingsDefaults.performanceTrackingOneShot,
    ),
    promptNotificationText: String(
      raw?.promptNotificationText ??
        raw?.PromptNotificationText ??
        settingsDefaults.promptNotificationText,
    ),
    maxUploadBytes: Number(
      raw?.maxUploadBytes ??
        raw?.MaxUploadBytes ??
        settingsDefaults.maxUploadBytes,
    ),
    chatMessageMaxLength: Number(
      raw?.chatMessageMaxLength ??
        raw?.ChatMessageMaxLength ??
        settingsDefaults.chatMessageMaxLength,
    ),
    chatMessageUnlimited: Boolean(
      raw?.chatMessageUnlimited ??
      raw?.ChatMessageUnlimited ??
      settingsDefaults.chatMessageUnlimited,
    ),
    postMediaMaxCount: Number(
      raw?.postMediaMaxCount ??
        raw?.PostMediaMaxCount ??
        settingsDefaults.postMediaMaxCount,
    ),
    postMediaUnlimited: Boolean(
      raw?.postMediaUnlimited ??
      raw?.PostMediaUnlimited ??
      settingsDefaults.postMediaUnlimited,
    ),
    chatCommandEnabled: Boolean(
      raw?.chatCommandEnabled ??
      raw?.ChatCommandEnabled ??
      settingsDefaults.chatCommandEnabled,
    ),
    chatCommandValue: String(
      raw?.chatCommandValue ??
        raw?.ChatCommandValue ??
        settingsDefaults.chatCommandValue,
    ),
    chatCommandTrigger: Boolean(
      raw?.chatCommandTrigger ??
      raw?.ChatCommandTrigger ??
      settingsDefaults.chatCommandTrigger,
    ),
    chatCommandSendPush: Boolean(
      raw?.chatCommandSendPush ??
      raw?.ChatCommandSendPush ??
      settingsDefaults.chatCommandSendPush,
    ),
    chatCommandPushText: String(
      raw?.chatCommandPushText ??
        raw?.ChatCommandPushText ??
        settingsDefaults.chatCommandPushText,
    ),
    chatCommandEchoChat: Boolean(
      raw?.chatCommandEchoChat ??
      raw?.ChatCommandEchoChat ??
      settingsDefaults.chatCommandEchoChat,
    ),
    chatCommandEchoText: String(
      raw?.chatCommandEchoText ??
        raw?.ChatCommandEchoText ??
        settingsDefaults.chatCommandEchoText,
    ),
    migrationEnabled: Boolean(
      raw?.migrationEnabled ??
      raw?.MigrationEnabled ??
      settingsDefaults.migrationEnabled,
    ),
    migrationStartedAt:
      raw?.migrationStartedAt ??
      raw?.MigrationStartedAt ??
      settingsDefaults.migrationStartedAt,
    migrationUntil:
      raw?.migrationUntil ??
      raw?.MigrationUntil ??
      settingsDefaults.migrationUntil,
    migrationAutoOffEnabled: Boolean(
      raw?.migrationAutoOffEnabled ??
      raw?.MigrationAutoOffEnabled ??
      settingsDefaults.migrationAutoOffEnabled,
    ),
    migrationTargetBaseUrl: String(
      raw?.migrationTargetBaseUrl ??
        raw?.MigrationTargetBaseURL ??
        settingsDefaults.migrationTargetBaseUrl,
    ),
    migrationDownloadUrl: String(
      raw?.migrationDownloadUrl ??
        raw?.MigrationDownloadURL ??
        settingsDefaults.migrationDownloadUrl,
    ),
    migrationPushTitle: String(
      raw?.migrationPushTitle ??
        raw?.MigrationPushTitle ??
        settingsDefaults.migrationPushTitle,
    ),
    migrationPushBody: String(
      raw?.migrationPushBody ??
        raw?.MigrationPushBody ??
        settingsDefaults.migrationPushBody,
    ),
    migrationScreenTitle: String(
      raw?.migrationScreenTitle ??
        raw?.MigrationScreenTitle ??
        settingsDefaults.migrationScreenTitle,
    ),
    migrationScreenBody: String(
      raw?.migrationScreenBody ??
        raw?.MigrationScreenBody ??
        settingsDefaults.migrationScreenBody,
    ),
    migrationRequirePromptFirst: Boolean(
      raw?.migrationRequirePromptFirst ??
      raw?.MigrationRequirePromptFirst ??
      settingsDefaults.migrationRequirePromptFirst,
    ),
    migrationExpectedSource: String(
      raw?.migrationExpectedSource ??
        raw?.MigrationExpectedSource ??
        settingsDefaults.migrationExpectedSource,
    ),
    migrationBaselineUserCount: Number(
      raw?.migrationBaselineUserCount ??
        raw?.MigrationBaselineUserCount ??
        settingsDefaults.migrationBaselineUserCount,
    ),
    userPromptRules: normalizedRules,
  };
}

function normalizeMigrationInfo(raw: any): AdminMigrationInfo {
  return {
    enabled: Boolean(raw?.enabled),
    startedAt: raw?.startedAt ?? null,
    until: raw?.until ?? null,
    autoOffEnabled: Boolean(raw?.autoOffEnabled ?? true),
    autoOffReason: String(raw?.autoOffReason ?? ""),
    targetBaseUrl: String(raw?.targetBaseUrl ?? ""),
    downloadUrl: String(raw?.downloadUrl ?? ""),
    pushTitle: String(raw?.pushTitle ?? ""),
    pushBody: String(raw?.pushBody ?? ""),
    screenTitle: String(raw?.screenTitle ?? ""),
    screenBody: String(raw?.screenBody ?? ""),
    requirePromptFirst: Boolean(raw?.requirePromptFirst ?? true),
    baselineUserCount: Number(raw?.baselineUserCount ?? 0),
    migratedUserCount: Number(raw?.migratedUserCount ?? 0),
    migrationRatio: Number(raw?.migrationRatio ?? 0),
    remainingSeconds: Number(raw?.remainingSeconds ?? 0),
    callbackExpectedSource: String(raw?.callbackExpectedSource ?? ""),
    callbackSecretConfigured: Boolean(raw?.callbackSecretConfigured),
    reportEnabled: Boolean(raw?.reportEnabled),
    reportTarget: String(raw?.reportTarget ?? ""),
    reportSource: String(raw?.reportSource ?? ""),
    reportSecretConfigured: Boolean(raw?.reportSecretConfigured),
  };
}

export async function login(
  username: string,
  password: string,
): Promise<AuthResponse> {
  const res = await fetch(`${apiBase}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });
  return parse<AuthResponse>(res);
}

export async function getSettings(token: string): Promise<Settings> {
  const res = await fetch(`${apiBase}/admin/settings`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<any>(res);
  return normalizeSettings(data);
}

export async function updateSettings(
  token: string,
  settings: Settings,
): Promise<Settings> {
  const res = await fetch(`${apiBase}/admin/settings`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(settings),
  });
  const data = await parse<any>(res);
  return normalizeSettings(data);
}

export async function getAdminEmailSettings(token: string): Promise<AdminEmailSettings> {
  const res = await fetch(`${apiBase}/admin/email/settings`, { headers: { Authorization: `Bearer ${token}` } });
  return parse<AdminEmailSettings>(res);
}

export async function updateAdminEmailSettings(token: string, settings: AdminEmailSettings): Promise<AdminEmailSettings> {
  const res = await fetch(`${apiBase}/admin/email/settings`, { method: "PUT", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify(settings) });
  return parse<AdminEmailSettings>(res);
}

export async function testAdminEmailConnection(token: string, settings: AdminEmailSettings): Promise<{ ok: boolean; stage: string; message?: string }> {
  const res = await fetch(`${apiBase}/admin/email/test-connection`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify(settings) });
  return parse(res);
}

export async function sendAdminEmailTest(token: string, settings: AdminEmailSettings, to: string): Promise<{ ok: boolean; message: string }> {
  const res = await fetch(`${apiBase}/admin/email/test-message`, { method: "POST", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify({ settings, to }) });
  return parse(res);
}

export async function getAdminEmailStatus(token: string): Promise<AdminEmailStatus> {
  const res = await fetch(`${apiBase}/admin/email/status`, { headers: { Authorization: `Bearer ${token}` } });
  return parse(res);
}

export async function deleteUserEmail(token: string, userId: number): Promise<{ ok: boolean }> {
  const res = await fetch(`${apiBase}/admin/users/${userId}/email`, { method: "DELETE", headers: { Authorization: `Bearer ${token}` } });
  return parse(res);
}

export async function getAdminUserEmail(token: string, userId: number): Promise<AdminUserEmailDetails> {
  const res = await fetch(`${apiBase}/admin/users/${userId}/email`, {
    cache: "no-store",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<AdminUserEmailDetails>(res);
}

export async function getAdminMediaRenditions(token: string): Promise<AdminMediaRenditions> {
  const res = await fetch(`${apiBase}/admin/media/renditions`, { headers: { Authorization: `Bearer ${token}` } });
  return parse<AdminMediaRenditions>(res);
}

export async function updateAdminMediaRenditions(token: string, update: { avifEnabled?: boolean; backgroundPaused?: boolean }): Promise<Pick<AdminMediaRenditions, "runtimeAvailable" | "avifEnabled" | "operatorDisabled">> {
  const res = await fetch(`${apiBase}/admin/media/renditions`, { method: "PUT", headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` }, body: JSON.stringify(update) });
  return parse(res);
}

export async function getStats(token: string): Promise<AdminStats> {
  const res = await fetch(`${apiBase}/admin/stats`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<AdminStats>(res);
}

export async function triggerPrompt(
  token: string,
  opts?: { silent?: boolean; notifyUserIds?: number[] },
): Promise<AdminTriggerPromptResponse> {
  const payload = {
    silent: Boolean(opts?.silent),
    notifyUserIds: (opts?.notifyUserIds || []).filter(
      (id) => Number.isFinite(id) && id > 0,
    ),
  };
  const res = await fetch(`${apiBase}/admin/prompt/trigger`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  return parse<AdminTriggerPromptResponse>(res);
}

export async function resetTodayPrompt(
  token: string,
): Promise<{ day: string; message: string }> {
  const res = await fetch(`${apiBase}/admin/prompt/reset-today`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ day: string; message: string }>(res);
}

export async function broadcastNotification(
  token: string,
  body: string,
): Promise<{ sentTo: number; provider: string }> {
  const res = await fetch(`${apiBase}/admin/notifications/broadcast`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  return parse<{ sentTo: number; provider: string }>(res);
}

export async function notifyUser(
  token: string,
  userId: number,
  body: string,
): Promise<{
  sentTo: number;
  failed: number;
  provider: string;
  username: string;
  devices: number;
}> {
  const res = await fetch(`${apiBase}/admin/notifications/user/${userId}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  return parse<{
    sentTo: number;
    failed: number;
    provider: string;
    username: string;
    devices: number;
  }>(res);
}

export async function listUsers(token: string): Promise<AdminUser[]> {
  const res = await fetch(`${apiBase}/admin/users`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminUser[] }>(res);
  return data.items;
}

export async function createUser(
  token: string,
  username: string,
  password: string,
  isAdmin: boolean,
): Promise<void> {
  const res = await fetch(`${apiBase}/admin/users`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ username, password, isAdmin }),
  });
  await parse(res);
}

export async function updateUser(
  token: string,
  id: number,
  payload: { password?: string; isAdmin?: boolean },
): Promise<void> {
  const res = await fetch(`${apiBase}/admin/users/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  await parse(res);
}

export async function deleteUser(token: string, id: number): Promise<void> {
  const res = await fetch(`${apiBase}/admin/users/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function issueUserAccessToken(
  token: string,
  id: number,
): Promise<AdminUserAccessToken> {
  const res = await fetch(`${apiBase}/admin/users/${id}/token`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<AdminUserAccessToken>(res);
}

export async function getAdminFeed(
  token: string,
  day?: string,
): Promise<AdminFeedResponse> {
  const qs = day ? `?day=${encodeURIComponent(day)}` : "";
  const res = await fetch(`${apiBase}/admin/feed${qs}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<AdminFeedResponse>(res);
}

export async function getAdminLocations(
  token: string,
  filters?: { userId?: number; from?: string; to?: string },
): Promise<AdminLocationItem[]> {
  const qs = new URLSearchParams();
  if (filters?.userId) qs.set("userId", String(filters.userId));
  if (filters?.from) qs.set("from", filters.from);
  if (filters?.to) qs.set("to", filters.to);
  const url = qs.toString()
    ? `${apiBase}/admin/locations?${qs.toString()}`
    : `${apiBase}/admin/locations`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminLocationItem[] }>(res);
  return data.items || [];
}

export async function deleteAdminPhotoLocation(
  token: string,
  photoId: number,
): Promise<void> {
  const res = await fetch(`${apiBase}/admin/photos/${photoId}/location`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function getChat(token: string): Promise<ChatItem[]> {
  const res = await fetch(`${apiBase}/chat`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: ChatItem[] }>(res);
  return data.items;
}

export async function getAdminPolls(
  token: string,
  opts?: {
    limit?: number;
    day?: string;
    from?: string;
    to?: string;
    openOnly?: boolean;
    creatorUserId?: number;
  },
): Promise<AdminPollResponse> {
  const qs = new URLSearchParams();
  if (opts?.limit && opts.limit > 0) qs.set("limit", String(opts.limit));
  if (opts?.day) qs.set("day", opts.day);
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  if (typeof opts?.openOnly === "boolean")
    qs.set("openOnly", String(opts.openOnly));
  if (opts?.creatorUserId && opts.creatorUserId > 0)
    qs.set("creatorUserId", String(opts.creatorUserId));
  const url = qs.toString()
    ? `${apiBase}/admin/polls?${qs.toString()}`
    : `${apiBase}/admin/polls`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminPollResponse>(res);
  return {
    items: data.items || [],
    count: Number(data.count || 0),
    limit: Number(data.limit || opts?.limit || 100),
  };
}

export async function sendChat(
  token: string,
  body: string,
): Promise<ChatSendResult> {
  const res = await fetch(`${apiBase}/chat`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ body }),
  });
  return parse<ChatSendResult>(res);
}

export async function clearChat(token: string): Promise<void> {
  const res = await fetch(`${apiBase}/admin/chat/clear`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function getChatCommands(token: string): Promise<ChatCommand[]> {
  const res = await fetch(`${apiBase}/admin/chat/commands`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: ChatCommand[] }>(res);
  return data.items;
}

export async function createChatCommand(
  token: string,
  body: Omit<ChatCommand, "id" | "lastUsedAt" | "createdAt" | "updatedAt">,
): Promise<ChatCommand> {
  const res = await fetch(`${apiBase}/admin/chat/commands`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
  return parse<ChatCommand>(res);
}

export async function updateChatCommand(
  token: string,
  id: number,
  body: Omit<ChatCommand, "id" | "lastUsedAt" | "createdAt" | "updatedAt">,
): Promise<ChatCommand> {
  const res = await fetch(`${apiBase}/admin/chat/commands/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  });
  return parse<ChatCommand>(res);
}

export async function deleteChatCommand(
  token: string,
  id: number,
): Promise<void> {
  const res = await fetch(`${apiBase}/admin/chat/commands/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  await parse(res);
}

export async function getCalendar(
  token: string,
  days = 7,
): Promise<CalendarItem[]> {
  const res = await fetch(`${apiBase}/admin/calendar?days=${days}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: CalendarItem[] }>(res);
  return data.items;
}

export async function getAdminHistory(
  token: string,
  days = 30,
  offset = 0,
  excludeEmpty = true,
  debugLimit = 1000,
): Promise<AdminHistoryResponse> {
  const qs = new URLSearchParams();
  qs.set("days", String(days));
  qs.set("offset", String(offset));
  qs.set("excludeEmpty", String(excludeEmpty));
  qs.set("debugLimit", String(debugLimit));
  const res = await fetch(`${apiBase}/admin/history?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminHistoryResponse>(res);
  return {
    items: (data.items || []).map((item) => ({
      ...item,
      userActivity: item.userActivity || [],
    })),
    days: data.days ?? days,
    offset: data.offset ?? offset,
    excludeEmpty: data.excludeEmpty ?? excludeEmpty,
    onlineTrackingSince: data.onlineTrackingSince || "",
    leaderboard: {
      reliableTop: data.leaderboard?.reliableTop || [],
      extraHeavyTop: data.leaderboard?.extraHeavyTop || [],
    },
    timeseries: data.timeseries || [],
    distribution: data.distribution || {
      photoMix: { promptRatio: 0, extraRatio: 0, capsuleRatio: 0 },
      userMix: { promptRatio: 0, extraRatio: 0 },
      rawTotals: {
        photos: 0,
        dailyMomentPhotos: 0,
        extraPhotos: 0,
        capsulePhotos: 0,
        postedUsersSum: 0,
        onlineUsersSum: 0,
      },
    },
    conversion: data.conversion || [],
    reliability: data.reliability || {
      daysAnalyzed: 0,
      daysWithPosts: 0,
      daysWithTriggerPerformance: 0,
      onTimeTriggerDays: 0,
      onTimeTriggerRate: 0,
      avgAbsoluteTriggerDelayMinutes: 0,
      debugErrorIndicators: 0,
      errorIndicatorRatePerDay: 0,
      avgPostedUsersPerDay: 0,
      avgOnlineUsersPerDay: 0,
      avgRequestsPerOnlineUser: 0,
    },
    cohorts: data.cohorts || [],
    anomalies: data.anomalies || [],
  };
}

export async function getAdminTimeCapsules(
  token: string,
): Promise<AdminTimeCapsuleItem[]> {
  const res = await fetch(`${apiBase}/admin/time-capsules`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminTimeCapsuleItem[] }>(res);
  return data.items || [];
}

export async function getAdminFotomojis(
  token: string,
  opts?: {
    day?: string;
    userId?: number;
    emoji?: string;
    from?: string;
    to?: string;
    limit?: number;
  },
): Promise<AdminFotomojiItem[]> {
  const qs = new URLSearchParams();
  if (opts?.day) qs.set("day", opts.day);
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  if (opts?.emoji && opts.emoji.trim()) qs.set("emoji", opts.emoji.trim());
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  qs.set("limit", String(opts?.limit ?? 300));
  const url = `${apiBase}/admin/fotomojis?${qs.toString()}`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminFotomojiItem[] }>(res);
  return data.items || [];
}

export async function getAdminFotomojiHistory(
  token: string,
  opts?: {
    userId?: number;
    emoji?: string;
    from?: string;
    to?: string;
    limit?: number;
  },
): Promise<AdminFotomojiHistoryResponse> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  if (opts?.emoji && opts.emoji.trim()) qs.set("emoji", opts.emoji.trim());
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  qs.set("limit", String(opts?.limit ?? 1200));
  const res = await fetch(
    `${apiBase}/admin/fotomojis/history?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<AdminFotomojiHistoryResponse>(res);
  return {
    items: data.items || [],
    userCount: data.userCount || 0,
    versionCount: data.versionCount || 0,
    filters: data.filters || {
      userId: opts?.userId || 0,
      emoji: opts?.emoji || "",
      from: opts?.from || "",
      to: opts?.to || "",
    },
  };
}

export async function deleteAdminFotomoji(
  token: string,
  id: number,
): Promise<{ ok: boolean; deletedId: number }> {
  const res = await fetch(`${apiBase}/admin/fotomojis/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ ok: boolean; deletedId: number }>(res);
}

export async function deleteAdminFotomojisBulk(
  token: string,
  ids: number[],
): Promise<{ ok: boolean; deletedCount: number; deletedIds: number[] }> {
  const cleanIds = Array.from(
    new Set(
      ids
        .map((id) => Number(id))
        .filter((id) => Number.isInteger(id) && id > 0),
    ),
  );
  const res = await fetch(`${apiBase}/admin/fotomojis/bulk-delete`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ ids: cleanIds }),
  });
  return parse<{ ok: boolean; deletedCount: number; deletedIds: number[] }>(
    res,
  );
}

export async function updateCalendarDay(
  token: string,
  day: string,
  plannedAt: string,
): Promise<CalendarItem> {
  const res = await fetch(
    `${apiBase}/admin/calendar/${encodeURIComponent(day)}`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ plannedAt }),
    },
  );
  return parse<CalendarItem>(res);
}

export async function getSystemHealth(token: string): Promise<SystemHealth> {
  const res = await fetch(`${apiBase}/admin/system/health`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<SystemHealth>(res);
}

export async function getAdminPerformanceOverview(
  token: string,
  opts?: { from?: string; to?: string; bucket?: "1m" | "5m" },
): Promise<AdminPerformanceOverview> {
  const qs = new URLSearchParams();
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  qs.set("bucket", opts?.bucket || "1m");
  const res = await fetch(
    `${apiBase}/admin/performance/overview?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<AdminPerformanceOverview>(res);
  return {
    schemaVersion: data.schemaVersion || "1.0",
    from: data.from,
    to: data.to,
    bucket: data.bucket || "1m",
    items: data.items || [],
    system: data.system || [],
    dbHotspots: data.dbHotspots || [],
    errorClasses: data.errorClasses || [],
    slo: data.slo,
    dataSync: data.dataSync,
    summary: data.summary || {
      requests: 0,
      errors: 0,
      p95Peak: 0,
      p99Peak: 0,
      throttleCount: 0,
      throttleRate: 0,
    },
  };
}

export async function getAdminPerformanceSlo(
  token: string,
  windowMinutes = 30,
): Promise<AdminPerformanceSloState> {
  const qs = new URLSearchParams();
  qs.set("windowMinutes", String(windowMinutes));
  const res = await fetch(`${apiBase}/admin/performance/slo?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminPerformanceSloState>(res);
  return {
    evaluatedAt: data.evaluatedAt,
    windowMinutes: data.windowMinutes || windowMinutes,
    status: data.status || "ok",
    metrics: data.metrics || {
      feedP95PeakMs: 0,
      global5xxRate: 0,
      uploadErrorRate: 0,
      feed4xxRate: 0,
      requestsTotal: 0,
    },
    thresholds: data.thresholds || {
      feedP95PeakMs: 2500,
      global5xxRate: 0.02,
      uploadErrorRate: 0.08,
      feed4xxRate: 0.15,
    },
    violations: data.violations || [],
  };
}

export async function getAdminPerformanceRoutes(
  token: string,
  opts?: { from?: string; to?: string; top?: number },
): Promise<AdminPerformanceRoutesResponse> {
  const qs = new URLSearchParams();
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  if (opts?.top && opts.top > 0) qs.set("top", String(opts.top));
  const res = await fetch(
    `${apiBase}/admin/performance/routes?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<AdminPerformanceRoutesResponse>(res);
  return {
    from: data.from,
    to: data.to,
    top: data.top || (opts?.top ?? 20),
    items: data.items || [],
  };
}

export async function getAdminPerformanceSpikes(
  token: string,
  days = 14,
): Promise<AdminPerformanceSpikesResponse> {
  const qs = new URLSearchParams();
  qs.set("days", String(days));
  const res = await fetch(
    `${apiBase}/admin/performance/spikes?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<AdminPerformanceSpikesResponse>(res);
  return {
    days: data.days || days,
    items: data.items || [],
  };
}

export async function getAdminPerformanceTracking(
  token: string,
): Promise<AdminPerformanceTrackingState> {
  const res = await fetch(`${apiBase}/admin/performance/tracking`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminPerformanceTrackingState>(res);
  return {
    enabled: Boolean(data.enabled),
    windowMinutes: Number(data.windowMinutes || 30),
    oneShot: Boolean(data.oneShot),
    activeSpike: data.activeSpike || null,
    latestSpike: data.latestSpike || null,
    serverNow: data.serverNow,
  };
}

export async function updateAdminPerformanceTracking(
  token: string,
  payload: { enabled: boolean; windowMinutes: number; oneShot?: boolean },
): Promise<AdminPerformanceTrackingState> {
  const res = await fetch(`${apiBase}/admin/performance/tracking`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  const data = await parse<AdminPerformanceTrackingState>(res);
  return {
    enabled: Boolean(data.enabled),
    windowMinutes: Number(data.windowMinutes || payload.windowMinutes || 30),
    oneShot: Boolean(data.oneShot),
    activeSpike: data.activeSpike || null,
    latestSpike: data.latestSpike || null,
    serverNow: data.serverNow,
  };
}

export async function getAdminTriggerAudit(
  token: string,
  opts?: {
    day?: string;
    from?: string;
    to?: string;
    source?: string;
    result?: string;
    actorUserId?: number;
    requestId?: string;
    limit?: number;
  },
): Promise<AdminTriggerAuditResponse> {
  const qs = new URLSearchParams();
  if (opts?.day) qs.set("day", opts.day);
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  if (opts?.source) qs.set("source", opts.source);
  if (opts?.result) qs.set("result", opts.result);
  if (opts?.actorUserId && opts.actorUserId > 0)
    qs.set("actorUserId", String(opts.actorUserId));
  if (opts?.requestId) qs.set("requestId", opts.requestId);
  if (opts?.limit && opts.limit > 0) qs.set("limit", String(opts.limit));
  const res = await fetch(`${apiBase}/admin/trigger-audit?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminTriggerAuditResponse>(res);
  return {
    items: data.items || [],
    from: data.from || "",
    to: data.to || "",
    count: Number(data.count || 0),
    limit: Number(data.limit || opts?.limit || 200),
  };
}

export async function getAdminTriggerAuditSummary(
  token: string,
  days = 7,
): Promise<AdminTriggerAuditSummary> {
  const qs = new URLSearchParams();
  qs.set("days", String(days));
  const res = await fetch(
    `${apiBase}/admin/trigger-audit/summary?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<AdminTriggerAuditSummary>(res);
  return {
    days: Number(data.days || days),
    from: data.from || "",
    to: data.to || "",
    summary: {
      attempts: Number(data.summary?.attempts || 0),
      triggered: Number(data.summary?.triggered || 0),
      blocked: Number(data.summary?.blocked || 0),
      failed: Number(data.summary?.failed || 0),
      dbLocked: Number(data.summary?.dbLocked || 0),
      duplicateAttempts: Number(data.summary?.duplicateAttempts || 0),
      multipleAttemptDays: Number(data.summary?.multipleAttemptDays || 0),
      blockedRate: Number(data.summary?.blockedRate || 0),
      failedRate: Number(data.summary?.failedRate || 0),
    },
    byDay: data.byDay || [],
    bySource: data.bySource || [],
  };
}

export async function downloadTriggerAuditExport(
  token: string,
  opts?: {
    day?: string;
    from?: string;
    to?: string;
    source?: string;
    result?: string;
    actorUserId?: number;
    requestId?: string;
    format?: "json" | "csv";
  },
): Promise<void> {
  const qs = new URLSearchParams();
  if (opts?.day) qs.set("day", opts.day);
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  if (opts?.source) qs.set("source", opts.source);
  if (opts?.result) qs.set("result", opts.result);
  if (opts?.actorUserId && opts.actorUserId > 0)
    qs.set("actorUserId", String(opts.actorUserId));
  if (opts?.requestId) qs.set("requestId", opts.requestId);
  qs.set("format", opts?.format || "json");
  const res = await fetch(
    `${apiBase}/admin/trigger-audit/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }
  const blob = await res.blob();
  const disposition = res.headers.get("content-disposition") || "";
  const fileMatch = disposition.match(/filename=\"?([^\"]+)\"?/i);
  const fallbackExt = (opts?.format || "json") === "csv" ? "csv" : "json";
  const filename = fileMatch?.[1] || `trigger-audit-export.${fallbackExt}`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function getAdminIncidentExportStatus(
  token: string,
  opts?: { from?: string; to?: string; day?: string; includeGateway?: boolean },
): Promise<AdminIncidentExportStatus> {
  const qs = new URLSearchParams();
  qs.set("format", "json");
  qs.set("statusOnly", "true");
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  if (opts?.day) qs.set("day", opts.day);
  qs.set("includeGateway", String(opts?.includeGateway ?? true));
  const res = await fetch(
    `${apiBase}/admin/incidents/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<AdminIncidentExportStatus>(res);
  return {
    meta: {
      schemaVersion: String(data.meta?.schemaVersion || "incident_bundle_v1"),
      generatedAt: String(data.meta?.generatedAt || ""),
      serverVersion: String(data.meta?.serverVersion || ""),
      timezone: String(data.meta?.timezone || ""),
      requestId: String(data.meta?.requestId || ""),
      from: String(data.meta?.from || ""),
      to: String(data.meta?.to || ""),
      includeGateway: Boolean(data.meta?.includeGateway),
    },
    status: {
      duplicateAttempts: Number(data.status?.duplicateAttempts || 0),
      multipleAttemptDays: Number(data.status?.multipleAttemptDays || 0),
      duplicateAttemptsDaily: Number(data.status?.duplicateAttemptsDaily || 0),
      duplicateAttemptsSpecial: Number(
        data.status?.duplicateAttemptsSpecial || 0,
      ),
      multipleAttemptDaysDaily: Number(
        data.status?.multipleAttemptDaysDaily || 0,
      ),
      multipleAttemptDaysSpecial: Number(
        data.status?.multipleAttemptDaysSpecial || 0,
      ),
      dailyAttempts: Number(data.status?.dailyAttempts || 0),
      dailyTriggered: Number(data.status?.dailyTriggered || 0),
      dailyBlocked: Number(data.status?.dailyBlocked || 0),
      dailyFailed: Number(data.status?.dailyFailed || 0),
      specialAttempts: Number(data.status?.specialAttempts || 0),
      specialTriggered: Number(data.status?.specialTriggered || 0),
      specialBlocked: Number(data.status?.specialBlocked || 0),
      specialFailed: Number(data.status?.specialFailed || 0),
      dailyPending: Boolean(data.status?.dailyPending),
      lastTriggerSource: data.status?.lastTriggerSource || "",
      gatewayLogAvailable: Boolean(data.status?.gatewayLogAvailable),
      backendLogAvailable: Boolean(data.status?.backendLogAvailable),
    },
    collectionWarnings: data.collectionWarnings || [],
  };
}

export async function getAdminTriggerRuntime(
  token: string,
  opts?: { windowMinutes?: number },
): Promise<AdminTriggerRuntimeResponse> {
  const qs = new URLSearchParams();
  if (opts?.windowMinutes && opts.windowMinutes > 0) {
    qs.set("windowMinutes", String(opts.windowMinutes));
  }
  const url = qs.toString()
    ? `${apiBase}/admin/trigger-runtime?${qs.toString()}`
    : `${apiBase}/admin/trigger-runtime`;
  const res = await fetch(url, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<AdminTriggerRuntimeResponse>(res);
  return {
    serverNow: String(data.serverNow || ""),
    windowMinutes: Number(data.windowMinutes || opts?.windowMinutes || 60),
    runtime: data.runtime || {},
    recent: {
      attempts: Number(data.recent?.attempts || 0),
      blocked: Number(data.recent?.blocked || 0),
      failed: Number(data.recent?.failed || 0),
      dbLocked: Number(data.recent?.dbLocked || 0),
      duplicateToday: Number(data.recent?.duplicateToday || 0),
      blockRate: Number(data.recent?.blockRate || 0),
      byReason: (data.recent?.byReason || {}) as Record<string, number>,
      byReasonRate: (data.recent?.byReasonRate || {}) as Record<string, number>,
    },
    slo: data.slo || { status: "ok", violations: [] },
    dispatch: {
      kind: String(data.dispatch?.kind || ""),
      last: data.dispatch?.last || {},
    },
  };
}

export async function updateAdminTriggerRuntime(
  token: string,
  payload: { action: "pause" | "unpause" | "release_lease"; reason?: string },
): Promise<AdminTriggerRuntimeResponse> {
  const res = await fetch(`${apiBase}/admin/trigger-runtime`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  return parse<AdminTriggerRuntimeResponse>(res);
}

export async function getAdminMigration(
  token: string,
): Promise<AdminMigrationResponse> {
  const res = await fetch(`${apiBase}/admin/migration`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<any>(res);
  return { migration: normalizeMigrationInfo(data?.migration || {}) };
}

export async function updateAdminMigration(
  token: string,
  payload: {
    migrationAutoOffEnabled?: boolean;
    migrationTargetBaseUrl?: string;
    migrationDownloadUrl?: string;
    migrationPushTitle?: string;
    migrationPushBody?: string;
    migrationScreenTitle?: string;
    migrationScreenBody?: string;
    migrationRequirePromptFirst?: boolean;
    migrationCallbackSecret?: string;
    migrationExpectedSource?: string;
    migrationReportEnabled?: boolean;
    migrationReportTarget?: string;
    migrationReportSecret?: string;
    migrationReportSource?: string;
  },
): Promise<AdminMigrationResponse> {
  const res = await fetch(`${apiBase}/admin/migration`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  const data = await parse<any>(res);
  return { migration: normalizeMigrationInfo(data?.migration || {}) };
}

export async function activateAdminMigration(
  token: string,
  days = 7,
): Promise<AdminMigrationResponse> {
  const res = await fetch(`${apiBase}/admin/migration/activate`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ days }),
  });
  const data = await parse<any>(res);
  return { migration: normalizeMigrationInfo(data?.migration || {}) };
}

export async function deactivateAdminMigration(
  token: string,
): Promise<AdminMigrationResponse> {
  const res = await fetch(`${apiBase}/admin/migration/deactivate`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<any>(res);
  return { migration: normalizeMigrationInfo(data?.migration || {}) };
}

export async function pushAdminMigration(
  token: string,
  payload: { title?: string; body?: string; testUserId?: number },
): Promise<{
  ok: boolean;
  sentTo: number;
  failed: number;
  invalidRemoved: number;
}> {
  const res = await fetch(`${apiBase}/admin/migration/push`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  return parse<{
    ok: boolean;
    sentTo: number;
    failed: number;
    invalidRemoved: number;
  }>(res);
}

export async function downloadAdminMigrationExport(
  token: string,
  format: "json" | "csv" = "json",
): Promise<void> {
  const qs = new URLSearchParams();
  qs.set("format", format);
  const res = await fetch(
    `${apiBase}/admin/migration/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }
  const blob = await res.blob();
  const disposition = res.headers.get("content-disposition") || "";
  const fileMatch = disposition.match(/filename=\"?([^\"]+)\"?/i);
  const filename = fileMatch?.[1] || `migration-export.${format}`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function exportAdminMigrationLinkToken(
  token: string,
  instanceRole: "old" | "new",
): Promise<AdminMigrationLinkExportResponse> {
  const res = await fetch(`${apiBase}/admin/migration/link/export`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ instanceRole }),
  });
  const data = await parse<any>(res);
  return {
    instanceRole: data.instanceRole === "new" ? "new" : "old",
    token: String(data.token || ""),
    expiresAt: String(data.expiresAt || ""),
    instanceBase: String(data.instanceBase || ""),
    hints: data.hints || {},
  };
}

export async function importAdminMigrationLinkToken(
  token: string,
  linkToken: string,
): Promise<AdminMigrationLinkImportResponse> {
  const res = await fetch(`${apiBase}/admin/migration/link/import`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ token: linkToken }),
  });
  const data = await parse<any>(res);
  return {
    ok: Boolean(data.ok),
    imported: data.imported === "new" ? "new" : "old",
    remoteUrl: String(data.remoteUrl || ""),
    migration: normalizeMigrationInfo(data.migration || {}),
  };
}

export async function downloadIncidentExport(
  token: string,
  opts?: { from?: string; to?: string; day?: string; includeGateway?: boolean },
): Promise<void> {
  const qs = new URLSearchParams();
  qs.set("format", "json");
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  if (opts?.day) qs.set("day", opts.day);
  qs.set("includeGateway", String(opts?.includeGateway ?? true));

  const res = await fetch(
    `${apiBase}/admin/incidents/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }
  const blob = await res.blob();
  const disposition = res.headers.get("content-disposition") || "";
  const fileMatch = disposition.match(/filename=\"?([^\"]+)\"?/i);
  const filename =
    fileMatch?.[1] ||
    `incident-export-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function downloadPerformanceTrackingExport(
  token: string,
  opts?: {
    eventId?: number;
    day?: string;
    from?: string;
    to?: string;
    bucket?: "1m" | "5m";
  },
): Promise<void> {
  const qs = new URLSearchParams();
  if (opts?.eventId && opts.eventId > 0)
    qs.set("eventId", String(opts.eventId));
  if (opts?.day) qs.set("day", opts.day);
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  qs.set("bucket", opts?.bucket || "1m");
  const res = await fetch(
    `${apiBase}/admin/performance/tracking/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }
  const blob = await res.blob();
  const filename = `performance-tracking-${new Date().toISOString().replace(/[:.]/g, "-")}.json`;
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function downloadPerformanceExport(
  token: string,
  opts?: { from?: string; to?: string; format?: "csv" | "json" },
): Promise<void> {
  const qs = new URLSearchParams();
  if (opts?.from) qs.set("from", opts.from);
  if (opts?.to) qs.set("to", opts.to);
  qs.set("format", opts?.format || "json");
  const res = await fetch(
    `${apiBase}/admin/performance/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }

  const blob = await res.blob();
  const disposition = res.headers.get("content-disposition") || "";
  const fileMatch = disposition.match(/filename="?([^"]+)"?/i);
  const fallbackExt = (opts?.format || "json") === "csv" ? "csv" : "json";
  const filename = fileMatch?.[1] || `performance-export.${fallbackExt}`;

  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function getDebugLogs(
  token: string,
  userId?: number,
  limit = 150,
  sinceHours = 24,
): Promise<DebugLogsResponse> {
  const qs = new URLSearchParams();
  qs.set("limit", String(limit));
  qs.set("sinceHours", String(sinceHours));
  if (userId && userId > 0) qs.set("userId", String(userId));
  const res = await fetch(`${apiBase}/admin/debug/logs?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<DebugLogsResponse>(res);
  return {
    items: data.items || [],
    sinceHours: data.sinceHours ?? sinceHours,
    since: data.since || "",
    serverNow: data.serverNow || "",
  };
}

export async function getDebugLogsSummary(
  token: string,
  userId?: number,
  limit = 1000,
  sinceHours = 24,
): Promise<DebugLogSummaryResponse> {
  const qs = new URLSearchParams();
  qs.set("limit", String(limit));
  qs.set("sinceHours", String(sinceHours));
  if (userId && userId > 0) qs.set("userId", String(userId));
  const res = await fetch(
    `${apiBase}/admin/debug/logs/summary?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<DebugLogSummaryResponse>(res);
  return {
    items: data.items || [],
    sinceHours: data.sinceHours ?? sinceHours,
    since: data.since || "",
    serverNow: data.serverNow || "",
  };
}

export async function getUploadTimeline(
  token: string,
  userId?: number,
  limit = 150,
  sinceHours = 24,
): Promise<UploadTimelineResponse> {
  const qs = new URLSearchParams();
  qs.set("limit", String(limit));
  qs.set("sinceHours", String(sinceHours));
  if (userId && userId > 0) qs.set("userId", String(userId));
  const res = await fetch(
    `${apiBase}/admin/debug/upload-timeline?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  const data = await parse<UploadTimelineResponse>(res);
  return {
    items: data.items || [],
    sinceHours: data.sinceHours ?? sinceHours,
    since: data.since || "",
    serverNow: data.serverNow || "",
    summary: {
      total: data.summary?.total ?? 0,
      uniqueUploads: data.summary?.uniqueUploads ?? 0,
      failedCount: data.summary?.failedCount ?? 0,
      waitingForNetworkCount: data.summary?.waitingForNetworkCount ?? 0,
      liveCount: data.summary?.liveCount ?? 0,
    },
  };
}

export async function deleteDebugLogs(
  token: string,
  opts?: { userId?: number; sinceHours?: number },
): Promise<{ deletedCount: number; userId: number; sinceHours: number }> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  qs.set("sinceHours", String(opts?.sinceHours ?? 24));
  const res = await fetch(`${apiBase}/admin/debug/logs?${qs.toString()}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ deletedCount: number; userId: number; sinceHours: number }>(
    res,
  );
}

export async function downloadDebugLogs(
  token: string,
  opts?: {
    userId?: number;
    sinceHours?: number;
    format?: "csv" | "json";
    limit?: number;
  },
): Promise<void> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  qs.set("sinceHours", String(opts?.sinceHours ?? 24));
  qs.set("format", opts?.format ?? "csv");
  qs.set("limit", String(opts?.limit ?? 5000));

  const res = await fetch(
    `${apiBase}/admin/debug/logs/export?${qs.toString()}`,
    {
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  if (!res.ok) {
    const body = await res
      .json()
      .catch(() => ({ error: "Download fehlgeschlagen" }));
    throw new Error(body.error || "Download fehlgeschlagen");
  }

  const blob = await res.blob();
  const disposition = res.headers.get("content-disposition") || "";
  const fileMatch = disposition.match(/filename="?([^"]+)"?/i);
  const fallbackExt = (opts?.format ?? "csv") === "json" ? "json" : "csv";
  const filename = fileMatch?.[1] || `debug-logs-last-24h.${fallbackExt}`;

  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

export async function getReports(
  token: string,
  opts?: {
    userId?: number;
    type?: "" | "bug" | "idea" | "post";
    status?: "" | "open" | "in_review" | "done" | "rejected";
    limit?: number;
  },
): Promise<AdminReportItem[]> {
  const qs = new URLSearchParams();
  qs.set("limit", String(opts?.limit ?? 200));
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  if (opts?.type) qs.set("type", opts.type);
  if (opts?.status) qs.set("status", opts.status);
  const res = await fetch(`${apiBase}/admin/reports?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminReportItem[] }>(res);
  return data.items || [];
}

export async function getAdminSearch(
  token: string,
  q: string,
  opts?: { scopes?: AdminSearchScope[]; limit?: number },
): Promise<AdminSearchResult[]> {
  const query = q.trim();
  if (!query) return [];
  const qs = new URLSearchParams();
  qs.set("q", query);
  if (opts?.scopes && opts.scopes.length > 0) {
    qs.set("scope", opts.scopes.join(","));
  }
  if (opts?.limit && opts.limit > 0) {
    qs.set("limit", String(opts.limit));
  }
  const res = await fetch(`${apiBase}/admin/search?${qs.toString()}`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  const data = await parse<{ items: AdminSearchResult[] }>(res);
  return data.items || [];
}

export async function updateReport(
  token: string,
  id: number,
  payload: {
    status: "open" | "in_review" | "done" | "rejected";
    githubIssueNumber?: number | null;
  },
): Promise<AdminReportItem> {
  const res = await fetch(`${apiBase}/admin/reports/${id}`, {
    method: "PUT",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(payload),
  });
  return parse<AdminReportItem>(res);
}

export async function deleteReport(
  token: string,
  id: number,
): Promise<{ ok: boolean; deletedId: number }> {
  const res = await fetch(`${apiBase}/admin/reports/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ ok: boolean; deletedId: number }>(res);
}

export async function deleteReports(
  token: string,
  opts?: {
    userId?: number;
    type?: "" | "bug" | "idea" | "post";
    status?: "" | "open" | "in_review" | "done" | "rejected";
  },
): Promise<{ ok: boolean; deletedCount: number }> {
  const qs = new URLSearchParams();
  if (opts?.userId && opts.userId > 0) qs.set("userId", String(opts.userId));
  if (opts?.type) qs.set("type", opts.type);
  if (opts?.status) qs.set("status", opts.status);
  const res = await fetch(`${apiBase}/admin/reports?${qs.toString()}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ ok: boolean; deletedCount: number }>(res);
}

export async function getDistributionProfiles(
  token: string,
): Promise<DistributionProfilesResponse> {
  const res = await fetch(`${apiBase}/admin/distribution/profiles`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<DistributionProfilesResponse>(res);
}

export async function createDistributionProfile(
  token: string,
  profile: DistributionProfile,
): Promise<{ profile: DistributionProfile }> {
  const res = await fetch(`${apiBase}/admin/distribution/profiles`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(profile),
  });
  return parse<{ profile: DistributionProfile }>(res);
}

export async function updateDistributionProfile(
  token: string,
  profile: DistributionProfile,
): Promise<{ profile: DistributionProfile }> {
  const res = await fetch(
    `${apiBase}/admin/distribution/profiles/${profile.id}`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ ...profile, expectedRevision: profile.revision }),
    },
  );
  return parse<{ profile: DistributionProfile }>(res);
}

export async function deleteDistributionProfile(
  token: string,
  profileId: number,
): Promise<{ ok: boolean }> {
  const res = await fetch(
    `${apiBase}/admin/distribution/profiles/${profileId}`,
    {
      method: "DELETE",
      headers: { Authorization: `Bearer ${token}` },
    },
  );
  return parse<{ ok: boolean }>(res);
}

export async function testDistributionProfile(
  token: string,
  profile: DistributionProfile,
): Promise<{ result: DistributionTestResult }> {
  const res = await fetch(`${apiBase}/admin/distribution/test`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(profile),
  });
  return parse<{ result: DistributionTestResult }>(res);
}

export async function getDistributionAudit(
  token: string,
): Promise<{ items: DistributionAuditItem[] }> {
  const res = await fetch(`${apiBase}/admin/distribution/audit`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  return parse<{ items: DistributionAuditItem[] }>(res);
}

export async function assignUserDistributionProfile(
  token: string,
  userId: number,
  distributionProfileId: number | null,
): Promise<{ userId: number; distributionProfileId: number | null }> {
  const res = await fetch(
    `${apiBase}/admin/users/${userId}/distribution-profile`,
    {
      method: "PUT",
      headers: {
        "Content-Type": "application/json",
        Authorization: `Bearer ${token}`,
      },
      body: JSON.stringify({ distributionProfileId }),
    },
  );
  return parse<{ userId: number; distributionProfileId: number | null }>(res);
}
