'use strict';
const { linkedIssueNumbers, stageForPR } = require('./pr_issue_status.cjs');

async function run({ github, context, core }) {
  const pr = context.payload.pull_request;
  if (!pr) return;
  const repoFull = context.repo.owner + '/' + context.repo.repo;
  if (pr.head?.repo?.full_name?.toLowerCase() !== repoFull.toLowerCase()) {
    return core.notice('Fork PR ignored for project writes.');
  }
  const owner = (process.env.BIN_TIME_PROJECT_OWNER || '').trim();
  const projectNumber = Number(process.env.BIN_TIME_PROJECT_NUMBER);
  if (!process.env.BIN_TIME_PROJECT_TOKEN || !owner || !Number.isInteger(projectNumber) || projectNumber < 1) {
    return core.notice('Project credentials missing; skip Project status sync.');
  }
  const issues = linkedIssueNumbers(pr.body, repoFull);
  if (!issues.length) return core.notice('No linked issue number.');
  const stage = stageForPR(pr, context.payload.action, context.payload.repository.default_branch);
  const typeResult = await github.graphql(
    'query($owner:String!){ repositoryOwner(login:$owner){ __typename } }', { owner }
  );
  const isOrg = typeResult.repositoryOwner?.__typename === 'Organization';
  const projectQuery = isOrg
    ? 'query($owner:String!,$number:Int!){ organization(login:$owner){ projectV2(number:$number){ id fields(first:100){ nodes{ ... on ProjectV2SingleSelectField{ id name options{ id name } } } } } } }'
    : 'query($owner:String!,$number:Int!){ user(login:$owner){ projectV2(number:$number){ id fields(first:100){ nodes{ ... on ProjectV2SingleSelectField{ id name options{ id name } } } } } } }';
  const result = await github.graphql(projectQuery, { owner, number: projectNumber });
  const project = (isOrg ? result.organization : result.user)?.projectV2;
  if (!project) throw Error('Project not found; verify owner, number, token project scope.');
  const statusField = project.fields.nodes.find(f => f?.name === 'Status');
  if (!statusField) throw Error('Project Status field missing.');
  const option = statusField.options.find(o => o.name.toLowerCase() === stage.toLowerCase())
    || (stage === 'In Review' ? statusField.options.find(o => o.name.toLowerCase() === 'in progress') : undefined);
  if (!option) throw Error('Project Status option missing: ' + stage);

  const [repoOwner, repoName] = repoFull.split('/');
  for (const issue_number of issues) {
    const itemResult = await github.graphql(
      'query($owner:String!,$repo:String!,$number:Int!){ repository(owner:$owner,name:$repo){ issue(number:$number){ id state projectItems(first:100){ nodes{ id project{ id } } pageInfo{ hasNextPage } } } } }',
      { owner: repoOwner, repo: repoName, number: issue_number }
    );
    const issue = itemResult.repository?.issue;
    if (!issue || (issue.state === 'CLOSED' && stage !== 'Done')) continue;
    if (issue.projectItems.pageInfo.hasNextPage) throw Error('Project pagination needed for issue #' + issue_number);
    let projectItem = issue.projectItems.nodes.find(n => n?.project?.id === project.id);
    if (!projectItem) {
      const added = await github.graphql(
        'mutation($project:ID!,$issue:ID!){ addProjectV2ItemById(input:{projectId:$project,contentId:$issue}){ item{ id } } }',
        { project: project.id, issue: issue.id }
      );
      projectItem = added.addProjectV2ItemById.item;
    }
    await github.graphql(
      'mutation($project:ID!,$item:ID!,$field:ID!,$option:String!){ updateProjectV2ItemFieldValue(input:{projectId:$project,itemId:$item,fieldId:$field,value:{singleSelectOptionId:$option}}){ projectV2Item{ id } } }',
      { project: project.id, item: projectItem.id, field: statusField.id, option: option.id }
    );
    core.info('Issue #' + issue_number + ': Project Status=' + option.name);
  }
}
module.exports = { run };
