# Issue #12: OIDC Login Failure - Analysis

## Reported Problem
When attempting to login using OIDC (kanidm) on the Android app:
- Desktop and WebUI work fine
- Android app receives: `{"detail":"Missing PKCE state cookie"}`
- App first tries opening OIDC auth in external browser (fails)
- After force quit/reopen, login UI appears in WebView
- After successful login, external browser opens with: "Error Code: UI0003InvalidOauth2Resume"

## Root Cause Analysis

### OAuth2 PKCE Flow Requirements
OAuth2 PKCE (Proof Key for Code Exchange) requires:
1. Client generates a PKCE state and challenge
2. State is stored in a secure cookie (`pkce_state`)
3. Authorization request includes the challenge
4. Auth server stores state cookie
5. On callback, state cookie must be present to validate

### Potential Issues in Hermes-Android

#### 1. Popup WebView Cookie Handling (LIKELY ROOT CAUSE)
Located in `MainActivity.kt` lines 481-507:

```kotlin
override fun onCreateWindow(view: WebView?, ...): Boolean {
    val popup = WebView(this@MainActivity)
    popup.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(...): Boolean {
            handleNewWindowUrl(target)  // Loads on MAIN webView
            popup.destroy()             // Popup destroyed immediately
            return true
        }
        override fun onPageStarted(...) {
            handleNewWindowUrl(url)
            popup.destroy()             // Destroyed before page loads
        }
    }
    // ...
}
```

**Problem**: The popup WebView is destroyed immediately after the first redirect/load, losing any cookies/state set during auth initialization.

#### 2. CookieManager Sharing
- Cookies should be shared across WebView instances via `CookieManager.getInstance()`
- BUT: If popup is destroyed before cookies are fully set/flushed, they may be lost
- Line 579 calls `CookieManager.getInstance().flush()` only on page finish

#### 3. External Browser Context Loss
When auth opens in external browser then returns:
- External browser has its own cookie jar
- App WebView cookie context is separate
- PKCE state set in external browser is not available in WebView

#### 4. Window.open() vs. Direct Navigation
- If WebUI calls `window.open()` for auth, it creates popup
- If it's a redirect (`window.location = ...`), it should stay in same context
- Current code treats both the same way

## What We Need to Know From You

Before proceeding with the fix, I need to clarify:

1. **Auth Flow**: When the user initiates OIDC login on the Hermes WebUI:
   - Does WebUI use `window.open()` to open the auth dialog?
   - Or does it do a direct navigation in the main frame?

2. **Expected Behavior**: Should the OIDC auth flow:
   - Open in an external browser? (desktop pattern)
   - Or stay within the app WebView?
   - Or give user a choice?

3. **Cookie Domain**: For kanidm OIDC:
   - Are cookies set with domain rules that might prevent sharing between contexts?
   - Any special PKCE configuration?

## Proposed Fix Strategy

Once we clarify above, the fix will likely involve one or more of:

1. **Option A - Keep popup alive during auth**: 
   - Don't destroy popup until auth completes
   - Preserve cookie context throughout flow

2. **Option B - Redirect to main WebView safely**:
   - Load redirect URL on main WebView immediately
   - Ensure cookies flushed before popup destroyed

3. **Option C - Use external browser intentionally**:
   - Detect OIDC auth URLs early
   - Open in external browser deliberately
   - Handle callback via deep link or intent

4. **Option D - Hybrid approach**:
   - Keep popup alive for auth
   - On final callback, redirect to main WebView with preserved cookies

## Next Steps

1. Review hermes-webui OIDC implementation to understand flow
2. Determine if we should use external browser or WebView for OIDC
3. Implement the appropriate fix
4. Test with kanidm or similar OIDC provider

