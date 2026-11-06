# Releases

An elf for [Elf Desk](https://github.com/House-elves/elf-desk) that releases your GitHub repos: the ones that keep
their version in `.github/project.yml`, as SmallRye and Quarkiverse projects do.

Its tab lists the repos you can release, in the accounts and organizations you choose, with each one's version, when
that was released and how many commits its default branch has since. Click **patch**, **minor** or **major** (each
shows the version it makes) and confirm in the desk's bar, and the rest happens on its own:

1. For a major release (2.3.4 to 3.0.0), the line it leaves is kept first in a branch of its own, `2.x`, made from
   the default branch as it is then, unless the repo has one already.
1. A branch on the repo (`release-<version>`) sets `.github/project.yml` to the new version and the next snapshot,
   and a PR of it opens. It's on the repo itself, not a fork: release checks refuse a PR from a fork (Quarkus's
   pre-release does), and it only lists repos you can push to. When the repo checks a milestone named after the version (SmallRye's
   review does) and has none, it's made.
2. When the PR's CI is green, it's merged. If CI fails, its failed jobs run again once (you can turn that off); if
   the repo needs a review, it waits for one.
3. The merge starts the repo's own release workflows: it follows the one that prepares (tags) the release and the one
   that deploys it, found by their file names (`prepare-release.yml` and `release.yml`, or `release-prepare.yml` and
   `release-perform.yml`).
4. It's done when the version is on Maven Central (the root `pom.xml`'s artifact), or when the release workflow
   ends, for a repo without one.

Each release shows how far it got, with links to its PR, its workflow runs and Maven Central. When one stops (CI
failed twice, a workflow failed), it says why, and **Try again** carries on from there. **Stop** closes the PR before
it's merged. Releasing needs the GitHub elf on your desk.

## What it reads and writes

- **Reads**, through gh as your account: the repos of the accounts in its settings that you can push to, their
  `.github/project.yml`, tags and commits; for a release, its PR, CI, reviews and workflow runs; and Maven Central.
- **Writes**, only when you click a release and confirm it: a branch on the repo, the PR, the version's
  milestone if one is needed, then the PR's merge when CI is green (and a re-run of failed CI jobs, once). Stop closes
  the PR, after you confirm it. What it found is kept in `~/.config/release/`.

## Settings

| Setting | What it is |
|---|---|
| GitHub account | The gh account it reads and releases as (`gh auth login`). |
| Accounts and organizations | Whose repos it lists, comma-separated: your login, `smallrye`, `quarkiverse`... |
| Re-run failed CI once | Re-run a release PR's failed CI jobs once before stopping. On by default. |

## Adding it

On your desk, click **+** and pick Releases.

## Running it by hand

```
jbang Release.java                  # lists the repos you can release, saves nothing
jbang Release.java --board          # saves the list for the tab, and moves releases on
jbang Release.java --follow         # follows the releases in progress until they're done
```
