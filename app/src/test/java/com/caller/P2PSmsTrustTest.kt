package com.caller

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit P2P-1 and P2P-2: the caller-ID SMS ("CALLER|<number>|<name>|<time>") comes from anyone.
 * - The name was also saved under the number the message CLAIMS, so a stranger could put any name on any number.
 *   The claimed number is now kept only when it is the sender's own number or the number of a call with this phone
 *   (a dual-SIM caller calls from one SIM and texts from the other).
 * - Every such SMS was answered with this phone's name and number, without limit. Now only during or right after a
 *   call with that number.
 */
class P2PSmsTrustTest {
    private fun keys(number: String) = buildP2PNumberSnapshot(number).lookupKeys.toSet()

    private val stranger = keys("+919000000001")
    private val victim = keys("9876543210")
    private val callerSim1 = keys("+919811111111")
    private val callerSim2 = keys("+919822222222")

    @Test
    fun aClaimedNumberThatIsTheSendersOwnIsKept() {
        assertTrue(p2pDeclaredNumberTrusted(transportKeys = keys("+919876543210"), declaredKeys = victim, callKeys = emptySet()))
    }

    @Test
    fun aStrangerCannotNameSomeoneElsesNumber() {
        assertFalse(p2pDeclaredNumberTrusted(transportKeys = stranger, declaredKeys = victim, callKeys = emptySet()))
    }

    @Test
    fun aDualSimCallerStillGetsTheCallingNumberNamed() {
        // The call rings from SIM 1 while the SMS comes from SIM 2.
        assertTrue(p2pDeclaredNumberTrusted(transportKeys = callerSim2, declaredKeys = callerSim1, callKeys = callerSim1))
    }

    @Test
    fun noReplyToSomeoneWhoIsNotOnACallWithThisPhone() {
        assertFalse(p2pReplyAllowed(senderKeys = stranger, trustedDeclaredKeys = emptySet(), callKeys = emptySet()))
        assertFalse(p2pReplyAllowed(senderKeys = stranger, trustedDeclaredKeys = emptySet(), callKeys = callerSim1))
    }

    @Test
    fun theCallerIsAnswered() {
        assertTrue(p2pReplyAllowed(senderKeys = callerSim1, trustedDeclaredKeys = emptySet(), callKeys = callerSim1))
        // Dual SIM: the reply goes to the texting SIM because the claimed (calling) number is on the call.
        assertTrue(p2pReplyAllowed(senderKeys = callerSim2, trustedDeclaredKeys = callerSim1, callKeys = callerSim1))
    }
}
