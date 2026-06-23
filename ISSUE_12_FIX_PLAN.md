# Issue #12 Fix Plan: OIDC PKCE State Cookie Loss

## Problem Diagnosis

### Reported Issue
- User attempts OIDC login (kanidm) on Android app
- Gets error: `{"detail":"Missing PKCE state cookie"}`
- Flow: App tries opening auth in external browser → fails → force quit → login UI in WebView → redirects back to external browser with `UI0003InvalidOauth2Resume` error

### Root Cause
The Android app's WebView popup handling **destroys the popup too early**, losing PKCE state cookies before OAuth authentication completes.

**Code at fault:** `MainActivity.kt` lines 481-507

```kotlin
override fun onCreateWindow(view: WebView?, ...): Boolean {
    val popup = WebView(this@MainActivity)
    popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(...): Boolean {
            handleNewWindowUrl(target)  // Redirect to MAIN webview
            popup.destroy()             // ← DESTROYED TOO EARLY
            return true
        }
        override fun onPageStarted(...) {
            handleNewWindowUrl(url)
            popup.destroy()             // ← DESTROYED TOO EARLY
        }
    }
}
```

### Why This Breaks OIDC PKCE

OAuth2 PKCE flow requires persistent state across multiple redirects:

1. **Step 1**: Browser starts auth: `GET https://auth-server/authorize?code_challenge=...`
2. **Step 2**: Auth server sets PKCE state cookie and redirects to login UI
3. **Step 3**: User logs in via kanidm
4. **Step 4**: Server validates PKCE state and redirects back with auth code
5. **Problem**: Android app popup is destroyed after Step 2, losing the PKCE state cookie

The PKCE state cookie set in Step 2 lives in the popup WebView's cookie jar, which gets destroyed before Step 4 can use it.

## How Hermes WebUI Handles OAuth

From code inspection (`api/oauth.py`, `api/onboarding.py`, `static/onboarding.js`):

1. **Flow Initiation**: Frontend calls `/api/onboarding/oauth/start` with provider (e.g., `anthropic`)
2. **Server Response**: Returns `auth_uri` (e.g., `https://auth.openai.com/authorize?...`)  
3. **Frontend Action**: Opens `auth_uri` in **external browser** OR **same-window navigation** (depends on provider)
4. **Callback**: Auth server redirects to WebUI's configured callback URL (`/api/onboarding/oauth/callback`)
5. **Polling**: Frontend polls `/api/onboarding/oauth/poll?flow_id=...` for completion

The problem: **When Android WebUI does `window.open(auth_uri)` for OAuth providers, the popup is used briefly, then destroyed by the Android app before the auth flow completes.**

## Fix Strategy

### Option 1: Don't Destroy Popup During Active OAuth (RECOMMENDED)

**Approach**: Detect if popup is being used for OAuth/OIDC, and keep it alive until callback completes.

**Implementation**:
1. Modify `onCreateWindow()` to **not destroy popup immediately**
2. Add timeout/lifecycle handler to **destroy popup after OAuth flow succeeds/fails**
3. Keep popup alive across multiple redirects that are part of the auth flow

**Pros**: 
- Minimal changes
- Respects WebUI's intended OAuth flow
- Works with all OIDC providers (kanidm, Auth0, etc.)

**Cons**: 
- Popup stays visible while auth completes

### Option 2: Force OIDC to Use External Browser Intentionally

**Approach**: Detect OAuth URIs early and open them in external browser, then handle callback via deep link.

**Implementation**:
1. In `shouldOverrideUrlLoading()`, detect OAuth auth URLs (common patterns: `authorize`, `oauth`, `oidc`)
2. Open these in external browser
3. Handle callback via `hermes://` deep link or intent

**Pros**: 
- More native-feeling (auth happens in system browser)
- Popup stays hidden

**Cons**: 
- Requires WebUI to support callback-from-external-browser
- May need custom redirect URI handling

### Option 3: Use WebView-Level Cookie Sharing & Lifecycle

**Approach**: Ensure cookies persist across popup lifecycle by properly managing WebView lifecycle and CookieManager.

**Implementation**:
1. Don't destroy popup immediately in `onPageStarted` — wait for OAuth completion
2. Use `CookieManager.getInstance().flush()` explicitly after redirects
3. Wait for final redirect before destroying popup

**Pros**: 
- Transparent to user
- Cookies properly persisted

**Cons**: 
- Requires careful timing logic

## Recommended Fix (Option 1)

### Changes to `MainActivity.kt`

**Step 1**: Add a flag to track OAuth flow state

```kotlin
private var activeOAuthPopup: WebView? = null
private var oauthFlowTimeout: Long = 0
```

**Step 2**: Detect OAuth URLs and keep popup alive

In `onCreateWindow()`:

```kotlin
override fun onCreateWindow(...): Boolean {
    val popup = WebView(this@MainActivity)
    popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val target = request?.url?.toString() ?: return true
            
            // Detect if this is an OAuth/OIDC flow
            if (isOAuthUrl(target)) {
                // Keep popup alive for OAuth completion
                activeOAuthPopup = popup
                oauthFlowTimeout = System.currentTimeMillis() + 5 * 60 * 1000 // 5 min timeout
                
                // Load OAuth URL in popup, don't redirect to main webview
                view?.loadUrl(target)
                return true
            }
            
            // Non-OAuth redirects: handle as before
            handleNewWindowUrl(target)
            popup.destroy()
            return true
        }
    }
    
    // Set up cleanup on OAuth completion
    popup.webViewClient.onPageFinished = { view, url ->
        if (isOAuthCallbackUrl(url)) {
            activeOAuthPopup = null
            popup.destroy()
        }
    }
    
    return true
}

private fun isOAuthUrl(url: String): Boolean {
    // Detect OAuth/OIDC auth endpoints
    val lower = url.lowercase()
    return lower.contains("authorize") || 
           lower.contains("oauth") || 
           lower.contains("oidc") ||
           lower.contains("authenticate") ||
           lower.contains("/auth/")
}

private fun isOAuthCallbackUrl(url: String): Boolean {
    // Detect callback completion
    val lower = url.lowercase()
    return lower.contains("code=") || 
           lower.contains("state=") ||
           lower.contains("oauth/callback") ||
           lower.contains("error=")
}
```

**Step 3**: Add cleanup on pause/stop

```kotlin
override fun onPause() {
    super.onPause()
    // Check if OAuth timed out
    if (activeOAuthPopup != null && System.currentTimeMillis() > oauthFlowTimeout) {
        activeOAuthPopup?.destroy()
        activeOAuthPopup = null
    }
}
```

## Alternative: Check WebUI's Intended Design

**Question for user**: Can you check the hermes-webui code to confirm:
- How does the onboarding wizard actually handle OAuth provider login?
- Does it use `window.open(auth_uri)` or direct navigation?
- Is there a documented pattern for mobile apps to follow?

Once confirmed, the fix will be tailored to match WebUI's intended OAuth flow.

## Testing Plan

1. **Unit Test**: Mock OAuth URLs and verify popup stays alive
2. **Integration Test**: 
   - Configure kanidm OIDC endpoint
   - Initiate login through Android app
   - Verify PKCE state cookie persists through auth flow
3. **Manual Test**: 
   - Test with Auth0, Google, Azure AD OIDC
   - Verify successful login completes
   - Verify popup closes after auth completes

## Impact

- **Risk**: Low — changes only popup lifecycle, affects only OAuth flows
- **Scope**: Single method + minor state management
- **Backwards Compat**: Yes — non-OAuth flows unchanged


