package com.beduno.config;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.OutputStreamAppender;
import ch.qos.logback.core.encoder.LayoutWrappingEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The prod appender hand-builds JSON with a Logback pattern, and the comment above it promises
 * lines "parseable by log aggregators". It was not: the pattern carried no {@code %ex}/{@code
 * %nopex}, so Logback appended every stack trace as raw multi-line text after the closing brace,
 * and it escaped only the double quote -- a backslash or a newline in a message broke the object.
 * Both failures land hardest on {@code log.error("Unhandled exception", ex)}, which is exactly the
 * line an incident has to be reconstructed from.
 *
 * <p>The pattern is read out of {@code logback-spring.xml} itself and the output is fed to a real
 * JSON parser: the question is whether an aggregator can read these lines, and nothing short of
 * parsing them answers it. Reading the shipped file also means the test cannot drift away from
 * what production actually runs.
 */
class ProdLogFormatTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ByteArrayOutputStream captured;
    private ch.qos.logback.classic.Logger logger;
    private OutputStreamAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws Exception {
        // The bound context, not a fresh one: a hand-built LoggerContext has no MDC adapter, so
        // every event fails to append with an NPE and nothing is written at all.
        var context = (LoggerContext) org.slf4j.LoggerFactory.getILoggerFactory();

        var layout = new PatternLayout();
        layout.setContext(context);
        layout.setPattern(prodPattern());
        layout.start();

        var encoder = new LayoutWrappingEncoder<ILoggingEvent>();
        encoder.setContext(context);
        encoder.setLayout(layout);
        encoder.start();

        captured = new ByteArrayOutputStream();
        appender = new OutputStreamAppender<>();
        appender.setContext(context);
        appender.setEncoder(encoder);
        appender.setOutputStream(captured);
        appender.start();

        logger = context.getLogger("com.beduno.ProdLogFormatProbe");
        // Not additive: the captured output must be this appender's alone, and the surrounding
        // suite's console appender must not gain these lines either.
        logger.setAdditive(false);
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void shouldEmitParseableJson_whenMessageIsOrdinary() throws Exception {
        MDC.put("requestId", "rid-1");
        MDC.put("userId", "uid-1");
        MDC.put("agencyId", "aid-1");

        logger.info("a plain message");

        var node = JSON.readTree(output());
        assertThat(node.get("msg").asText()).isEqualTo("a plain message");
        assertThat(node.get("rid").asText()).isEqualTo("rid-1");
        assertThat(node.get("uid").asText()).isEqualTo("uid-1");
        assertThat(node.get("aid").asText()).isEqualTo("aid-1");
        assertThat(node.get("level").asText()).startsWith("INFO");
    }

    @Test
    void shouldEmitParseableJson_whenMessageContainsQuotesAndBackslashes() throws Exception {
        // Escaping the quote alone left a trailing backslash that terminated the JSON string
        // early, so one Windows-style path in a message corrupted the whole record.
        logger.info("he said \"hi\" and a path C:\\temp\\x");

        var node = JSON.readTree(output());
        assertThat(node.get("msg").asText()).contains("\"hi\"").contains("C:\\temp\\x");
    }

    @Test
    void shouldStayOnOneLine_whenMessageContainsNewlines() throws Exception {
        logger.info("first line\nsecond line\ttabbed");

        assertThat(output().lines()).hasSize(1);
        assertThat(JSON.readTree(output()).get("msg").asText()).doesNotContain("\n");
    }

    /** The case the format exists for, and the one it used to fail outright. */
    @Test
    void shouldEmitParseableJson_whenAnExceptionIsLogged() throws Exception {
        logger.error("Unhandled exception", new IllegalStateException("boom"));

        assertThat(output().lines())
                .as("the stack trace must not be appended after the closing brace")
                .hasSize(1);
        var node = JSON.readTree(output());
        assertThat(node.get("msg").asText()).isEqualTo("Unhandled exception");
        assertThat(node.get("ex").asText())
                .contains("IllegalStateException")
                .contains("boom")
                .contains("ProdLogFormatTest");
    }

    @Test
    void shouldLeaveTheExceptionFieldEmpty_whenThereIsNoThrowable() throws Exception {
        logger.info("nothing went wrong");

        assertThat(JSON.readTree(output()).get("ex").asText()).isEmpty();
    }

    /** The prod pattern, taken from the file production actually loads. */
    private String prodPattern() throws Exception {
        String xml;
        try (var in = getClass().getResourceAsStream("/logback-spring.xml")) {
            xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        var start = xml.indexOf("{\"ts\":");
        assertThat(start).as("the prod JSON pattern must be present in logback-spring.xml").isPositive();
        return xml.substring(start, xml.indexOf("</pattern>", start));
    }

    private String output() {
        return captured.toString(StandardCharsets.UTF_8).strip();
    }
}
