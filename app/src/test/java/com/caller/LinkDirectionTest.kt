package com.caller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sharing rules of one-way pairing, as the code applies them on each phone. They are the pure decisions behind the
 * cloud sync, the TCP/LAN/Tailscale sync and the roster ingest; the sync code itself needs Android and two phones.
 */
class LinkDirectionTest {
    private val link1 = "link_" + "a".repeat(32)
    private val link2 = "link_" + "b".repeat(32)
    private val legacyLink = "link_" + "c".repeat(32)
    private val oppo = "dev_OPPO_1"
    private val redmi = "dev_REDMI_1"
    private val stranger = "dev_OTHER_9"

    /** What a phone stores, written and read back as after an app restart. */
    private fun restart(roles: Map<String, LinkRole>): Map<String, LinkRole> = parseLinkRoles(formatLinkRoles(roles))

    private fun roleOn(roles: Map<String, LinkRole>, link: String): LinkRole = roles[link] ?: LinkRole.MUTUAL

    @Test
    fun oneCodeCreatesOneDirectionOnly() {
        // Oppo generated the code (SOURCE), Redmi entered it (RECEIVER).
        val oppoRole = LinkRole.SOURCE
        val redmiRole = LinkRole.RECEIVER

        // Oppo shares its calls and reads none; Redmi reads and shares none.
        assertTrue(mayPublishOwnCalls(oppoRole))
        assertFalse(mayPublishOwnCalls(redmiRole))
        assertTrue(mayPullPeerCalls(redmiRole))
        assertFalse(mayPullPeerCalls(oppoRole))

        // Redmi takes in Oppo's calls; Oppo takes in nothing that Redmi sends (TCP snapshot, cloud document, roster).
        assertTrue(mayIngestFrom(redmiRole, oppo, setOf(oppo)))
        assertFalse(mayIngestFrom(oppoRole, redmi, emptySet()))
        assertFalse(mayIngestFrom(oppoRole, "", emptySet()))
    }

    @Test
    fun aRosterNeverRegistersTheReceiverOnTheSource() {
        // The roster ingest is refused for the whole payload on a source, so no phone is registered from it.
        assertFalse(mayIngestFrom(LinkRole.SOURCE, redmi, setOf(redmi)))
        // And the receiver does not put itself into the roster it shares.
        assertFalse(mayPostRoster(LinkRole.RECEIVER))
        assertTrue(mayPostRoster(LinkRole.SOURCE))
    }

    @Test
    fun aReceiverTakesDataOnlyFromThePhoneItWasPairedWith() {
        val peers = setOf(oppo)
        assertTrue(mayIngestFrom(LinkRole.RECEIVER, oppo, peers))
        // Someone else who knows the link id.
        assertFalse(mayIngestFrom(LinkRole.RECEIVER, stranger, peers))
        // A link without a registered phone takes nothing from a named sender.
        assertFalse(mayIngestFrom(LinkRole.RECEIVER, oppo, emptySet()))
        // A cloud document the caller already attributed to a registered peer has no sender field.
        assertTrue(mayIngestFrom(LinkRole.RECEIVER, "", peers))
    }

    @Test
    fun aSecondIndependentPairingAddsTheReverseDirectionAndKeepsTheFirst() {
        // First pairing: Oppo generated link1, Redmi entered it.
        var oppoRoles = mapOf(link1 to LinkRole.SOURCE)
        var redmiRoles = mapOf(link1 to LinkRole.RECEIVER)
        assertFalse(mayPublishOwnCalls(roleOn(redmiRoles, link1)))

        // Second pairing, on its own code: Redmi generated link2, Oppo entered it.
        oppoRoles = restart(oppoRoles + (link2 to LinkRole.RECEIVER))
        redmiRoles = restart(redmiRoles + (link2 to LinkRole.SOURCE))

        // The first direction is unchanged.
        assertEquals(LinkRole.SOURCE, roleOn(oppoRoles, link1))
        assertEquals(LinkRole.RECEIVER, roleOn(redmiRoles, link1))
        assertTrue(mayPublishOwnCalls(roleOn(oppoRoles, link1)))
        assertTrue(mayIngestFrom(roleOn(redmiRoles, link1), oppo, setOf(oppo)))
        assertFalse(mayIngestFrom(roleOn(oppoRoles, link1), redmi, emptySet()))

        // The new direction works the other way round.
        assertTrue(mayPublishOwnCalls(roleOn(redmiRoles, link2)))
        assertTrue(mayIngestFrom(roleOn(oppoRoles, link2), redmi, setOf(redmi)))
        assertFalse(mayIngestFrom(roleOn(redmiRoles, link2), oppo, emptySet()))
        assertFalse(mayPublishOwnCalls(roleOn(oppoRoles, link2)))
    }

    @Test
    fun theRolesSurviveAnAppRestartAndANetworkReconnect() {
        val roles = mapOf(link1 to LinkRole.SOURCE, link2 to LinkRole.RECEIVER)
        val afterRestart = restart(restart(roles))
        assertEquals(roles, afterRestart)
    }

    @Test
    fun eachLinkIsJudgedOnItsOwnSoNothingLeaksBetweenLinks() {
        // One phone: source on link1, receiver on link2, an old mutual link as well.
        val roles = mapOf(link1 to LinkRole.SOURCE, link2 to LinkRole.RECEIVER)
        assertFalse(mayPullPeerCalls(roleOn(roles, link1)))
        assertTrue(mayPullPeerCalls(roleOn(roles, link2)))
        assertTrue(mayPublishOwnCalls(roleOn(roles, link1)))
        assertFalse(mayPublishOwnCalls(roleOn(roles, link2)))
        // The old link was never given a role and works as before in both directions.
        assertEquals(LinkRole.MUTUAL, roleOn(roles, legacyLink))
        assertTrue(mayPublishOwnCalls(LinkRole.MUTUAL))
        assertTrue(mayPullPeerCalls(LinkRole.MUTUAL))
        assertTrue(mayPostRoster(LinkRole.MUTUAL))
        assertTrue(mayIngestFrom(LinkRole.MUTUAL, stranger, emptySet()))
    }

    @Test
    fun aLinkThisPhoneGeneratedIsKeptEvenThoughItsReceiverIsNotListed() {
        assertTrue(keepLinkWhenPruning(LinkRole.SOURCE, hasPeer = false))
        // A receiver whose source was removed, and an old link without a phone, are dropped as before.
        assertFalse(keepLinkWhenPruning(LinkRole.RECEIVER, hasPeer = false))
        assertFalse(keepLinkWhenPruning(LinkRole.MUTUAL, hasPeer = false))
        assertTrue(keepLinkWhenPruning(LinkRole.RECEIVER, hasPeer = true))
        assertTrue(keepLinkWhenPruning(LinkRole.MUTUAL, hasPeer = true))
    }

    @Test
    fun storedRolesAreReadSafely() {
        assertEquals(emptyMap<String, LinkRole>(), parseLinkRoles(""))
        // Garbage and unknown roles are ignored, so an old or damaged value never grants or removes a direction.
        assertEquals(
            mapOf(link1 to LinkRole.SOURCE),
            parseLinkRoles("junk,$link2:UNKNOWN,:SOURCE,$link1:SOURCE")
        )
        assertEquals("", formatLinkRoles(emptyMap()))
        assertEquals("$link1:SOURCE,$link2:RECEIVER", formatLinkRoles(mapOf(link1 to LinkRole.SOURCE, link2 to LinkRole.RECEIVER)))
    }
}
