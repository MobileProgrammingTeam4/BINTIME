'use strict';

// Parse only explicit same-repository closing references, not code samples.
function linkedIssueNumbers(body, repository) {
  const clean = (body || '')
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\x60{3}[\s\S]*?\x60{3}/g, '')
    .replace(/~{3}[\s\S]*?~{3}/g, '')
    .replace(/\x60[^\x60\r\n]*\x60/g, '');
  const [owner, repo] = repository.toLowerCase().split('/');
  const re = /\b(?:close[sd]?|fix(?:es|ed)?|resolve[sd]?)\s+(?:([a-z0-9_.-]+)\/([a-z0-9_.-]+))?#(\d+)\b/gi;
  const refs = new Set();
  for (const m of clean.matchAll(re)) {
    if (m[1] && (m[1].toLowerCase() !== owner || m[2].toLowerCase() !== repo)) continue;
    const number = Number(m[3]);
    if (Number.isSafeInteger(number) && number > 0) refs.add(number);
  }
  return [...refs].sort((a, b) => a - b);
}

function stageForPR(pr, action, defaultBranch) {
  if (action === 'closed' && pr.merged && pr.base && pr.base.ref === defaultBranch) return 'Done';
  if (action === 'closed' || action === 'converted_to_draft' || pr.draft) return 'In Progress';
  return 'In Review';
}

const definitions = {
  'status:todo': { color: '8b949e', description: 'Not started' },
  'status:in-progress': { color: '1f6feb', description: 'Development in progress' },
  'status:in-review': { color: 'a371f7', description: 'PR awaiting review' },
  'status:done': { color: '2da44e', description: 'Merged into default branch' }
};

async function run({ github, context, core }) {
  const pr = context.payload.pull_request;
  if (!pr) return core.notice('No pull request payload.');
  const repository = context.repo.owner + '/' + context.repo.repo;
  if ((pr.head && pr.head.repo && pr.head.repo.full_name || '').toLowerCase() !== repository.toLowerCase()) {
    return core.notice('External/fork PR: privileged label update is disabled.');
  }
  const refs = linkedIssueNumbers(pr.body, repository);
  if (!refs.length) return core.notice('No Closes/Fixes/Resolves #issue in PR body.');
  const stage = stageForPR(pr, context.payload.action, context.payload.repository.default_branch);
  const target = 'status:' + stage.toLowerCase().replace(/ /g, '-');
  const { owner, repo } = context.repo;
  for (const [name, def] of Object.entries(definitions)) {
    try { await github.rest.issues.getLabel({ owner, repo, name }); }
    catch (err) {
      if (err.status !== 404) throw err;
      try { await github.rest.issues.createLabel({ owner, repo, name, ...def }); }
      catch (race) { if (race.status !== 422) throw race; }
    }
  }
  for (const issue_number of refs) {
    const response = await github.rest.issues.get({ owner, repo, issue_number });
    const issue = response.data;
    if (issue.pull_request || (issue.state === 'closed' && stage !== 'Done')) {
      core.notice('#' + issue_number + ': PR or already closed; skipped.');
      continue;
    }
    const existing = issue.labels.map(x => typeof x === 'string' ? x : x.name);
    for (const name of Object.keys(definitions)) {
      if (name !== target && existing.includes(name)) {
        try { await github.rest.issues.removeLabel({ owner, repo, issue_number, name }); }
        catch (err) { if (err.status !== 404) throw err; }
      }
    }
    if (!existing.includes(target)) {
      await github.rest.issues.addLabels({ owner, repo, issue_number, labels: [target] });
    }
    core.info('#' + issue_number + ': ' + stage);
  }
}
module.exports = { linkedIssueNumbers, stageForPR, run };
