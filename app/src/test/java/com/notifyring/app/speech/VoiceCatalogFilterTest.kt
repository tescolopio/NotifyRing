package com.notifyring.app.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCatalogFilterTest {

    @Test
    fun filtersOutNetworkNotInstalledAndBlankVoices() {
        val rawVoices = listOf(
            RawVoiceDescriptor(
                name = "en-us-x-sfg-local",
                localeTag = "en-US",
                quality = 400,
                isNetworkConnectionRequired = false,
                features = setOf("embeddedTts")
            ),
            RawVoiceDescriptor(
                name = "en-us-x-sfg-network",
                localeTag = "en-US",
                quality = 500,
                isNetworkConnectionRequired = true,
                features = emptySet()
            ),
            RawVoiceDescriptor(
                name = "en-us-x-tpf-not-installed",
                localeTag = "en-US",
                quality = 400,
                isNetworkConnectionRequired = false,
                features = setOf(VoiceCatalogFilter.FEATURE_NOT_INSTALLED)
            ),
            RawVoiceDescriptor(
                name = "en-us-x-case-not-installed",
                localeTag = "en-US",
                quality = 400,
                isNetworkConnectionRequired = false,
                features = setOf("NOTINSTALLED")
            ),
            RawVoiceDescriptor(
                name = "   ",
                localeTag = "en-US",
                quality = 400,
                isNetworkConnectionRequired = false,
                features = emptySet()
            )
        )

        val result = VoiceCatalogFilter.filterAndSort(rawVoices, Locale.US)

        assertEquals(1, result.size)
        assertEquals("en-us-x-sfg-local", result.single().name)
    }

    @Test
    fun sortsByLocaleMatchThenQualityDescendingThenNameAscending() {
        val rawVoices = listOf(
            RawVoiceDescriptor("fr-fr-high", "fr-FR", quality = 500, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-gb-very-high", "en-GB", quality = 500, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-normal", "en-US", quality = 300, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-high-b", "en-US", quality = 400, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-high-a", "en-US", quality = 400, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-very-high", "en-US", quality = 500, isNetworkConnectionRequired = false)
        )

        val sorted = VoiceCatalogFilter.filterAndSort(rawVoices, Locale.US)

        assertEquals(
            listOf(
                "en-us-very-high",
                "en-us-high-a",
                "en-us-high-b",
                "en-us-normal",
                "en-gb-very-high",
                "fr-fr-high"
            ),
            sorted.map { it.name }
        )
    }

    @Test
    fun formatsHumanReadableDisplayLabelsAcrossAllQualityTiers() {
        val rawVoices = listOf(
            RawVoiceDescriptor("en-us-x-sfg-local", "en-US", quality = 500, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-x-tpf-local", "en-US", quality = 400, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-x-iol-local", "en-US", quality = 300, isNetworkConnectionRequired = false),
            RawVoiceDescriptor("en-us-x-low-local", "en-US", quality = 200, isNetworkConnectionRequired = false)
        )

        val options = VoiceCatalogFilter.filterAndSort(rawVoices, Locale.US)

        assertEquals(
            listOf(
                "Voice 1 — Very High Quality",
                "Voice 2 — High Quality",
                "Voice 3 — Normal Quality",
                "Voice 4 — Low Quality"
            ),
            options.map { it.displayLabel }
        )
        assertEquals(
            listOf(
                VoiceQualityTier.VERY_HIGH,
                VoiceQualityTier.HIGH,
                VoiceQualityTier.NORMAL,
                VoiceQualityTier.LOW
            ),
            options.map { it.qualityTier }
        )
        options.forEach { option ->
            assertFalse(option.displayLabel.contains("en-us-x-"))
            assertTrue(option.localeDisplayName.isNotBlank())
        }
    }

    @Test
    fun resolveBestVoiceMatchesSavedOrFallsBackToBestLocalVoice() {
        val options = VoiceCatalogFilter.filterAndSort(
            listOf(
                RawVoiceDescriptor("voice-high", "en-US", quality = 400, isNetworkConnectionRequired = false),
                RawVoiceDescriptor("voice-normal", "en-US", quality = 300, isNetworkConnectionRequired = false)
            ),
            Locale.US
        )

        assertEquals("voice-normal", VoiceCatalogFilter.resolveBestVoice("voice-normal", options)?.name)
        assertEquals("voice-normal", VoiceCatalogFilter.resolveBestVoice("  voice-normal  ", options)?.name)
        assertEquals("voice-high", VoiceCatalogFilter.resolveBestVoice("missing-voice", options)?.name)
        assertEquals("voice-high", VoiceCatalogFilter.resolveBestVoice(null, options)?.name)
        assertEquals("voice-high", VoiceCatalogFilter.resolveBestVoice("   ", options)?.name)
        assertNull(VoiceCatalogFilter.resolveBestVoice("voice-high", emptyList()))
    }
}
