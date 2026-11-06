import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ~/.config/release/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml. */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "release");
    static final Path FILE = DIR.resolve("config");
    static final String LOGIN = "[A-Za-z0-9][A-Za-z0-9-]{0,38}";

    /** The gh account it reads and releases as. */
    String githubLogin;
    /** The GitHub accounts and organizations whose repos it lists (yours, smallrye, quarkiverse...). */
    List<String> accounts;
    /** Re-run CI's failed jobs once when a release PR's CI fails, before stopping. */
    boolean retryCi;

    static Config load() throws IOException {
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        Config c = new Config();
        c.githubLogin = raw.getOrDefault("GITHUB_LOGIN", "");
        if (!c.githubLogin.matches(LOGIN)) throw new IllegalStateException("Set your GitHub account in Releases' settings on the desk");
        c.accounts = Arrays.stream(raw.getOrDefault("ACCOUNTS", c.githubLogin).split(",")).map(String::strip)
                .filter(a -> a.matches(LOGIN)).distinct().limit(20).toList();
        if (c.accounts.isEmpty()) c.accounts = List.of(c.githubLogin);
        c.retryCi = Boolean.parseBoolean(raw.getOrDefault("RETRY_CI", "true"));
        return c;
    }
}
