# LiftLog — Claude notes

## After every push to `main`

This repo has no PR-triggered CI — `.github/workflows/build.yml` only runs
`on: push: branches: [main]`, and that single run both builds the signed
release APK and republishes it to the rolling `latest` GitHub Release (the
link in the README). So a push to `main` (a direct push, or merging a PR)
*is* the release step, not just a commit.

Before considering a change on this repo done, after it lands on `main`:

1. Find the workflow run for that commit: `actions_list` with
   `method: list_workflow_runs`, `branch: "main"` (or filter by the commit
   sha) for workflow `Build APK`.
2. Confirm `conclusion: "success"`. If it's `failure` or still running,
   that's not done yet — check the logs (`get_job_logs`) and fix it before
   telling the user the release is updated.
3. Only then tell the user the APK/release is up to date.

Don't assume the build succeeded just because the push/merge itself
succeeded — verify the actual workflow run.
