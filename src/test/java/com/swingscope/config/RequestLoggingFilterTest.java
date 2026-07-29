package com.swingscope.config;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class RequestLoggingFilterTest {

    private final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void putsACorrelationIdInMdcForTheDurationOfTheRequestAndClearsItAfter() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/analyze");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideChain = new AtomicReference<>();

        FilterChain chain = (req, res) -> seenInsideChain.set(MDC.get(RequestLoggingFilter.REQUEST_ID));

        filter.doFilter(request, response, chain);

        assertThat(seenInsideChain.get()).isNotNull().hasSize(8);
        assertThat(MDC.get(RequestLoggingFilter.REQUEST_ID)).isNull();
    }

    @Test
    void clearsMdcEvenWhenTheChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/boom");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain exploding = (req, res) -> {
            throw new IOException("downstream failure");
        };

        assertThatIOException()
                .isThrownBy(() -> filter.doFilter(request, response, exploding))
                .withMessage("downstream failure");

        assertThat(MDC.get(RequestLoggingFilter.REQUEST_ID)).isNull();
    }
}
