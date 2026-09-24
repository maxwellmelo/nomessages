package dev.mx3.nomessages

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * English is the app's default resource set (`values/`), with Portuguese (`values-pt/`) as the
 * only translation. Every other device locale must resolve to English rather than to any system
 * fallback the OS might otherwise pick, which [Configuration.setLocale] plus
 * [Context.createConfigurationContext] lets this test observe directly instead of relying on
 * `Locale.getDefault()` process state.
 */
@RunWith(AndroidJUnit4::class)
class LocaleResourcesTest {
    private fun stringFor(locale: Locale): String {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config).getString(R.string.unlock)
    }

    @Test
    fun portugueseLocaleResolvesPortugueseStrings() {
        assertEquals("Desbloquear", stringFor(Locale.Builder().setLanguage("pt").setRegion("BR").build()))
        assertEquals("Desbloquear", stringFor(Locale.Builder().setLanguage("pt").setRegion("PT").build()))
    }

    @Test
    fun englishLocaleResolvesEnglishStrings() {
        assertEquals("Unlock", stringFor(Locale.ENGLISH))
    }

    @Test
    fun unsupportedThirdLocaleFallsBackToEnglish() {
        // Spanish is not a supported locale (only en/pt ship, see locales_config.xml and
        // app/build.gradle.kts androidResources.localeFilters): it must fall back to the
        // default resource set (English), never to Portuguese or a raw resource-not-found.
        assertEquals("Unlock", stringFor(Locale.Builder().setLanguage("es").setRegion("ES").build()))
    }

    @Test
    fun unsupportedNonLatinLocaleFallsBackToEnglish() {
        assertEquals("Unlock", stringFor(Locale.JAPANESE))
    }
}
