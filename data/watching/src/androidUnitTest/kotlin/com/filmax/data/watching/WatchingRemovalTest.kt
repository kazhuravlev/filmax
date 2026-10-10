package com.filmax.data.watching

import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.watching.model.WatchingListType
import com.filmax.data.watching.remote.WatchingApi
import com.filmax.data.watching.remote.dto.ToggleWatchlistResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WatchingRemovalTest {
    @Test
    fun `series removal unsubscribes once and preserves history`() = runBlocking {
        val engine = responses(SERIES_LIST, """{"watching":0}""", EMPTY_LIST, EMPTY_LIST, EMPTY_LIST)
        client(engine).use { client ->
            val repository = WatchingRepositoryImpl(WatchingApi(client))
            assertIs<RequestResult.Success<Unit>>(repository.removeFromWatching(42, WatchingListType.Serials))
            assertIs<RequestResult.Success<Unit>>(repository.removeFromWatching(42, WatchingListType.Serials))
        }
        assertEquals(1, engine.requestHistory.count { it.url.encodedPath.endsWith("togglewatchlist") })
        assertTrue(engine.requestHistory.none { it.url.encodedPath.contains("history") })
        assertTrue(
            engine.requestHistory.filter { it.url.encodedPath.endsWith("serials") }
                .all { it.url.parameters["subscribed"] == "1" },
        )
        assertEquals("42", engine.requestHistory[1].url.parameters["id"])
    }

    @Test
    fun `already absent series is never toggled back on`() = runBlocking {
        val engine = responses(EMPTY_LIST, EMPTY_LIST)
        client(engine).use { client ->
            assertIs<RequestResult.Success<Unit>>(
                WatchingRepositoryImpl(WatchingApi(client)).removeFromWatching(42, WatchingListType.Serials),
            )
        }
        assertTrue(engine.requestHistory.all { it.url.encodedPath.endsWith("serials") })
    }

    @Test
    fun `movie removal posts history clear for only the selected title`() = runBlocking {
        val engine = responses("", EMPTY_LIST)
        client(engine).use { client ->
            assertIs<RequestResult.Success<Unit>>(
                WatchingRepositoryImpl(WatchingApi(client)).removeFromWatching(42, WatchingListType.Movies),
            )
        }
        val clear = engine.requestHistory.first()
        assertEquals(HttpMethod.Post, clear.method)
        assertEquals("/api/v1/history/clear-for-item", clear.url.encodedPath)
        assertEquals("42", clear.url.parameters["id"])
        assertEquals("/api/v1/watching/movies", engine.requestHistory.last().url.encodedPath)
    }

    @Test
    fun `remaining item is reported as failure`() = runBlocking {
        val engine = responses("", SERIES_LIST)
        client(engine).use { client ->
            val result = WatchingRepositoryImpl(WatchingApi(client)).removeFromWatching(42, WatchingListType.Movies)
            assertIs<RequestResult.Error>(result)
            assertTrue(result.message.orEmpty().contains("still in watching/movies"))
        }
    }

    @Test
    fun `missing list is not interpreted as successful removal`() = runBlocking {
        val engine = responses("""{"status":200}""")
        client(engine).use { client ->
            assertIs<RequestResult.Error>(
                WatchingRepositoryImpl(WatchingApi(client)).removeFromWatching(42, WatchingListType.Serials),
            )
        }
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `failed read cannot trigger a mutation`() = runBlocking {
        val engine = MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
        client(engine).use { client ->
            assertIs<RequestResult.Error>(
                WatchingRepositoryImpl(WatchingApi(client)).removeFromWatching(42, WatchingListType.Serials),
            )
        }
        assertTrue(engine.requestHistory.all { it.url.encodedPath.endsWith("serials") })
    }

    @Test
    fun `watchlist mutation is never automatically retried`() = runBlocking {
        val engine = MockEngine { respond("unavailable", HttpStatusCode.ServiceUnavailable) }
        client(engine).use { client ->
            assertIs<RequestResult.Error>(WatchingRepositoryImpl(WatchingApi(client)).toggleWatchlist(42))
        }
        assertEquals(1, engine.requestHistory.size)
    }

    @Test
    fun `watchlist responses accept API numeric and documented boolean values`() {
        listOf("1", "true", "\"1\"").forEach { value ->
            assertEquals(true, Json.decodeFromString<ToggleWatchlistResponseDto>("""{"watching":$value}""").inWatchlist)
        }
        listOf("0", "false", "\"0\"").forEach { value ->
            assertEquals(false, Json.decodeFromString<ToggleWatchlistResponseDto>("""{"watching":$value}""").inWatchlist)
        }
        assertFails { Json.decodeFromString<ToggleWatchlistResponseDto>("{}").inWatchlist }
        assertFails { Json.decodeFromString<ToggleWatchlistResponseDto>("""{"watching":2}""").inWatchlist }
    }

    private fun responses(vararg bodies: String): MockEngine {
        var index = 0
        return MockEngine {
            respond(bodies[index++], headers = headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun client(engine: MockEngine) = HttpClient(engine) {
        expectSuccess = true
        defaultRequest { url("https://example.test/") }
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 1)
            delayMillis { 0 }
        }
    }

    private companion object {
        const val SERIES_LIST = """{"items":[{"id":42}]}"""
        const val EMPTY_LIST = """{"items":[]}"""
    }
}
