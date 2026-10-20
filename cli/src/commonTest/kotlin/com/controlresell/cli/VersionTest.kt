package com.controlresell.cli

import com.github.ajalt.clikt.command.test
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VersionTest {
    @Test
    fun versionPrintsTheFixedLabel() = runTest {
        val result = Cli().test("version")
        assertEquals(0, result.statusCode)
        assertEquals("ControlResell CLI $VERSION\n", result.stdout)
    }

    @Test
    fun operationCodesAreRestrictedToSafeIdentifiers() {
        assertEquals("release_file_unreadable", CliOperationException("release_file_unreadable").code)
        assertFailsWith<IllegalArgumentException> { CliOperationException("contains a secret: cr_live_x") }
    }
}
