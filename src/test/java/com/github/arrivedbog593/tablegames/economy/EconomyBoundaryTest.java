package com.github.arrivedbog593.tablegames.economy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the economy movable.
 * <p>
 * The economy is on its way out of this mod into one of its own, which
 * TableGames and other mods will depend on. That only works if nothing in
 * it reaches back into the games: a single import of a table or a house
 * class and the economy can no longer be built without the casino. So this
 * reads the source rather than trusting anybody to remember, and fails on
 * the first line that crosses.
 * <p>
 * Comments count. A {@code {@link}} to a casino class is a dependency too:
 * it is the line that stops resolving the day the package moves.
 */
class EconomyBoundaryTest {

    private static final Path SOURCES =
            Path.of("src/main/java/com/github/arrivedbog593/tablegames/economy");

    /** Any reference into this mod that does not stay inside the economy. */
    private static final Pattern OUTSIDE =
            Pattern.compile("com\\.github\\.arrivedbog593\\.tablegames\\.(?!economy\\.)[\\w.]+");

    /** Minecraft, NeoForge and Mojang: what the engine half must stay free of. */
    private static final Pattern GAME_LIBRARIES =
            Pattern.compile("\\b(?:net\\.minecraft|net\\.neoforged|com\\.mojang)\\.[\\w.]+");

    @Test
    void nothingInTheEconomyReachesIntoTheGames() throws IOException {
        List<String> crossings = scan(SOURCES, OUTSIDE);
        assertTrue(crossings.isEmpty(),
                "The economy must not depend on the rest of TableGames:\n  "
                        + String.join("\n  ", crossings));
    }

    @Test
    void theEconomyEngineIsPlainJava() throws IOException {
        List<String> crossings = scan(SOURCES.resolve("engine"), GAME_LIBRARIES);
        assertTrue(crossings.isEmpty(),
                "economy.engine is tested without the game, so it must not use it:\n  "
                        + String.join("\n  ", crossings));
    }

    @Test
    void theScanSeesTheSources() throws IOException {
        // A wrong path would find no files and pass every check above.
        try (Stream<Path> files = Files.walk(SOURCES)) {
            assertTrue(files.anyMatch(path -> path.toString().endsWith("CreditStorage.java")),
                    "Expected to find the economy's sources under " + SOURCES.toAbsolutePath());
        }
    }

    private static List<String> scan(Path root, Pattern forbidden) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher matcher = forbidden.matcher(lines.get(i));
                    while (matcher.find()) {
                        found.add(SOURCES.relativize(file) + ":" + (i + 1) + "  " + matcher.group());
                    }
                }
            }
        }
        return found;
    }
}
