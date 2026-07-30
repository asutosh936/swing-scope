package com.swingscope.web.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards a CSS cascade bug that shipped once and was invisible to every HTML-level test.
 *
 * <p>{@code .journal-table a} sets the generic link colour to the accent blue. Its specificity
 * (0,1,1) beats {@code .button-link} (0,1,0), so a "Plan this trade" button inside the scan table
 * rendered with accent-blue text on its own accent-blue background — the label was present in the
 * markup and completely unreadable on screen.
 */
class ScanStylesheetTest {

    private static final Path STYLESHEET = Path.of("src/main/resources/static/css/app.css");

    private static String css() throws IOException {
        return Files.readString(STYLESHEET);
    }

    @Test
    @DisplayName("a button inside a table keeps its dark ink, not the generic link colour")
    void buttonLinksInsideTablesOverrideTheGenericLinkColour() throws IOException {
        String css = css();

        int genericLinkRule = css.indexOf(".journal-table a {");
        int buttonOverride = css.indexOf(".journal-table a.button-link");

        assertThat(genericLinkRule)
                .as("the generic table-link rule should still exist")
                .isGreaterThan(-1);
        assertThat(buttonOverride)
                .as("the button override must exist, or table buttons render blue-on-blue")
                .isGreaterThan(-1);
        assertThat(css.substring(buttonOverride, css.indexOf('}', buttonOverride)))
                .as("the override must set a colour that contrasts with the accent background")
                .contains("#0a1220");
    }

    @Test
    void theButtonBackgroundIsStillTheAccentColour() throws IOException {
        String css = css();
        int rule = css.indexOf(".button-link {");

        assertThat(rule).isGreaterThan(-1);
        assertThat(css.substring(rule, css.indexOf('}', rule)))
                .contains("background: var(--accent)")
                .contains("color: #0a1220");
    }
}
