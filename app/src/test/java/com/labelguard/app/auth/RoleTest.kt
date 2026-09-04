package com.labelguard.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each role may do.
 *
 * The consequential assertions are the negative ones. A shopper who could
 * register a reference pack would be able to decide what "correct" means for
 * every later scan of that product on this phone — including, in the case the
 * relabelling raids describe, a relabeller enrolling their own repasted pack.
 */
class RoleTest {

    @Test
    fun `a shopper can scan and read the findings`() {
        assertTrue(Role.CONSUMER.can(Role.Capability.SCAN))
        assertTrue(Role.CONSUMER.can(Role.Capability.VIEW_REPORT))
        assertTrue(Role.CONSUMER.can(Role.Capability.EXPORT_REPORT))
        assertTrue(Role.CONSUMER.can(Role.Capability.VIEW_OWN_HISTORY))
    }

    @Test
    fun `a shopper cannot register a reference pack`() {
        assertFalse(Role.CONSUMER.can(Role.Capability.ENROL_REFERENCE))
        assertFalse(Role.CONSUMER.can(Role.Capability.MANAGE_REGISTRY))
    }

    @Test
    fun `a shopper cannot export the whole inspection history`() {
        // Any single report is theirs to save. The aggregate is an
        // enforcement artefact.
        assertTrue(Role.CONSUMER.can(Role.Capability.EXPORT_REPORT))
        assertFalse(Role.CONSUMER.can(Role.Capability.EXPORT_HISTORY))
        assertFalse(Role.CONSUMER.can(Role.Capability.CLEAR_HISTORY))
    }

    @Test
    fun `an inspector can do everything a shopper can`() {
        assertTrue(
            "an inspector must never lose a shopper's abilities",
            Role.INSPECTOR.capabilities.containsAll(Role.CONSUMER.capabilities)
        )
    }

    @Test
    fun `an inspector can register references and export the history`() {
        assertTrue(Role.INSPECTOR.can(Role.Capability.ENROL_REFERENCE))
        assertTrue(Role.INSPECTOR.can(Role.Capability.EXPORT_HISTORY))
        assertTrue(Role.INSPECTOR.can(Role.Capability.MANAGE_REGISTRY))
    }

    @Test
    fun `every capability belongs to at least one role`() {
        // A capability nobody holds is dead code that reads like a feature.
        val held = Role.entries.flatMap { it.capabilities }.toSet()
        assertEquals(Role.Capability.entries.toSet(), held)
    }

    @Test
    fun `every role describes itself`() {
        Role.entries.forEach {
            assertTrue("${it.name} has no label", it.label.isNotBlank())
            assertTrue("${it.name} has no summary", it.summary.isNotBlank())
        }
    }
}
