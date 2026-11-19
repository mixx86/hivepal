package dev.agentring.app.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class BillingParserTest {
    private val today = LocalDate.of(2026, 11, 18)
    private fun day(b: Billing) = b.until?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }

    @Test fun englishRenewal() {
        val b = BillingParser.parse("Billing\nPro plan\nYour subscription will auto renew on Jan 5, 2027.\n€21.78 per month", today)
        assertEquals("active", b.status)
        assertEquals(LocalDate.of(2027, 1, 5), day(b))
        assertEquals("Pro", b.plan)
        assertEquals("€21.78 per month", b.price)
    }

    @Test fun spanishRenewal() {
        val b = BillingParser.parse("Plan Max 5x · Se renueva el 5 de enero de 2027 · 108,90 €/mes", today)
        assertEquals(LocalDate.of(2027, 1, 5), day(b))
        assertEquals("Max 5x", b.plan)
        assertEquals("108,90 €/mes", b.price)
    }

    @Test fun cancelledEnds() {
        val b = BillingParser.parse("SuperGrok\nYour plan is canceled and ends on January 5, 2027\n$30.00 / month", today)
        assertEquals("ending", b.status)
        assertEquals("SuperGrok", b.plan)
        assertEquals("$30.00 / month", b.price)
    }

    @Test fun dayFirstSlashes() {
        val b = BillingParser.parse("Invoices\nNext invoice 05/01/2027  9,95 EUR\nPremium", today)
        assertEquals(LocalDate.of(2027, 1, 5), day(b))
        assertEquals("9,95 EUR", b.price)
    }

    @Test fun loginAndChallengeAndNothing() {
        assertEquals("login", BillingParser.parse("Log in to continue\nContinue with Google", today).error)
        assertEquals("challenge", BillingParser.parse("claude.ai Verificación de seguridad en curso", today).error)
        val b = BillingParser.parse("Welcome back", today)
        assertEquals("unknown", b.status)
        assertNull(b.until)
    }

    @Test fun pastDatesIgnored() {
        assertNull(BillingParser.parse("Renewed on March 1, 2025", today).until)
    }

    @Test fun spotifyStyleTwoDigitYear() {
        val b = BillingParser.parse("Premium Individual\nYour next bill is for 10,99 € on 5/12/26.", today)
        assertEquals(LocalDate.of(2026, 12, 5), day(b))
        assertEquals("Premium Individual", b.plan)
        assertEquals("10,99 €", b.price)
    }

    @Test fun retiredServicesAreNotOffered() {
        val offered = BillingParser.PRESETS.map { it.id }.toSet()
        assertEquals(emptySet<String>(), BillingParser.RETIRED.map { it.id }.toSet() intersect offered)
        assertEquals(false, "netflix" in offered || "disney" in offered)
    }
}
