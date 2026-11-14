package dev.aidos.ui.palette

enum class KeyAction {
    OPEN_COMMAND_PALETTE,
    OPEN_ACTION_INBOX,
    NAVIGATE_UP,
    NAVIGATE_DOWN,
    TOGGLE_COMPLETE,
    ESCAPE,
}

object KeyboardShortcutRegistry {
    fun resolveKeyAction(key: String, isMetaOrCtrl: Boolean): KeyAction? {
        return when {
            isMetaOrCtrl && (key.equals("k", ignoreCase = true)) -> KeyAction.OPEN_COMMAND_PALETTE
            isMetaOrCtrl && (key.equals("i", ignoreCase = true)) -> KeyAction.OPEN_ACTION_INBOX
            key.equals("j", ignoreCase = true) -> KeyAction.NAVIGATE_DOWN
            key.equals("k", ignoreCase = true) -> KeyAction.NAVIGATE_UP
            key.equals(" ", ignoreCase = true) || key.equals("Space", ignoreCase = true) -> KeyAction.TOGGLE_COMPLETE
            key.equals("Escape", ignoreCase = true) -> KeyAction.ESCAPE
            else -> null
        }
    }
}
