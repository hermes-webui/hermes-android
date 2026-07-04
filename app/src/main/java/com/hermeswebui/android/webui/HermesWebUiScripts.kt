package com.hermeswebui.android.webui

import org.json.JSONObject

object HermesWebUiScripts {
    val viewportFixScript = """
        (function() {
          var styleId = 'hermes-android-viewport-fix';
          var repairedAttr = 'data-hermes-android-panel-repaired';
          var repairedCollapsedValue = 'collapsed';
          var repairedThemeValue = 'theme-creator';
          var scheduledApply = false;
          var normalizedText = function(value) {
            return String(value || '').trim().toLowerCase();
          };
          var repairThemeCreatorPanel = function(height) {
            var minPanel = Math.max(260, Math.round(height * 0.45)) + 'px';
            var maxPanel = Math.max(220, Math.round(height * 0.86)) + 'px';
            var textNodes = document.querySelectorAll('h1, h2, h3, h4, span, div, button, label');

            // Drop stale Theme Creator repairs before recomputing geometry.
            document.querySelectorAll('[' + repairedAttr + '="' + repairedThemeValue + '"]').forEach(function(el) {
              el.style.removeProperty('height');
              el.style.removeProperty('min-height');
              el.style.removeProperty('max-height');
              el.style.removeProperty('overflow-y');
              el.style.removeProperty('display');
              el.style.removeProperty('justify-content');
              el.style.removeProperty('align-content');
              el.removeAttribute(repairedAttr);
            });

            textNodes.forEach(function(node) {
              if (!node || !node.textContent) return;
              var text = normalizedText(node.textContent);
              if (text !== 'theme creator') return;

              var current = node;
              for (var depth = 0; current && depth < 10; depth++) {
                current = current.parentElement;
                if (!current || !current.getBoundingClientRect) continue;
                var rect = current.getBoundingClientRect();
                if (rect.width < 220) continue;

                // Target the Theme Creator content container (the one that owns most
                // form controls) instead of outer wrappers to avoid inflating header space.
                var inputCount = 0;
                try {
                  inputCount = current.querySelectorAll('input, textarea, select, [contenteditable="true"]').length;
                } catch (_) { inputCount = 0; }
                if (inputCount < 4) continue;

                var isCollapsed = rect.height > 0 && rect.height <= 140;
                var hasOverflowMismatch = current.scrollHeight > rect.height + 120;
                if (!isCollapsed && !hasOverflowMismatch) continue;

                current.style.height = 'auto';
                current.style.minHeight = minPanel;
                current.style.maxHeight = maxPanel;
                current.style.overflowY = 'auto';
                current.setAttribute(repairedAttr, repairedThemeValue);
                break;
              }
            });
          };

          var repairCollapsedPanels = function(height) {
            var maxPanel = Math.max(180, Math.round(height * 0.82)) + 'px';
            var minPanel = Math.max(120, Math.round(height * 0.35)) + 'px';
            var selectors = [
              '[role="dialog"]',
              '.modal',
              '.dialog',
              '.popover',
              '.dropdown-menu',
              '[class*="theme"]',
              '[id*="theme"]',
              '[class*="creator"]',
              '[id*="creator"]',
              '[style*="vh"]',
              '[style*="dvh"]'
            ].join(', ');

            document.querySelectorAll(selectors).forEach(function(el) {
              if (!el || !el.getBoundingClientRect) return;
              var rect = el.getBoundingClientRect();
              var scrollHeight = el.scrollHeight || 0;
              var text = normalizedText(el.textContent);
              var isThemeCreatorLike = text.indexOf('theme creator') !== -1;

              // Ignore tiny controls/chips that happen to match selector text.
              if (rect.width < 160) return;

              if (rect.height > 28) {
                // Remove stale repair markers once the panel is healthy again.
                if (el.getAttribute(repairedAttr) === repairedCollapsedValue) {
                  el.style.removeProperty('height');
                  el.style.removeProperty('min-height');
                  el.style.removeProperty('max-height');
                  el.style.removeProperty('overflow-y');
                  el.style.removeProperty('display');
                  el.removeAttribute(repairedAttr);
                }
                return;
              }
              // Only repair truly collapsed containers that still have meaningful content.
              if (rect.height <= 0) return;
              if (!isThemeCreatorLike && scrollHeight < 80) return;

              el.style.height = 'auto';
              el.style.minHeight = minPanel;
              el.style.maxHeight = maxPanel;
              el.style.overflowY = 'auto';

              // Theme Creator can collapse while header/footer stay visible; ensure the
              // container can grow into a full working panel.
              if (isThemeCreatorLike) {
                el.style.display = 'block';
              }

              el.setAttribute(repairedAttr, repairedCollapsedValue);
            });
          };

          var applyViewportFix = function() {
            var visualHeight = window.visualViewport && window.visualViewport.height;
            var height = Math.max(
              window.innerHeight || 0,
              document.documentElement.clientHeight || 0,
              visualHeight || 0
            );
            if (!height) return;

            var px = Math.round(height) + 'px';
            // Hermes WebUI floating menus cap their height with `max-height: calc(100vh - 16px)`
            // and the generated update-summary panel uses `max-height: min(34vh, 260px)`.
            // Android System WebView evaluates these viewport units as 0 here (same quirk it can
            // apply to `100dvh`), so those panels collapse to a tiny sliver with the content
            // scrolled out of view. Re-cap them with the measured viewport height instead.
            var menuMax = Math.max(120, Math.round(height) - 16) + 'px';
            var viewportWidth = window.visualViewport && window.visualViewport.width || window.innerWidth || 0;
            var updateSummaryMax = Math.max(120, Math.min(260, Math.round(height * 0.34))) + 'px';
            var updateSummaryExpandedMax = Math.max(180, Math.min(560, Math.round(height * 0.75))) + 'px';
            var updateSummaryExpandedMobileMax = Math.max(220, Math.min(640, Math.round(height * 0.82))) + 'px';
            var style = document.getElementById(styleId);
            if (!style) {
              style = document.createElement('style');
              style.id = styleId;
              (document.head || document.documentElement).appendChild(style);
            }
            // Keep vertical scrolling available. Some WebUI panels (for example the generated
            // update summary/details block) expand below the fold and become unusable if body
            // scrolling is locked with `overflow: hidden`.
            style.textContent = [
              // Avoid fixed `height` on html/body: some extension panels size from percentage/flex
              // chains and can collapse when the root is hard-locked.
              'html, body { min-height: ' + px + ' !important; }',
              'body { overflow-x: hidden !important; }',
              '.layout, .rail, .sidebar, #sessionList, .messages { min-height: 0 !important; }',
              '.session-action-menu, .workspace-prefs-menu { max-height: ' + menuMax + ' !important; }',
              '#updateSummaryPanel { max-height: ' + updateSummaryMax + ' !important; overflow-y: auto !important; }',
              '#updateSummaryScroll { max-height: ' + updateSummaryMax + ' !important; overflow-y: auto !important; }',
              '#updateSummaryPanel.update-summary-expanded #updateSummaryScroll { max-height: ' + updateSummaryExpandedMax + ' !important; }',
              (viewportWidth > 0 && viewportWidth <= 600
                ? '#updateSummaryPanel.update-summary-expanded #updateSummaryScroll { max-height: ' + updateSummaryExpandedMobileMax + ' !important; }'
                : '')
            ].join('\n');

            // Some extension panels (for example Theme Creator) can still collapse to a
            // tiny strip when Android WebView resolves vh-based caps to ~0px.
            repairCollapsedPanels(height);
            repairThemeCreatorPanel(height);
          };

          var scheduleApplyViewportFix = function() {
            if (scheduledApply) return;
            scheduledApply = true;
            window.requestAnimationFrame(function() {
              scheduledApply = false;
              applyViewportFix();
            });
          };

          window.__hermesAndroidApplyViewportFix = applyViewportFix;
          scheduleApplyViewportFix();

          if (!window.__hermesAndroidViewportFixInstalled) {
            window.__hermesAndroidViewportFixInstalled = true;
            window.addEventListener('resize', scheduleApplyViewportFix, { passive: true });
            window.addEventListener('orientationchange', function() {
              window.setTimeout(scheduleApplyViewportFix, 0);
              window.setTimeout(scheduleApplyViewportFix, 250);
            }, { passive: true });
            if (window.visualViewport) {
              window.visualViewport.addEventListener('resize', scheduleApplyViewportFix, { passive: true });
            }

            // Theme/extension dialogs are often mounted after initial page load. Re-run
            // the viewport fix when the DOM changes so collapsed vh-based panels are
            // repaired at open time, not only on window resize.
            try {
              var observer = new MutationObserver(function() { scheduleApplyViewportFix(); });
              observer.observe(document.documentElement || document.body, { childList: true, subtree: true, attributes: true });
            } catch (_) {}
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

          var isComposer = function(el) {
            if (!el) return false;
            return (el.tagName || '').toUpperCase() === 'TEXTAREA' || el.isContentEditable;
          };

          document.addEventListener('keydown', function(e) {
            if (e.key !== 'Enter' && e.keyCode !== 13) return;
            if (e.shiftKey || e.isComposing) return;
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
