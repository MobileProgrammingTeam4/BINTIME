'use strict';

const INTEGRATION_BRANCH = 'develop';

// Parse only explicit same-repository closing references, not code samples.
function linkedIssueNumbers(body, repository) {
  let fence = null;
  const clean = (body || '').replace(/<!--[\s\S]*?(?:-->|$)/g, '')
    .split(/\r?\n/).map(line => {
      const marker = line.match(/^ {0,3}(\x60{3,}|~{3,})/);
      if (fence) {
        if (marker && marker[1][0] === fence[0] && marker[1].length >= fence.length &&
          !line.slice(marker[0].length).trim()) fence = null;
        return '';
      }
      if (marker) { fence = marker[1]; return ''; }
      if (/^(?:[ \t]*>| {4}|\t)/.test(line)) return '';
      return line.replace(/(\x60+)[^\r\n]*?\1/g, '');
    }).join('\n');
  const [owner, repo] = repository.toLowerCase().split('/');
  const re = /\b(?:close[sd]?|fix(?:es|ed)?|resolve[sd]?):?\s+(?:([a-z0-9_.-]+)\/([a-z0-9_.-]+))?#(\d+)\b/gi;
  const refs = new Set();
  for (const m of clean.matchAll(re)) {
    if (m[1] && (m[1].toLowerCase() !== owner || m[2].toLowerCase() !== repo)) continue;
    const number = Number(m[3]);
    if (Number.isSafeInteger(number) && number > 0) refs.add(number);
  }
  return [...refs].sort((a, b) => a - b);
}

function stageForPR(pr) {
  if (pr.base?.ref !== INTEGRATION_BRANCH) return null;
  if (pr.merged) return 'Done';
  if (pr.state === 'closed' || pr.draft) return 'In Progress';
  return 'In Review';
}

// Event payloads can arrive late (including edited events after a merge).
// Always reconcile the current PR, never regress a merged issue from stale data.
async function currentPR({ github, context, core }) {
  const number = context.payload.pull_request?.number;
  if (!number) { core.notice('No pull request payload.'); return null; }
  const { data: pr } = await github.rest.pulls.get({ ...context.repo, pull_number: number });
  const repository = context.repo.owner + '/' + context.repo.repo;
  if (pr.head?.repo?.full_name?.toLowerCase() !== repository.toLowerCase()) {
    core.notice('External/fork PR: privileged updates are disabled.');
    return null;
  }
  if (!stageForPR(pr)) {
    core.notice('Only develop PRs drive development issue status.');
    return null;
  }
  return pr;
}

const definitions = {
  'status:todo': { color: '8b949e', description: 'Not started' },
  'status:in-progress': { color: '1f6feb', description: 'Development in progress' },
  'status:in-review': { color: 'a371f7', description: 'PR awaiting review' },
  'status:done': { color: '2da44e', description: 'Completed in develop' }
};

async function run({ github, context, core }) {
  const pr = await currentPR({ github, context, core });
  if (!pr) return;
  const repository = context.repo.owner + '/' + context.repo.repo;
  const refs = linkedIssueNumbers(pr.body, repository);
  if (!refs.length) return core.notice('No Closes/Fixes/Resolves #issue in PR body.');
  const stage = stageForPR(pr);
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
    let response;
    try { response = await github.rest.issues.get({ owner, repo, issue_number }); }
    catch (err) {
      if (err.status !== 404) throw err;
      core.warning('#' + issue_number + ': issue not found; skipped.');
      continue;
    }
    const issue = response.data;
    if (issue.pull_request || (issue.state === 'closed' &&
      (stage !== 'Done' || issue.state_reason !== 'completed'))) {
      core.notice('#' + issue_number + ': PR or already closed; skipped.');
      continue;
    }
    const existing = issue.labels.map(x => typeof x === 'string' ? x : x.name);
    if (!existing.includes(target)) {
      await github.rest.issues.addLabels({ owner, repo, issue_number, labels: [target] });
    }
    for (const name of Object.keys(definitions)) {
      if (name !== target && existing.includes(name)) {
        try { await github.rest.issues.removeLabel({ owner, repo, issue_number, name }); }
        catch (err) { if (err.status !== 404) throw err; }
      }
    }
    if (stage === 'Done' && issue.state !== 'closed') {
      await github.rest.issues.update({ owner, repo, issue_number, state: 'closed', state_reason: 'completed' });
    }
    core.info('#' + issue_number + ': ' + stage);
  }
}
module.exports = { INTEGRATION_BRANCH, linkedIssueNumbers, stageForPR, currentPR, run };
