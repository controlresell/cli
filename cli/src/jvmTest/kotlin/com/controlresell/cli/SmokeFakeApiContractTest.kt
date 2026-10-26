package com.controlresell.cli

import com.controlresell.sdk.generated.models.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The native smoke (scripts/smoke-native.py) only runs on tags and full validation, so a drift between the payloads
 * served by scripts/fake-api.py and the pinned Kotlin SDK models would surface at release rehearsal. This test decodes
 * every payload the fake serves with those models on each pull request. Unknown keys are refused too, unlike the SDK's
 * own lenient client, so a misspelled or renamed field fails here as well.
 */
class SmokeFakeApiContractTest {
    private val strict = Json { ignoreUnknownKeys = false }

    private val served: Map<String, JsonElement> by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null && !File(directory, "scripts/fake-api.py").isFile) directory = directory.parentFile
        val script = checkNotNull(directory) { "scripts/fake-api.py not found above ${File("").absolutePath}" }.resolve("scripts/fake-api.py")
        val stdout = File.createTempFile("fake-api-dump", ".json")
        val stderr = File.createTempFile("fake-api-dump", ".err")
        try {
            val process = ProcessBuilder("python3", script.path, "--dump").redirectOutput(stdout).redirectError(stderr).start()
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("fake-api.py --dump did not finish within 60 seconds")
            }
            assertEquals(0, process.exitValue(), "fake-api.py --dump failed:\n${stderr.readText()}")
        } catch (failure: Throwable) {
            stdout.delete(); stderr.delete()
            throw failure
        }
        val output = stdout.readText()
        stdout.delete(); stderr.delete()
        strict.parseToJsonElement(output).jsonObject
    }

    private inline fun <reified T> decode(name: String): T = strict.decodeFromJsonElement(checkNotNull(served[name]) { "No $name served" })

    @Test fun `the fake serves the identity the SDK model reads`() {
        assertEquals("smoke-seller", decode<Me>("me").username)
    }

    @Test fun `the fake serves accounts the SDK model reads`() {
        assertEquals(3, decode<AccountPage>("accountPage").data.size)
        assertEquals("acc_1", decode<Account>("account").id)
    }

    @Test fun `the fake serves items with every required field and non-empty nested lists`() {
        val page = decode<ItemPage>("itemPage")
        assertEquals(5, page.data.size)
        val item = decode<Item>("item")
        assertTrue(item.photos.isNotEmpty() && item.enumValues.isNotEmpty() && item.attributes.isNotEmpty() && item.customFields.isNotEmpty())
        assertEquals("season", item.enumValues.single().enumKey)
        assertEquals("brand_note", item.attributes.single().fieldKey)
        assertEquals("shelf A", item.customFields.single().value)
    }

    @Test fun `the fake serves listings the SDK model reads`() {
        assertEquals("lst_1", decode<ListingPage>("listingPage").data.single().id)
    }

    @Test fun `the fake serves conversations and messages the SDK models read`() {
        assertEquals(2, decode<ConversationPage>("conversationPage").data.size)
        assertEquals("conv_1", decode<Conversation>("conversation").id)
        assertEquals(3, decode<MessagePage>("messagePage").data.size)
    }

    @Test fun `the fake serves every error body the SDK model reads`() {
        val errors = strict.decodeFromJsonElement<List<ErrorResponse>>(checkNotNull(served["errors"]))
        assertEquals(listOf("item_not_found", "plan_required", "rate_limited", "route_not_found", "invalid_token"), errors.map { it.error.code })
    }
}
