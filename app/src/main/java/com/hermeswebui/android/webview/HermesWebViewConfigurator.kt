package com.hermeswebui.android.webview

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature

object HermesWebViewConfigurator {
    @SuppressLint("SetJavaScriptEnabled")
    fun configureMainWebView(
        webView: WebView,
        appVersionName: String,
        configureStorageAndCache: (WebSettings) -> Unit,
        disableWebViewDarkening: (WebSettings) -> Unit
    ) {
        with(webView.settings) {
            javaScriptEnabled = true
            configureStorageAndCache(this)
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            userAgentString = "${userAgentString} Hermes-Android/$appVersionName"
            disableWebViewDarkening(this)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                WebSettingsCompat.setWebAuthenticationSupport(
                    this,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
                )
            }
        }

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configurePopupWebView(
        webView: WebView,
        configureStorageAndCache: (WebSettings) -> Unit,
        disableWebViewDarkening: (WebSettings) -> Unit
    ) {
        with(webView.settings) {
            javaScriptEnabled = true
            configureStorageAndCache(this)
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = true
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(true)
            disableWebViewDarkening(this)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_AUTHENTICATION)) {
                WebSettingsCompat.setWebAuthenticationSupport(
                    this,
                    WebSettingsCompat.WEB_AUTHENTICATION_SUPPORT_FOR_APP
                )
            }
        }

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
    }
}
