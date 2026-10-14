package com.aurora.music.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavMenuTest {
    private fun NavLayout.barIds() = bar.map { it.id }
    private fun NavLayout.mainIds() = main.map { it.id }

    @Test fun layoutsSavedBeforeTheTabBarExistedKeepTheDefaultTabs() {
        val layout = NavMenu.parse("main=home,search,library,radio;more=podcasts")
        assertEquals(listOf("home", "search", "library"), layout.barIds())
        assertFalse(layout.barMore)
        assertTrue("radio" in layout.mainIds())
    }

    @Test fun tabBarSurvivesEncodingAndSideMenuEditsLeaveItAlone() {
        val layout = NavMenu.parse("").addTab("radio").shiftTab("radio", -1).withBarMore(true)
        val reparsed = NavMenu.parse(layout.encode())
        assertEquals(listOf("home", "search", "radio", "library"), reparsed.barIds())
        assertTrue(reparsed.barMore)
        val sideEdit = NavMenu.parse(reparsed.move("stats", NavPlacement.MAIN).encode())
        assertEquals(reparsed.barIds(), sideEdit.barIds())
        assertTrue(sideEdit.barMore)
    }

    @Test fun theMoreTabTakesOneOfTheFiveSlots() {
        val four = NavMenu.parse("").addTab("radio")
        assertTrue(four.canAddTab)
        val full = four.addTab("podcasts")
        assertEquals(5, full.tabSlots)
        assertFalse(full.canAddTab)
        assertEquals(full, full.addTab("history"))
        assertFalse(full.withBarMore(true).barMore)
        val withMore = four.withBarMore(true)
        assertFalse(withMore.canAddTab)
        assertEquals(withMore, withMore.addTab("podcasts"))
        assertEquals(listOf("home", "search", "library", "radio"),
            NavMenu.parse("bar=home,search,library,radio,podcasts;barmore=1").barIds())
    }

    @Test fun homeAlwaysStaysAndTheBarKeepsTwoTabs() {
        val layout = NavMenu.parse("bar=radio")
        assertEquals(listOf("home", "radio"), layout.barIds())
        assertEquals(layout, layout.removeTab("home"))
        assertEquals(layout, layout.removeTab("radio"))
        assertEquals(listOf("home", "search"), NavMenu.parse("bar=home").barIds())
    }

    @Test fun searchAndLibraryCanLeaveTheTabBarWhileStayingInTheSideMenu() {
        val layout = NavMenu.parse("").removeTab("search").removeTab("library")
        assertEquals(listOf("home", "library"), NavMenu.parse("").removeTab("search").barIds())
        assertEquals(listOf("home", "library"), layout.barIds())
        assertTrue("search" in layout.mainIds() && "library" in layout.mainIds())
    }

    @Test fun eachResetOnlyTouchesItsOwnPart() {
        val custom = NavMenu.parse("").addTab("radio").withBarMore(true).move("stats", NavPlacement.MAIN)
        val tabsReset = custom.resetTabBar()
        assertEquals(listOf("home", "search", "library"), tabsReset.barIds())
        assertFalse(tabsReset.barMore)
        assertTrue("stats" in tabsReset.mainIds())
        val sideReset = custom.resetSideMenu()
        assertEquals(custom.barIds(), sideReset.barIds())
        assertTrue(sideReset.barMore)
        assertFalse("stats" in sideReset.mainIds())
    }

    @Test fun simpleModeHidesAdvancedAudioTabs() {
        val layout = NavMenu.parse("").addTab("signal_path")
        assertEquals(listOf("home", "search", "library"), layout.visible(simpleMode = true).barIds())
        assertEquals(layout.barIds(), layout.visible(simpleMode = false).barIds())
    }
}
