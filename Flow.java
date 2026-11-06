import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One release, from your click to Maven Central, in releases/<owner>__<name>.json for its tab. Starting it opens the
 * PR from a branch on the repo; following it (advance, again and again) waits for CI, merges, then watches the repo's release
 * workflows and Maven Central. Each step only moves forward, so following it from several places is harmless.
 *
 * stage: ci, prepare, release, central, done. state: running, waiting (on someone: a review), stopped, done.
 */
final class Flow {

    static final Path DIR = Config.DIR.resolve("releases");
    static final List<String> STAGES = List.of("pr", "ci", "prepare", "release", "central", "done");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    /** Kept on the tab after they finish. */
    static final Duration KEEP = Duration.ofDays(7);

    private Flow() {}

    static Path file(String repo) {
        if (!Repos.REPO.matcher(repo).matches()) throw new IllegalArgumentException("Not a repo: " + repo);
        return DIR.resolve(repo.replace("/", "__") + ".json");
    }

    static ObjectNode read(String repo) throws IOException {
        Path f = file(repo);
        return Files.isRegularFile(f) ? (ObjectNode) Gh.JSON.readTree(f.toFile()) : null;
    }

    static List<ObjectNode> all() throws IOException {
        List<ObjectNode> out = new ArrayList<>();
        if (!Files.isDirectory(DIR)) return out;
        try (var files = Files.list(DIR)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    out.add((ObjectNode) Gh.JSON.readTree(f.toFile()));
                } catch (IOException | ClassCastException e) {
                    System.out.println("Skipping " + f.getFileName() + ": " + e.getMessage());
                }
            }
        }
        return out;
    }

    static void write(ObjectNode r) throws IOException {
        r.put("updatedAt", Instant.now().toString());
        Path f = file(r.path("repo").asText());
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling("." + f.getFileName() + ".tmp");
        Files.writeString(tmp, Gh.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(r));
        Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static boolean active(JsonNode r) {
        return List.of("running", "waiting").contains(r.path("state").asText());
    }

    /** What the tab says about it, and where it is. */
    static void say(ObjectNode r, String stage, String state, String say) {
        r.put("stage", stage).put("state", state).put("say", say);
        System.out.println(r.path("repo").asText() + " " + r.path("version").asText() + ": " + say);
    }

    // ---- starting: the version, the milestone, its branch with the new project.yml, the PR ----

    /**
     * Starts a release: what you confirmed (the version after the one project.yml says now) is checked again, then the
     * PR goes up from a branch on the repo. Answers with the release as saved.
     */
    static ObjectNode start(Gh gh, String repo, String kind, String version) throws IOException {
        ObjectNode old = read(repo);
        if (old != null && active(old)) throw new IllegalStateException(repo + " is releasing " + old.path("version").asText() + " already");
        JsonNode info = gh.get("/repos/" + repo);
        String branch = info.path("default_branch").asText();
        if (!List.of("admin", "maintain", "push").stream().anyMatch(p -> info.path("permissions").path(p).asBoolean())) {
            throw new IllegalStateException(gh.login + " can't push to " + repo);
        }
        JsonNode file = gh.get("/repos/" + repo + "/contents/.github/project.yml?ref=" + branch);
        String text = new String(Base64.getMimeDecoder().decode(file.path("content").asText()), StandardCharsets.UTF_8);
        Matcher m = Repos.CURRENT.matcher(text);
        if (!m.find()) throw new IllegalStateException(repo + "'s .github/project.yml has no current-version");
        ObjectNode next = Repos.nextVersions(m.group(3));
        if (next == null || !version.equals(next.path(kind).asText())) {
            throw new IllegalStateException(repo + " is at " + m.group(3) + " now, so a " + kind + " release isn't " + version + ": refresh and look again");
        }
        String snapshot = Repos.snapshotAfter(version);
        String head = gh.get("/repos/" + repo + "/git/ref/heads/" + branch).path("object").path("sha").asText();
        if (!SHA.matcher(head).matches()) throw new IOException("Couldn't read " + repo + "'s " + branch);

        // Its workflows: the one that prepares (tags) and the one that releases (deploys), by their file names.
        String prepare = null, release = null;
        for (JsonNode w : gh.get("/repos/" + repo + "/actions/workflows?per_page=100").path("workflows")) {
            String path = w.path("path").asText().toLowerCase(java.util.Locale.ROOT);
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (!name.contains("release") || name.contains("review") || !w.path("state").asText().equals("active")) continue;
            if (name.contains("prepare")) prepare = w.path("path").asText();
            else if (release == null || name.equals("release.yml") || name.contains("perform")) release = w.path("path").asText();
        }
        if (prepare == null && release == null) throw new IllegalStateException(repo + " has no release workflow in .github/workflows");

        ObjectNode r = Gh.JSON.createObjectNode().put("repo", repo).put("kind", kind).put("version", version)
                .put("from", m.group(3)).put("snapshot", snapshot).put("branch", branch).put("as", gh.login)
                .put("startedAt", Instant.now().toString()).put("url", "https://github.com/" + repo);
        if (prepare != null) r.put("prepareWorkflow", prepare);
        if (release != null) r.put("releaseWorkflow", release);
        r.put("mergeMethod", info.path("allow_merge_commit").asBoolean() ? "merge" : info.path("allow_squash_merge").asBoolean() ? "squash" : "rebase");
        maven(gh, repo, branch, r);

        // A repo whose review checks a milestone named after the version (SmallRye's): there must be one.
        JsonNode milestones = gh.get("/repos/" + repo + "/milestones?state=all&per_page=100");
        if (milestones.isArray() && !milestones.isEmpty()) {
            boolean there = false;
            for (JsonNode ms : milestones) if (ms.path("title").asText().equals(version)) there = true;
            if (!there) {
                gh.api("POST", "/repos/" + repo + "/milestones", Gh.JSON.createObjectNode().put("title", version));
                r.put("milestone", "made");
                System.out.println("Made the milestone " + version + " on " + repo);
            }
        }

        // A branch on the repo itself, at its head, with the edit: release checks refuse a PR from a fork (Quarkus's
        // pre-release does), and you can push to every repo it lists.
        String ref = "release-" + version;
        if (gh.find("/repos/" + repo + "/git/ref/heads/" + ref) != null) ref += "-" + Instant.now().getEpochSecond();
        gh.api("POST", "/repos/" + repo + "/git/refs", Gh.JSON.createObjectNode().put("ref", "refs/heads/" + ref).put("sha", head));
        String edited = Repos.edit(text, version, snapshot);
        gh.api("PUT", "/repos/" + repo + "/contents/.github/project.yml", Gh.JSON.createObjectNode()
                .put("message", "Release " + version).put("branch", ref).put("sha", file.path("sha").asText())
                .put("content", Base64.getEncoder().encodeToString(edited.getBytes(StandardCharsets.UTF_8))));
        JsonNode pr = gh.api("POST", "/repos/" + repo + "/pulls", Gh.JSON.createObjectNode()
                .put("title", "Release " + version).put("base", branch).put("head", ref)
                .put("body", "Release " + version + " (" + kind + ", from " + m.group(3) + "): sets `.github/project.yml` to "
                        + version + ", next " + snapshot + ".\n\nOpened by the release elf on Elf Desk, which merges it when CI is green."));
        r.put("releaseBranch", ref);
        r.putObject("pr").put("number", pr.path("number").asInt()).put("url", pr.path("html_url").asText())
                .put("at", Instant.now().toString());
        // The release before, if it stopped with its PR open: this one takes its place.
        if (old != null && old.path("pr").path("number").asInt() > 0) supersede(gh, repo, old, pr.path("number").asInt());
        say(r, "ci", "running", "Opened the PR. Waiting for CI.");
        write(r);
        return r;
    }

    /** Closes a stopped release's PR, if it's still open, pointing at the one that replaces it, and drops its branch. */
    static void supersede(Gh gh, String repo, JsonNode old, int now) {
        int n = old.path("pr").path("number").asInt();
        try {
            JsonNode pr = gh.get("/repos/" + repo + "/pulls/" + n);
            if (!pr.path("state").asText().equals("open")) return;
            gh.api("POST", "/repos/" + repo + "/issues/" + n + "/comments", Gh.JSON.createObjectNode().put("body", "Replaced by #" + now + "."));
            gh.api("PATCH", "/repos/" + repo + "/pulls/" + n, Gh.JSON.createObjectNode().put("state", "closed"));
            dropBranch(gh, repo, old);
            System.out.println("Closed #" + n + ", replaced by #" + now);
        } catch (IOException e) {
            System.out.println("Couldn't close #" + n + ": " + e.getMessage());
        }
    }

    /** The release's own branch on the repo, once it's merged or given up: GitHub may have deleted it already. */
    static void dropBranch(Gh gh, String repo, JsonNode r) {
        String ref = r.path("releaseBranch").asText("");
        if (!ref.startsWith("release-")) return;
        try {
            gh.api("DELETE", "/repos/" + repo + "/git/refs/heads/" + ref, null);
        } catch (IOException e) {
            // gone already
        }
    }

    /** The root pom's groupId and artifactId, to find the release on Maven Central; none for a repo without one. */
    static void maven(Gh gh, String repo, String branch, ObjectNode r) {
        try {
            JsonNode pom = gh.find("/repos/" + repo + "/contents/pom.xml?ref=" + branch);
            if (pom == null) return;
            String xml = new String(Base64.getMimeDecoder().decode(pom.path("content").asText()), StandardCharsets.UTF_8);
            // The project's own coordinates: not its parent's, and only above its first section (properties,
            // dependencies, build...), where its dependencies' groupIds are.
            xml = xml.replaceAll("(?s)<!--.*?-->", "");
            String own = xml.replaceAll("(?s)<parent>.*?</parent>", "");
            Matcher section = Pattern.compile("<(properties|dependencyManagement|dependencies|build|modules|profiles|"
                    + "distributionManagement|scm|repositories|reporting|licenses|developers)\\b").matcher(own);
            if (section.find()) own = own.substring(0, section.start());
            Matcher g = Pattern.compile("<groupId>\\s*([A-Za-z0-9._-]+)\\s*</groupId>").matcher(own);
            Matcher a = Pattern.compile("<artifactId>\\s*([A-Za-z0-9._-]+)\\s*</artifactId>").matcher(own);
            String group = g.find() ? g.group(1) : null;
            if (group == null) {
                Matcher pg = Pattern.compile("(?s)<parent>.*?<groupId>\\s*([A-Za-z0-9._-]+)\\s*</groupId>").matcher(xml);
                if (pg.find()) group = pg.group(1);
            }
            if (group != null && a.find()) r.put("groupId", group).put("artifactId", a.group(1));
        } catch (IOException e) {
            System.out.println("Couldn't read " + repo + "'s pom.xml: " + e.getMessage());
        }
    }

    // ---- following: one step at a time, from whatever GitHub says now ----

    static final String PR = """
            query($owner: String!, $name: String!, $number: Int!) {
              repository(owner: $owner, name: $name) {
                pullRequest(number: $number) {
                  state merged mergedAt headRefOid mergeStateStatus reviewDecision createdAt
                  commits(last: 1) { nodes { commit { statusCheckRollup { state contexts(first: 60) { nodes {
                    __typename ... on CheckRun { name conclusion detailsUrl } ... on StatusContext { context state targetUrl } } } } } } }
                }
              }
            }""";

    /** Moves a release on as far as it can go now. Answers whether it changed. */
    static boolean advance(Gh gh, Config config, ObjectNode r) throws IOException {
        if (!active(r)) return false;
        String before = r.path("say").asText() + r.path("stage").asText() + r.path("state").asText();
        switch (r.path("stage").asText()) {
            case "ci" -> ci(gh, config, r);
            case "prepare" -> workflow(gh, r, "prepare");
            case "release" -> workflow(gh, r, "release");
            case "central" -> central(r);
            default -> { }
        }
        r.put("checkedAt", Instant.now().toString());
        return !before.equals(r.path("say").asText() + r.path("stage").asText() + r.path("state").asText());
    }

    static void ci(Gh gh, Config config, ObjectNode r) throws IOException {
        String repo = r.path("repo").asText();
        String[] on = repo.split("/");
        int number = r.path("pr").path("number").asInt();
        JsonNode pr = gh.graphql(PR, Map.of("owner", on[0], "name", on[1], "number", String.valueOf(number)))
                .path("repository").path("pullRequest");
        if (pr.path("merged").asBoolean()) {
            merged(r, pr.path("mergedAt").asText(Instant.now().toString()));
            return;
        }
        if (!pr.path("state").asText().equals("OPEN")) {
            say(r, "ci", "stopped", "The PR was closed without merging, so nothing was released.");
            return;
        }
        String sha = pr.path("headRefOid").asText();
        r.with("pr").put("headSha", sha);
        // Every check, not GitHub's sum of them: that counts a cancelled job as failed, and some repos have one
        // that's cancelled on every run (a macOS runner there isn't any of). Done when none is still running.
        JsonNode contexts = pr.path("commits").path("nodes").path(0).path("commit").path("statusCheckRollup").path("contexts").path("nodes");
        int running = 0, failed = 0, total = 0;
        for (JsonNode c : contexts) {
            total++;
            boolean run = c.path("__typename").asText().equals("CheckRun");
            if (run ? !c.path("status").asText().equals("COMPLETED") : List.of("PENDING", "EXPECTED").contains(c.path("state").asText())) running++;
            else if (FAILED.contains(run ? c.path("conclusion").asText("") : c.path("state").asText(""))) failed++;
        }
        Instant opened = Instant.parse(pr.path("createdAt").asText(Instant.now().toString()));
        // No checks at all (yet): give CI ten minutes to start before taking it as none.
        if (total == 0 && opened.isAfter(Instant.now().minus(Duration.ofMinutes(10)))) {
            say(r, "ci", "running", "Waiting for CI to start on the release PR.");
            return;
        }
        if (running > 0) {
            say(r, "ci", "running", "CI is running on the release PR" + (failed > 0 ? " (" + failed + " failed so far)." : "."));
            return;
        }
        if (failed > 0) {
            if (config.retryCi && !r.path("ciRetried").asBoolean()) {
                int n = rerun(gh, repo, sha);
                r.put("ciRetried", true);
                if (n > 0) {
                    say(r, "ci", "running", "CI failed, so its failed jobs are running again, once.");
                    return;
                }
            }
            say(r, "ci", "stopped", "CI failed on the release PR, so it isn't merged." + failedChecks(pr, r));
            return;
        }
        String review = pr.path("reviewDecision").asText("");
        if (review.equals("REVIEW_REQUIRED") || review.equals("CHANGES_REQUESTED")) {
            say(r, "ci", "waiting", review.equals("CHANGES_REQUESTED") ? "CI is green, but a reviewer asked for changes."
                    : "CI is green. Waiting for a review: the repo needs one before it merges.");
            return;
        }
        if (pr.path("mergeStateStatus").asText().equals("BEHIND")) {
            gh.api("PUT", "/repos/" + repo + "/pulls/" + number + "/update-branch", Gh.JSON.createObjectNode().put("expected_head_sha", sha));
            say(r, "ci", "running", "The PR was behind " + r.path("branch").asText() + ", so it was brought up to date. CI runs again.");
            return;
        }
        try {
            gh.api("PUT", "/repos/" + repo + "/pulls/" + number + "/merge", Gh.JSON.createObjectNode()
                    .put("sha", sha).put("merge_method", r.path("mergeMethod").asText("merge")));
        } catch (IOException e) {
            say(r, "ci", "waiting", "CI is green, but GitHub won't merge it yet: " + e.getMessage());
            return;
        }
        dropBranch(gh, repo, r);
        merged(r, Instant.now().toString());
    }

    /** The checks that failed, by name, for what the tab says (and their links, for it to show). */
    static String failedChecks(JsonNode pr, ObjectNode r) {
        List<String> names = new ArrayList<>();
        ArrayNode links = r.putArray("failed");
        for (JsonNode c : pr.path("commits").path("nodes").path(0).path("commit").path("statusCheckRollup").path("contexts").path("nodes")) {
            boolean run = c.path("__typename").asText().equals("CheckRun");
            String state = run ? c.path("conclusion").asText("") : c.path("state").asText("");
            if (!FAILED.contains(state)) continue;
            String name = run ? c.path("name").asText() : c.path("context").asText();
            names.add(name);
            links.addObject().put("name", name).put("url", run ? c.path("detailsUrl").asText("") : c.path("targetUrl").asText(""));
        }
        return names.isEmpty() ? "" : " Failed: " + String.join(", ", names) + ".";
    }

    /** A check that failed: not one cancelled or skipped. */
    static final List<String> FAILED = List.of("FAILURE", "ERROR", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED");

    static void merged(ObjectNode r, String at) {
        r.put("mergedAt", at);
        if (r.has("prepareWorkflow")) say(r, "prepare", "running", "Merged. Waiting for the release to be prepared (tagged).");
        else say(r, "release", "running", "Merged. Waiting for the release workflow.");
    }

    /** Re-runs the failed jobs of the PR's CI at its head: how many runs. */
    static int rerun(Gh gh, String repo, String sha) throws IOException {
        int n = 0;
        for (JsonNode run : gh.get("/repos/" + repo + "/actions/runs?head_sha=" + sha + "&per_page=50").path("workflow_runs")) {
            if (List.of("failure", "timed_out").contains(run.path("conclusion").asText())) {
                gh.api("POST", "/repos/" + repo + "/actions/runs/" + run.path("id").asLong() + "/rerun-failed-jobs", Gh.JSON.createObjectNode());
                n++;
            }
        }
        return n;
    }

    /** The prepare or release workflow's run since the merge (or since the prepare run): its progress and its end. */
    static void workflow(Gh gh, ObjectNode r, String which) throws IOException {
        String repo = r.path("repo").asText();
        String path = r.path(which + "Workflow").asText();
        String since = which.equals("release") && r.path("runs").path("prepare").has("createdAt")
                ? r.path("runs").path("prepare").path("createdAt").asText() : r.path("mergedAt").asText();
        Instant after = Instant.parse(since).minusSeconds(60);
        JsonNode found = null;
        for (JsonNode run : gh.get("/repos/" + repo + "/actions/runs?per_page=50&created=%3E%3D" + after.toString().substring(0, 19) + "Z").path("workflow_runs")) {
            if (run.path("path").asText().equals(path) && (found == null || run.path("id").asLong() > found.path("id").asLong())) found = run;
        }
        ObjectNode runs = r.with("runs");
        if (found == null) {
            if (Instant.parse(since).isBefore(Instant.now().minus(Duration.ofMinutes(30)))) {
                say(r, which, "stopped", "The " + (which.equals("prepare") ? "prepare" : "release") + " workflow (" + path
                        + ") hasn't started in 30 minutes.");
            }
            return;
        }
        runs.putObject(which).put("id", found.path("id").asLong()).put("url", found.path("html_url").asText())
                .put("status", found.path("status").asText()).put("conclusion", found.path("conclusion").asText(""))
                .put("createdAt", found.path("created_at").asText());
        if (!found.path("status").asText().equals("completed")) {
            say(r, which, "running", which.equals("prepare") ? "Preparing the release: tagging " + r.path("version").asText() + "."
                    : "Releasing " + r.path("version").asText() + ": building and deploying.");
            return;
        }
        if (!found.path("conclusion").asText().equals("success")) {
            say(r, which, "stopped", "The " + (which.equals("prepare") ? "prepare" : "release") + " workflow ended with "
                    + found.path("conclusion").asText() + ".");
            return;
        }
        if (which.equals("prepare") && r.has("releaseWorkflow")) say(r, "release", "running", "Tagged. Waiting for the release workflow.");
        else if (r.has("groupId")) say(r, "central", "running", "Released. Waiting for it on Maven Central.");
        else finished(r);
    }

    /** On Maven Central: the version's folder of the project's root artifact. */
    static void central(ObjectNode r) {
        String url = "https://repo1.maven.org/maven2/" + r.path("groupId").asText().replace('.', '/') + "/" + r.path("artifactId").asText()
                + "/" + r.path("version").asText() + "/";
        r.put("centralUrl", url);
        try {
            HttpResponse<Void> res = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                            .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.discarding());
            if (res.statusCode() == 200) {
                finished(r);
                return;
            }
        } catch (IOException e) {
            System.out.println("Couldn't reach Maven Central: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        String released = r.path("runs").path("release").path("createdAt").asText(r.path("mergedAt").asText());
        if (Instant.parse(released).isBefore(Instant.now().minus(Duration.ofHours(6)))) {
            say(r, "central", "stopped", "Released, but " + r.path("version").asText() + " isn't on Maven Central after 6 hours.");
        } else {
            say(r, "central", "running", "Released. Waiting for it on Maven Central (that can take half an hour).");
        }
    }

    static void finished(ObjectNode r) {
        r.put("doneAt", Instant.now().toString());
        say(r, "done", "done", "Released " + r.path("version").asText() + (r.has("centralUrl") ? ", and it's on Maven Central." : "."));
    }

    /** Old finished releases leave the tab. */
    static void prune() throws IOException {
        for (ObjectNode r : all()) {
            String end = r.path("doneAt").asText(r.path("updatedAt").asText(""));
            if (!active(r) && !end.isEmpty() && Instant.parse(end).isBefore(Instant.now().minus(KEEP))) Files.deleteIfExists(file(r.path("repo").asText()));
        }
    }

    static void sleep(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static ArrayNode list() throws IOException {
        ArrayNode out = Gh.JSON.createArrayNode();
        all().forEach(out::add);
        return out;
    }
}
