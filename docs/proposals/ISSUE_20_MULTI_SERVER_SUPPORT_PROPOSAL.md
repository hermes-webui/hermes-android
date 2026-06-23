# Issue 20 Proposal: Multi-server support and profile switching

Related issue: https://github.com/hermes-webui/hermes-android/issues/20

## Goal

Enable users to:
1. Access app settings at any time (not just on error screen).
2. Save and manage multiple Hermes server configurations.
3. Switch between configured servers without deleting and reconfiguring.

This keeps the Android wrapper as a thin, secure multi-server companion to Hermes WebUI while respecting existing security and trust boundaries.

## Current baseline

- Initial server URL is prompted on first run and stored in encrypted `SettingsRepository`.
- After setup, there is no easily accessible way to reopen connection settings.
- Only error screen provides an "Edit server URL" recovery action (see Issue #8/#14).
- No multi-server or profile-switching support.
- All server state is global: one WebView, one cookies store, one active server.

## Proposed delivery plan

### Phase 1: Native entry point (Issue 20 Part A) ✅ COMPLETE

**Status**: Delivered 2026-06-23  
**Branch**: `A-020-multi-server-profiles`

#### Scope

- Add a native "Application Settings" entry point accessible from the Hermes WebUI sidebar at all times.
- Place it below Help so users always know where to find app-level configuration.
- Trigger native settings bottom sheet from a deep link without breaking WebUI navigation.

#### Implementation

- **WebView shim**: Document-start injection clones the Help menu item, strips routing attributes, injects custom phone-outline SVG icon, and binds click to `hermes://app/settings` deep link.
- **Deep link handler**: `hermes://app/settings` intercepted in `MainActivity.shouldOverrideUrlLoading()`, routes to `handleAppSettingsNavigation()`.
- **Native UI**: `MainViewModel.openSettings()` displays existing `SettingsBottomSheet` (renamed header to "Application Settings").
- **Icon**: Custom SVG phone outline that respects WebUI theme colors via `currentColor`.
- **Mutation observer**: Shim survives SPA page reloads and navigations with a mutation observer that reapplies injection after DOM changes.

#### Files touched

- `app/src/main/java/com/hermeswebui/android/MainActivity.kt`
  - Added `HermesWebUiAppSettingsEntryScript` (JavaScript shim)
  - Added `appSettingsEntryScriptHandler` property for cleanup
  - Added `handleAppSettingsNavigation(url: String?): Boolean` method
  - Integrated shim into document-start script + post-load pass
  
- `app/src/main/java/com/hermeswebui/android/ui/settings/SettingsBottomSheet.kt`
  - Renamed header from "App settings" to "Application Settings"

#### Acceptance criteria ✅

- [x] Sidebar shows "Application Settings" menu item with phone icon below Help
- [x] Click opens native settings bottom sheet
- [x] No Help page navigation side effect
- [x] Icon matches sidebar styling and respects theme colors
- [x] All unit tests pass
- [x] Emulator deployment validates behavior

#### Estimated effort

- 6-8 engineering hours (completed in one session)

---

### Phase 2: Multi-server profile storage (Issue 20 Part B) 📋 PENDING

#### Scope

- Add unencrypted data model for server profiles (URLs and names, no secrets).
- Persist multiple named server configurations in `SharedPreferences`.
- Extend `SettingsBottomSheet` UI following Android Material Design guidelines.
- Display list of servers with "Add new server" button.
- Support migration from single-server to multi-server schema.
- Only one profile can be active at a time (tracked in settings).

#### Candidate implementation

- **Data model**
  - New `ServerProfile` data class: `id`, `name`, `url`, `createdAt`, `isActive`.
  - Store in `SharedPreferences` as unencrypted JSON array (e.g., `KEY_SERVER_PROFILES`).
  
- **SettingsRepository**
  - Add `ServerProfile` CRUD methods: `addProfile()`, `updateProfile()`, `deleteProfile()`, `getProfiles()`, `setActiveProfile()`, `getActiveProfile()`.
  - Increment `KEY_LAST_MIGRATION_VERSION` and add migration block to convert single server URL → `activeProfile + profilesList`.
  - Preserve backward compatibility: if `lastLoadedUrl` exists and profiles list is empty, migrate to single unnamed "Default" profile.

- **SettingsBottomSheet**
  - Add "Server Profiles" section below the URL input field.
  - Use Material 3 ListItem composables for each server profile.
  - Each row displays: server name and URL, with trailing delete icon.
  - "Add new server" button (Material 3 Button) below the list.
  - Tapping "Add new server" opens a Material 3 dialog with:
    - Text field for server name (optional; auto-defaults to server URL if empty)
    - Text field for server URL
    - Validate button and Cancel button
  - Delete by tapping trailing icon (confirm deletion with Material 3 AlertDialog).
  - Active server is indicated by a leading radio button (for Phase 3 switching UI).

- **Settings validation**
  - Reuse existing `ServerUrlValidator` for URL format and protocol validation.
  - Allow HTTP and HTTPS per `UrlPolicy`.
  - Show validation errors in the dialog (snackbar or inline error text).

#### Files touched

- `app/src/main/java/com/hermeswebui/android/data/ServerProfile.kt` (new)
- `app/src/main/java/com/hermeswebui/android/data/SettingsRepository.kt`
  - Add profile CRUD, migration logic, active-profile tracking
  
- `app/src/main/java/com/hermeswebui/android/ui/settings/SettingsBottomSheet.kt`
  - Add profile list and dialogs using Material 3 components
  
- `app/src/main/java/com/hermeswebui/android/ui/MainViewModel.kt`
  - Add observable active profile state

#### Acceptance criteria

- [ ] Multiple profiles can be created with valid URLs and displayed in a list
- [ ] Active profile is persisted and loaded on app restart
- [ ] Migration from single-server to multi-server succeeds without data loss
- [ ] Profile list UI displays with proper add/delete interactions
- [ ] URL validation rejects invalid formats and non-allowlisted protocols
- [ ] "Add new server" button and dialog follow Material 3 design
- [ ] Tests verify CRUD operations and migration correctness

#### Estimated effort

- 8-12 engineering hours

---

### Phase 3: Profile switching logic (Issue 20 Part C) 📋 PENDING

#### Scope

- Implement server-switch action: load new server, clear prior session, update allowlist trust boundary.
- Add radio-button UI to profile list (Phase 2 UI extended) so user can tap to switch.
- Ensure only one profile is active at a time.
- Validate that all profiles respect `UrlPolicy` allowlist rules.
- Test profile lifecycle: add, activate, switch, delete, verify isolation.

#### Candidate implementation

- **Server switch trigger**
  - User taps radio button or "Set Active" action on a profile in the settings list.
  - `MainViewModel.switchServerProfile(profile: ServerProfile)` is called.
  
- **Switch flow**
  - Validate profile URL against `ServerUrlValidator` and `UrlPolicy.hasSameOrigin()`.
  - If invalid, show error snackbar and skip switch.
  - Call `MainViewModel.loadServerProfile(profile: ServerProfile)`:
    - Update `SettingsRepository.setActiveProfile(profile.id)`.
    - Clear WebView cookies, cache, and DOM storage for the prior server origin.
    - Reload WebView with new server URL (triggers document-start shim + fresh session).
    - Update internal `serverUrl` state.
  - On successful load, close settings sheet and return to WebUI.
  
- **Trust boundary**
  - Each profile is validated against `UrlPolicy` allowlist at creation and switch time.
  - If a profile URL is blocked by allowlist, show error and prevent switch.
  - If allowlist is manually updated to exclude a profile's origin, warn user on next settings open.

- **Testing**
  - Unit tests for profile CRUD + active state persistence.
  - Integration tests for server switch flow, cookie clearing, and WebView reload.
  - Edge cases: switch to same profile (no-op), delete active profile (handle gracefully), rapid switches.

#### Files touched

- `app/src/main/java/com/hermeswebui/android/ui/MainViewModel.kt`
  - Add `switchServerProfile()` and `loadServerProfile()` methods
  - Add server validation logic
  
- `app/src/main/java/com/hermeswebui/android/data/SettingsRepository.kt`
  - Add `clearServerData()` method to wipe cookies/cache for a given origin
  
- Tests: new or expanded test classes for profile switching scenarios

#### Acceptance criteria

- [ ] Switching to a valid profile reloads WebView with new server
- [ ] Prior server cookies and cache are cleared
- [ ] Invalid profile URLs are rejected before switch
- [ ] Allowlist violations are caught and reported
- [ ] Deleting the active profile handles gracefully (fallback, error, or cleanup)
- [ ] Comprehensive integration and edge-case tests pass

#### Estimated effort

- 10-14 engineering hours

---

## Security and privacy constraints

- **Trust boundary**: Each profile is validated against `UrlPolicy` allowlist at creation, switch, and app load time. Never allow off-list origins in profiles.
- **Session isolation**: Switching servers must clear cookies, DOM storage, and service-worker cache for the old origin so credentials do not leak.
- **Storage**: Profile URLs and names are stored in unencrypted `SharedPreferences` (plain text OK for URLs; no secrets).
- **No secrets in profiles**: Profile storage holds only server origin/name, not API keys, auth tokens, or session state. Auth is managed by WebUI cookies and WebView storage.
- **Allowlist enforcement**: If allowlist is reconfigured to exclude a stored profile, subsequent app load or switch attempt must fail with clear messaging so users are not silently blocked.

---

## Testing strategy

### Phase 1

- [x] Unit tests: JavaScript shim injection and DOM cloning.
- [x] Integration tests: deep-link routing to settings sheet.
- [x] Manual device: sidebar visual consistency, icon rendering, click behavior.

### Phase 2

- [ ] Unit tests: profile CRUD, active-state persistence, migration correctness.
- [ ] Integration tests: settings-sheet UI interactions (add, edit, delete, set active).
- [ ] Device tests: profile list rendering, dialog input handling, error states.

### Phase 3

- [ ] Unit tests: server-switch flow, cookie clearing, URL validation.
- [ ] Integration tests: WebView reload after profile switch, session isolation.
- [ ] Edge-case tests: rapid switches, delete-active-profile, allowlist changes mid-session.
- [ ] Manual device: multi-profile real-server switching, verify no session/data leaks.

---

## Rollout strategy

1. **Phase 1** shipped (2026-06-23): Low-risk UX improvement, native settings always accessible.
2. **Phase 2** development: Build profile storage and settings UI behind feature flag or as "experimental" until Phase 3 validation completes.
3. **Phase 3** validation + shipping: After comprehensive testing, enable multi-server switching as default behavior.
4. **Future**: Monitor battery/performance impact of multiple stored profiles in encrypted storage.

---

## Scope and out-of-scope

### In scope

- Android native multi-server profile management (storage, UI, switching).
- URL validation using existing `ServerUrlValidator` and `UrlPolicy`.
- WebView session/cookie isolation.
- Encrypted persistence in `SettingsRepository`.

### Out of scope

- WebUI changes: Hermes WebUI does not see or manage profiles; it only renders with the active server URL.
- Profile sync across devices: No cloud or web-service profile sync; profiles are local-app-only.
- Profile-level auth tokens or secrets: Profiles hold only server origin; WebUI manages session auth via cookies/WebStorage.
- Backup/export of profiles: Nice-to-have future; not required for MVP.

---

## Detailed implementation blueprint

### Phase 1 (Complete - see above for code sections)

Files: `MainActivity.kt`, `SettingsBottomSheet.kt`

### Phase 2 blueprint

#### File: `app/src/main/java/com/hermeswebui/android/data/ServerProfile.kt` (new)

```kotlin
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val url: String,
    val createdAt: Long = System.currentTimeMillis(),
    val isActive: Boolean = false
) {
    // Validate URL format
    fun isValid(): Boolean {
        return ServerUrlValidator.isValidUrl(url)
    }
}
```

#### File: `app/src/main/java/com/hermeswebui/android/data/SettingsRepository.kt`

```kotlin
// Add to SettingsRepository class:

private companion object {
    private const val KEY_SERVER_PROFILES = "server_profiles"  // Unencrypted JSON array
    private const val KEY_ACTIVE_PROFILE_ID = "active_profile_id"  // Unencrypted ID
    private const val currentMigrationVersion = 2
}

// CRUD methods:
fun addProfile(name: String, url: String): ServerProfile {
    val profile = ServerProfile(name = name, url = url, isActive = false)
    val profiles = getProfiles().toMutableList()
    profiles.add(profile)
    saveProfiles(profiles)
    return profile
}

fun deleteProfile(profileId: String) {
    val profiles = getProfiles().toMutableList()
    profiles.removeAll { it.id == profileId }
    saveProfiles(profiles)
    // If deleted profile was active, clear active state
    if (prefs.getString(KEY_ACTIVE_PROFILE_ID, null) == profileId) {
        prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply()
    }
}

fun getProfiles(): List<ServerProfile> {
    val json = prefs.getString(KEY_SERVER_PROFILES, "[]") ?: "[]"
    return Gson().fromJson(json, Array<ServerProfile>::class.java).toList()
}

fun setActiveProfile(profileId: String) {
    prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, profileId).apply()
}

fun getActiveProfile(): ServerProfile? {
    val activeId = prefs.getString(KEY_ACTIVE_PROFILE_ID, null)
    return getProfiles().firstOrNull { it.id == activeId }
}

// Migration:
private fun runMigration() {
    val lastVersion = prefs.getInt(KEY_LAST_MIGRATION_VERSION, 0)
    if (lastVersion < 2) {
        // Migrate single URL to profiles list
        val oldUrl = prefs.getString(KEY_SERVER_URL, null)
        if (oldUrl != null && getProfiles().isEmpty()) {
            val profile = ServerProfile(name = "Default", url = oldUrl, isActive = true)
            saveProfiles(listOf(profile))
            setActiveProfile(profile.id)
        }
    }
    prefs.edit().putInt(KEY_LAST_MIGRATION_VERSION, currentMigrationVersion).apply()
}

private fun saveProfiles(profiles: List<ServerProfile>) {
    val json = Gson().toJson(profiles)
    prefs.edit().putString(KEY_SERVER_PROFILES, json).apply()
}
```

#### File: `app/src/main/java/com/hermeswebui/android/ui/settings/SettingsBottomSheet.kt`

Add composables for:
- Profile list with add/delete actions
- Profile name + URL display
- Active profile indicator (radio button or checkmark)
- Add Profile dialog (name + URL inputs, validation)
- Edit Profile dialog (if needed)
- Delete confirmation dialog

### Phase 3 blueprint

#### File: `app/src/main/java/com/hermeswebui/android/ui/MainViewModel.kt`

```kotlin
fun switchServerProfile(profile: ServerProfile) {
    // 1. Validate profile
    if (!ServerUrlValidator.isValidUrl(profile.url)) {
        updateErrorState("Invalid server URL in profile")
        return
    }
    if (!UrlPolicy.isAllowedOrigin(profile.url)) {
        updateErrorState("Server URL is not in the allowlist")
        return
    }
    
    // 2. Clear prior server data
    val currentServer = SettingsRepository.getActiveServer()
    if (currentServer != null && currentServer != profile.url) {
        clearServerData(currentServer)
    }
    
    // 3. Update active profile and reload
    SettingsRepository.setActiveProfile(profile.id)
    loadServerProfile(profile)
}

private fun clearServerData(serverOrigin: String) {
    // Clear WebView cookies, cache, DOM storage for serverOrigin
    CookieManager.getInstance().apply {
        removeAllCookies { }
        flush()
    }
    // Clear WebView cache if needed
    webView.clearCache(true)
}

private fun loadServerProfile(profile: ServerProfile) {
    serverUrl = profile.url
    webView.loadUrl(profile.url)
}
```

---

## API/contract decisions (for Phase 2/3 implementation)

- Multi-server support is purely local/client-side; no server-side API calls needed.
- Profile storage uses existing encrypted `SharedPreferences` (no new database).
- URL validation reuses `ServerUrlValidator` and `UrlPolicy` without modifications.

---

## Estimated total schedule

- Phase 1: **6-8 hours** (complete ✅)
- Phase 2: **8-12 hours**
- Phase 3: **10-14 hours**

**Total: ~24-34 engineering hours** for full multi-server support feature.

---

## Related issues

- Issue #8: Added "Edit server URL" button to error screen (partial solution, error-only access).
- Issue #14: Initial connection setup refinement.
- Issue #20: This proposal (full multi-server + always-accessible settings).

---

## Success metrics

- Users can always access app settings from the WebUI sidebar.
- Users can add, name, and delete multiple server profiles.
- Users can switch between profiles without losing prior sessions in different app launches.
- Switching servers clears prior session state (cookies, cache) so profiles are isolated.
- No regressions in existing single-server use cases.

