package com.example.lanremote.control

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlStateTest {
    private val pkg = "com.example.lanremote"
    private val cls = "com.example.lanremote.control.ControlService"

    @Test fun matchesFullyQualifiedName() {
        assertTrue(ControlState.isEnabled("$pkg/$cls", pkg, cls))
    }

    @Test fun matchesShortFormAndroidStores() {
        assertTrue(ControlState.isEnabled("$pkg/.control.ControlService", pkg, cls))
    }

    @Test fun matchesAmongSeveralServices() {
        assertTrue(
            ControlState.isEnabled(
                "com.other/.Svc:$pkg/.control.ControlService:com.third/.Other", pkg, cls
            )
        )
    }

    @Test fun otherServiceDoesNotMatch() {
        assertFalse(ControlState.isEnabled("com.other/.Svc", pkg, cls))
    }

    @Test fun nullOrEmptyDoesNotMatch() {
        assertFalse(ControlState.isEnabled(null, pkg, cls))
        assertFalse(ControlState.isEnabled("", pkg, cls))
    }
}
