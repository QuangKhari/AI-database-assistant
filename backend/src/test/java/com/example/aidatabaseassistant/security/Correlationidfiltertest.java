package com.example.aidatabaseassistant.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test truc tiep CorrelationIdFilter (khong can load full Spring context)
 * de tra loi dut khoat: MDC co THAT SU duoc set trong luc xu ly request
 * hay khong - khong doan mo bang mat code nua.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void cleanupMdc() {
        // Don dep MDC sau moi test, tranh anh huong test khac chay cung JVM.
        MDC.remove(CorrelationIdFilter.MDC_KEY);
    }

    @Test
    void doFilter_shouldPutCorrelationIdIntoMdc_duringRequestProcessing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcValueSeenInsideChain = new AtomicReference<>();

        FilterChain chain = (req, res) -> {
            // Doc MDC NGAY TRONG LUC dang xu ly request - day chinh la thoi
            // diem log ben trong service/controller se doc duoc gia tri nay.
            mdcValueSeenInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        };

        filter.doFilter(request, response, chain);

        assertNotNull(mdcValueSeenInsideChain.get(),
                "MDC phải có giá trị correlationId trong lúc xử lý request");
        assertFalse(mdcValueSeenInsideChain.get().isBlank());
    }

    @Test
    void doFilter_shouldRemoveMdc_afterRequestFinishes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> { /* khong lam gi */ };

        filter.doFilter(request, response, chain);

        // Sau khi doFilter tra ve (request da xu ly xong), MDC phai duoc
        // don dep - neu khong, request tiep theo tren CUNG 1 thread (thread
        // pool tai su dung thread) se bi "dinh" nham correlationId cua
        // request truoc do.
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void doFilter_shouldReuseClientProvidedHeader_insteadOfGeneratingNew() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CorrelationIdFilter.HEADER_NAME, "abc-trace-id-tu-frontend");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<String> mdcValueSeenInsideChain = new AtomicReference<>();
        FilterChain chain = (req, res) ->
                mdcValueSeenInsideChain.set(MDC.get(CorrelationIdFilter.MDC_KEY));

        filter.doFilter(request, response, chain);

        assertEquals("abc-trace-id-tu-frontend", mdcValueSeenInsideChain.get());
        assertEquals("abc-trace-id-tu-frontend", response.getHeader(CorrelationIdFilter.HEADER_NAME));
    }

    @Test
    void doFilter_shouldGenerateNewId_whenNoHeaderProvided() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> { /* khong lam gi */ };

        filter.doFilter(request, response, chain);

        // Response phai co header tra ve, dung dinh dang UUID (36 ky tu,
        // co dau gach ngang) de client/frontend co the doi chieu.
        String responseHeader = response.getHeader(CorrelationIdFilter.HEADER_NAME);
        assertNotNull(responseHeader);
        assertEquals(36, responseHeader.length());
    }
}