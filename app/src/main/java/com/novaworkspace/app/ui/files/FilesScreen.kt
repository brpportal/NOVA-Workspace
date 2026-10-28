package com.novaworkspace.app.ui.files

import androidx.compose.runtime.Composable
import com.novaworkspace.app.ui.EmptyState

/**
 * Milestone 1 Files screen: honest empty state only.
 * The local workspace (NovaWorkspace/ Projects, Research, Documents, ...)
 * and file manager arrive in Milestone 2 (SPEC Sec. 13).
 */
@Composable
fun FilesScreen() {
    EmptyState(
        title = "Files",
        body = "No workspace files yet. The local-first workspace and file manager arrive in Milestone 2."
    )
}
