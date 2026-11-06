///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//DEPS info.picocli:picocli:4.7.6
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.2
//SOURCES Config.java
//SOURCES Gh.java
//SOURCES Repos.java
//SOURCES Flow.java

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * release: lists the repos you can release (a .github/project.yml, in the accounts in its settings) with their
 * versions, and releases one when you click patch, minor or major and confirm: a PR from a branch on the repo that sets the
 * version, merged when CI is green, which starts the repo's own release workflows. It follows each release to Maven
 * Central and says how far it got.
 */
@Command(name = "release", mixinStandardHelpOptions = true, version = "0.2.0", description = "Releases your GitHub repos from Elf Desk")
public class Release implements Callable<Integer> {

    static final Path BOARD = Config.DIR.resolve("board").resolve("board.json");
    static final Path LOCK = Config.DIR.resolve(".lock");
    /** How often a follow looks again, and for how long at most. */
    static final Duration EVERY = Duration.ofSeconds(30);
    static final Duration AT_MOST = Duration.ofHours(6);

    @Option(names = "--board", description = "Read the repos you can release, and move releases on, for the tab")
    boolean board;

    @Option(names = "--follow", description = "Follow the releases in progress until they're done")
    boolean follow;

    @Option(names = "--action", paramLabel = "NAME", description = "For its tab: release, retry, stop, dismiss (values as JSON on stdin)")
    String action;

    public static void main(String[] args) {
        System.exit(new CommandLine(new Release()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        Config config;
        Gh gh;
        try {
            config = Config.load();
            gh = new Gh(config.githubLogin);
        } catch (IllegalStateException e) {
            System.err.println(e.getMessage());
            return 1;
        }
        if (action != null) return action(config, gh);
        if (follow) return follow(config, gh);
        System.out.println("Reading the repos you can release in " + String.join(", ", config.accounts) + "...");
        ArrayNode repos = Repos.list(gh, config.accounts);
        System.out.println("Found " + repos.size() + " with a .github/project.yml you can release.");
        if (!board) {
            for (JsonNode r : repos) {
                System.out.println("  " + r.path("repo").asText() + " " + r.path("version").asText()
                        + (r.has("since") ? ", " + r.path("since").asInt() + " commits since" : ""));
            }
            return 0;
        }
        ObjectNode out = Gh.JSON.createObjectNode().put("at", Instant.now().toString()).put("as", config.githubLogin);
        out.set("accounts", Gh.JSON.valueToTree(config.accounts));
        out.set("repos", repos);
        write(BOARD, out);
        // Releases in progress move on with each refresh too, in case nothing follows them.
        try (FileLock lock = lock()) {
            for (ObjectNode r : Flow.all()) step(gh, config, r);
            Flow.prune();
        }
        return 0;
    }

    /** Follows every release in progress, every 30 seconds, until none is left (or six hours went by). */
    int follow(Config config, Gh gh) throws IOException {
        Instant until = Instant.now().plus(AT_MOST);
        while (Instant.now().isBefore(until)) {
            boolean any = false;
            try (FileLock lock = lock()) {
                for (ObjectNode r : Flow.all()) {
                    step(gh, config, r);
                    any |= Flow.active(r);
                }
            }
            if (!any) {
                System.out.println("No release in progress.");
                return 0;
            }
            Flow.sleep((int) EVERY.toSeconds());
        }
        System.out.println("Still in progress after six hours: the next refresh carries on.");
        return 0;
    }

    /** One step of one release; a failure to read GitHub is said, and tried again next time. */
    static void step(Gh gh, Config config, ObjectNode r) throws IOException {
        if (!Flow.active(r)) return;
        try {
            Flow.advance(gh, config, r);
            r.remove("problem");
        } catch (IOException | RuntimeException e) {
            r.put("problem", "Couldn't check on it: " + e.getMessage());
            System.out.println(r.path("repo").asText() + ": " + e.getMessage());
        }
        Flow.write(r);
    }

    int action(Config config, Gh gh) throws IOException {
        JsonNode in = Gh.JSON.readTree(new String(System.in.readAllBytes(), StandardCharsets.UTF_8));
        String repo = in.path("repo").asText("");
        try (FileLock lock = lock()) {
            ObjectNode answer = switch (action) {
                case "release" -> {
                    if (!in.path("as").asText("").equalsIgnoreCase(config.githubLogin)) throw new IllegalArgumentException("Not the account in Releases' settings");
                    yield Flow.start(gh, repo, in.path("kind").asText(""), in.path("version").asText(""));
                }
                case "retry" -> retry(gh, config, repo);
                case "stop" -> stop(gh, repo);
                case "dismiss" -> {
                    ObjectNode r = Flow.read(repo);
                    if (r != null && Flow.active(r)) throw new IllegalStateException("It's still in progress: stop it first");
                    Files.deleteIfExists(Flow.file(repo));
                    yield Gh.JSON.createObjectNode().put("dismissed", repo);
                }
                default -> throw new IllegalArgumentException("Not one of release's actions: " + action);
            };
            System.out.println(Gh.JSON.writeValueAsString(answer));
            return 0;
        } catch (IOException | RuntimeException e) {
            System.err.println(e.getMessage());
            return 1;
        }
    }

    /** Try again where it stopped: CI's failed jobs run again, a workflow is looked for again. */
    static ObjectNode retry(Gh gh, Config config, String repo) throws IOException {
        ObjectNode r = Flow.read(repo);
        if (r == null || !r.path("state").asText().equals("stopped")) throw new IllegalStateException("Nothing stopped to try again");
        if (r.path("stage").asText().equals("ci")) {
            String sha = r.path("pr").path("headSha").asText("");
            if (Flow.SHA.matcher(sha).matches()) Flow.rerun(gh, repo, sha);
            r.put("ciRetried", true);
        } else if (List.of("prepare", "release").contains(r.path("stage").asText())) {
            // Look for a run from now on: one you re-ran on GitHub, say.
            r.with("runs").remove(r.path("stage").asText());
            if (r.path("stage").asText().equals("prepare")) r.put("mergedAt", Instant.now().toString());
            else r.with("runs").with("prepare").put("createdAt", Instant.now().toString());
        }
        Flow.say(r, r.path("stage").asText(), "running", "Trying again.");
        Flow.write(r);
        return r;
    }

    /** Stops a release before it's merged: its PR is closed, and nothing is released. */
    static ObjectNode stop(Gh gh, String repo) throws IOException {
        ObjectNode r = Flow.read(repo);
        if (r == null || !Flow.active(r)) throw new IllegalStateException("No release of " + repo + " is in progress");
        if (!r.path("stage").asText().equals("ci")) throw new IllegalStateException("It's merged already: the release workflows run on GitHub now");
        gh.api("PATCH", "/repos/" + repo + "/pulls/" + r.path("pr").path("number").asInt(), Gh.JSON.createObjectNode().put("state", "closed"));
        Flow.dropBranch(gh, repo, r);
        Flow.say(r, "ci", "stopped", "You stopped it: the PR is closed, and nothing was released.");
        r.put("doneAt", Instant.now().toString());
        Flow.write(r);
        return r;
    }

    static FileLock lock() throws IOException {
        Files.createDirectories(Config.DIR);
        return new RandomAccessFile(LOCK.toFile(), "rw").getChannel().lock();
    }

    static void write(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling("." + file.getFileName() + ".tmp");
        Files.writeString(tmp, Gh.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value));
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
