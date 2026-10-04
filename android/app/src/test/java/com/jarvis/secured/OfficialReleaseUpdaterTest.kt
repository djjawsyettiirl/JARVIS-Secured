package com.jarvis.secured

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialReleaseUpdaterTest {
    @Test
    fun acceptsOnlyTheExactOfficialReleaseAssetUrl() {
        assertTrue(
            OfficialReleaseUpdater.isOfficialReleaseAsset(
                "https://github.com/djjawsyettiirl/JARVIS-Secured/releases/download/v2.2.2-beta/JARVIS-2.2.2-beta-Android-Direct.apk",
                "v2.2.2-beta",
                "JARVIS-2.2.2-beta-Android-Direct.apk"
            )
        )
    }

    @Test
    fun rejectsCopiedOrRedirectedAssetSources() {
        val officialPath = "/djjawsyettiirl/JARVIS-Secured/releases/download/v2.2.2-beta/JARVIS-2.2.2-beta-Android-Direct.apk"
        assertFalse(
            OfficialReleaseUpdater.isOfficialReleaseAsset(
                "https://github.com/attacker/JARVIS-Secured/releases/download/v2.2.2-beta/JARVIS-2.2.2-beta-Android-Direct.apk",
                "v2.2.2-beta",
                "JARVIS-2.2.2-beta-Android-Direct.apk"
            )
        )
        assertFalse(
            OfficialReleaseUpdater.isOfficialReleaseAsset(
                "https://evil.example$officialPath",
                "v2.2.2-beta",
                "JARVIS-2.2.2-beta-Android-Direct.apk"
            )
        )
        assertFalse(
            OfficialReleaseUpdater.isOfficialReleaseAsset(
                "https://github.com/djjawsyettiirl/JARVIS-Secured/releases/download/v2.2.2-beta/JARVIS-2.2.2-beta-Android-Direct.apk?mirror=1",
                "v2.2.2-beta",
                "JARVIS-2.2.2-beta-Android-Direct.apk"
            )
        )
    }

    @Test
    fun rejectsAssetNameAndTagMismatch() {
        assertFalse(
            OfficialReleaseUpdater.isOfficialReleaseAsset(
                "https://github.com/djjawsyettiirl/JARVIS-Secured/releases/download/v2.2.1-beta/JARVIS-2.2.1-beta-Android-Direct.apk",
                "v2.2.2-beta",
                "JARVIS-2.2.2-beta-Android-Direct.apk"
            )
        )
    }
}
