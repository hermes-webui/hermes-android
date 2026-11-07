import { execFileSync } from 'node:child_process';
import { existsSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

function findAdb() {
  if (process.env.HERMES_QA_ADB) return process.env.HERMES_QA_ADB;
  const exe = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const roots = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA && join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
    process.env.HOME && join(process.env.HOME, 'Android', 'Sdk'),
    process.env.HOME && join(process.env.HOME, 'Library', 'Android', 'sdk'),
  ].filter(Boolean);
  for (const r of roots) {
    const p = join(r, 'platform-tools', exe);
    if (existsSync(p)) return p;
  }
  return exe; // fall back to PATH
}

export class Adb {
  constructor(serial) {
    this.bin = findAdb();
    this.serial = serial || null;
  }

  run(args, { binary = false, allowFail = false } = {}) {
    const full = this.serial ? ['-s', this.serial, ...args] : args;
    try {
      const out = execFileSync(this.bin, full, {
        stdio: ['ignore', 'pipe', 'ignore'],
        maxBuffer: 64 * 1024 * 1024,
      });
      return binary ? out : out.toString('utf8').replace(/\r/g, '').trim();
    } catch {
      if (allowFail) return binary ? Buffer.alloc(0) : '';
      throw new Error(`adb ${full.join(' ')} failed`);
    }
  }

  devices() {
    return this.run(['devices'])
      .split('\n')
      .slice(1)
      .map((l) => l.split('\t'))
      .filter((p) => p[1] === 'device')
      .map((p) => p[0]);
  }

  isInstalled(pkg) {
    return this.run(['shell', 'pm', 'list', 'packages', pkg]).includes(`package:${pkg}`);
  }

  pidof(pkg) {
    return this.run(['shell', 'pidof', pkg], { allowFail: true }).split(/\s+/)[0] || null;
  }

  launch(pkg) {
    this.run(['shell', 'monkey', '-p', pkg, '-c', 'android.intent.category.LAUNCHER', '1'], { allowFail: true });
  }

  forward(port, pid) {
    this.run(['forward', `tcp:${port}`, `localabstract:webview_devtools_remote_${pid}`]);
  }

  unforward(port) {
    this.run(['forward', '--remove', `tcp:${port}`], { allowFail: true });
  }

  /** Foldables expose several displays; pick the first so screencap does not prepend a warning. */
  screenshot(file) {
    const ids = [
      ...this.run(['shell', 'dumpsys', 'SurfaceFlinger', '--display-id'], { allowFail: true }).matchAll(/Display (\d+)/g),
    ].map((m) => m[1]);
    const args = ['exec-out', 'screencap', '-p', ...(ids.length > 1 ? ['-d', ids[0]] : [])];
    writeFileSync(file, this.run(args, { binary: true }));
  }
}
