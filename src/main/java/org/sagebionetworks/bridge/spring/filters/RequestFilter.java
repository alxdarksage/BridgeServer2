package org.sagebionetworks.bridge.spring.filters;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.BridgeConstants.BRIDGE_API_STATUS_HEADER;
import static org.sagebionetworks.bridge.BridgeConstants.WARN_NO_ACCEPT_LANGUAGE;
import static org.sagebionetworks.bridge.BridgeConstants.WARN_NO_USER_AGENT;
import static org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE;
import static org.springframework.http.HttpHeaders.USER_AGENT;

import java.io.IOException;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Vector;
import java.util.Locale.LanguageRange;
import java.util.stream.Collectors;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;

import com.google.common.collect.ImmutableList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeConstants;
import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.models.ClientInfo;

@Component
public class RequestFilter implements Filter {
    private final static Logger LOG = LoggerFactory.getLogger(RequestFilter.class);

    private static class RequestIdWrapper extends HttpServletRequestWrapper {
        private final String requestId;
        RequestIdWrapper(HttpServletRequest request, String requestId) {
            super(request);
            this.requestId = requestId;
        }
        @Override
        public String getHeader(String name) {
            if (BridgeConstants.X_REQUEST_ID_HEADER.equalsIgnoreCase(name)) {
                return requestId;
            }
            return super.getHeader(name);
        }
        @Override
        public Enumeration<String> getHeaderNames() {
            Vector<String> vector = new Vector<>();
            Enumeration<String> headerNames = super.getHeaderNames();
            while (headerNames.hasMoreElements()) {
                vector.add(headerNames.nextElement());
            }
            if (!vector.contains(BridgeConstants.X_REQUEST_ID_HEADER)) {
                vector.add(BridgeConstants.X_REQUEST_ID_HEADER);    
            }
            return vector.elements();
        }
    }
    
    @Override
    public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
            throws IOException, ServletException {
        // Set request ID in a request-scoped context object. This object will be replaced 
        // with further user-specific security information, if the controller method that 
        // was intercepted retrieves the user's session (this code is consolidated in the 
        // BaseController). For unauthenticated/public requests, we do *not* want a 
        // Bridge-Session header changing the security context of the call.
        
        HttpServletRequest request = (HttpServletRequest)req;
        HttpServletResponse response = (HttpServletResponse)res;
        String requestId = request.getHeader(BridgeConstants.X_REQUEST_ID_HEADER);
        if (requestId == null) {
            requestId = generateRequestId();
        }
        RequestContext.Builder builder = new RequestContext.Builder()
                .withCallerLanguages(getLanguagesFromAcceptLanguageHeader(request, response))
                .withCallerClientInfo(getClientInfoFromUserAgentHeader(request, response))
                .withRequestId(requestId);
        setRequestContext(builder.build());

        req = new RequestIdWrapper((HttpServletRequest)req, requestId);
        try {
            chain.doFilter(req, res);
        } finally {
            // Clear request context when finished.
            setRequestContext(null);
        }
    }
    
    // Isolated for testing
    protected String generateRequestId() {
        return BridgeUtils.generateGuid();
    }
    
    // Isolated for testing
    protected void setRequestContext(RequestContext context) {
        BridgeUtils.setRequestContext(context);
    }

    @Override
    public void init(FilterConfig filterConfig) {
        // no-op
    }

    @Override
    public void destroy() {
        // no-op
    }
    
    
    /**
     * Returns languages in the order of their quality rating in the original LanguageRange objects 
     * that are created from the Accept-Language header (first item in ordered set is the most-preferred 
     * language option).
     * @return
     */
    List<String> getLanguagesFromAcceptLanguageHeader(HttpServletRequest request, HttpServletResponse response) {
        String acceptLanguageHeader = request.getHeader(ACCEPT_LANGUAGE);
        if (isNotBlank(acceptLanguageHeader)) {
            try {
                List<LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguageHeader);
                LinkedHashSet<String> languageSet = ranges.stream().map(range -> {
                    return Locale.forLanguageTag(range.getRange()).getLanguage();
                }).collect(Collectors.toCollection(LinkedHashSet::new));
                return ImmutableList.copyOf(languageSet);
            } catch(IllegalArgumentException e) {
                // Accept-Language header was not properly formatted, do not throw an exception over 
                // a malformed header, just return that no languages were found.
                LOG.debug("Malformed Accept-Language header sent: " + acceptLanguageHeader);
            }
        }

        // if no Accept-Language header detected, we shall add an extra warning header
        addWarningMessage(response, WARN_NO_ACCEPT_LANGUAGE);
        return ImmutableList.of();
    }
    
    ClientInfo getClientInfoFromUserAgentHeader(HttpServletRequest request, HttpServletResponse response) {
        String userAgentHeader = request.getHeader(USER_AGENT);
        ClientInfo info = ClientInfo.fromUserAgentCache(userAgentHeader);

        // if the user agent cannot be parsed (probably due to missing user agent string or unrecognizable user agent),
        // should set an extra header to http response as warning - we should have an user agent info for filtering to work
        if (info.equals(ClientInfo.UNKNOWN_CLIENT)) {
            addWarningMessage(response, WARN_NO_USER_AGENT);
        }
        LOG.debug("User-Agent: '"+userAgentHeader+"' converted to " + info);    
        return info;
    }
    /**
     * Helper method to add warning message as an HTTP header.
     * @param msg
     */
    void addWarningMessage(HttpServletResponse response, String msg) {
        if (response.getHeaderNames().contains(BRIDGE_API_STATUS_HEADER)) {
            String previousWarning = response.getHeader(BRIDGE_API_STATUS_HEADER);
            response.setHeader(BRIDGE_API_STATUS_HEADER, previousWarning + "; " + msg);
        } else {
            response.setHeader(BRIDGE_API_STATUS_HEADER, msg);
        }
    }
}
