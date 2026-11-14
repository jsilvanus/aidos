package fi.italeino.aidos

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build

/**
 * Handles creation and management of dynamic project launchers (RFC-0111).
 *
 * Allows users to pin dedicated launcher shortcuts for specific projects/workspaces
 * to their Android home screen and launch projects as independent document tasks in
 * Android Recents (Overview screen).
 */
object ProjectLauncher {

    /**
     * Builds an Intent configured for Android Concurrent Documents (RFC-0111).
     * Opens the specified project into a dedicated card in the Android Recents app switcher.
     */
    fun createProjectIntent(context: Context, projectId: String, projectName: String): Intent {
        return Intent(context, ProjectActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = Uri.parse("aidos://project/$projectId")
            putExtra("projectId", projectId)
            putExtra("projectName", projectName)
            flags = Intent.FLAG_ACTIVITY_NEW_DOCUMENT or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
        }
    }

    /**
     * Pins a launcher shortcut for the given project to the Android home screen launcher.
     */
    fun pinProjectShortcut(context: Context, projectId: String, projectName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val shortcutManager = context.getSystemService(ShortcutManager::class.java) ?: return
            if (shortcutManager.isRequestPinShortcutSupported) {
                val intent = createProjectIntent(context, projectId, projectName)
                val shortcut = ShortcutInfo.Builder(context, "project-$projectId")
                    .setShortLabel(projectName)
                    .setLongLabel("Aidos: $projectName")
                    .setIcon(Icon.createWithResource(context, android.R.drawable.ic_menu_agenda))
                    .setIntent(intent)
                    .build()

                shortcutManager.requestPinShortcut(shortcut, null)
            }
        }
    }

    /**
     * Directly launches the project into its own independent document task in Android Recents.
     */
    fun launchProjectTask(context: Context, projectId: String, projectName: String) {
        val intent = createProjectIntent(context, projectId, projectName)
        context.startActivity(intent)
    }
}
