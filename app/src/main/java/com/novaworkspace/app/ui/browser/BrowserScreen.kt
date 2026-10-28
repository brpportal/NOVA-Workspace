package com.novaworkspace.app.ui.browser

import androidx.compose.runtime.Composable
import com.novaworkspace.app.ui.EmptyState

/**
 * Milestone 1 Browser screen: honest empty state only.
 * The real embedded WebView, tabs and BrowserController arrive in
 * Milestones 3, 5 and 7 (SPEC Sec. 6-8). No fake browser is embedded here.
 */
@Composable
fun BrowserScreen() {
    EmptyState(
        title = "Browser",
        body = "The embedded WebView browser arrives in Milestone 3. There is no browser in Milestone 1, and this screen does not simulate one."
    )
}
