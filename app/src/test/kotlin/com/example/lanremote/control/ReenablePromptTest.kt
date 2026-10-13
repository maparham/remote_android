package com.example.lanremote.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReenablePromptTest {
    @Test fun freshInstallNeverShows() {
        assertFalse(ReenablePrompt.shouldShow(ReenablePrompt.NO_VERSION, 3, false, ReenablePrompt.NO_VERSION))
    }

    @Test fun sameVersionNeverShows() {
        assertFalse(ReenablePrompt.shouldShow(3, 3, false, ReenablePrompt.NO_VERSION))
    }

    @Test fun showsOnceAfterUpdateWhileServiceOff() {
        assertTrue(ReenablePrompt.shouldShow(2, 3, false, ReenablePrompt.NO_VERSION))
    }

    @Test fun noShowWhenServiceAlreadyOn() {
        assertFalse(ReenablePrompt.shouldShow(2, 3, true, ReenablePrompt.NO_VERSION))
    }

    @Test fun noShowTwiceForSameVersion() {
        assertFalse(ReenablePrompt.shouldShow(2, 3, false, 3))
    }

    @Test fun downgradeNeverShows() {
        assertFalse(ReenablePrompt.shouldShow(4, 3, false, ReenablePrompt.NO_VERSION))
    }
}
