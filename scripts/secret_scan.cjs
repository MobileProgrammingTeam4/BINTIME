#!/usr/bin/env node
'use strict';

// Never relay scanner stdout/stderr or matched source lines: they may contain secrets.
const { spawnSync } = require('node:child_process');
const { mkdtempSync, readFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join } = require('node:path');

const mode = process.argv[2] || '--staged';
if (!['--staged', '--history'].includes(mode) || process.argv.length > 3) {
  console.error('Usage: node scripts/secret_scan.cjs [--staged|--history]');
  process.exit(2);
}
const directory = mkdtempSync(join(tmpdir(), 'bintime-secret-scan-'));
try {
  const report = join(directory, 'redacted.json');
  const args = ['git', '--no-banner', '--redact=100', '--ignore-gitleaks-allow',
    '--report-format=json', `--report-path=${report}`,
    ...(mode === '--staged' ? ['--pre-commit', '--staged'] : ['--log-opts=--all'])];
  const result = spawnSync(process.env.GITLEAKS_BIN || 'gitleaks', args, {
    encoding: 'utf8', windowsHide: true, maxBuffer: 8 * 1024 * 1024,
  });
  if (result.error || ![0, 1].includes(result.status)) {
    console.error('Secret scan could not complete. Install Gitleaks 8.30.1 on PATH or set GITLEAKS_BIN. Commit blocked.');
    process.exitCode = 2;
  } else if (result.status === 1) {
    const findings = JSON.parse(readFileSync(report, 'utf8'));
    console.error(`Secret scan blocked: ${findings.length} potential secret(s). Do not paste secret values into issues or logs.`);
    for (const finding of findings) {
      // JSON encoding also prevents control characters in filenames from changing terminal output.
      console.error(JSON.stringify({ file: finding.File, line: finding.StartLine, rule: finding.RuleID }));
    }
    process.exitCode = 1;
  } else {
    console.log(`Secret scan passed (${mode.slice(2)}). This does not audit images, PDFs, or external logs.`);
  }
} catch {
  console.error('Secret scan failed; no successful audit result is available. Commit blocked.');
  process.exitCode = 2;
} finally {
  rmSync(directory, { recursive: true, force: true });
}
