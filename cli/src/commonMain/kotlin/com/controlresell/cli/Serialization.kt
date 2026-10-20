package com.controlresell.cli

import kotlinx.serialization.json.Json

object Serialization {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
