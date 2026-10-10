package com.pocketqc

import com.pocketqc.ui.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {

    @Test
    fun testScreenRoutes() {
        assertEquals("home", Screen.Home.route)
        assertEquals("inspection", Screen.Inspection.route)
        assertEquals("results", Screen.Results.route)
        assertEquals("batch_passport", Screen.BatchPassport.route)
    }

    @Test
    fun testExpectedFlowSequence() {
        // Expected flow: Home -> Inspection -> Results -> Batch Passport
        val flow = listOf(
            Screen.Home,
            Screen.Inspection,
            Screen.Results,
            Screen.BatchPassport
        )

        assertEquals(4, flow.size)
        assertEquals("home", flow[0].route)
        assertEquals("inspection", flow[1].route)
        assertEquals("results", flow[2].route)
        assertEquals("batch_passport", flow[3].route)
    }
}
