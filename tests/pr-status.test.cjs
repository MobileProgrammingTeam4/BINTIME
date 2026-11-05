'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { linkedIssueNumbers, stageForPR } = require('../scripts/pr_issue_status.cjs');

test('only same repository closing references are matched', () => {
  const text = 'Closes #17\nFixes MobileProgrammingTeam4/BINTIME#22\nResolves other/repo#14\nRelated #21';
  assert.deepEqual(linkedIssueNumbers(text, 'MobileProgrammingTeam4/BINTIME'), [17, 22]);
});

test('deduplicates and ignores code and comments', () => {
  const text = '<!-- Closes #10 -->\n`Closes #11`\n```text\nFixes #12\n```\nCloses #17\nCloses #17';
  assert.deepEqual(linkedIssueNumbers(text, 'MobileProgrammingTeam4/BINTIME'), [17]);
});

test('status follows draft, review, close, merge', () => {
  const pr = { merged: false, draft: false, state: 'open', base: { ref: 'develop' } };
  assert.equal(stageForPR(pr), 'In Review');
  assert.equal(stageForPR({ ...pr, draft: true }), 'In Progress');
  assert.equal(stageForPR({ ...pr, state: 'closed' }), 'In Progress');
  assert.equal(stageForPR({ ...pr, merged: true }), 'Done');
  assert.equal(stageForPR({ ...pr, merged: true, base: { ref: 'main' } }), null);
});
