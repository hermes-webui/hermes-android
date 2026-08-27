// Runtime-error guard for the JavaScript that Android injects into the Hermes
// WebUI WebView (see tools/extract_webui_scripts.py).
//
// Deliberately NOT a style linter: no formatting, naming, or complexity rules.
// It enables only the rules that catch code which parses fine but throws when a
// real WebView executes it — the class of bug that bricks WebUI rendering on
// device while every Kotlin unit test and Android Lint check stays green.

const browserGlobals = [
  "AbortController",
  "Blob",
  "CSS",
  "CustomEvent",
  "DOMException",
  "Date",
  "Element",
  "Event",
  "FormData",
  "Headers",
  "HTMLElement",
  "Image",
  "JSON",
  "Math",
  "MediaRecorder",
  "MutationObserver",
  "Node",
  "NodeFilter",
  "Notification",
  "Promise",
  "Request",
  "Response",
  "ResizeObserver",
  "ServiceWorkerRegistration",
  "URL",
  "URLSearchParams",
  "cancelAnimationFrame",
  "clearInterval",
  "clearTimeout",
  "console",
  "document",
  "fetch",
  "getComputedStyle",
  "localStorage",
  "location",
  "navigator",
  "queueMicrotask",
  "requestAnimationFrame",
  "screen",
  "self",
  "sessionStorage",
  "setInterval",
  "setTimeout",
  "visualViewport",
  "window",
];

// Globals owned by the Hermes WebUI page itself, not by Android. The shims read
// them defensively (`typeof x !== 'undefined'`) because WebUI may rename or drop
// them; list them here so no-undef stays meaningful for genuine typos.
const webUiPageGlobals = ["_clarifyId", "_clarifySignature"];

const globals = Object.fromEntries(
  [...browserGlobals, ...webUiPageGlobals].map((name) => [name, "readonly"]),
);

export default [
  {
    files: ["**/*.js"],
    languageOptions: {
      ecmaVersion: 2020,
      sourceType: "script",
      globals,
    },
    linterOptions: {
      reportUnusedDisableDirectives: true,
    },
    rules: {
      // Assignment/binding errors that throw only at execution time.
      "no-const-assign": "error",
      "no-import-assign": "error",
      "no-func-assign": "error",
      "no-class-assign": "error",
      "no-self-assign": "error",

      // References that resolve to nothing once the browser runs the script.
      "no-undef": "error",
      "no-obj-calls": "error",
      "no-unreachable": "error",

      // Silent-wrong-behavior traps.
      "no-dupe-args": "error",
      "no-dupe-keys": "error",
      "no-dupe-class-members": "error",
      "no-duplicate-case": "error",
      "no-unsafe-negation": "error",
      "no-unsafe-finally": "error",
      "no-compare-neg-zero": "error",
      "use-isnan": "error",
      "valid-typeof": "error",

      // Declaration hazards specific to the classic-script scope these run in.
      "no-redeclare": "error",
      "no-undef-init": "off",
      "no-sparse-arrays": "error",
    },
  },
];
