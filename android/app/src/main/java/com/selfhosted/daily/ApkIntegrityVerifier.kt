package com.selfhosted.daily

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Set<String>
)

class ApkIntegrityException(val errorClass: String, message: String) : SecurityException(message)

class ApkIntegrityVerifier(private val context: Context) {
    fun verify(apkFile: File, update: UpdateInfo): ApkIdentity {
        val archive = packageInfo(apkFile.absolutePath)
            ?: throw ApkIntegrityException("invalid_apk", "Android konnte die heruntergeladene Datei nicht als APK lesen.")
        val installed = runCatching {
            context.packageManager.getPackageInfo(context.packageName, signingFlags())
        }.getOrNull() ?: throw ApkIntegrityException("installed_identity_unavailable", "Installierte App-Identitaet konnte nicht gelesen werden.")
        return validateMetadata(identity(archive), identity(installed), update)
    }

    internal fun validateMetadata(actual: ApkIdentity, installed: ApkIdentity, update: UpdateInfo): ApkIdentity {
        val expectedPackage = update.packageName.trim()
        if (expectedPackage.isBlank()) throw ApkIntegrityException("missing_package_name", "Im Releaseeintrag fehlt der Paketname.")
        val profilePackage = update.profilePackageName.trim()
        if (profilePackage.isBlank()) throw ApkIntegrityException("missing_profile_package", "Im Verteilungsprofil fehlt der Paketname.")
        if (actual.packageName != expectedPackage || actual.packageName != profilePackage || actual.packageName != installed.packageName) {
            throw ApkIntegrityException("package_mismatch", "APK-Paketname stimmt nicht mit der installierten App ueberein.")
        }
        val announcedName = update.latestVersion.trim().removePrefix("v")
        if (announcedName.isBlank() || actual.versionName.trim().removePrefix("v") != announcedName) {
            throw ApkIntegrityException("version_name_mismatch", "APK-VersionName stimmt nicht mit dem Releaseeintrag ueberein.")
        }
        if (actual.versionCode <= installed.versionCode) {
            throw ApkIntegrityException("version_not_newer", "APK-VersionCode ist nicht neuer als die installierte App.")
        }
        val announcedCode = update.versionCode
        if (announcedCode == null && !update.legacyOfficialArtifact) {
            throw ApkIntegrityException("missing_version_code", "Im Releaseeintrag fehlt der VersionCode.")
        }
        if (announcedCode != null && actual.versionCode != announcedCode) {
            throw ApkIntegrityException("version_code_mismatch", "APK-VersionCode stimmt nicht mit dem Releaseeintrag ueberein.")
        }
        if (actual.signerSha256.isEmpty() || installed.signerSha256.isEmpty() || actual.signerSha256 != installed.signerSha256) {
            throw ApkIntegrityException("signer_mismatch", "APK-Signatur stimmt nicht mit der installierten App ueberein.")
        }
        val announcedSigner = normalizeFingerprint(update.signingCertSha256)
        if (announcedSigner.isNotBlank() && announcedSigner !in actual.signerSha256) {
            throw ApkIntegrityException("release_signer_mismatch", "APK-Signatur stimmt nicht mit dem Releaseeintrag ueberein.")
        }
        val configuredSigner = normalizeFingerprint(update.profileSigningCertSha256)
        if (configuredSigner.isBlank()) throw ApkIntegrityException("missing_profile_signer", "Im Verteilungsprofil fehlt der Signaturfingerprint.")
        if (configuredSigner !in actual.signerSha256) {
            throw ApkIntegrityException("profile_signer_mismatch", "APK-Signatur stimmt nicht mit dem Verteilungsprofil ueberein.")
        }
        return actual
    }

    @Suppress("DEPRECATION")
    private fun packageInfo(path: String): PackageInfo? = context.packageManager.getPackageArchiveInfo(path, signingFlags())

    @Suppress("DEPRECATION")
    private fun identity(info: PackageInfo): ApkIdentity {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            info.signatures.orEmpty()
        }
        return ApkIdentity(
            packageName = info.packageName.orEmpty(),
            versionName = info.versionName.orEmpty(),
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong(),
            signerSha256 = signatures.map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                    .joinToString("") { "%02x".format(it) }
            }.toSet()
        )
    }

    private fun signingFlags(): Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        @Suppress("DEPRECATION")
        PackageManager.GET_SIGNATURES
    }

    private fun normalizeFingerprint(value: String): String = value.trim().lowercase().replace(Regex("[^a-f0-9]"), "")
}
