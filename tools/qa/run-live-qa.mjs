#!/usr/bin/env node
// Local live-QA harness: drives the installed debug app on an emulator/device against a real
// Hermes WebUI server over adb + WebView DevTools. It is NOT run in public CI (the server is
// private). See tools/qa/README.md.
//
//   node tools/qa/run-live-qa.mjs [--only s01,s02] [--serial emulator-5554] [--no-launch]
//
// The server URL comes from HERMES_QA_SERVER_URL or the untracked tools/qa/.env.qa and is
// redacted from every report. Never commit it.
import { existsSync, readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { Adb } from './lib/adb.mjs';
import { connect } from './lib/cdp.mjs';
import { makeRedactor, writeReport } from './lib/report.mjs';

const here = dirname(fileURLToPath(import.meta.url));

function loadEnvFile() {
  const file = join(here, '.env.qa');
  if (!existsSync(file)) return;
  for (const line of readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = line.match(/^\s*([A-Z0-9_]+)\s*=\s*(.*?)\s*$/);
    if (m && !line.trim().startsWith('#') && process.env[m[1]] === undefined) process.env[m[1]] = m[2];
  }
}

function parseArgs(argv) {
  const args = { only: null, serial: null, launch: true };
  for (let i = 0; i < argv.length; i++) {
    if (argv[i] === '--only') args.only = argv[++i].split(',').map((s) => s.trim());
    else if (argv[i] === '--serial') args.serial = argv[++i];
    else if (argv[i] === '--no-launch') args.launch = false;
    else if (argv[i] === '--help') {
      console.log('Usage: node tools/qa/run-live-qa.mjs [--only s01,s02] [--serial id] [--no-launch]');
      process.exit(0);
    }
  }
  return args;
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
class HarnessExit extends Error {
  constructor(code, message) {
    super(message);
    this.code = code;
  }
}
// Throw instead of process.exit(): exiting with an open DevTools socket trips a libuv assertion on Windows.
function die(code, message) {
  throw new HarnessExit(code, message);
}
process.on('uncaughtException', (e) => {
  if (!(e instanceof HarnessExit)) throw e;
  console.error(e.message);
  process.exitCode = e.code;
});

loadEnvFile();
const args = parseArgs(process.argv.slice(2));
const serverUrl = process.env.HERMES_QA_SERVER_URL;
if (!serverUrl) {
  die(2, 'HERMES_QA_SERVER_URL is not set. Export it or put it in tools/qa/.env.qa (untracked). See tools/qa/README.md.');
}
let origin;
try {
  origin = new URL(serverUrl).origin;
} catch {
  die(2, 'HERMES_QA_SERVER_URL is not a valid URL.');
}
const redact = makeRedactor(serverUrl);
const pkg = process.env.HERMES_QA_PACKAGE || 'com.hermeswebui.android.debug';
const port = Number(process.env.HERMES_QA_PORT || 9333);

const devices = new Adb(null).devices();
const serial = args.serial || process.env.HERMES_QA_SERIAL || devices[0];
if (!serial) die(2, 'No adb device or emulator is connected.');
const adb = new Adb(serial);
if (!adb.isInstalled(pkg)) die(2, `${pkg} is not installed. Run: gradlew installDebug`);

if (args.launch) adb.launch(pkg);
let pid = adb.pidof(pkg);
if (!pid && args.launch) {
  for (let i = 0; i < 20 && !pid; i++) {
    await sleep(1000);
    pid = adb.pidof(pkg);
  }
}
if (!pid) die(2, `${pkg} is not running.`);

const results = [];
const meta = {
  date: new Date().toISOString(),
  device: serial,
  api: adb.run(['shell', 'getprop', 'ro.build.version.sdk']),
  package: pkg,
  appVersion: (adb.run(['shell', 'dumpsys', 'package', pkg], { allowFail: true }).match(/versionName=(\S+)/) || [])[1] || 'unknown',
};

let cdp;
let exitError = null;
const outDir = join(here, 'out', meta.date.replace(/[:.]/g, '-'));
try {
  adb.forward(port, pid);
  cdp = await connect(port);
  try {
    meta.webview = (await (await fetch(`http://127.0.0.1:${port}/json/version`)).json()).Browser;
  } catch {
    meta.webview = 'unknown';
  }
  // Preflight: right server, signed in.
  const pageOrigin = (() => {
    try {
      return new URL(cdp.pageUrl).origin;
    } catch {
      return '';
    }
  })();
  if (pageOrigin !== origin) {
    // Never echo the page origin: the redactor only knows the configured URL, so a mismatched
    // page could leak a private hostname into terminal output or a pasted report.
    die(2, 'The app is not on the configured QA server (origin mismatch). Configure that server in the app first.');
  }
  // Only a *visible* password field means signed out; WebUI keeps hidden ones in settings panels.
  const needsLogin = await cdp.evaluate(`Array.prototype.some.call(document.querySelectorAll('input[type="password"]'), function(n){
    var r = n.getBoundingClientRect();
    return r.width > 0 && r.height > 0 && getComputedStyle(n).visibility !== 'hidden';
  })`);
  if (needsLogin) die(2, 'The app is showing a sign-in page. Sign in manually in the emulator, then re-run.');

  const dir = join(here, 'scenarios');
  const files = readdirSync(dir).filter((f) => /^s\d+.*\.mjs$/.test(f)).sort();
  for (const file of files) {
    const scenario = (await import(pathToFileURL(join(dir, file)).href)).default;
    if (args.only && !args.only.includes(scenario.id)) continue;
    try {
      for (const r of await scenario.run({ cdp, adb, sleep })) {
        results.push({ scenario: scenario.id, name: r.name, status: r.pass ? 'PASS' : 'FAIL', detail: r.detail });
      }
    } catch (e) {
      results.push({ scenario: scenario.id, name: 'scenario ran', status: 'FAIL', detail: `error: ${e.message}` });
    }
  }
} catch (e) {
  if (e instanceof HarnessExit) exitError = e;
  else results.push({ scenario: 'harness', name: 'setup', status: 'FAIL', detail: `error: ${e.message}` });
} finally {
  cdp?.close();
  adb.unforward(port);
}

if (exitError) {
  // Preflight problem (wrong server, signed out): report it, but do not write a results report.
  console.error(exitError.message);
  process.exitCode = exitError.code;
} else {
  const { failed, md } = writeReport(outDir, meta, results, redact);
  if (failed) {
    try {
      adb.screenshot(join(outDir, 'failure.png'));
    } catch {
      /* screenshot is best effort */
    }
  }
  console.log(md);
  console.log(`\nReport: ${outDir}`);
  // exitCode, not process.exit(): exiting with an open DevTools socket trips a libuv assertion on Windows.
  process.exitCode = failed ? 1 : 0;
}
