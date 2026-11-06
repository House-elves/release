# What's new

What changed for you in each version of Releases, newest first. The desk shows the parts that are new when it offers
you an update.

## 0.2.2

- Fixed: a release waits for all of its PR's checks to finish, and only a failed one stops it. A cancelled check
  (quarkus-agent-mcp's macOS build is cancelled on every run) no longer counts as CI failing.

## 0.2.1

- A release that stopped at CI says which checks failed, with a link to each, and offers Start over (a new release
  PR, the old one closed) next to Re-run failed CI, which only re-runs the same PR's failed jobs.

## 0.2.0

- Fixed: a release's PR comes from a branch on the repo itself, not your fork. Quarkus's pre-release check refuses a
  PR from a fork, so releases there stopped at CI. The branch is deleted once it's merged (or stopped).
- Releasing a repo again after one stopped closes the stopped one's PR, saying which PR replaces it.

## 0.1.1

- The patch button's label is readable.

## 0.1.0

- New: lists the repos you can release (a `.github/project.yml`, in the accounts and organizations you choose) with
  their versions, and releases one when you click patch, minor or major: a PR from your fork, merged when CI is green,
  followed through the repo's release workflows to Maven Central.
