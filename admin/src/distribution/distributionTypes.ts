export type DistributionSourceMode = "manifest" | "direct" | "disabled";

export type DistributionProfile = {
  id: number;
  name: string;
  enabled: boolean;
  isDefault: boolean;
  sourceMode: DistributionSourceMode;
  channel: string;
  projectUrl: string;
  releaseIndexUrl: string;
  releaseHistoryUrl: string;
  releasePageUrl: string;
  directApkUrl: string;
  directApkVersionName: string;
  directApkVersionCode: number;
  directApkSha256: string;
  directApkSizeBytes: number | null;
  expectedPackageName: string;
  expectedSigningCertSha256: string;
  minSupportedVersionCode: number | null;
  allowPrerelease: boolean;
  revision: number;
  createdByUserId?: number | null;
  createdAt?: string;
  updatedAt?: string;
};

export type DistributionDirectApk = {
  versionName: string;
  versionCode: number;
  url: string;
  sha256: string;
  size: number | null;
};

export type DistributionClientPreview = {
  schemaVersion: number;
  enabled: boolean;
  profileId: number;
  profileUpdatedAt?: string;
  channel: string;
  projectUrl: string;
  releaseIndexUrl: string;
  releaseHistoryUrl: string;
  releasePageUrl: string;
  directApk: DistributionDirectApk | null;
  expectedPackageName: string;
  expectedSigningCertSha256: string;
  minSupportedVersionCode: number | null;
  allowPrerelease: boolean;
};

export type DistributionProfileItem = {
  profile: DistributionProfile;
  assignedUserCount: number;
  clientPreview: DistributionClientPreview;
};

export type DistributionDeploymentPolicy = {
  allowInsecureHttp: boolean;
  privateHostAllowlistConfigured: boolean;
  manifestMaxBytes: number;
  apkMaxBytes: number;
};

export type DistributionProfilesResponse = {
  items: DistributionProfileItem[];
  deploymentPolicy: DistributionDeploymentPolicy;
};

export type DistributionTestResult = {
  success: boolean;
  finalHost: string;
  httpStatusClass: string;
  latencyMs: number;
  schemaVersion?: number;
  detectedVersion?: string;
  detectedSize?: number | null;
  warnings: string[];
  errorClass?: string;
};

export type DistributionAuditItem = {
  id: number;
  actorUserId?: number | null;
  actorUsername: string;
  action: string;
  profileId?: number | null;
  targetUserId?: number | null;
  before?: Record<string, unknown> | null;
  after?: Record<string, unknown> | null;
  testResult?: DistributionTestResult | null;
  errorClass?: string;
  createdAt: string;
};

export const emptyDistributionProfile = (): DistributionProfile => ({
  id: 0,
  name: "",
  enabled: true,
  isDefault: false,
  sourceMode: "manifest",
  channel: "stable",
  projectUrl: "",
  releaseIndexUrl: "",
  releaseHistoryUrl: "",
  releasePageUrl: "",
  directApkUrl: "",
  directApkVersionName: "",
  directApkVersionCode: 0,
  directApkSha256: "",
  directApkSizeBytes: null,
  expectedPackageName: "com.selfhosted.daily",
  expectedSigningCertSha256: "",
  minSupportedVersionCode: null,
  allowPrerelease: false,
  revision: 1,
});
