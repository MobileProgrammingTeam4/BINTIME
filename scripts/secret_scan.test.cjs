'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const { spawnSync } = require('node:child_process');
const { mkdtempSync, mkdirSync, writeFileSync, rmSync } = require('node:fs');
const { tmpdir } = require('node:os');
const { join, resolve, dirname } = require('node:path');
const { randomBytes } = require('node:crypto');
const scanner = resolve(__dirname, 'secret_scan.cjs');

test('scanner rejects staged synthetic credentials, catches deleted history and fails closed', () => {
  const folder = mkdtempSync(join(tmpdir(), 'bintime-scan-test-'));
  // Recursive cleanup is restricted to this freshly-created temporary workspace.
  assert.equal(dirname(folder), resolve(tmpdir()));
  const git = (...args) => {
    const result = spawnSync('git', args, { cwd: folder, encoding: 'utf8', windowsHide: true });
    assert.equal(result.status, 0, 'fixture git command failed');
  };
  const scan = (mode, env = process.env) => spawnSync(process.execPath, [scanner, mode], {
    cwd: folder, encoding: 'utf8', windowsHide: true, env,
  });
  try {
    git('init', '-q');
    mkdirSync(join(folder, 'empty-hooks'));
    git('config', 'core.hooksPath', join(folder, 'empty-hooks'));
    git('config', 'user.email', 'fixture@example.invalid');
    git('config', 'user.name', 'Local scanner fixture');
    writeFileSync(join(folder, 'fixture.txt'), 'safe example\n');
    git('add', 'fixture.txt');
    assert.equal(scan('--staged').status, 0);
    git('commit', '-qm', 'safe fixture');
    // Random, synthetic, never valid on GitHub. No credential literal is committed.
    const fake = ['gh', 'p_'].join('') + randomBytes(27).toString('base64url');
    writeFileSync(join(folder, 'fixture.txt'), `token=${fake}\n`);
    git('add', 'fixture.txt');
    const blocked = scan('--staged');
    assert.equal(blocked.status, 1);
    assert.ok(!(`${blocked.stdout}${blocked.stderr}`).includes(fake), 'must never print a match');
    git('commit', '-qm', 'synthetic detector fixture');
    writeFileSync(join(folder, 'fixture.txt'), 'safe again\n');
    git('add', 'fixture.txt');
    git('commit', '-qm', 'remove synthetic fixture');
    assert.equal(scan('--staged').status, 0);
    assert.equal(scan('--history').status, 1, 'removed credentials must still be detected');
    assert.equal(scan('--staged', { ...process.env, GITLEAKS_BIN: join(folder, 'missing-binary') }).status, 2);
  } finally {
    rmSync(folder, { recursive: true, force: true });
  }
});
