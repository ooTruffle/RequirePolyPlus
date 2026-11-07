package dev.requirepolyplus;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;

/** Settings stored in config/require-polyplus.properties. Changed with /rpp or by editing the file. */
final class Config {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("require-polyplus.properties");

    /** Kick players who don't have Poly+. */
    boolean kickEnabled = true;
    int timeoutSeconds = 10;
    String kickMessage = "You need the Poly+ mod to play on this server.\nGet it at modrinth.com/mod/polyplus";
    /** Chat message shown to everyone when a player is kicked for not having Poly+. Empty turns it off. */
    String kickBroadcast = "&e%player% was kicked for using %client% instead of Poly+";
    /** Lowest Poly+ version allowed in, like "1.4.0". Empty allows any version. */
    String minVersion = "";
    String outdatedMessage = "Your Poly+ (%version%) is too old.\nPlease update to %min% or newer.";
    /** Scoreboard tag given to Poly+ players while they're online, e.g. for @a[tag=polyplus]. Empty turns it off. */
    String tag = "polyplus";
    /** Chat message sent to Poly+ players when they join. Empty turns it off. */
    String welcomeMessage = "";
    /** Lowercase usernames and UUIDs allowed in without Poly+. */
    final Set<String> exempt = new LinkedHashSet<>();

    private final Logger logger;

    private Config(Logger logger) {
        this.logger = logger;
    }

    static Config load(Logger logger) {
        Config config = new Config(logger);
        Properties props = new Properties();

        if (Files.exists(FILE)) {
            try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
                props.load(reader);
            } catch (IOException e) {
                logger.error("Could not read {}, using defaults", FILE, e);
            }
        }

        config.kickEnabled = Boolean.parseBoolean(props.getProperty("require-polyplus", String.valueOf(config.kickEnabled)).trim());
        try {
            String raw = props.getProperty("timeout-seconds", String.valueOf(config.timeoutSeconds)).trim();
            config.timeoutSeconds = Math.max(1, Integer.parseInt(raw));
        } catch (NumberFormatException e) {
            logger.warn("timeout-seconds is not a number, using {}", config.timeoutSeconds);
        }
        config.kickMessage = props.getProperty("kick-message", config.kickMessage);
        config.kickBroadcast = props.getProperty("kick-broadcast", config.kickBroadcast);
        config.minVersion = props.getProperty("min-version", config.minVersion).trim();
        config.outdatedMessage = props.getProperty("outdated-message", config.outdatedMessage);
        config.tag = props.getProperty("tag", config.tag).trim();
        config.welcomeMessage = props.getProperty("welcome-message", config.welcomeMessage);
        for (String entry : props.getProperty("exempt", "").split(",")) {
            if (!entry.isBlank()) {
                config.exempt.add(entry.trim().toLowerCase(Locale.ROOT));
            }
        }

        config.save(); // so every option shows up in the file
        return config;
    }

    void save() {
        Properties props = new Properties();
        props.setProperty("require-polyplus", String.valueOf(kickEnabled));
        props.setProperty("timeout-seconds", String.valueOf(timeoutSeconds));
        props.setProperty("kick-message", kickMessage);
        props.setProperty("kick-broadcast", kickBroadcast);
        props.setProperty("min-version", minVersion);
        props.setProperty("outdated-message", outdatedMessage);
        props.setProperty("tag", tag);
        props.setProperty("welcome-message", welcomeMessage);
        props.setProperty("exempt", String.join(",", exempt));
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                props.store(writer, String.join("\n",
                    " Require Poly+ (all of these can also be changed in game with /rpp)",
                    " require-polyplus: kick players who don't have Poly+ (true/false)",
                    " timeout-seconds:  how long a player has to identify as Poly+ before being kicked",
                    " kick-message:     shown to players kicked for not having Poly+ (\\n makes a new line)",
                    " kick-broadcast:   chat message to everyone when someone is kicked (%player% and %client% are filled in, empty turns it off)",
                    " min-version:      lowest Poly+ version allowed, like 1.4.0 (empty allows any)",
                    " outdated-message: shown to players kicked for an old Poly+ (%version% and %min% are filled in)",
                    " tag:              scoreboard tag given to Poly+ players, for @a[tag=...] (empty turns it off)",
                    " welcome-message:  chat message sent to Poly+ players when they join (empty turns it off)",
                    " exempt:           comma-separated usernames or UUIDs allowed in without Poly+"));
            }
        } catch (IOException e) {
            logger.error("Could not write {}", FILE, e);
        }
    }

    /** Turns a typed "\n" into a new line and &amp; colour codes (&amp;a, &amp;l, ...) into Minecraft formatting. */
    static String format(String message) {
        return message.replace("\\n", "\n").replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    /** True if {@code version} is at least {@link #minVersion}. Compares numbers part by part, so 1.10 > 1.9. */
    boolean meetsMinVersion(String version) {
        if (minVersion.isEmpty()) {
            return true;
        }
        String[] have = version.split("[.+\\-]");
        String[] need = minVersion.split("[.+\\-]");
        for (int i = 0; i < need.length; i++) {
            int h = i < have.length ? leadingNumber(have[i]) : 0;
            int n = leadingNumber(need[i]);
            if (h != n) {
                return h > n;
            }
        }
        return true;
    }

    private static int leadingNumber(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        try {
            return end == 0 ? 0 : Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }
}
