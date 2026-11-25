'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { execFileSync } = require('node:child_process');

const CI_FILES = new Set(['.github/workflows/android-ci.yml', 'scripts/ci_plan.cjs', 'tests/ci-plan.test.cjs']);
const SERVER_FILES = new Set(['firebase.json', '.firebaserc', 'firestore.rules', 'firestore.indexes.json', 'database.rules.json', 'storage.rules']);
const BUILD_FILE = /^(?:build|settings)\.gradle(?:\.kts)?$|^gradle\.properties$|^gradlew(?:\.bat)?$/;
const SHA = /^(?:[a-f0-9]{40}|[a-f0-9]{64})$/i;

function androidDirectory(root) {
  for (const dir of ['.', 'android']) {
    const base = path.join(root, dir);
    if (fs.existsSync(path.join(base, 'gradlew')) && ['settings.gradle', 'settings.gradle.kts'].some(file => fs.existsSync(path.join(base, file)))) return dir;
  }
  return 'none';
}

function classify(files) {
  let android = false, automation = false;
  for (const file of files) {
    if (CI_FILES.has(file) || file.startsWith('.github/actions/') || ['.gitattributes', '.gitmodules'].includes(file)) {
      android = automation = true;
    } else if (file.startsWith('app/') || file.startsWith('android/') || file.startsWith('gradle/')
        || file.startsWith('buildSrc/') || file.startsWith('build-logic/') || BUILD_FILE.test(file)) {
      android = true;
    } else if (file.startsWith('scripts/') || file.startsWith('tests/') || file.startsWith('.github/workflows/')
        || ['package.json', 'package-lock.json', 'npm-shrinkwrap.json'].includes(file)) {
      automation = true;
    } else if (file.startsWith('functions/') || SERVER_FILES.has(file)) {
      // This workflow does not validate the Firebase backend; its own checks remain separate.
    } else if (file.startsWith('docs/') || file.startsWith('.github/ISSUE_TEMPLATE/')
        || ['README.md', 'AGENTS.md', 'CONTRIBUTING.md', 'LICENSE', '.github/PULL_REQUEST_TEMPLATE.md'].includes(file)) {
      // Documentation-only changes need no Android runner or automation test runner.
    } else {
      // A new build/config location must not silently bypass checks.
      android = automation = true;
    }
  }
  return { android, automation };
}

function plan({ root = process.cwd(), eventName, event = {}, sha }) {
  const directory = androidDirectory(root);
  const all = reason => ({ directory, android: directory !== 'none', automation: true, reason, changedCount: null });
  if (eventName === 'workflow_dispatch') return all('manual-full-check');
  let base;
  if (eventName === 'pull_request') base = event.pull_request?.base?.sha;
  else if (eventName === 'push') base = event.before;
  else return all('unknown-event-full-check');
  if (!SHA.test(base || '') || !SHA.test(sha || '') || /^0+$/.test(base)) return all('missing-comparison-full-check');
  const git = args => execFileSync('git', args, { cwd: root, encoding: 'utf8', maxBuffer: 16 * 1024 * 1024, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    git(['cat-file', '-e', `${base}^{commit}`]);
    git(['cat-file', '-e', `${sha}^{commit}`]);
    if (eventName === 'pull_request') {
      // Checkout's merge commit includes the target's changes. Compare it to its base
      // parent, not HEAD^..HEAD on the feature branch or only the most recent commit.
      const parents = git(['rev-list', '--parents', '-n', '1', sha]).trim().split(/\s+/);
      if (parents.length !== 3 || parents[1] !== base) return all('unexpected-pr-merge-full-check');
    }
    // No rename detection: a move from app/ to docs/ must still inspect the deleted source.
    // NUL delimiters preserve spaces/newlines and avoid shell interpolation of paths.
    const files = git(['diff', '--name-only', '--no-renames', '-z', base, sha, '--']).split('\0').filter(Boolean);
    const changed = classify(files);
    return { directory, android: directory !== 'none' && changed.android, automation: changed.automation,
      reason: 'changed-files', changedCount: files.length };
  } catch {
    // Shallow/multi-commit pushes, unavailable refs or diff errors run the full checks.
    return all('comparison-unavailable-full-check');
  }
}

function writePlan(result, env = process.env) {
  const lines = [`directory=${result.directory}`, `android=${result.android}`, `automation=${result.automation}`];
  if (env.GITHUB_OUTPUT) fs.appendFileSync(env.GITHUB_OUTPUT, `${lines.join('\n')}\n`);
  const summary = ['## CI execution plan', `- Reason: ${result.reason}`,
    `- Changed files: ${result.changedCount ?? 'unknown / full check'}`,
    `- Android: ${result.android ? 'RUN assembleDebug + unit tests + lint' : 'SKIPPED (not a successful build)'}`,
    `- Automation tests: ${result.automation ? 'RUN' : 'SKIPPED'}`, `- Android directory: ${result.directory}`, ''].join('\n');
  if (env.GITHUB_STEP_SUMMARY) fs.appendFileSync(env.GITHUB_STEP_SUMMARY, summary);
  console.log(summary);
}

if (require.main === module) {
  try {
    let event = {};
    try { event = JSON.parse(fs.readFileSync(process.env.GITHUB_EVENT_PATH, 'utf8')); } catch { /* missing metadata runs full checks */ }
    writePlan(plan({ eventName: process.env.GITHUB_EVENT_NAME, event, sha: process.env.GITHUB_SHA }));
  } catch (error) {
    // Output/runner failures must fail detection; downstream jobs explicitly fail too.
    console.error('CI change detection failed. No checks may be treated as passed.');
    process.exitCode = 1;
  }
}

module.exports = { androidDirectory, classify, plan, writePlan };
