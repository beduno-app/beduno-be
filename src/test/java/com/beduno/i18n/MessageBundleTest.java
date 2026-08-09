package com.beduno.i18n;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The bundles have drifted twice: DE/RU/UA were missing the export, status and
 * exception sections entirely, and seven error codes thrown by services were
 * defined in no bundle at all. Both failures are invisible at runtime because
 * {@code MessageSource} falls back to the key itself. These tests make the drift
 * fail the build instead.
 */
class MessageBundleTest {

    private static final Path I18N = Path.of("src/main/resources/i18n");
    private static final Path SOURCES = Path.of("src/main/java");
    private static final String REFERENCE = "messages_en.properties";

    /** Locale suffixes the application actually resolves. UA maps to the "uk" bundle. */
    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_en.properties",
            "messages_pl.properties",
            "messages_de.properties",
            "messages_ru.properties",
            "messages_uk.properties");

    private static final Pattern MESSAGE_CODE =
            Pattern.compile("\"((?:error|constraint)\\.[a-zA-Z_.]+)\"");

    @Nested
    class KeyParity {

        @Test
        void shouldDefineTheSameKeys_inEveryBundle() {
            var reference = keysOf(REFERENCE);
            assertThat(reference).isNotEmpty();

            for (var bundle : BUNDLES) {
                assertThat(keysOf(bundle))
                        .as("%s must define exactly the same keys as %s", bundle, REFERENCE)
                        .isEqualTo(reference);
            }
        }

        @Test
        void shouldNeverHaveABlankValue() {
            for (var bundle : BUNDLES) {
                var properties = load(bundle);
                for (var key : properties.stringPropertyNames()) {
                    assertThat(properties.getProperty(key).trim())
                            .as("%s in %s must not be blank", key, bundle)
                            .isNotEmpty();
                }
            }
        }

        @Test
        void shouldIncludeABundleForEveryLocaleTheCodeResolves() {
            // ExportService maps "UA" to Locale "uk", so the file must be messages_uk.
            for (var bundle : BUNDLES) {
                assertThat(I18N.resolve(bundle))
                        .as("bundle %s is referenced by the application but missing", bundle)
                        .exists();
            }
        }
    }

    @Nested
    class CodeCoverage {

        @Test
        void shouldDefineEveryMessageCodeReferencedFromJavaSources() {
            assumeTrue(Files.isDirectory(SOURCES), "run from the project root");

            var defined = keysOf(REFERENCE);
            var referenced = messageCodesInSources();

            assertThat(referenced)
                    .as("sanity: the scan must find message codes")
                    .isNotEmpty();
            assertThat(defined)
                    .as("every error/constraint code thrown in code must exist in %s, "
                            + "otherwise MessageSource silently returns the raw key", REFERENCE)
                    .containsAll(referenced);
        }
    }

    private static Set<String> messageCodesInSources() {
        try (Stream<Path> files = Files.walk(SOURCES)) {
            return files
                    .filter(path -> path.toString().endsWith(".java"))
                    .flatMap(MessageBundleTest::codesIn)
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Stream<String> codesIn(Path file) {
        try {
            var matcher = MESSAGE_CODE.matcher(Files.readString(file, StandardCharsets.UTF_8));
            return matcher.results().map(result -> result.group(1)).toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Set<String> keysOf(String bundle) {
        return new TreeSet<>(load(bundle).stringPropertyNames());
    }

    private static Properties load(String bundle) {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(I18N.resolve(bundle), StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + bundle, e);
        }
        return properties;
    }
}
