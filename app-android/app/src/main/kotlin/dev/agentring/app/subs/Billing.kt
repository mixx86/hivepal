package dev.agentring.app.subs

import java.time.LocalDate
import java.time.ZoneId

/** A service whose billing page the app reads. */
data class Service(val id: String, val name: String, val start: String)

/** What a billing page says. `until` = when it renews (or ends, if `status` is "ending"), ms since epoch. */
data class Billing(
    val status: String,
    val until: Long? = null,
    val plan: String? = null,
    val price: String? = null,
    val error: String? = null,
)

/**
 * Reads plan, renewal date and price from the visible text of a billing page, in English or Spanish.
 * Deliberately generic: the date nearest to a word like "renews" / "se renueva" / "next invoice".
 */
object BillingParser {
    /**
     * Ready-made services: the page to start from (ideally the billing one; the user can move on from
     * there before "Use this page"). The list is for what an agent's owner pays to run it: AI, APIs,
     * code hosting, servers. Anything else (streaming, shopping…): "Other service" with its own name and
     * address. Google-only services aren't listed: typing a Google password inside an app's browser is refused.
     */
    val PRESETS = listOf(
        Service("claude", "Claude", "https://claude.ai/settings/billing"),
        Service("chatgpt", "ChatGPT", "https://chatgpt.com/#settings/Account"),
        Service("grok", "Grok", "https://grok.com/"),
        Service("perplexity", "Perplexity", "https://www.perplexity.ai/settings/account"),
        Service("cursor", "Cursor", "https://cursor.com/dashboard"),
        Service("github", "GitHub", "https://github.com/settings/billing"),
        Service("proton", "Proton", "https://account.proton.me/dashboard"),
        Service("webdock", "Webdock", "https://app.webdock.io/en/login"),
        Service("digitalocean", "DigitalOcean", "https://cloud.digitalocean.com/account/billing"),
        Service("hetzner", "Hetzner", "https://accounts.hetzner.com/"),
    )

    /**
     * Services that were ready-made once and no longer are: not offered any more, but one already
     * connected keeps being read (its id is in the user's saved pages and on the relay).
     */
    val RETIRED = listOf(
        Service("spotify", "Spotify", "https://www.spotify.com/account/overview/"),
        Service("netflix", "Netflix", "https://www.netflix.com/account"),
        Service("disney", "Disney+", "https://www.disneyplus.com/account"),
        Service("prime", "Amazon Prime", "https://www.amazon.com/gp/primecentral"),
    )

    private val MONTHS = mapOf(
        "jan" to 1, "january" to 1, "feb" to 2, "february" to 2, "mar" to 3, "march" to 3, "apr" to 4, "april" to 4,
        "may" to 5, "jun" to 6, "june" to 6, "jul" to 7, "july" to 7, "aug" to 8, "august" to 8, "sep" to 9,
        "sept" to 9, "september" to 9, "oct" to 10, "october" to 10, "nov" to 11, "november" to 11, "dec" to 12,
        "december" to 12,
        "ene" to 1, "enero" to 1, "febrero" to 2, "marzo" to 3, "abr" to 4, "abril" to 4, "mayo" to 5, "junio" to 6,
        "julio" to 7, "ago" to 8, "agosto" to 8, "septiembre" to 9, "setiembre" to 9, "octubre" to 10,
        "noviembre" to 11, "dic" to 12, "diciembre" to 12,
    )
    private val MON = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private val DATE_MDY = Regex("""\b($MON)\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val DATE_DMY = Regex("""\b(\d{1,2})\s+(?:de\s+)?($MON)\.?,?\s+(?:de\s+)?(\d{4})\b""", RegexOption.IGNORE_CASE)
    private val DATE_ISO = Regex("""\b(\d{4})-(\d{2})-(\d{2})\b""")
    private val DATE_SLASH = Regex("""\b(\d{1,2})/(\d{1,2})/(\d{4}|\d{2})\b""")  // day first, as in Spain
    private val RENEW = Regex(
        """renew|next (?:bill|billing|payment|invoice|charge)|billed on|billing date|member since|""" +
            """se renueva|renueva|renovaci[oó]n|pr[oó]xim[ao] (?:factura|cobro|pago|cargo)|siguiente (?:pago|cobro)|""" +
            """facturaci[oó]n""",
        RegexOption.IGNORE_CASE,
    )
    private val ENDING = Regex(
        """expires?|ends? on|will end|cancel(?:led|ed)?|won.t renew|caduca|vence|termina|finaliza|no se renovar""",
        RegexOption.IGNORE_CASE,
    )
    private const val PER = """(?:[  ]*(?:/|per|al|a la|por)[  ]*(?:month|mo|year|yr|mes|año))?"""
    private val PRICES = listOf(
        Regex("""[$€£][  ]?\d+(?:[.,]\d{2})?$PER""", RegexOption.IGNORE_CASE),
        Regex("""\b\d{1,4}[.,]\d{2}[  ]?(?:€|EUR|USD)$PER""", RegexOption.IGNORE_CASE),
    )
    private val PLANS = listOf("Max 20x", "Max 5x", "SuperGrok Heavy", "SuperGrok", "Premium Individual", "Premium Family",
        "Premium Duo", "Premium+", "Premium", "Plus", "Pro+", "Pro", "Max", "Team", "Business", "Ultra", "Standard",
        "Basic", "Family", "Free", "Gratis")
    private val LOGIN = Regex("""\b(log ?in|sign ?in|iniciar sesi[oó]n|inicia sesi[oó]n|continue with google|continuar con google)\b""",
        RegexOption.IGNORE_CASE)
    private val CHALLENGE = Regex("""verificaci[oó]n de seguridad|verify(?:ing)? you are (?:a )?human|just a moment|un momento""",
        RegexOption.IGNORE_CASE)

    private fun dates(text: String): List<Pair<Int, LocalDate>> {
        val out = mutableListOf<Pair<Int, LocalDate>>()
        fun add(i: Int, y: String, m: Int?, d: String) {
            runCatching { out += i to LocalDate.of(y.toInt(), m!!, d.toInt()) }
        }
        DATE_MDY.findAll(text).forEach { add(it.range.first, it.groupValues[3], MONTHS[it.groupValues[1].lowercase()], it.groupValues[2]) }
        DATE_DMY.findAll(text).forEach { add(it.range.first, it.groupValues[3], MONTHS[it.groupValues[2].lowercase()], it.groupValues[1]) }
        DATE_ISO.findAll(text).forEach { add(it.range.first, it.groupValues[1], it.groupValues[2].toIntOrNull(), it.groupValues[3]) }
        DATE_SLASH.findAll(text).forEach {
            val y = it.groupValues[3].let { y -> if (y.length == 2) "20$y" else y }
            add(it.range.first, y, it.groupValues[2].toIntOrNull(), it.groupValues[1])
        }
        return out.sortedBy { it.first }
    }

    fun parse(text: String, today: LocalDate = LocalDate.now()): Billing {
        val ds = dates(text).filter { !it.second.isBefore(today.minusDays(2)) && !it.second.isAfter(today.plusDays(800)) }
        var best: Triple<Int, LocalDate, Boolean>? = null  // distance, date, ending
        for ((rx, ending) in listOf(RENEW to false, ENDING to true)) {
            for (kw in rx.findAll(text)) {
                for ((i, d) in ds) {
                    val off = i - kw.range.first
                    if (off in -40..160) {
                        val dist = kotlin.math.abs(off)
                        if (best == null || dist < best.first) best = Triple(dist, d, ending)
                    }
                }
            }
        }
        if (best == null && ds.size == 1) best = Triple(0, ds[0].second, false)
        val plan = PLANS.firstOrNull { Regex("""\b${Regex.escape(it)}\b""").containsMatchIn(text) }
        val price = PRICES.firstNotNullOfOrNull { it.find(text)?.value?.replace(Regex("""\s+"""), " ")?.trim() }
        if (best == null) {
            return when {
                CHALLENGE.containsMatchIn(text.take(600)) -> Billing("error", error = "challenge")
                LOGIN.containsMatchIn(text) -> Billing("error", error = "login")
                else -> Billing("unknown", plan = plan, price = price)
            }
        }
        val until = best.second.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return Billing(if (best.third) "ending" else "active", until, plan, price)
    }
}
