'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { execFileSync, spawnSync } = require('node:child_process');
const { androidDirectory, classify, plan, writePlan } = require('../scripts/ci_plan.cjs');

function workspace(t, directory = '.') {
  const parent = fs.realpathSync(os.tmpdir());
  const root = fs.mkdtempSync(path.join(parent, 'bintime-ci-plan-'));
  t.after(() => {
    const relative = path.relative(parent, path.resolve(root));
    assert.ok(!relative.startsWith('..') && !path.isAbsolute(relative) && /^bintime-ci-plan-[^/\\]+$/.test(relative));
    fs.rmSync(root, { recursive: true, force: true });
  });
  const write = (file, content = 'fixture\n') => {
    const target = path.join(root, file); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, content);
  };
  if (directory !== 'none') {
    write(path.join(directory, 'gradlew')); write(path.join(directory, 'settings.gradle.kts'));
  }
  return { root, write };
}

function repository(t) {
  const files = workspace(t);
  const git = (...args) => execFileSync('git', args, { cwd: files.root, encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] }).trim();
  git('init', '-q'); git('config', 'user.name', 'Local CI test'); git('config', 'user.email', 'ci@example.invalid');
  git('config', 'core.autocrlf', 'false');
  const commit = message => { git('add', '.'); git('commit', '-qm', message); return git('rev-parse', 'HEAD'); };
  const base = commit('base');
  return { ...files, git, commit, base };
}

for (const [label, files, android, automation] of [
  ['docs only', ['README.md', 'AGENTS.md', 'docs/design.png', '.github/PULL_REQUEST_TEMPLATE.md'], false, false],
  ['server only', ['functions/src/index.ts', 'functions/package-lock.json', 'firestore.rules', 'database.rules.json', 'firebase.json'], false, false],
  ['Android resources and tests', ['app/src/main/res/layout/example.xml', 'app/src/androidTest/java/Example.java'], true, false],
  ['Android deletion', ['app/src/main/java/Removed.java'], true, false],
  ['Gradle configuration', ['gradle/libs.versions.toml', 'gradlew.bat', 'settings.gradle.kts', 'buildSrc/build.gradle.kts'], true, false],
  ['nested Android project', ['android/app/src/main/AndroidManifest.xml'], true, false],
  ['automation logic', ['scripts/pr_issue_status.cjs', 'tests/pr-status.test.cjs'], false, true],
  ['status workflow', ['.github/workflows/pr-issue-status.yml'], false, true],
  ['CI change', ['.github/workflows/android-ci.yml', 'scripts/ci_plan.cjs', 'tests/ci-plan.test.cjs'], true, true],
  ['unknown file', ['new-build-config.json'], true, true],
  ['mixed changes', ['functions/src/index.ts', 'app/src/main/AndroidManifest.xml', 'scripts/pr_project_status.cjs'], true, true],
  ['empty diff', [], false, false],
]) {
  test(`classification: ${label}`, () => assert.deepEqual(classify(files), { android, automation }));
}

test('recognizes root/nested wrappers and reports an absent project as skipped', t => {
  assert.equal(androidDirectory(workspace(t).root), '.');
  assert.equal(androidDirectory(workspace(t, 'android').root), 'android');
  const root = workspace(t, 'none').root;
  assert.deepEqual(plan({ root, eventName: 'workflow_dispatch' }), {
    directory: 'none', android: false, automation: true, reason: 'manual-full-check', changedCount: null,
  });
});

test('PR comparison includes earlier code commits and excludes changes made only on the target branch', t => {
  const r = repository(t);
  r.git('checkout', '-qb', 'feature');
  r.write('app/src/main/java/Changed.java'); r.commit('Android change');
  r.write('README.md'); r.commit('last commit is docs');
  r.git('checkout', '-qb', 'target', r.base);
  r.write('scripts/target-only.cjs'); const target = r.commit('target-only automation change');
  r.git('merge', '--no-ff', 'feature', '-m', 'merge preview'); const sha = r.git('rev-parse', 'HEAD');
  const result = plan({ root: r.root, eventName: 'pull_request', event: { pull_request: { base: { sha: target } } }, sha });
  assert.deepEqual(result, { directory: '.', android: true, automation: false, reason: 'changed-files', changedCount: 2 });
});

test('push comparison covers all pushed commits, not only the last documentation commit', t => {
  const r = repository(t);
  r.write('app/src/main/java/Changed.java'); r.commit('code');
  r.write('docs/notes.md'); const sha = r.commit('docs');
  const result = plan({ root: r.root, eventName: 'push', event: { before: r.base }, sha });
  assert.equal(result.android, true); assert.equal(result.automation, false); assert.equal(result.changedCount, 2);
});

test('moving a source file into docs still requires the Android build', t => {
  const r = repository(t);
  r.write('app/source.txt', 'identical content\n'); const before = r.commit('source');
  r.write('docs/placeholder.md'); r.git('mv', 'app/source.txt', 'docs/source.txt'); const sha = r.commit('move');
  const result = plan({ root: r.root, eventName: 'push', event: { before }, sha });
  assert.equal(result.android, true); assert.equal(result.changedCount, 3);
});

test('documentation filenames with spaces use NUL-safe diff parsing', t => {
  const r = repository(t); r.write('docs/한글 공백 문서.md'); const sha = r.commit('docs');
  const result = plan({ root: r.root, eventName: 'push', event: { before: r.base }, sha });
  assert.equal(result.android, false); assert.equal(result.automation, false); assert.equal(result.changedCount, 1);
});

test('missing history and a PR head that is not a merge commit fall back to full checks', t => {
  const r = repository(t);
  for (const [eventName, event, sha] of [
    ['push', { before: 'a'.repeat(40) }, r.base],
    ['pull_request', { pull_request: { base: { sha: r.base } } }, r.base],
    ['push', { before: '0'.repeat(40) }, r.base],
  ]) {
    const result = plan({ root: r.root, eventName, event, sha });
    assert.equal(result.android, true); assert.equal(result.automation, true);
    assert.notEqual(result.reason, 'changed-files');
  }
});

test('manual, unknown events and malformed refs cannot bypass checks or execute shell text', t => {
  const r = repository(t);
  for (const options of [
    { eventName: 'workflow_dispatch' }, { eventName: 'unexpected' },
    { eventName: 'push', event: { before: 'HEAD; echo unsafe' }, sha: r.base },
    { eventName: 'pull_request', event: {} },
  ]) {
    const result = plan({ root: r.root, ...options });
    assert.equal(result.android, true); assert.equal(result.automation, true);
  }
});

test('writes boolean outputs and explicitly marks a skipped Android build', t => {
  const { root } = workspace(t);
  const output = path.join(root, 'output'), summary = path.join(root, 'summary');
  t.mock.method(console, 'log', () => {});
  writePlan({ directory: '.', android: false, automation: false, reason: 'changed-files', changedCount: 1 },
    { GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: summary });
  assert.match(fs.readFileSync(output, 'utf8'), /android=false\nautomation=false/);
  assert.match(fs.readFileSync(summary, 'utf8'), /SKIPPED \(not a successful build\)/);
});

test('CLI runs full checks for unreadable event metadata and fails on unwritable outputs', t => {
  const { root, write } = workspace(t); write('event.json', '{invalid');
  const script = path.resolve(__dirname, '../scripts/ci_plan.cjs'), output = path.join(root, 'output');
  const env = { ...process.env, GITHUB_EVENT_PATH: path.join(root, 'event.json'), GITHUB_EVENT_NAME: 'push',
    GITHUB_OUTPUT: output, GITHUB_STEP_SUMMARY: '', GITHUB_SHA: 'a'.repeat(40) };
  const run = override => spawnSync(process.execPath, [script], { cwd: root, env: { ...env, ...override }, encoding: 'utf8' });
  assert.equal(run({}).status, 0);
  assert.match(fs.readFileSync(output, 'utf8'), /android=true\nautomation=true/);
  assert.equal(run({ GITHUB_OUTPUT: root }).status, 1);
});

test('workflow preserves check names, cancels superseded CI and propagates detection failure', () => {
  const workflow = fs.readFileSync(path.resolve(__dirname, '../.github/workflows/android-ci.yml'), 'utf8');
  assert.match(workflow, /types: \[opened, reopened, synchronize, ready_for_review\]/);
  assert.doesNotMatch(workflow, /\bedited\b|pull_request_target|upload-artifact/);
  assert.match(workflow, /cancel-in-progress: true/);
  assert.match(workflow, /github\.event\.pull_request\.number \|\| github\.ref/);
  assert.match(workflow, /name: Automation logic tests/);
  assert.match(workflow, /android-build:/);
  assert.equal((workflow.match(/needs\.detect-android\.result != 'success'/g) || []).length, 2);
  assert.equal((workflow.match(/run: test "\$DETECTION_RESULT" = success/g) || []).length, 2);
  assert.match(workflow, /assembleDebug testDebugUnitTest lintDebug/);
  assert.doesNotMatch(workflow, /\n    paths(?:-ignore)?:/);
  assert.match(workflow, /contents: read/);
  assert.doesNotMatch(workflow, /(?:contents|issues|pull-requests): write/);
  for (const file of ['pr-issue-status.yml', 'pr-project-status.yml']) {
    const status = fs.readFileSync(path.resolve(__dirname, '../.github/workflows', file), 'utf8');
    assert.match(status, /pull_request_target:/); assert.match(status, /\bedited\b/);
    assert.match(status, /cancel-in-progress: false/);
  }
});
