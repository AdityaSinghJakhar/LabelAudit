package com.labelguard.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.labelguard.app.auth.Role
import com.labelguard.app.auth.RoleStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Claiming and releasing the inspector role.
 *
 * The passcode is a lane-keeper, not a security boundary, and these tests
 * pin the behaviour that matters for that: it is never stored in the clear,
 * a wrong one does not grant the role, and stepping back down is never gated.
 */
@RunWith(AndroidJUnit4::class)
class RoleStoreTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: RoleStore

    @Before
    fun setUp() {
        store = RoleStore(context)
        store.reset()
    }

    @After
    fun tearDown() {
        RoleStore(context).reset()
    }

    @Test
    fun everyoneStartsAsAShopper() {
        assertEquals(Role.CONSUMER, store.role)
        assertFalse(store.hasPasscode)
    }

    @Test
    fun theFirstPasscodeSetsItUp() {
        val result = store.claimInspector("2468")

        assertTrue(result is RoleStore.Result.Granted)
        assertTrue((result as RoleStore.Result.Granted).firstTime)
        assertEquals(Role.INSPECTOR, store.role)
        assertTrue(store.hasPasscode)
    }

    @Test
    fun theSamePasscodeSignsBackIn() {
        store.claimInspector("2468")
        store.releaseInspector()
        assertEquals(Role.CONSUMER, store.role)

        val again = store.claimInspector("2468")
        assertTrue(again is RoleStore.Result.Granted)
        assertFalse((again as RoleStore.Result.Granted).firstTime)
        assertEquals(Role.INSPECTOR, store.role)
    }

    @Test
    fun aWrongPasscodeGrantsNothing() {
        store.claimInspector("2468")
        store.releaseInspector()

        val result = store.claimInspector("1111")
        assertTrue(result is RoleStore.Result.Rejected)
        assertEquals(Role.CONSUMER, store.role)
    }

    @Test
    fun aShortPasscodeIsRejectedBeforeAnythingIsStored() {
        val result = store.claimInspector("1")

        assertTrue(result is RoleStore.Result.Rejected)
        assertFalse("a rejected passcode must not be saved", store.hasPasscode)
        assertEquals(Role.CONSUMER, store.role)
    }

    @Test
    fun thePasscodeIsNotStoredInTheClear() {
        store.claimInspector("2468")

        val prefs = context.getSharedPreferences("labelguard_role", android.content.Context.MODE_PRIVATE)
        val values = prefs.all.values.map { it.toString() }
        assertTrue(
            "the passcode must not appear anywhere in the file: " + values,
            values.none { it.contains("2468") }
        )
    }

    @Test
    fun theRoleSurvivesAcrossInstances() {
        store.claimInspector("2468")

        assertEquals(Role.INSPECTOR, RoleStore(context).role)
    }

    @Test
    fun steppingDownNeedsNoProof() {
        store.claimInspector("2468")
        store.releaseInspector()

        assertEquals(Role.CONSUMER, store.role)
        assertTrue("the passcode is kept for signing back in", store.hasPasscode)
    }

    @Test
    fun resettingForgetsThePasscode() {
        store.claimInspector("2468")
        store.reset()

        assertFalse(store.hasPasscode)
        assertEquals(Role.CONSUMER, store.role)
    }
}
