package dev.agentring.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class UpdatesTest {
    @Test
    fun `versions compare like release codes`() {
        val url = "https://github.com/mixx86/hivepal/releases/tag/v0.6.0"
        assertEquals(Updates.Result.Newer("0.6.0", url), Updates.compare("0.5.0", "v0.6.0", url))
        assertEquals(Updates.Result.Newer("0.5.1", url), Updates.compare("0.5.0", "v0.5.1", url))
        assertEquals(Updates.Result.Newer("0.6.0", url), Updates.compare("0.6.0rc2", "v0.6.0", url))
        assertEquals(Updates.Result.UpToDate("0.6.0"), Updates.compare("0.6.0", "v0.6.0", url))
        assertEquals(Updates.Result.UpToDate("0.6.0"), Updates.compare("0.6.0", "v0.6.0rc1", url))
        assertEquals(Updates.Result.UpToDate("0.10.0"), Updates.compare("0.10.0", "v0.9.9", url))
        // Previews and local builds are another channel.
        assertEquals(Updates.Result.NotARelease, Updates.compare("0.5.0+3.gabc1234", "v0.6.0", url))
        assertEquals(Updates.Result.NotARelease, Updates.compare("0.2.0-dev", "v0.6.0", url))
    }

    @Test
    fun `reads the latest release from the API`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"tag_name":"v0.6.0","html_url":"https://x/v0.6.0","name":"hivepal 0.6.0"}"""))
            val r = Updates.check("0.5.0", api = server.url("").toString().trimEnd('/'))
            assertEquals(Updates.Result.Newer("0.6.0", "https://x/v0.6.0"), r)
            assertEquals("/repos/mixx86/hivepal/releases/latest", server.takeRequest().path)
            // A preview never asks.
            assertEquals(Updates.Result.NotARelease, Updates.check("0.5.0+1.gabc", api = server.url("").toString()))
            assertEquals(1, server.requestCount)
        }
    }
}
