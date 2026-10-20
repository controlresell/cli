package com.controlresell.cli

import com.controlresell.cli.config.*
import kotlin.test.*

class ConfigurationTest {
    @Test fun `configuration honors XDG or falls back to home dot config`() {
        assertEquals("/custom/controlresell/config.json", configPath { if (it == "XDG_CONFIG_HOME") "/custom" else null })
        assertEquals("/users/me/.config/controlresell/config.json", configPath { if (it == "HOME") "/users/me" else null })
        assertFailsWith<IllegalArgumentException> { configPath { if (it == "XDG_CONFIG_HOME") "relative" else null } }
    }
    @Test fun `remote plaintext redirects and embedded credentials are not acceptable origins`() {
        for (value in listOf("", "   ", "http://api.example", "https://user:secret@api.example", "https://api.example/path", "https://api.example?token=secret", "https://api.example#fragment")) {
            assertFailsWith<IllegalArgumentException>(value) { validateApiUrl(value) }
        }
        assertEquals("https://api.controlresell.com", validateApiUrl("https://api.controlresell.com/"))
        assertEquals("http://127.0.0.1:8080", validateApiUrl("http://127.0.0.1:8080"))
    }
    @Test fun `configuration debug rendering redacts the API key`() {
        assertFalse(CliConfig("https://api.example", "cr_live_secret").toString().contains("cr_live_secret"))
    }
}
