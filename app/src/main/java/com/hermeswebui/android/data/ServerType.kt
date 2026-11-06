package com.hermeswebui.android.data

enum class ServerType(val storedValue: String) {
    UNSPECIFIED("unspecified"),
    AGENT("agent"),
    WEBUI("webui");

    companion object {
        fun fromStoredValue(value: String?): ServerType =
            entries.firstOrNull { it.storedValue == value } ?: UNSPECIFIED
    }
}
