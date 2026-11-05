'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { linkedIssueNumbers, stageForPR, run } = require('../scripts/pr_issue_status.cjs');
const project = require('../scripts/pr_project_status.cjs');

const repository = 'MobileProgrammingTeam4/BINTIME';
const pr = {
  number: 68, body: 'Closes #7', state: 'open', draft: false, merged: false,
  base: { ref: 'develop' }, head: { repo: { full_name: repository } }
};

test('supports closing keywords, case, colon, multiple refs and deduplication', () => {
  const body = 'close #1, closes #2, closed #3, fix #4, fixes #5, fixed #6, resolve #7, resolves #8, resolved #9\nCLOSES: #1\nFixes mobileprogrammingteam4/bintime#10';
  assert.deepEqual(linkedIssueNumbers(body, repository), [1, 2, 3, 4, 5, 6, 7, 8, 9, 10]);
});

test('ignores examples, comments, quotes, invalid numbers and unrelated references', () => {
  const body = '<!-- Closes #10 -->\n`Closes #11`\n```text\nFixes #12\n```\n~~~\nCloses #13\n~~~\n> Closes #14\n    Closes #15\n\tCloses #16\nCloses #ISSUE_NUMBER\nCloses #0\nCloses #9007199254740992\nRelated #18\nCloses #17\nCloses #17';
  assert.deepEqual(linkedIssueNumbers(body, repository), [17]);
  assert.deepEqual(linkedIssueNumbers(null, repository), []);
});

test('main, other branches and missing base cannot drive development issue status', () => {
  for (const ref of ['main', 'release/v1', 'feature/example']) {
    assert.equal(stageForPR({ ...pr, base: { ref }, merged: true }), null);
    assert.equal(stageForPR({ ...pr, base: { ref } }), null);
  }
  assert.equal(stageForPR({ ...pr, base: null }), null);
});

test('variable length or unclosed Markdown examples never close issues', () => {
  const body = '````text\nCloses #1\n```\nCloses #2\n````\n``Closes #3``\nCloses #4\n~~~text\nCloses #5';
  assert.deepEqual(linkedIssueNumbers(body, repository), [4]);
  assert.deepEqual(linkedIssueNumbers('<!-- Closes #6', repository), []);
});

function harness(latest = pr, issues = {}) {
  const calls = [];
  const notices = [];
  const issue = { state: 'open', labels: [{ name: 'priority:P0' }, { name: 'owner:D' }, { name: 'status:in-progress' }] };
  const context = { repo: { owner: 'MobileProgrammingTeam4', repo: 'BINTIME' },
    payload: { action: 'opened', repository: { default_branch: 'main' }, pull_request: pr } };
  const github = { rest: {
    pulls: { get: async args => { calls.push(['getPR', args]); return { data: latest }; } },
    issues: {
      getLabel: async () => ({}),
      createLabel: async args => calls.push(['createLabel', args]),
      get: async args => {
        const value = issues[args.issue_number] || issue;
        if (value instanceof Error) throw value;
        return { data: value };
      },
      addLabels: async args => {
        calls.push(['addLabels', args]);
        (issues[args.issue_number] || issue).labels.push(...args.labels);
      },
      removeLabel: async args => {
        calls.push(['removeLabel', args]);
        const value = issues[args.issue_number] || issue;
        value.labels = value.labels.filter(l => (typeof l === 'string' ? l : l.name) !== args.name);
      },
      update: async args => {
        calls.push(['update', args]);
        Object.assign(issues[args.issue_number] || issue, { state: args.state, state_reason: args.state_reason });
      }
    }
  } };
  const core = { notice: m => notices.push(m), warning: m => notices.push(m), info: () => {} };
  return { github, context, core, calls, notices, issue };
}

test('merged develop PR closes issue as completed, preserves labels and is idempotent', async () => {
  const h = harness({ ...pr, merged: true, state: 'closed' });
  await run(h);
  assert.deepEqual(h.calls.filter(c => c[0] === 'update'), [['update', {
    owner: 'MobileProgrammingTeam4', repo: 'BINTIME', issue_number: 7,
    state: 'closed', state_reason: 'completed'
  }]]);
  assert.deepEqual(h.issue.labels, [{ name: 'priority:P0' }, { name: 'owner:D' }, 'status:done']);
  await run(h);
  assert.equal(h.calls.filter(c => c[0] === 'update').length, 1);
});

test('delayed event uses latest merged PR instead of regressing status', async () => {
  const h = harness({ ...pr, merged: true, state: 'closed' });
  h.context.payload.action = 'edited';
  await run(h);
  assert.equal(h.issue.state, 'closed');
  assert.ok(h.issue.labels.includes('status:done'));
});

for (const [name, latest, target] of [
  ['draft', { ...pr, draft: true }, 'status:in-progress'],
  ['ready for review', pr, 'status:in-review'],
  ['closed without merge', { ...pr, state: 'closed' }, 'status:in-progress'],
  ['reopened', { ...pr, state: 'open' }, 'status:in-review']
]) {
  test(name + ' updates labels without closing the issue', async () => {
    const h = harness(latest);
    await run(h);
    assert.ok(h.issue.labels.some(l => (l.name || l) === target));
    assert.equal(h.issue.state, 'open');
    assert.equal(h.calls.filter(c => c[0] === 'update').length, 0);
  });
}

test('forks, retargeted/main PRs and unrelated body never write issue state', async () => {
  for (const latest of [
    { ...pr, head: { repo: { full_name: 'external/BINTIME' } } },
    { ...pr, head: { repo: null } },
    { ...pr, base: { ref: 'main' }, merged: true },
    { ...pr, body: 'Related #7' }
  ]) {
    const h = harness(latest);
    await run(h);
    assert.deepEqual(h.calls.map(c => c[0]), ['getPR']);
  }
});

test('missing payload is a safe no-op', async () => {
  const h = harness();
  h.context.payload = {};
  await run(h);
  assert.deepEqual(h.calls, []);
});

test('PR references and already closed issues are skipped during review', async () => {
  for (const issue of [
    { pull_request: {}, state: 'open', labels: [] },
    { state: 'closed', state_reason: 'completed', labels: ['status:done'] }
  ]) {
    const h = harness(pr, { 7: issue });
    await run(h);
    assert.deepEqual(h.calls.map(c => c[0]), ['getPR']);
  }
});

test('merge does not overwrite an issue closed as not planned', async () => {
  const h = harness({ ...pr, merged: true }, {
    7: { state: 'closed', state_reason: 'not_planned', labels: ['status:todo'] }
  });
  await run(h);
  assert.deepEqual(h.calls.map(c => c[0]), ['getPR']);
});

test('missing issue does not prevent another valid closing reference', async () => {
  const h = harness({ ...pr, body: 'Closes #6, closes #7', merged: true }, {
    6: Object.assign(new Error('not found'), { status: 404 })
  });
  await run(h);
  assert.equal(h.calls.find(c => c[0] === 'update')[1].issue_number, 7);
});

test('permission failures surface instead of being reported as success', async () => {
  const h = harness();
  h.github.rest.issues.get = async () => { throw Object.assign(new Error('forbidden'), { status: 403 }); };
  await assert.rejects(run(h), /forbidden/);
});

test('optional Project sync skips when credentials are absent', async () => {
  const previous = process.env.BIN_TIME_PROJECT_TOKEN;
  delete process.env.BIN_TIME_PROJECT_TOKEN;
  try {
    const h = harness();
    await project.run(h);
    assert.ok(h.notices.some(n => n.includes('credentials missing')));
  } finally {
    if (previous === undefined) delete process.env.BIN_TIME_PROJECT_TOKEN;
    else process.env.BIN_TIME_PROJECT_TOKEN = previous;
  }
});

test('Project uses latest develop stage and does not downgrade a completed issue', async () => {
  const keys = ['BIN_TIME_PROJECT_TOKEN', 'BIN_TIME_PROJECT_OWNER', 'BIN_TIME_PROJECT_NUMBER'];
  const previous = keys.map(k => process.env[k]);
  Object.assign(process.env, { BIN_TIME_PROJECT_TOKEN: 'mock', BIN_TIME_PROJECT_OWNER: 'MobileProgrammingTeam4', BIN_TIME_PROJECT_NUMBER: '1' });
  try {
    for (const merged of [false, true]) {
      const h = harness({ ...pr, merged });
      const mutations = [];
      h.github.graphql = async (query, args) => {
        if (query.includes('repositoryOwner')) return { repositoryOwner: { __typename: 'Organization' } };
        if (query.includes('organization(')) return { organization: { projectV2: {
          id: 'project', fields: { nodes: [{ id: 'field', name: 'Status', options: [
            { id: 'review', name: 'In Review' }, { id: 'done', name: 'Done' }
          ] }] }
        } } };
        if (query.includes('issue(number:')) return { repository: { issue: {
          id: 'issue', state: 'CLOSED', stateReason: 'COMPLETED', projectItems: {
            nodes: [{ id: 'item', project: { id: 'project' } }], pageInfo: { hasNextPage: false }
          }
        } } };
        mutations.push(args);
        return {};
      };
      await project.run(h);
      assert.equal(mutations.length, merged ? 1 : 0);
      if (merged) assert.equal(mutations[0].option, 'done');
    }
  } finally {
    keys.forEach((key, i) => { if (previous[i] === undefined) delete process.env[key]; else process.env[key] = previous[i]; });
  }
});
