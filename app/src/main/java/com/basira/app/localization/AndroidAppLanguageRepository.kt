package com.basira.app.localization

import android.app.LocaleManager
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import com.basira.domain.model.AppLanguage
import com.basira.domain.model.LanguagePreference
import com.basira.domain.repository.AppLanguageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [AppLanguageRepository] backed by Android per-app languages.
 *
 * - Android 13+: the choice is the system per-app language ([LocaleManager]), so it is the same
 *   setting as Settings > Apps > Basira > Language; the system persists it and recreates the screens.
 * - Older versions: the choice is kept in private preferences and applied by [AppLocales.wrap] when an
 *   activity is created; `MainActivity` recreates itself when [language] changes.
 *
 * [language] is re-resolved on every configuration change, so a change of the phone language while
 * the app runs also switches speech.
 */
@Singleton
class AndroidAppLanguageRepository @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : AppLanguageRepository {

    private val _preference = MutableStateFlow(readPreference())
    override val preference: StateFlow<LanguagePreference> = _preference.asStateFlow()

    private val _language = MutableStateFlow(resolve(_preference.value))
    override val language: StateFlow<AppLanguage> = _language.asStateFlow()

    init {
        context.registerComponentCallbacks(
            object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) = refresh()

                @Deprecated("Deprecated in Java")
                override fun onLowMemory() = Unit
            },
        )
    }

    override fun setPreference(preference: LanguagePreference) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager().applicationLocales =
                preference.language?.let { LocaleList.forLanguageTags(it.tag) } ?: LocaleList.getEmptyLocaleList()
        } else {
            AppLocales.store(context, preference.language)
        }
        _preference.value = preference
        _language.value = resolve(preference)
    }

    private fun refresh() {
        val current = readPreference()
        _preference.value = current
        _language.value = resolve(current)
    }

    private fun readPreference(): LanguagePreference = LanguagePreference.of(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager().applicationLocales.takeUnless { it.isEmpty }?.let { AppLanguage.fromTag(it[0].toLanguageTag()) }
        } else {
            AppLocales.stored(context)
        },
    )

    private fun resolve(preference: LanguagePreference): AppLanguage {
        val system = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            localeManager().systemLocales
        } else {
            Resources.getSystem().configuration.locales
        }
        return AppLanguage.resolve(preference, List(system.size()) { system[it].toLanguageTag() })
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun localeManager(): LocaleManager = context.getSystemService(LocaleManager::class.java)
}

/** Applies the in-app language on Android versions without per-app languages (before 13). */
object AppLocales {
    private const val PREFERENCES = "app_language"
    private const val KEY_LANGUAGE = "language"

    /**
     * Wraps an activity's base context so its resources use the stored language. On Android 13+
     * the system does this itself and [base] is returned unchanged.
     *
     * @param base the context passed to `attachBaseContext`.
     * @return the context to attach.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        return stored(base)?.let(base::withLanguage) ?: base
    }

    internal fun stored(context: Context): AppLanguage? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_LANGUAGE, null)?.let(AppLanguage::fromTag)

    internal fun store(context: Context, language: AppLanguage?) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putString(KEY_LANGUAGE, language?.tag) }
}

/**
 * Returns a context whose resources are in [language], independent of the configuration of this
 * context. Used for spoken text, which must match the app language even from the application context.
 *
 * @param language the language of the returned resources.
 */
fun Context.withLanguage(language: AppLanguage): Context = createConfigurationContext(
    Configuration(resources.configuration).apply { setLocale(Locale.forLanguageTag(language.tag)) },
)

/** The language this context's resources are shown in, resolved like Android resource lookup. */
val Context.shownLanguage: AppLanguage
    get() {
        val locales = resources.configuration.locales
        return AppLanguage.resolve(LanguagePreference.SYSTEM, List(locales.size()) { locales[it].toLanguageTag() })
    }
