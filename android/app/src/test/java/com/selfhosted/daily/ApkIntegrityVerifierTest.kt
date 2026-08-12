package com.selfhosted.daily

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ApkIntegrityVerifierTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val verifier = ApkIntegrityVerifier(context)
    private val signer = "ab".repeat(32)
    private val installed = ApkIdentity("com.selfhosted.daily", "0.8.28", 142028, setOf(signer))
    private val validActual = ApkIdentity("com.selfhosted.daily", "0.8.29", 142029, setOf(signer))
    private val update = UpdateInfo(
        latestVersion = "0.8.29",
        versionCode = 142029,
        releaseUrl = "https://example.invalid/release",
        apkUrl = "https://example.invalid/app.apk",
        apkSha256 = "cd".repeat(32),
        packageName = "com.selfhosted.daily",
        signingCertSha256 = signer,
        profilePackageName = "com.selfhosted.daily",
        profileSigningCertSha256 = signer
    )

    @Test
    fun correctlySignedNewerApkIsAccepted() {
        assertEquals(validActual, verifier.validateMetadata(validActual, installed, update))
    }

    @Test
    fun wrongPackageIsRejected() {
        assertFailure("package_mismatch", validActual.copy(packageName = "attacker.app"), update)
    }

    @Test
    fun wrongAnnouncedVersionCodeIsRejected() {
        assertFailure("version_code_mismatch", validActual, update.copy(versionCode = 142030))
    }

    @Test
    fun wrongVersionNameIsRejected() {
        assertFailure("version_name_mismatch", validActual.copy(versionName = "0.8.30"), update)
    }

    @Test
    fun nonIncreasingVersionCodeIsRejected() {
        assertFailure("version_not_newer", validActual.copy(versionCode = 142028), update.copy(versionCode = 142028))
    }

    @Test
    fun foreignCertificateIsRejectedEvenWhenProfileClaimsIt() {
        val foreign = "ef".repeat(32)
        assertFailure(
            "signer_mismatch",
            validActual.copy(signerSha256 = setOf(foreign)),
            update.copy(signingCertSha256 = foreign, profileSigningCertSha256 = foreign)
        )
    }

    @Test
    fun configuredFingerprintMustAlsoMatchInstalledChain() {
        assertFailure("profile_signer_mismatch", validActual, update.copy(profileSigningCertSha256 = "12".repeat(32)))
    }

    @Test
    fun profileFingerprintMayBeOmittedBecauseInstalledSignerStillPinsIdentity() {
        assertEquals(validActual, verifier.validateMetadata(validActual, installed, update.copy(profileSigningCertSha256 = "")))
    }

    @Test
    fun multipleCurrentApkSignersAreRejected() {
        assertFailure(
            "unexpected_signer_count",
            validActual.copy(signerSha256 = setOf(signer, "cd".repeat(32))),
            update
        )
    }

    @Test
    fun multipleInstalledCurrentSignersAreRejected() {
        val installedWithTwoSigners = installed.copy(signerSha256 = setOf(signer, "cd".repeat(32)))
        val error = runCatching { verifier.validateMetadata(validActual, installedWithTwoSigners, update) }.exceptionOrNull()
        assertEquals("unexpected_signer_count", (error as ApkIntegrityException).errorClass)
    }

    private fun assertFailure(errorClass: String, actual: ApkIdentity, candidate: UpdateInfo) {
        val error = runCatching { verifier.validateMetadata(actual, installed, candidate) }.exceptionOrNull()
        assertEquals(errorClass, (error as ApkIntegrityException).errorClass)
    }
}
