package com.pchuri.returnfairy.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test
    fun comparesDottedVersions() {
        assertTrue(UpdateChecker.isNewer("3.0.1", "3.0.0"))
        assertTrue(UpdateChecker.isNewer("v3.1", "3.0.9"))
        assertTrue(UpdateChecker.isNewer("3.10.0", "3.9.0"))
        assertFalse(UpdateChecker.isNewer("3.0.0", "3.0.0"))
        assertFalse(UpdateChecker.isNewer("3.0", "3.0.0"))
        assertFalse(UpdateChecker.isNewer("2.9.9", "3.0.0"))
    }

    @Test
    fun unparsableVersionsAreNeverNewer() {
        assertFalse(UpdateChecker.isNewer("latest", "3.0.0"))
        assertFalse(UpdateChecker.isNewer("", "3.0.0"))
        assertFalse(UpdateChecker.isNewer("3.0.1", "dev"))
        assertTrue(UpdateChecker.isNewer("3.0.1-beta", "3.0.0"))
    }

    @Test
    fun postponedVersionIsNotOfferedAgainUntilANewerOne() {
        assertTrue(UpdateChecker.shouldOffer("3.0.1", "3.0.0", ""))
        assertFalse(UpdateChecker.shouldOffer("3.0.1", "3.0.0", "3.0.1"))
        assertTrue(UpdateChecker.shouldOffer("3.0.2", "3.0.0", "3.0.1"))
        assertFalse(UpdateChecker.shouldOffer("3.0.0", "3.0.0", ""))
    }

    @Test
    fun readsTagNameFromReleaseJson() {
        val json = """{"url":"x","html_url":"y","tag_name": "v3.0.1","name":"Return Fairy 3.0.1"}"""
        assertEquals("3.0.1", UpdateChecker.parseLatestVersion(json))
        assertNull(UpdateChecker.parseLatestVersion("""{"message":"Not Found"}"""))
    }
}
