import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The repos you can release: in the accounts in its settings, ones you can push to (write, maintain or admin), not
 * archived or forks, with a .github/project.yml that has a release version. For each, its version, when that was
 * tagged, and how many commits its default branch has since.
 */
final class Repos {

    static final Pattern REPO = Pattern.compile("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9._-]{1,100}");
    static final Pattern CURRENT = Pattern.compile("(?m)^(\\s+current-version:\\s*)([\"']?)([^\"'\\s#]+)([\"']?)");
    static final Pattern NEXT = Pattern.compile("(?m)^(\\s+next-version:\\s*)([\"']?)([^\"'\\s#]+)([\"']?)");
    static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private Repos() {}

    static final String LIST = """
            query($login: String!, $after: String) {
              repositoryOwner(login: $login) {
                repositories(first: 50, after: $after, isFork: false, isArchived: false, ownerAffiliations: [OWNER]) {
                  pageInfo { hasNextPage endCursor }
                  nodes {
                    nameWithOwner url viewerPermission pushedAt
                    mergeCommitAllowed squashMergeAllowed rebaseMergeAllowed
                    defaultBranchRef { name }
                    project: object(expression: "HEAD:.github/project.yml") { ... on Blob { text } }
                  }
                }
              }
            }""";

    /** Every releasable repo in the accounts, with its version and how far its default branch is past it. */
    static ArrayNode list(Gh gh, List<String> accounts) throws IOException {
        List<ObjectNode> found = new ArrayList<>();
        for (String account : accounts) {
            String after = null;
            for (int page = 0; page < 20; page++) {
                Map<String, String> vars = new java.util.HashMap<>(Map.of("login", account));
                if (after != null) vars.put("after", after);
                JsonNode repos = gh.graphql(LIST, vars).path("repositoryOwner").path("repositories");
                if (repos.isMissingNode()) {
                    System.out.println("No GitHub account or organization " + account + ": check Releases' settings.");
                    break;
                }
                for (JsonNode r : repos.path("nodes")) {
                    ObjectNode one = repo(r);
                    if (one != null) found.add(one);
                }
                if (!repos.path("pageInfo").path("hasNextPage").asBoolean()) break;
                after = repos.path("pageInfo").path("endCursor").asText();
            }
        }
        tags(gh, found);
        ArrayNode out = Gh.JSON.createArrayNode();
        found.forEach(out::add);
        return out;
    }

    /** A repo it can release, or null. */
    static ObjectNode repo(JsonNode r) {
        String name = r.path("nameWithOwner").asText();
        if (!REPO.matcher(name).matches() || !List.of("ADMIN", "MAINTAIN", "WRITE").contains(r.path("viewerPermission").asText())) return null;
        String text = r.path("project").path("text").asText(null);
        if (text == null) return null;
        Matcher m = CURRENT.matcher(text);
        if (!m.find()) return null;
        String version = m.group(3);
        ObjectNode o = Gh.JSON.createObjectNode().put("repo", name).put("url", r.path("url").asText())
                .put("branch", r.path("defaultBranchRef").path("name").asText("main")).put("version", version)
                .put("pushedAt", r.path("pushedAt").asText(""));
        Matcher n = NEXT.matcher(text);
        if (n.find()) o.put("nextVersion", n.group(3));
        ObjectNode next = nextVersions(version);
        if (next != null) o.set("next", next);
        return o;
    }

    /** patch, minor and major after a plain x.y.z version, or null (a qualifier like .Final: release that by hand). */
    static ObjectNode nextVersions(String version) {
        Matcher v = VERSION.matcher(version);
        if (!v.matches()) return null;
        int a = Integer.parseInt(v.group(1)), b = Integer.parseInt(v.group(2)), c = Integer.parseInt(v.group(3));
        return Gh.JSON.createObjectNode().put("patch", a + "." + b + "." + (c + 1)).put("minor", a + "." + (b + 1) + ".0")
                .put("major", (a + 1) + ".0.0");
    }

    /** The snapshot after a release: its next patch. */
    static String snapshotAfter(String version) {
        Matcher v = VERSION.matcher(version);
        if (!v.matches()) throw new IllegalArgumentException("Not a plain version: " + version);
        return v.group(1) + "." + v.group(2) + "." + (Integer.parseInt(v.group(3)) + 1) + "-SNAPSHOT";
    }

    /** project.yml with the release's versions in place of the old ones, the rest as it was. */
    static String edit(String text, String version, String next) {
        Matcher m = CURRENT.matcher(text);
        if (!m.find()) throw new IllegalStateException("project.yml has no current-version");
        String out = text.substring(0, m.start()) + m.group(1) + m.group(2) + version + m.group(4) + text.substring(m.end());
        Matcher n = NEXT.matcher(out);
        if (n.find()) out = out.substring(0, n.start()) + n.group(1) + n.group(2) + next + n.group(4) + out.substring(n.end());
        return out;
    }

    static final String TAG = "{ name target { __typename ... on Commit { committedDate } ... on Tag { target { ... on Commit { committedDate } } } } }";

    /** The tag of each repo's version (x.y.z or vx.y.z): when it was made, and the commits since on the default branch. */
    static void tags(Gh gh, List<ObjectNode> repos) {
        for (int from = 0; from < repos.size(); from += 25) {
            List<ObjectNode> batch = repos.subList(from, Math.min(repos.size(), from + 25));
            StringBuilder q = new StringBuilder("query {");
            for (int i = 0; i < batch.size(); i++) {
                ObjectNode r = batch.get(i);
                String[] on = r.path("repo").asText().split("/");
                String v = r.path("version").asText(), branch = r.path("branch").asText();
                if (!v.matches("[A-Za-z0-9._-]{1,60}") || !branch.matches("[A-Za-z0-9._/-]{1,100}")) continue;
                q.append(" r").append(i).append(": repository(owner: \"").append(on[0]).append("\", name: \"").append(on[1]).append("\") {");
                for (String t : List.of(v, "v" + v)) {
                    String alias = t.equals(v) ? "plain" : "prefixed";
                    q.append(' ').append(alias).append(": ref(qualifiedName: \"refs/tags/").append(t).append("\") { name target { ")
                            .append("__typename ... on Commit { committedDate } ... on Tag { target { ... on Commit { committedDate } } } } ")
                            .append("compare(headRef: \"").append(branch).append("\") { aheadBy } }");
                }
                q.append(" }");
            }
            q.append(" }");
            try {
                JsonNode data = gh.graphql(q.toString(), Map.of());
                for (int i = 0; i < batch.size(); i++) {
                    JsonNode r = data.path("r" + i);
                    JsonNode ref = r.path("plain").isObject() ? r.path("plain") : r.path("prefixed");
                    if (!ref.isObject()) continue;
                    ObjectNode o = batch.get(i);
                    o.put("tag", ref.path("name").asText());
                    String at = ref.path("target").path("committedDate").asText(ref.path("target").path("target").path("committedDate").asText(""));
                    if (!at.isEmpty()) o.put("releasedAt", at);
                    if (ref.path("compare").path("aheadBy").isInt()) o.put("since", ref.path("compare").path("aheadBy").asInt());
                }
            } catch (IOException e) {
                System.out.println("Couldn't read the tags of some repos: " + e.getMessage());
            }
        }
    }
}
