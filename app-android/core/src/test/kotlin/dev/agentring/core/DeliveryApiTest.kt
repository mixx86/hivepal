package dev.agentring.core

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Hivepal: the delivery and memory calls (issue #41). */
class DeliveryApiTest {
    private val view = """{"email":{"configured":true,"address":"bots@gmail.com","server":"smtp.gmail.com:465"},
        "mailboxes":{"all_agents":true},"memory":{"markdown":true,"sqlite":false},
        "mailbox_repo":{"url":"git@github.com:me/notes.git","public_key":"ssh-ed25519 AAAA hivepal-relay","last_push_at":5}}"""

    @Test
    fun `reads the settings and sends nulls where they mean something`() {
        MockWebServer().use { server ->
            val api = RelayApi(server.url("/").toString(), "jwt")
            server.enqueue(MockResponse().setBody(view))
            val d = api.delivery()
            assertEquals("bots@gmail.com", d.email.address)
            assertTrue(d.mailboxes.allAgents && d.memory.markdown && d.memory.on)
            assertEquals("ssh-ed25519 AAAA hivepal-relay", d.mailboxRepo.publicKey)
            assertEquals("GET /v1/settings/delivery", server.takeRequest().let { "${it.method} ${it.path}" })

            server.enqueue(MockResponse().setBody("""{"agent_id":"nubi","name":"Nubi","mailbox":true}"""))
            assertEquals(true, api.setAgentMailbox("nubi", null).mailbox)
            server.takeRequest().let {
                assertEquals("PUT /v1/agents/nubi/mailbox", "${it.method} ${it.path}")
                assertEquals("""{"enabled":null}""", it.body.readUtf8())
            }

            server.enqueue(MockResponse().setBody(view))
            api.setDeliveryEmail("bots@gmail.com", "abcd efgh", null)
            assertEquals("""{"address":"bots@gmail.com","password":"abcd efgh"}""", server.takeRequest().body.readUtf8())

            server.enqueue(MockResponse().setBody(view))
            api.setMailboxRepo(null)
            assertEquals("""{"url":null}""", server.takeRequest().body.readUtf8())

            server.enqueue(MockResponse().setBody(view))
            api.setMemory(markdown = false, sqlite = true)
            assertEquals("""{"markdown":false,"sqlite":true}""", server.takeRequest().body.readUtf8())

            server.enqueue(MockResponse().setResponseCode(502).setBody("""{"error":{"code":"email_failed","message":"The mail server said: 535"}}"""))
            val e = runCatching { api.setDeliveryEmail("a@gmail.com", "bad") }.exceptionOrNull() as RelayException
            assertEquals("email_failed", e.code)
        }
    }
}
