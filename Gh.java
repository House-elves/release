import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * GitHub, through gh as the account in its settings: its token is read fresh from gh for each run and handed to gh in
 * its environment, never written down.
 */
final class Gh {

    static final ObjectMapper JSON = new ObjectMapper();

    final String login;
    private final String token;

    Gh(String login) throws IOException {
        this.login = login;
        String t = run(List.of("gh", "auth", "token", "-u", login), null, null).out().strip();
        if (t.isEmpty() || t.contains(" ")) throw new IllegalStateException("Log gh in as " + login + " (gh auth login), or change the account in Releases' settings");
        this.token = t;
    }

    record Result(int code, String out, String err) {}

    /** gh api: the JSON GitHub answered, or an IOException with what it said. */
    JsonNode api(String method, String path, JsonNode body) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("gh", "api", "-X", method, path, "-H", "Accept: application/vnd.github+json"));
        if (body != null) cmd.addAll(List.of("--input", "-"));
        Result r = run(cmd, token, body == null ? null : JSON.writeValueAsString(body));
        if (r.code() != 0) throw new IOException(message(r));
        return r.out().isBlank() ? JSON.createObjectNode() : JSON.readTree(r.out());
    }

    JsonNode get(String path) throws IOException {
        return api("GET", path, null);
    }

    /** GET, or null when GitHub says there's no such thing. */
    JsonNode find(String path) throws IOException {
        try {
            return get(path);
        } catch (IOException e) {
            if (e.getMessage().contains("404") || e.getMessage().contains("Not Found")) return null;
            throw e;
        }
    }

    JsonNode graphql(String query, java.util.Map<String, String> vars) throws IOException {
        List<String> cmd = new ArrayList<>(List.of("gh", "api", "graphql", "-f", "query=" + query));
        vars.forEach((k, v) -> cmd.addAll(List.of("-F", k + "=" + v)));
        Result r = run(cmd, token, null);
        JsonNode res = r.out().isBlank() ? null : JSON.readTree(r.out());
        if (res == null || (r.code() != 0 && res.path("data").isMissingNode())) throw new IOException(message(r));
        return res.path("data");
    }

    /** What GitHub said, in a line: its message, not gh's usage. */
    static String message(Result r) {
        try {
            JsonNode j = JSON.readTree(r.out());
            if (j.path("message").isTextual()) return j.path("message").asText() + (r.err().contains("HTTP") ? " (" + r.err().strip().lines().reduce((a, b) -> b).orElse("") + ")" : "");
            if (j.path("errors").isArray() && !j.path("errors").isEmpty()) return j.path("errors").path(0).path("message").asText("GitHub refused it");
        } catch (Exception ignored) {
            // not JSON
        }
        String s = (r.err() + " " + r.out()).strip();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    static Result run(List<String> cmd, String token, String stdin) throws IOException {
        Path out = Files.createTempFile("release-", ".out"), err = Files.createTempFile("release-", ".err");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectOutput(out.toFile()).redirectError(err.toFile());
            if (token != null) pb.environment().put("GH_TOKEN", token);
            if (stdin == null) pb.redirectInput(ProcessBuilder.Redirect.from(new java.io.File("/dev/null")));
            Process p = pb.start();
            if (stdin != null) {
                try (var in = p.getOutputStream()) {
                    in.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!p.waitFor(90, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("GitHub took too long to answer");
            }
            return new Result(p.exitValue(), Files.readString(out), Files.readString(err));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted");
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }
}
