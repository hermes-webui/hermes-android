import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';

/** Replace the configured server (full URL, origin, host) so reports are safe to paste into a PR. */
export function makeRedactor(serverUrl) {
  const u = new URL(serverUrl);
  const needles = [serverUrl, u.origin, u.host, u.hostname].filter(Boolean).sort((a, b) => b.length - a.length);
  return (text) => needles.reduce((t, n) => t.split(n).join('<server>'), String(text));
}

export function writeReport(outDir, meta, results, redact) {
  mkdirSync(outDir, { recursive: true });
  const clean = results.map((r) => ({ ...r, detail: redact(r.detail ?? '') }));
  const failed = clean.filter((r) => r.status === 'FAIL').length;
  writeFileSync(join(outDir, 'report.json'), JSON.stringify({ meta, results: clean, failed }, null, 2));
  const lines = [
    '# Live QA report',
    '',
    ...Object.entries(meta).map(([k, v]) => `- ${k}: ${redact(v)}`),
    '',
    '| Scenario | Check | Result | Detail |',
    '|---|---|---|---|',
    ...clean.map(
      (r) => `| ${r.scenario} | ${r.name} | ${r.status} | ${String(r.detail).replace(/\|/g, '/').replace(/\n/g, ' ')} |`,
    ),
    '',
    failed ? `**${failed} check(s) failed.**` : '**All checks passed.**',
  ];
  writeFileSync(join(outDir, 'report.md'), lines.join('\n') + '\n');
  return { failed, md: lines.join('\n') };
}
