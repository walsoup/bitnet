package com.bluenet

import com.bluenet.utils.BluetoothUtils
import org.junit.Assert.assertNotNull
import org.junit.Test

class BluetoothUtilsTest {

    @Test
    fun testBluetoothUtilsSingleton() {
        assertNotNull(BluetoothUtils)
    }
}
