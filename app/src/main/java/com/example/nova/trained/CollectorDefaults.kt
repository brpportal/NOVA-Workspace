package com.example.nova.trained

/**
 * Round B — user-configurable defaults for the two collectors,
 * persisted in the app's existing key/value settings store
 * (app_settings via SettingsDao) and edited on the Settings screen's
 * Collectors section. Both Trained Tasks cards initialize their
 * controls from these values.
 *
 * Parsing is pure and defensive: a missing or unparseable stored value
 * falls back to the built-in default, and every number is clamped into
 * the collector's own supported range — the same clamps the launchers
 * apply — so a hand-edited or stale value can never push a run out of
 * bounds.
 */
data class CollectorDefaults(
    val method: FacebookExtractionMode,
    /** Links Collection: default post links per source (1–100, default 5). */
    val linksPerSource: Int,
    /** Screenshots Collection: default post screenshots per profile (1–10, default 2). */
    val screenshotPostsPerProfile: Int
) {
    companion object {
        const val KEY_METHOD = "collector_default_method"
        const val KEY_LINKS_PER_SOURCE = "collector_default_links_per_source"
        const val KEY_SCREENSHOT_POSTS = "collector_default_screenshot_posts"

        val BUILT_IN = CollectorDefaults(
            method = FacebookExtractionMode.HYBRID,
            linksPerSource = FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE,
            screenshotPostsPerProfile = ScreenshotsCollection.DEFAULT_POSTS_PER_PROFILE
        )

        /** Builds defaults from raw stored settings values (key → value). */
        fun fromSettings(values: Map<String, String>): CollectorDefaults {
            val method = FacebookExtractionMode.fromToolArg(values[KEY_METHOD])
            val links = values[KEY_LINKS_PER_SOURCE]?.trim()?.toIntOrNull()
                ?.let { FacebookPostLinks.clampPostsPerProfile(it) }
                ?: FacebookPostLinks.DEFAULT_POSTS_PER_PROFILE
            val shots = values[KEY_SCREENSHOT_POSTS]?.trim()?.toIntOrNull()
                ?.let { ScreenshotsCollection.clampPostsPerProfile(it) }
                ?: ScreenshotsCollection.DEFAULT_POSTS_PER_PROFILE
            return CollectorDefaults(method, links, shots)
        }

        /** The exact key → value pairs to persist for these defaults. */
        fun toSettings(defaults: CollectorDefaults): Map<String, String> = mapOf(
            KEY_METHOD to defaults.method.toolArg(),
            KEY_LINKS_PER_SOURCE to
                FacebookPostLinks.clampPostsPerProfile(defaults.linksPerSource).toString(),
            KEY_SCREENSHOT_POSTS to
                ScreenshotsCollection.clampPostsPerProfile(defaults.screenshotPostsPerProfile).toString()
        )
    }
}
