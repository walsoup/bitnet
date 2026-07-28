package com.bluenet

import com.bluenet.utils.PreferencesManager
import org.junit.Assert.assertNotNull
import org.junit.Test

class PreferencesManagerTest {

    @Test
    fun testPreferencesManagerSingleton() {
        assertNotNull(PreferencesManager)
    }
}
