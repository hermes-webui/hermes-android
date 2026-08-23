package com.hermeswebui.android.webui

import org.json.JSONObject

object HermesWebUiScripts {
    /**
     * Hybrid Viewport Polyfill for Android WebView
     *
     * Android System WebView has a bug where CSS viewport units (vh, dvh, svh, lvh) can
     * evaluate to 0px instead of the actual viewport dimensions. This causes elements with
     * viewport-relative sizing (e.g., `max-height: min(60dvh, 420px)`) to collapse.
     *
     * This polyfill uses a hybrid approach:
     * 1. Injects CSS custom properties (--vh, --dvh) with measured pixel values
     * 2. Injects baseline CSS rules for html/body/layout containers
     * 3. Uses generic collapse detection to find and repair ANY element that appears
     *    collapsed due to the vh=0 bug, without needing explicit selectors
     *
     * The generic detection catches new UI elements automatically, eliminating the need
     * to add new selectors each time a viewport-unit-based component is discovered.
     */
    val viewportFixScript = """
        (function() {
          'use strict';

          var STYLE_ID = 'hermes-android-viewport-fix';
          var REPAIRED_ATTR = 'data-hermes-android-vh-repaired';
          var MAX_REPAIRS_PER_SCAN = 50;
          var MIN_SCAN_INTERVAL_MS = 100;

          var lastScanTime = 0;
          var scheduled = false;

          // Tags to skip entirely (not visual content containers)
          var SKIP_TAGS = {
            'script': 1, 'style': 1, 'link': 1, 'meta': 1, 'head': 1, 'html': 1,
            'br': 1, 'hr': 1, 'img': 1, 'svg': 1, 'path': 1, 'circle': 1,
            'rect': 1, 'line': 1, 'polygon': 1, 'polyline': 1, 'g': 1, 'defs': 1,
            'clippath': 1, 'mask': 1, 'use': 1, 'symbol': 1, 'text': 1, 'tspan': 1,
            'input': 1, 'textarea': 1, 'select': 1, 'option': 1, 'canvas': 1,
            'video': 1, 'audio': 1, 'source': 1, 'track': 1, 'iframe': 1, 'embed': 1,
            'object': 1, 'param': 1, 'noscript': 1, 'template': 1
          };

          function getMeasuredViewport() {
            var visualHeight = window.visualViewport && window.visualViewport.height;
            var visualWidth = window.visualViewport && window.visualViewport.width;
            return {
              height: Math.max(
                window.innerHeight || 0,
                document.documentElement.clientHeight || 0,
                visualHeight || 0
              ),
              width: Math.max(
                window.innerWidth || 0,
                document.documentElement.clientWidth || 0,
                visualWidth || 0
              )
            };
          }

          function injectBaselineCSS(viewport) {
            var px = Math.round(viewport.height) + 'px';

            // Inject CSS custom properties on :root for potential future use
            var root = document.documentElement;
            root.style.setProperty('--vh', (viewport.height / 100) + 'px');
            root.style.setProperty('--dvh', (viewport.height / 100) + 'px');
            root.style.setProperty('--vw', (viewport.width / 100) + 'px');
            root.style.setProperty('--viewport-height', viewport.height + 'px');
            root.style.setProperty('--viewport-width', viewport.width + 'px');

            // Baseline CSS rules that generic detection cannot handle
            var style = document.getElementById(STYLE_ID);
            if (!style) {
              style = document.createElement('style');
              style.id = STYLE_ID;
              (document.head || document.documentElement).appendChild(style);
            }

            // Issue #44/#80: the approval and clarify prompt panels are absolutely
            // anchored just above the composer (bottom:-24px inside the zero-height
            // .composer-flyout) and cap their height with viewport units (vh/dvh) that
            // Android WebView can evaluate as 0. Re-cap the expanded panel to the
            // measured space between the app titlebar and the composer so it can never
            // slide behind either one, and let oversized prompt content scroll inside
            // the panel. The card bottom is anchor-invariant (it tracks the composer,
            // not the panel height), so this measurement stays stable across scans and
            // cannot oscillate.
            var promptPanelMax = Math.min(420, Math.round(viewport.height * 0.68));
            try {
              var promptCard = document.querySelector('.approval-card.visible:not(.collapsed), .clarify-card.visible:not(.collapsed)');
              if (promptCard && promptCard.getBoundingClientRect) {
                var titlebar = document.querySelector('.app-titlebar');
                var titlebarBottom = 0;
                if (titlebar && titlebar.getBoundingClientRect) {
                  titlebarBottom = titlebar.getBoundingClientRect().bottom;
                }
                var promptAvailable = promptCard.getBoundingClientRect().bottom - titlebarBottom - 8;
                if (promptAvailable > 0) {
                  promptPanelMax = Math.min(promptPanelMax, Math.floor(promptAvailable));
                }
              }
            } catch (e) {}
            // Keep the WebUI clamp's 180px floor so the panel stays usable and
            // scrollable even when the measured space is tight.
            var promptPanelMaxPx = Math.max(180, promptPanelMax) + 'px';

            style.textContent = [
              // Root sizing baseline
              'html, body { min-height: ' + px + ' !important; }',
              'body { overflow-x: hidden !important; }',
              // Flex container helpers - prevent min-height inheritance issues
              '.layout, .rail, .sidebar, #sessionList, .messages { min-height: 0 !important; }',
              // Settings page clip fix
              (viewport.width > 0 && viewport.width <= 600
                ? '.main.showing-settings .main-view { max-height: none !important; overflow-y: auto !important; }'
                : ''),
              // Prompt panel (approval/clarify) measured geometry cap. !important beats
              // both the WebUI viewport-unit clamp and any stale inline repair styles.
              '.approval-card:not(.collapsed), .clarify-card:not(.collapsed) { max-height: ' + promptPanelMaxPx + ' !important; }',
              '.approval-card:not(.collapsed) .approval-inner, .clarify-card:not(.collapsed) .clarify-inner { box-sizing: border-box !important; max-height: ' + promptPanelMaxPx + ' !important; overflow-y: auto !important; }',
              // The prompt cards float above the composer from inside the
              // zero-height .composer-flyout, so neither the flyout nor the
              // composer-wrap may ever become a scroll/clip container — that
              // re-clips the card to a sliver behind the composer (#80 follow-up).
              // Force overflow visible (re-applied every scan) so a stray inline
              // repair or WebUI change can never re-clip the floating prompt surface.
              '.composer-flyout, .composer-wrap { overflow: visible !important; }'
            ].filter(Boolean).join('\n');
          }

          function shouldSkipElement(el) {
            var tag = (el.tagName || '').toLowerCase();
            return SKIP_TAGS[tag] === 1;
          }

          function shouldSkipRepairForElement(el) {
            if (!el || !el.closest) return false;

            // The approval/clarify prompt panels use the measured titlebar/composer-aware
            // cap injected above, and the .composer-flyout anchor is intentionally
            // zero-height. The generic viewport-derived repair would fight that cap and
            // oscillate, so leave the prompt surface to the measured contract (#44/#80).
            // Other flyout children (e.g. the composer terminal, which still sizes with
            // vh units) remain eligible for generic repair.
            if (el.closest('.approval-card, .clarify-card')) return true;
            if (el.classList && el.classList.contains && el.classList.contains('composer-flyout')) return true;
            // The composer-wrap is the floating prompt cards' clipping ancestor. A
            // generic repair here (overflow-y:auto) turns it into a scroll container
            // that re-clips the card to a sliver behind the composer, and the repair
            // is retained while visible so it never recovers (#80 follow-up). The
            // wrap is flex/content-sized (never vh-sized), so it never legitimately
            // needs the collapse repair.
            if (el.classList && el.classList.contains && el.classList.contains('composer-wrap')) return true;

            // Keep generic collapse repair off the primary conversation surface to
            // avoid chat-window flicker from repeated style churn while messages stream.
            var chatSurface = el.closest('.messages, #messages, [data-testid="messages"]');
            if (!chatSurface) return false;

            // Allow the primary chat/messages container itself to be repaired when
            // it collapses; only skip its descendants to avoid per-message churn.
            if (el === chatSurface) return false;

            // Keep floating overlays eligible for repair even when they are rendered
            // inside chat containers.
            try {
              var style = window.getComputedStyle(el);
              if (style && (style.position === 'fixed' || style.position === 'absolute')) {
                return false;
              }
            } catch (e) {}

            return true;
          }

          function clearRepair(el) {
            if (!el) return;
            el.style.removeProperty('height');
            el.style.removeProperty('min-height');
            el.style.removeProperty('max-height');
            el.style.removeProperty('overflow-y');
            el.removeAttribute(REPAIRED_ATTR);
          }

          function isCollapsedElement(el, viewport) {
            if (!el || !el.getBoundingClientRect) return false;
            if (shouldSkipElement(el)) return false;
            if (shouldSkipRepairForElement(el)) return false;

            var rect = el.getBoundingClientRect();
            var scrollHeight = el.scrollHeight || 0;

            // Quick filters - skip obviously fine elements
            if (rect.width < 100) return false;
            if (rect.height <= 0) {
              // The vh/dvh=0 bug can zero out full-page app shells entirely (e.g.
              // `h-screen` root containers on OAuth provider pages). Only repair
              // zero-height elements that hide substantial page-level content, so
              // small spacers and intentionally collapsed widgets are left alone.
              var minShellContent = Math.max(200, Math.round(viewport.height * 0.3));
              if (scrollHeight < minShellContent) return false;
            } else {
              if (rect.height >= viewport.height * 0.25) return false;
              if (rect.height >= scrollHeight * 0.8) return false;
            }

            // Collapsed threshold
            var collapsedThreshold = Math.max(48, Math.min(180, Math.round(viewport.height * 0.16)));
            var hasOverflowMismatch = scrollHeight > rect.height + 96;

            // Not collapsed if height is reasonable AND no significant overflow
            if (rect.height > collapsedThreshold && !hasOverflowMismatch) return false;

            // Skip elements with trivial content
            if (scrollHeight < 80) return false;

            // Check for meaningful interactive content
            var hasInteractive = false;
            try {
              hasInteractive = !!el.querySelector('button, input, a, [role="button"], [role="menuitem"], textarea, select');
            } catch (e) {}

            // Require either interactive content OR significant hidden content
            if (!hasInteractive && scrollHeight < 200) return false;

            // Final safety: skip invisible elements
            try {
              var style = window.getComputedStyle(el);
              if (style.display === 'none' || style.visibility === 'hidden') return false;
            } catch (e) {}

            return true;
          }

          function updateRepair(el, viewport) {
            var maxPanel = Math.max(180, Math.round(viewport.height * 0.82)) + 'px';
            var minPanel = Math.max(100, Math.round(viewport.height * 0.25)) + 'px';

            el.style.height = 'auto';
            el.style.minHeight = minPanel;
            el.style.maxHeight = maxPanel;
            el.style.overflowY = 'auto';
          }

          function repairElement(el, viewport) {
            if (el.getAttribute(REPAIRED_ATTR)) return false;

            updateRepair(el, viewport);
            el.setAttribute(REPAIRED_ATTR, 'true');

            return true;
          }

          function clearRepairIfHidden(el) {
            if (!el.getAttribute(REPAIRED_ATTR)) return;

            try {
              var style = window.getComputedStyle(el);
              if (style.display === 'none' || style.visibility === 'hidden') {
                clearRepair(el);
              }
            } catch (e) {}
          }

          function scanAndRepair() {
            var now = Date.now();
            if (now - lastScanTime < MIN_SCAN_INTERVAL_MS) return;
            lastScanTime = now;

            var viewport = getMeasuredViewport();
            if (!viewport.height) return;

            // Inject baseline CSS and custom properties
            injectBaselineCSS(viewport);

            // Scan all elements for collapsed state
            var elements = document.querySelectorAll('*');
            var repaired = 0;

            for (var i = 0; i < elements.length; i++) {
              var el = elements[i];

              if (shouldSkipElement(el)) continue;

              if (shouldSkipRepairForElement(el)) {
                // If an older run marked a chat element before exclusion was added,
                // clean that stale repair so normal WebUI layout can take over.
                if (el.getAttribute(REPAIRED_ATTR)) {
                  clearRepair(el);
                }
                continue;
              }

              // Retain a repair while the element remains visible. Its measured height
              // includes this repair, so clearing it based on the current layout would
              // re-enable the broken vh/dvh rule and make the panel oscillate.
              if (el.getAttribute(REPAIRED_ATTR)) {
                clearRepairIfHidden(el);
                if (el.getAttribute(REPAIRED_ATTR)) {
                  updateRepair(el, viewport);
                }
                continue;
              }

              // Check if element needs repair
              if (isCollapsedElement(el, viewport)) {
                if (repairElement(el, viewport)) {
                  repaired++;
                  if (repaired >= MAX_REPAIRS_PER_SCAN) break;
                }
              }
            }
          }

          function schedulePolyfill() {
            if (scheduled) return;
            scheduled = true;
            window.requestAnimationFrame(function() {
              scheduled = false;
              scanAndRepair();
            });
          }

          // Expose for debugging
          window.__hermesAndroidApplyViewportFix = scanAndRepair;

          // Initial run
          schedulePolyfill();

          if (!window.__hermesAndroidViewportFixInstalled) {
            window.__hermesAndroidViewportFixInstalled = true;

            window.addEventListener('resize', schedulePolyfill, { passive: true });
            window.addEventListener('orientationchange', function() {
              setTimeout(schedulePolyfill, 0);
              setTimeout(schedulePolyfill, 250);
            }, { passive: true });

            if (window.visualViewport) {
              window.visualViewport.addEventListener('resize', schedulePolyfill, { passive: true });
            }

            // MutationObserver for DOM changes
            try {
              var observer = new MutationObserver(function(mutations) {
                // Skip mutations that are just our own repairs
                var dominated = mutations.every(function(m) {
                  return m.attributeName === REPAIRED_ATTR ||
                    (m.attributeName === 'style' && m.target.getAttribute && m.target.getAttribute(REPAIRED_ATTR));
                });
                if (!dominated) schedulePolyfill();
              });
              observer.observe(document.documentElement || document.body, {
                childList: true,
                subtree: true,
                attributes: true,
                attributeFilter: ['style', 'class', REPAIRED_ATTR]
              });
            } catch (e) {}
          }
        })();
    """.trimIndent()

    val microphoneFallbackScript = """
        (function() {
          try {
            window.localStorage.setItem('mic_force_mediarecorder', '1');
          } catch (_) {}

          // Some Android WebView builds expose SpeechRecognition but fail with not-allowed.
          // Hide these constructors so Hermes WebUI consistently uses MediaRecorder/getUserMedia.
          var disableSpeechConstructor = function(name) {
            try {
              Object.defineProperty(window, name, {
                configurable: true,
                get: function() { return undefined; },
                set: function(_) {}
              });
            } catch (_) {
              try { window[name] = undefined; } catch (_) {}
            }
          };

          disableSpeechConstructor('SpeechRecognition');
          disableSpeechConstructor('webkitSpeechRecognition');
          try { window.__hermesAndroidMicForceMediaRecorder = true; } catch (_) {}

          // Android WebView can fail to open microphone streams when a specific input
          // deviceId/groupId constraint is requested. Fall back to default mic capture.
          try {
            if (navigator.mediaDevices && typeof navigator.mediaDevices.getUserMedia === 'function' &&
                !navigator.mediaDevices.__hermesAndroidWrappedGetUserMedia) {
              var originalGetUserMedia = navigator.mediaDevices.getUserMedia.bind(navigator.mediaDevices);
              var sanitizeAudioConstraints = function(audio) {
                if (audio === true || audio === false || audio == null) return audio;
                if (typeof audio !== 'object') return true;

                var clone = {};
                for (var key in audio) {
                  if (!Object.prototype.hasOwnProperty.call(audio, key)) continue;
                  if (key === 'deviceId' || key === 'groupId') continue;
                  clone[key] = audio[key];
                }
                return Object.keys(clone).length ? clone : true;
              };

              navigator.mediaDevices.getUserMedia = function(constraints) {
                var next = constraints;
                try {
                  if (constraints && typeof constraints === 'object' && constraints.audio) {
                    next = {};
                    for (var key in constraints) {
                      if (Object.prototype.hasOwnProperty.call(constraints, key)) {
                        next[key] = constraints[key];
                      }
                    }
                    next.audio = sanitizeAudioConstraints(constraints.audio);
                  }
                } catch (_) {}
                return originalGetUserMedia(next);
              };
              navigator.mediaDevices.__hermesAndroidWrappedGetUserMedia = true;
              try { window.__hermesAndroidSanitizeAudioConstraints = true; } catch (_) {}
            }
          } catch (_) {}
         })();
    """.trimIndent()

    val enterKeyNewlineScript = """
        (function() {
          if (window.__hermesAndroidEnterNewlineInstalled) return;
          window.__hermesAndroidEnterNewlineInstalled = true;

          // Issue #34: Hermes WebUI submits the composer on a plain Enter press, so
          // multi-line messages can't be composed on Android. This document-start
          // listener registers before the web app's own handlers, so a capture-phase
          // stopImmediatePropagation suppresses its submit. preventDefault then blocks
          // the default action and we insert the newline ourselves. Messages are still
          // sent with the on-screen send button (and Shift+Enter still inserts a newline
          // via the browser default, since it is left untouched here).
          // Issue #83: this newline-forcing only applies while no hardware keyboard is
          // attached. When one is attached, the handler below returns early so WebUI's
          // native Enter-to-submit / Shift+Enter-newline handling runs (desktop
          // convention). The native app keeps window.__hermesAndroidHardwareKeyboard in
          // sync as keyboards attach and detach.

          var isComposer = function(el) {
            if (!el) return false;
            return (el.tagName || '').toUpperCase() === 'TEXTAREA' || el.isContentEditable;
          };

          document.addEventListener('keydown', function(e) {
            if (e.key !== 'Enter' && e.keyCode !== 13) return;
            if (e.shiftKey || e.isComposing) return;
            // Hardware keyboard attached: defer to WebUI's native handling so a plain
            // Enter submits and Shift+Enter inserts a newline (desktop convention).
            if (window.__hermesAndroidHardwareKeyboard === true) return;
            var el = e.target;
            if (!isComposer(el)) return;

            e.preventDefault();
            e.stopImmediatePropagation();

            if ((el.tagName || '').toUpperCase() === 'TEXTAREA') {
              var start = el.selectionStart;
              var end = el.selectionEnd;
              el.value = el.value.slice(0, start) + '\n' + el.value.slice(end);
              el.selectionStart = el.selectionEnd = start + 1;
              el.dispatchEvent(new Event('input', { bubbles: true }));
            } else {
              try { document.execCommand('insertLineBreak'); }
              catch (_) { try { document.execCommand('insertText', false, '\n'); } catch (_) {} }
            }
          }, true);
        })();
    """.trimIndent()

    val appSettingsEntryScript = """
        (function() {
          var appSettingsHref = 'hermes://app/settings';
          var markerAttr = 'data-hermes-android-app-settings-entry';
          var markerValue = '1';
          var labelText = 'Application Settings';
          var scheduled = false;

          var normalizedText = function(value) {
            return String(value || '').trim().toLowerCase();
          };

          var textMatchesRegularSettings = function(value) {
            var normalized = normalizedText(value);
            if (!normalized) return false;
            if (normalized.indexOf('application settings') !== -1 || normalized.indexOf('app settings') !== -1) {
              return false;
            }
            return normalized === 'settings' ||
              normalized === 'open settings' ||
              normalized.indexOf('settings ') === 0 ||
              normalized.indexOf(' settings') !== -1;
          };

          var bindNativeSettingsClick = function(el) {
            if (!el) return;
            if (el.__hermesAndroidAppSettingsBound) return;
            el.__hermesAndroidAppSettingsBound = true;
            var openNativeSettings = function(event) {
              if (event) {
                event.preventDefault();
                event.stopPropagation();
                if (typeof event.stopImmediatePropagation === 'function') {
                  event.stopImmediatePropagation();
                }
              }
              window.location.href = appSettingsHref;
              return false;
            };
            el.addEventListener('click', openNativeSettings, false);
            el.addEventListener('auxclick', openNativeSettings, false);
            el.addEventListener('keydown', function(event) {
              if (event.key === 'Enter' || event.key === ' ') {
                openNativeSettings(event);
              }
            }, false);
          };

          var getInteractiveNodes = function(scope) {
            if (!scope || !scope.querySelectorAll) return [];
            return scope.querySelectorAll('a, button, [role="button"], [role="menuitem"]');
          };

          var isNodeVisible = function(node) {
            if (!node || !node.getBoundingClientRect || !window.getComputedStyle) return false;
            var current = node;
            while (current && current !== document.documentElement) {
              var style = window.getComputedStyle(current);
              if (!style || style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') {
                return false;
              }
              current = current.parentElement;
            }
            var rect = node.getBoundingClientRect();
            if (rect.width <= 0 || rect.height <= 0) return false;
            var viewportWidth = window.visualViewport && window.visualViewport.width || window.innerWidth || 0;
            var viewportHeight = window.visualViewport && window.visualViewport.height || window.innerHeight || 0;
            if (viewportWidth > 0 && rect.right <= 0) return false;
            if (viewportWidth > 0 && rect.left >= viewportWidth) return false;
            if (viewportHeight > 0 && rect.bottom <= 0) return false;
            if (viewportHeight > 0 && rect.top >= viewportHeight) return false;
            return true;
          };

          var compactSettingsCandidateScore = function(node) {
            if (!node || !node.getAttribute) return -1;
            var score = 0;
            var cls = normalizedText(node.getAttribute('class'));
            var tooltip = normalizedText(node.getAttribute('data-tooltip'));
            var title = normalizedText(node.getAttribute('title'));
            var aria = normalizedText(node.getAttribute('aria-label'));
            if (cls.indexOf('has-tooltip--bottom') !== -1) score += 4;
            if (cls.indexOf('nav-tab') !== -1) score += 3;
            if (tooltip === 'settings') score += 3;
            if (title === 'settings') score += 2;
            if (aria === 'settings') score += 2;
            return score;
          };

          var isCompactLayout = function() {
            var width = window.visualViewport && window.visualViewport.width || window.innerWidth || 0;
            return width > 0 && width < 799;
          };

          var matchesSettingsNode = function(node) {
            if (!node || !node.getAttribute) return false;
            if (node.getAttribute(markerAttr)) return false;
            var href = normalizedText(node.getAttribute('href'));
            if (normalizedText(node.getAttribute('data-panel')) === 'settings') return true;
            if (normalizedText(node.getAttribute('data-settings-section')) === 'settings') return true;
            if (href && (href.indexOf('settings') !== -1 || href.indexOf('/config') !== -1)) return true;
            if (textMatchesRegularSettings(node.getAttribute('aria-label'))) return true;
            if (textMatchesRegularSettings(node.getAttribute('title'))) return true;
            if (textMatchesRegularSettings(node.getAttribute('data-tooltip'))) return true;
            if (textMatchesRegularSettings(node.textContent)) return true;
            return false;
          };

          var findCompactSettingsAnchor = function() {
            if (!isCompactLayout()) return null;
            // Hermes WebUI uses a separate compact nav under narrow widths. Prefer
            // the visible compact Settings control instead of the hidden desktop rail.
            var selector = [
              'button.nav-tab.has-tooltip--bottom[data-tooltip="Settings"]',
              'button.nav-tab.has-tooltip--bottom[title="Settings"]',
              'button.nav-tab.has-tooltip--bottom[aria-label="Settings"]',
              '.mobile-nav button[data-tooltip="Settings"]',
              '.mobile-nav button[title="Settings"]',
              '.mobile-nav button[aria-label="Settings"]',
              '.bottom-nav button[data-tooltip="Settings"]',
              '.bottom-nav button[title="Settings"]',
              '.bottom-nav button[aria-label="Settings"]'
            ].join(', ');
            var direct = document.querySelector(selector);
            if (isNodeVisible(direct)) return direct;

            var candidates = Array.prototype.slice.call(
              document.querySelectorAll('button.nav-tab, a.nav-tab, [role="button"].nav-tab, [role="menuitem"].nav-tab')
            );
            var best = null;
            var bestScore = -1;
            candidates.forEach(function(node) {
              if (!matchesSettingsNode(node)) return;
              var score = compactSettingsCandidateScore(node);
              if (score > bestScore) {
                best = node;
                bestScore = score;
              }
            });
            return best;
          };

          var findAnchorByKind = function(kind) {
            var matchesNode = function(node) {
              if (kind === 'settings') return matchesSettingsNode(node);
              if (!node || !node.getAttribute) return false;
              if (node.getAttribute(markerAttr)) return false;
              var href = normalizedText(node.getAttribute('href'));
              var text = normalizedText(node.textContent);
              return normalizedText(node.getAttribute('data-panel')) === 'help' ||
                normalizedText(node.getAttribute('data-settings-section')) === 'help' ||
                (href && href.indexOf('help') !== -1) ||
                normalizedText(node.getAttribute('aria-label')) === 'help' ||
                normalizedText(node.getAttribute('title')) === 'help' ||
                normalizedText(node.getAttribute('data-tooltip')) === 'help' ||
                text === 'help';
            };

            var scopeSelectors = ['.rail', '.sidebar', '.sidebar-nav', '.leftpanel', 'aside', 'nav'];
            var bestVisible = null;
            var bestVisibleScore = -1;
            var firstHidden = null;
            for (var i = 0; i < scopeSelectors.length; i++) {
              var scope = document.querySelector(scopeSelectors[i]);
              var nodes = getInteractiveNodes(scope);
              for (var j = 0; j < nodes.length; j++) {
                var node = nodes[j];
                if (!matchesNode(node)) continue;
                if (isNodeVisible(node)) {
                  var score = kind === 'settings' ? compactSettingsCandidateScore(node) : 0;
                  if (!bestVisible || score > bestVisibleScore) {
                    bestVisible = node;
                    bestVisibleScore = score;
                  }
                } else if (!firstHidden) {
                  firstHidden = node;
                }
              }
            }
            var globalNodes = getInteractiveNodes(document);
            for (var k = 0; k < globalNodes.length; k++) {
              var globalNode = globalNodes[k];
              if (!matchesNode(globalNode)) continue;
              if (isNodeVisible(globalNode)) {
                var globalScore = kind === 'settings' ? compactSettingsCandidateScore(globalNode) : 0;
                if (!bestVisible || globalScore > bestVisibleScore) {
                  bestVisible = globalNode;
                  bestVisibleScore = globalScore;
                }
              } else if (!firstHidden) {
                firstHidden = globalNode;
              }
            }
            return bestVisible || firstHidden;
          };

          var findSettingsAnchor = function() {
            return findCompactSettingsAnchor() || findAnchorByKind('settings') || findAnchorByKind('help');
          };

          var findInsertedEntry = function(anchorContainer) {
            if (!anchorContainer || !anchorContainer.parentNode) return null;
            var sibling = anchorContainer.nextElementSibling;
            if (sibling && sibling.getAttribute && sibling.getAttribute(markerAttr) === markerValue) {
              return sibling;
            }
            return anchorContainer.parentNode.querySelector('[' + markerAttr + '="' + markerValue + '"]');
          };

          var clearActiveState = function(root) {
            if (!root || !root.querySelectorAll) return;
            root.removeAttribute('aria-current');
            root.removeAttribute('aria-selected');
            root.classList.remove('active');
            root.classList.remove('selected');
            root.classList.remove('is-active');
            root.querySelectorAll('[aria-current], [aria-selected], .active, .selected, .is-active').forEach(function(el) {
              el.removeAttribute('aria-current');
              el.removeAttribute('aria-selected');
              el.classList.remove('active');
              el.classList.remove('selected');
              el.classList.remove('is-active');
            });
          };

          var createAppIconSvg = function(className) {
            var svgNs = 'http://www.w3.org/2000/svg';
            var svg = document.createElementNS(svgNs, 'svg');
            svg.setAttribute('viewBox', '0 0 24 24');
            svg.setAttribute('fill', 'none');
            svg.setAttribute('stroke', 'currentColor');
            svg.setAttribute('stroke-width', '1.8');
            svg.setAttribute('stroke-linecap', 'round');
            svg.setAttribute('stroke-linejoin', 'round');
            svg.setAttribute('aria-hidden', 'true');
            if (className) svg.setAttribute('class', className);

            var frame = document.createElementNS(svgNs, 'rect');
            frame.setAttribute('x', '7');
            frame.setAttribute('y', '2');
            frame.setAttribute('width', '10');
            frame.setAttribute('height', '20');
            frame.setAttribute('rx', '2.5');
            frame.setAttribute('ry', '2.5');

            var speaker = document.createElementNS(svgNs, 'line');
            speaker.setAttribute('x1', '10');
            speaker.setAttribute('y1', '5');
            speaker.setAttribute('x2', '14');
            speaker.setAttribute('y2', '5');

            var home = document.createElementNS(svgNs, 'circle');
            home.setAttribute('cx', '12');
            home.setAttribute('cy', '18');
            home.setAttribute('r', '1');

            svg.appendChild(frame);
            svg.appendChild(speaker);
            svg.appendChild(home);
            return svg;
          };

          var applyApplicationIcon = function(interactive) {
            if (!interactive || !interactive.querySelector) return;
            var existing = interactive.querySelector('svg, i, [data-icon], [class*="icon"]');
            var className = existing && existing.getAttribute ? (existing.getAttribute('class') || '') : '';
            var appIcon = createAppIconSvg(className);
            appIcon.setAttribute(markerAttr, 'icon');
            if (existing && existing.parentNode) {
              existing.parentNode.replaceChild(appIcon, existing);
            } else {
              interactive.insertBefore(appIcon, interactive.firstChild);
            }
          };

          var setEntryLabel = function(interactive) {
            if (!interactive) return;
            var replaced = false;
            var walker = document.createTreeWalker(interactive, NodeFilter.SHOW_TEXT, null);
            var node = walker.nextNode();
            while (node) {
              if (textMatchesRegularSettings(node.nodeValue)) {
                node.nodeValue = labelText;
                replaced = true;
                break;
              }
              node = walker.nextNode();
            }
            if (replaced) return;

            var labelNode = interactive.querySelector('span, p, strong, em, div');
            if (labelNode) {
              labelNode.textContent = labelText;
              return;
            }

            var fallback = document.createElement('span');
            fallback.textContent = labelText;
            fallback.setAttribute(markerAttr, 'label');
            interactive.appendChild(fallback);
          };

          var createEntryNode = function(anchorContainer, anchorInteractive) {
            var clone = anchorContainer.cloneNode(true);
            clone.setAttribute(markerAttr, markerValue);
            clone.removeAttribute('id');
            clearActiveState(clone);

            var interactiveSelector = 'a, button, [role="button"], [role="menuitem"]';
            var interactive = clone.matches(interactiveSelector) ? clone : clone.querySelector(interactiveSelector);
            if (!interactive) {
              interactive = document.createElement('a');
              interactive.textContent = labelText;
              clone.appendChild(interactive);
            }

            interactive.removeAttribute('id');
            interactive.setAttribute(markerAttr, markerValue);
            interactive.setAttribute('href', appSettingsHref);
            interactive.setAttribute('role', 'link');
            interactive.setAttribute('aria-label', labelText);
            interactive.setAttribute('title', labelText);
            interactive.setAttribute('data-tooltip', labelText);
            interactive.removeAttribute('data-panel');
            interactive.removeAttribute('data-settings-section');
            interactive.removeAttribute('data-i18n-title');

            setEntryLabel(interactive);
            applyApplicationIcon(interactive);
            bindNativeSettingsClick(interactive);
            return clone;
          };

          var ensureEntry = function() {
            try {
              var anchorInteractive = findSettingsAnchor();
              if (!anchorInteractive) return;

              var anchorContainer = anchorInteractive.closest('li, [role="menuitem"], .menu-item, .nav-item, .sidebar-item, [data-menu-item], .nav-tab') || anchorInteractive;
              if (!anchorContainer || !anchorContainer.parentNode) return;

              var existing = findInsertedEntry(anchorContainer);
              if (existing) {
                var existingInteractive = existing.matches('a, button, [role="button"], [role="menuitem"]') ? existing : existing.querySelector('a, button, [role="button"], [role="menuitem"]');
                bindNativeSettingsClick(existingInteractive || existing);
                if (existing !== anchorContainer.nextElementSibling) {
                  anchorContainer.parentNode.insertBefore(existing, anchorContainer.nextSibling);
                }
                return;
              }

              var entry = createEntryNode(anchorContainer, anchorInteractive);
              anchorContainer.parentNode.insertBefore(entry, anchorContainer.nextSibling);
            } catch (_) {}
          };

          var scheduleEnsure = function() {
            if (scheduled) return;
            scheduled = true;
            window.requestAnimationFrame(function() {
              scheduled = false;
              ensureEntry();
            });
          };

          scheduleEnsure();

          if (!window.__hermesAndroidAppSettingsEntryInstalled) {
            window.__hermesAndroidAppSettingsEntryInstalled = true;
            var observer = new MutationObserver(function() { scheduleEnsure(); });
            observer.observe(document.body || document.documentElement, { childList: true, subtree: true });
            window.addEventListener('pageshow', scheduleEnsure, { passive: true });
            window.addEventListener('focus', scheduleEnsure, { passive: true });
            window.addEventListener('resize', scheduleEnsure, { passive: true });
            window.addEventListener('orientationchange', function() {
              window.setTimeout(scheduleEnsure, 0);
              window.setTimeout(scheduleEnsure, 200);
            }, { passive: true });
            document.addEventListener('visibilitychange', function() {
              if (!document.hidden) scheduleEnsure();
            }, { passive: true });
          }
        })();
    """.trimIndent()

    val suppressKeyboardForDialogsScript = """
        (function() {
          if (window.__hermesAndroidSuppressKeyboardForDialogsInstalled) return;
          window.__hermesAndroidSuppressKeyboardForDialogsInstalled = true;

          // Issue #65: When the agent calls the Clarify tool (multiple-choice prompt),
          // the Android app automatically opens the on-screen keyboard, overlapping
          // the option buttons. This script suppresses the keyboard for dialog/modal
          // elements by preventing focus on input elements within dialogs.

          var isDialogLike = function(el) {
            if (!el) return false;
            var role = (el.getAttribute('role') || '').toLowerCase();
            var tag = (el.tagName || '').toLowerCase();
            
            // Check for modal/dialog ARIA roles
            if (role === 'dialog' || role === 'alertdialog') return true;
            
            // Check for modal/dialog HTML elements
            if (tag === 'dialog') return true;
            
            // Check for common modal class names
            var cls = (el.getAttribute('class') || '').toLowerCase();
            if (cls.indexOf('modal') !== -1 || cls.indexOf('dialog') !== -1 || 
                cls.indexOf('prompt') !== -1 || cls.indexOf('alert') !== -1) return true;
            
            return false;
          };

          var getDialogContainer = function(el) {
            if (!el) return null;
            var current = el;
            while (current && current !== document.body && current !== document.documentElement) {
              if (isDialogLike(current)) return current;
              current = current.parentElement;
            }
            return null;
          };

          var suppressKeyboardForElement = function(el) {
            if (!el) return;
            try {
              // Blur the element to hide the keyboard if it gained focus
              el.blur();
            } catch (_) {}
          };

          // Listen for focus events on input elements
          document.addEventListener('focus', function(event) {
            var target = event.target;
            if (!target) return;

            var tag = (target.tagName || '').toLowerCase();
            var isInput = tag === 'input' || tag === 'textarea' || target.isContentEditable;
            if (!isInput) return;

            var dialogContainer = getDialogContainer(target);
            if (!dialogContainer) return;

            // If an input element in a dialog gains focus, suppress the keyboard
            suppressKeyboardForElement(target);
          }, true);

          // Also watch for new dialogs being added to the DOM and suppress focus on their inputs
          if (window.MutationObserver) {
            var suppressInputsInDialog = function(dialog) {
              if (!dialog || !dialog.querySelectorAll) return;
              try {
                var inputs = dialog.querySelectorAll('input, textarea, [contenteditable="true"]');
                inputs.forEach(function(input) {
                  if (input && input.blur) {
                    input.blur();
                  }
                  // Prevent auto-focus by marking as temporarily unfocusable
                  if (input && input.setAttribute) {
                    var wasTabIndex = input.getAttribute('tabindex');
                    input.setAttribute('tabindex', '-1');
                    // Restore tabindex after a small delay to allow dialog to initialize
                    setTimeout(function() {
                      if (wasTabIndex !== null && wasTabIndex !== undefined) {
                        input.setAttribute('tabindex', wasTabIndex);
                      } else {
                        input.removeAttribute('tabindex');
                      }
                    }, 100);
                  }
                });
              } catch (_) {}
            };

            var observer = new MutationObserver(function(mutations) {
              mutations.forEach(function(mutation) {
                if (mutation.addedNodes && mutation.addedNodes.length) {
                  mutation.addedNodes.forEach(function(node) {
                    if (!node.querySelectorAll) return;

                    // Check if the added node itself is a dialog
                    if (isDialogLike(node)) {
                      suppressInputsInDialog(node);
                    }

                    // Also check for dialogs nested within the added node
                    try {
                      var nestedDialogs = node.querySelectorAll('[role="dialog"], [role="alertdialog"], dialog, .modal, .dialog, .prompt, .alert');
                      nestedDialogs.forEach(function(dialog) {
                        if (isDialogLike(dialog)) {
                          suppressInputsInDialog(dialog);
                        }
                      });
                    } catch (_) {}
                  });
                }
              });
            });

            observer.observe(document.body || document.documentElement, {
              childList: true,
              subtree: true
            });
          }
        })();
    """.trimIndent()

    fun buildRouteRecoveryScript(recoveryUrl: String): String {
        val quotedLastUrl = JSONObject.quote(recoveryUrl)
        return """
            (function() {
              try {
                if (window.__hermesAndroidRouteRecoveryInstalled) return;
                window.__hermesAndroidRouteRecoveryInstalled = true;
                var recoveryUrl = $quotedLastUrl;
                var panelIsHidden = function() {
                  try {
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel) return true;
                    var style = window.getComputedStyle(rightPanel);
                    return style.display === 'none' || rightPanel.getBoundingClientRect().width === 0;
                  } catch (_) { return true; }
                };
                var fallbackOpenAttr = 'data-hermes-android-fallback-open';
                var fallbackWidthAttr = 'data-hermes-android-fallback-width';
                var forcePanelOpen = function() {
                  try {
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel) return;
                    var existingInlineWidth = (rightPanel.style && rightPanel.style.width) || '';
                    if (!rightPanel.getAttribute(fallbackWidthAttr)) {
                      rightPanel.setAttribute(fallbackWidthAttr, existingInlineWidth);
                    }
                    rightPanel.style.setProperty('display', 'block', 'important');
                    rightPanel.style.setProperty('visibility', 'visible', 'important');
                    rightPanel.style.setProperty('opacity', '1', 'important');
                    if (!rightPanel.style.width || rightPanel.getBoundingClientRect().width === 0) {
                      var width = Math.max(320, Math.min(520, Math.round(window.innerWidth * 0.42)));
                      rightPanel.style.setProperty('width', String(width) + 'px', 'important');
                      rightPanel.style.setProperty('max-width', String(width) + 'px', 'important');
                    }
                    document.body.classList.add('workspace-open', 'rightpanel-open');
                    rightPanel.setAttribute(fallbackOpenAttr, '1');
                  } catch (_) {}
                };
                var releaseFallbackPanelStyles = function() {
                  try {
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel) return;
                    if (rightPanel.getAttribute(fallbackOpenAttr) !== '1') return;
                    var previousWidth = rightPanel.getAttribute(fallbackWidthAttr);
                    rightPanel.style.removeProperty('display');
                    rightPanel.style.removeProperty('visibility');
                    rightPanel.style.removeProperty('opacity');
                    rightPanel.style.removeProperty('max-width');
                    if (previousWidth != null) {
                      rightPanel.style.width = previousWidth;
                    } else {
                      rightPanel.style.removeProperty('width');
                    }
                    rightPanel.removeAttribute(fallbackOpenAttr);
                    rightPanel.removeAttribute(fallbackWidthAttr);
                  } catch (_) {}
                };
                var scheduleFallbackRelease = function() {
                  window.setTimeout(function() {
                    releaseFallbackPanelStyles();
                  }, 0);
                };
                window.addEventListener('click', function(event) {
                  try {
                    var target = event.target;
                    var button = target && target.closest ? target.closest('#btnWorkspacePanelToggle') : null;
                    if (!button) return;
                    // If fallback opened the panel previously, release temporary styles first so
                    // WebUI's own toggle handler can close it naturally.
                    releaseFallbackPanelStyles();
                    window.setTimeout(function() {
                      try {
                        if (!panelIsHidden()) return;
                        forcePanelOpen();
                        window.setTimeout(function() {
                          if (!panelIsHidden()) return;
                          if (recoveryUrl && window.location && window.location.href !== recoveryUrl) {
                            window.location.href = recoveryUrl;
                          }
                        }, 90);
                        if (panelIsHidden() && recoveryUrl && window.location && window.location.href !== recoveryUrl) {
                          window.location.href = recoveryUrl;
                        }
                      } catch (_) {}
                    }, 75);
                  } catch (_) {}
                }, true);
                window.addEventListener('click', function(event) {
                  try {
                    var target = event.target;
                    if (!target || !target.closest) return;
                    var rightPanel = document.querySelector('.rightpanel');
                    if (!rightPanel || rightPanel.getAttribute(fallbackOpenAttr) !== '1') return;
                    var panelAction = target.closest('.rightpanel button, .rightpanel [role="button"], .rightpanel a');
                    if (!panelAction) return;
                    // After WebUI handles the click (including close), drop fallback styles
                    // so panel visibility is controlled solely by WebUI state.
                    scheduleFallbackRelease();
                  } catch (_) {}
                }, true);
              } catch (_) {}
            })();
        """.trimIndent()
    }

    fun buildNotificationBridgeScript(
        bridgeName: String,
        initialPermission: String
    ): String {
        val quotedBridgeName = JSONObject.quote(bridgeName)
        val quotedPermission = JSONObject.quote(initialPermission)
        return """
            (function() {
              var bridgeName = $quotedBridgeName;
              var initialPermission = $quotedPermission;
              var nativeBridge = window[bridgeName];

              var normalizePermission = function(value) {
                value = String(value || '').toLowerCase();
                return (value === 'granted' || value === 'denied' || value === 'default') ? value : 'default';
              };

              if (window.__hermesAndroidNotificationsInstalled) {
                if (typeof window.__hermesAndroidSetNotificationPermission === 'function') {
                  window.__hermesAndroidSetNotificationPermission(initialPermission);
                }
                return;
              }

              if (!nativeBridge || typeof nativeBridge.postMessage !== 'function') return;

              var permission = normalizePermission(initialPermission);
              var pending = {};
              var nextId = 1;

              var makeDomException = function(message, name) {
                try { return new DOMException(message, name); } catch (_) {
                  var error = new Error(message);
                  error.name = name;
                  return error;
                }
              };

              var safeEvent = function(type) {
                try { return new Event(type); } catch (_) { return { type: type }; }
              };

              var cloneOptions = function(options) {
                var clone = {};
                if (!options || typeof options !== 'object') return clone;
                ['body', 'tag', 'icon', 'badge'].forEach(function(key) {
                  if (options[key] != null) clone[key] = String(options[key]);
                });
                if (options.data && typeof options.data === 'object') {
                  clone.data = {};
                  if (options.data.url != null) clone.data.url = String(options.data.url);
                }
                return clone;
              };

              var postNative = function(type, payload) {
                return new Promise(function(resolve) {
                  var id = String(Date.now()) + '-' + String(nextId++);
                  pending[id] = resolve;
                  try {
                    nativeBridge.postMessage(JSON.stringify({ id: id, type: type, payload: payload || {} }));
                  } catch (_) {
                    delete pending[id];
                    resolve({ ok: false, permission: permission, error: 'post-failed' });
                    return;
                  }
                  window.setTimeout(function() {
                    if (!pending[id]) return;
                    delete pending[id];
                    resolve({ ok: false, permission: permission, error: 'timeout' });
                  }, 15000);
                });
              };

              nativeBridge.onmessage = function(event) {
                var response = null;
                try { response = JSON.parse(event && event.data ? String(event.data) : '{}'); } catch (_) {}
                if (!response) return;
                if (response.permission) permission = normalizePermission(response.permission);
                var id = String(response.id || '');
                var resolve = pending[id];
                if (resolve) {
                  delete pending[id];
                  resolve(response);
                }
              };

              window.__hermesAndroidSetNotificationPermission = function(nextPermission) {
                permission = normalizePermission(nextPermission);
              };

              var showNativeNotification = function(title, options) {
                if (permission !== 'granted') {
                  return Promise.reject(makeDomException('Notification permission denied', 'NotAllowedError'));
                }
                return postNative('show', {
                  title: String(title || ''),
                  options: cloneOptions(options)
                }).then(function(response) {
                  if (response && response.permission) permission = normalizePermission(response.permission);
                  if (response && response.ok) return undefined;
                  throw makeDomException('Notification delivery failed', 'AbortError');
                });
              };

              function HermesAndroidNotification(title, options) {
                if (!(this instanceof HermesAndroidNotification)) {
                  return new HermesAndroidNotification(title, options);
                }
                if (permission !== 'granted') {
                  throw makeDomException('Notification permission denied', 'NotAllowedError');
                }
                this.title = String(title || '');
                this.body = options && options.body != null ? String(options.body) : '';
                this.tag = options && options.tag != null ? String(options.tag) : '';
                this.data = options && options.data != null ? options.data : null;
                this.onclick = null;
                this.onshow = null;
                this.onerror = null;
                this.onclose = null;

                var notification = this;
                showNativeNotification(this.title, options)
                  .then(function() {
                    if (typeof notification.onshow === 'function') notification.onshow(safeEvent('show'));
                  })
                  .catch(function(error) {
                    if (typeof notification.onerror === 'function') notification.onerror(error);
                  });
              }

              HermesAndroidNotification.prototype.close = function() {
                if (typeof this.onclose === 'function') this.onclose(safeEvent('close'));
              };

              Object.defineProperty(HermesAndroidNotification, 'permission', {
                configurable: true,
                enumerable: true,
                get: function() { return permission; }
              });
              Object.defineProperty(HermesAndroidNotification, 'maxActions', {
                configurable: true,
                enumerable: true,
                get: function() { return 0; }
              });
              HermesAndroidNotification.requestPermission = function(callback) {
                return postNative('requestPermission', {}).then(function(response) {
                  if (response && response.permission) permission = normalizePermission(response.permission);
                  if (typeof callback === 'function') {
                    window.setTimeout(function() { callback(permission); }, 0);
                  }
                  return permission;
                });
              };

              try {
                Object.defineProperty(window, 'Notification', {
                  configurable: true,
                  writable: true,
                  value: HermesAndroidNotification
                });
              } catch (_) {
                try { window.Notification = HermesAndroidNotification; } catch (_) {}
              }

              var patchServiceWorkerNotifications = function() {
                try {
                  if (!window.ServiceWorkerRegistration || !window.ServiceWorkerRegistration.prototype) return;
                  var proto = window.ServiceWorkerRegistration.prototype;
                  if (proto.__hermesAndroidNotificationsPatched) return;
                  Object.defineProperty(proto, 'showNotification', {
                    configurable: true,
                    writable: true,
                    value: function(title, options) {
                      return showNativeNotification(title, options);
                    }
                  });
                  if (typeof proto.getNotifications !== 'function') {
                    Object.defineProperty(proto, 'getNotifications', {
                      configurable: true,
                      writable: true,
                      value: function() { return Promise.resolve([]); }
                    });
                  }
                  proto.__hermesAndroidNotificationsPatched = true;
                } catch (_) {}
              };

              window.__hermesAndroidNotificationsInstalled = true;
              patchServiceWorkerNotifications();
              window.setTimeout(patchServiceWorkerNotifications, 0);
              window.setTimeout(patchServiceWorkerNotifications, 1000);
              postNative('permissionState', {}).then(function(response) {
                if (response && response.permission) permission = normalizePermission(response.permission);
              });
            })();
        """.trimIndent()
    }
}
