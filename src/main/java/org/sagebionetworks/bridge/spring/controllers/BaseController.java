package org.sagebionetworks.bridge.spring.controllers;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.sagebionetworks.bridge.BridgeConstants.BRIDGE_API_STATUS_HEADER;
import static org.sagebionetworks.bridge.BridgeConstants.BRIDGE_SESSION_EXPIRE_IN_SECONDS;
import static org.sagebionetworks.bridge.BridgeConstants.SESSION_TOKEN_HEADER;
import static org.sagebionetworks.bridge.BridgeConstants.WARN_NO_ACCEPT_LANGUAGE;
import static org.sagebionetworks.bridge.BridgeConstants.WARN_NO_USER_AGENT;
import static org.sagebionetworks.bridge.BridgeConstants.X_FORWARDED_FOR_HEADER;
import static org.sagebionetworks.bridge.BridgeConstants.X_REQUEST_ID_HEADER;
import static org.springframework.http.HttpHeaders.ACCEPT_LANGUAGE;
import static org.springframework.http.HttpHeaders.USER_AGENT;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.Locale.LanguageRange;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import com.amazonaws.util.Throwables;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.util.WebUtils;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.Roles;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.config.Environment;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.exceptions.ConsentRequiredException;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.exceptions.NotAuthenticatedException;
import org.sagebionetworks.bridge.exceptions.UnauthorizedException;
import org.sagebionetworks.bridge.exceptions.UnsupportedVersionException;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.CriteriaContext;
import org.sagebionetworks.bridge.models.Metrics;
import org.sagebionetworks.bridge.models.RequestInfo;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.models.studies.StudyIdentifier;
import org.sagebionetworks.bridge.services.AuthenticationService;
import org.sagebionetworks.bridge.services.SessionUpdateService;
import org.sagebionetworks.bridge.services.StudyService;
import org.sagebionetworks.bridge.time.DateUtils;

public abstract class BaseController {

    private final static Logger LOG = LoggerFactory.getLogger(BaseController.class);
    
    protected final static ObjectMapper MAPPER = BridgeObjectMapper.get();

    CacheProvider cacheProvider;
    
    BridgeConfig bridgeConfig;

    AccountDao accountDao;

    StudyService studyService;

    AuthenticationService authenticationService;
    
    SessionUpdateService sessionUpdateService;
    
    @Autowired
    final void setBridgeConfig(BridgeConfig bridgeConfig) {
        this.bridgeConfig = bridgeConfig;
    }

    @Autowired
    final void setCacheProvider(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }

    @Autowired
    final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    
    @Autowired
    final void setAccountDao(AccountDao accountDao) {
        this.accountDao = accountDao;
    }

    @Autowired
    final void setAuthenticationService(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }
    
    @Autowired
    final void setSessionUpdateService(SessionUpdateService sessionUpdateService) {
        this.sessionUpdateService = sessionUpdateService;
    }
    
    protected HttpServletRequest request() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        if (request == null) {
            throw new IllegalStateException("Request cannot be found in ThreadLocal context");
        }
        return request;
    }

    protected HttpServletResponse response() {
        return ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getResponse();
    }
    
    /**
     * Returns a session. Will not throw exception if user is not authorized or has not consented to research.
     * @return session if it exists, or null otherwise.
     */
    UserSession getSessionIfItExists() {
        final String sessionToken = getSessionToken();
        if (StringUtils.isBlank(sessionToken)){
            return null;
        }
        final UserSession session = authenticationService.getSession(sessionToken);
        writeSessionInfoToMetrics(session);
        return session;
    }

    /**
     * Retrieve user's session using the Bridge-Session header, throwing an exception if the session doesn't
     * exist (user not authorized), consent has not been given or the client app version is not supported.
     */
    UserSession getAuthenticatedAndConsentedSession() throws NotAuthenticatedException, ConsentRequiredException, UnsupportedVersionException {
        return getAuthenticatedSession(true);
    }

    /**
     * Retrieve a user's session or throw an exception if the user is not authenticated. User does not have to give
     * consent. If roles are provided, user must have one of the specified roles or an authorization exception will be
     * thrown. If no roles are supplied, the user just needs to be authenticated.
     */
    UserSession getAuthenticatedSession(Roles... roles) throws NotAuthenticatedException, UnauthorizedException {
        return getAuthenticatedSession(false, roles);
    }
    
    /**
     * Return a session if the user is a consented participant, OR if the user has one of the supplied roles. If no
     * roles are supplied, this method returns the session only if the caller is a consented participant.
     */
    UserSession getSessionEitherConsentedOrInRole(Roles... roles) throws NotAuthenticatedException,
            ConsentRequiredException, UnsupportedVersionException, UnauthorizedException {
        
        return getAuthenticatedSession(true, roles);
    }
    
    /**
     * This method centralizes session checking. If consent is required, user must be consented, if roles are supplied,
     * the user must have one of the roles, and if both are provided, the user must be EITHER consented OR in one of the
     * given roles. If neither is supplied (<code>getAuthenticatedSession(false)</code>), than you just need to be
     * authenticated. This method also ensures that the user's app version is up-to-date if consent is required.
     */
    UserSession getAuthenticatedSession(boolean consentRequired, Roles...roles) {
        final UserSession session = getSessionIfItExists();
        if (session == null || !session.isAuthenticated()) {
            throw new NotAuthenticatedException();
        }
        
        // Update request context with security-related information about the user. This will be
        // immediately removed from the thread local if an exception is thrown.
        // RequestContext is evolving to replace CriteriaContext because of the many places we are 
        // now tailoring server content based on factors like app version or language.
        String requestId = request().getHeader(X_REQUEST_ID_HEADER);
        RequestContext.Builder builder = new RequestContext.Builder().withRequestId(requestId);
        builder.withCallerClientInfo(getClientInfoFromUserAgentHeader());
        builder.withCallerLanguages(getLanguagesFromAcceptLanguageHeader());
        builder.withCallerStudyId(session.getStudyIdentifier());
        builder.withCallerSubstudies(session.getParticipant().getSubstudyIds());
        builder.withCallerRoles(session.getParticipant().getRoles());
        builder.withCallerUserId(session.getParticipant().getId());
        BridgeUtils.setRequestContext(builder.build());
        
        // Sessions are locked to an IP address if (a) it is enabled in the study for unprivileged participant accounts
        // or (b) always for privileged accounts.
        Study study = studyService.getStudy(session.getStudyIdentifier());
        Set<Roles> userRoles = session.getParticipant().getRoles();
        boolean userHasRoles = !userRoles.isEmpty();
        if (study.isParticipantIpLockingEnabled() || userHasRoles) {
            String sessionIpAddress = parseIpAddress(session.getIpAddress());
            String requestIpAddress = parseIpAddress(getRemoteAddress());
            if (!Objects.equals(sessionIpAddress, requestIpAddress)) {
                throw new NotAuthenticatedException();
            }
        }

        // Any method that can throw a 412 can also throw a 410 (min app version not met).
        if (consentRequired) {
            verifySupportedVersionOrThrowException(study);
        }

        // if there are roles, they are required
        boolean rolesRequired = (roles != null && roles.length > 0); 
        boolean isInRole = (rolesRequired) ? !Collections.disjoint(Sets.newHashSet(roles), userRoles) : false;
        
        if ((consentRequired && session.doesConsent()) || (rolesRequired && isInRole)) {
            return session;
        }

        // Behavior here is unusual. It privileges the UnauthorizedException first for users with roles, 
        // and the ConsentRequiredException first for users without any roles.
        if (userHasRoles && rolesRequired && !isInRole) {
            throw new UnauthorizedException();
        }
        if (consentRequired && !session.doesConsent()) {
            throw new ConsentRequiredException(session);
        }
        if (rolesRequired && !isInRole) {
            throw new UnauthorizedException();
        }
        // If you get here, then all that was requested was an authenticated user, 
        // user doesn't need to be consented or to possess any specific role.
        return session;
    }
    
    /** Package-scoped to make available in unit tests. */
    String getSessionToken() {
        String session = request().getHeader(SESSION_TOKEN_HEADER);
        if (StringUtils.isNotBlank(session)) {
            return session;
        }
        if (bridgeConfig.getEnvironment() == Environment.LOCAL) {
            // Not sure why this is 
            Cookie sessionCookie = WebUtils.getCookie(request(), SESSION_TOKEN_HEADER);
            if (sessionCookie != null && StringUtils.isNotBlank(sessionCookie.getValue())) {
                Cookie cookie = makeSessionCookie(sessionCookie.getValue(), BRIDGE_SESSION_EXPIRE_IN_SECONDS);
                response().addCookie(cookie);
                return sessionCookie.getValue();
            }
        }
        return null;
    }
    
    void verifySupportedVersionOrThrowException(Study study) throws UnsupportedVersionException {
        ClientInfo clientInfo = getClientInfoFromUserAgentHeader();
        String osName = clientInfo.getOsName();
        Integer minVersionForOs = study.getMinSupportedAppVersions().get(osName);
        
        if (!clientInfo.isSupportedAppVersion(minVersionForOs)) {
            throw new UnsupportedVersionException(clientInfo);
        }
    }

    /**
     * Once we acquire a language for a user, we save it and use that language going forward. Changing their 
     * language in the host operating system will not change the language they are using (since changing the 
     * language might change their consent state). If they change their language by updating their UserProfile, 
     * then they may have to reconsent in the new language they are using for the study. Any warnings to 
     * that effect will need to be included in the application.
     */
    List<String> getLanguages(UserSession session) {
        StudyParticipant participant = session.getParticipant();
        if (!participant.getLanguages().isEmpty()) {
            return participant.getLanguages();
        }
        List<String> languages = getLanguagesFromAcceptLanguageHeader();
        if (!languages.isEmpty()) {
            accountDao.editAccount(session.getStudyIdentifier(), session.getHealthCode(),
                    account -> account.setLanguages(languages));

            CriteriaContext newContext = new CriteriaContext.Builder().withContext(getCriteriaContext(session))
                    .withLanguages(languages).build();

            sessionUpdateService.updateLanguage(session, newContext);
        }
        return languages;
    }
    
    /**
     * Returns languages in the order of their quality rating in the original LanguageRange objects 
     * that are created from the Accept-Language header (first item in ordered set is the most-preferred 
     * language option).
     * @return
     */
    List<String> getLanguagesFromAcceptLanguageHeader() {
        String acceptLanguageHeader = request().getHeader(ACCEPT_LANGUAGE);
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
        addWarningMessage(WARN_NO_ACCEPT_LANGUAGE);
        return ImmutableList.of();
    }
    
    ClientInfo getClientInfoFromUserAgentHeader() {
        String userAgentHeader = request().getHeader(USER_AGENT);
        ClientInfo info = ClientInfo.fromUserAgentCache(userAgentHeader);

        // if the user agent cannot be parsed (probably due to missing user agent string or unrecognizable user agent),
        // should set an extra header to http response as warning - we should have an user agent info for filtering to work
        if (info.equals(ClientInfo.UNKNOWN_CLIENT)) {
            addWarningMessage(WARN_NO_USER_AGENT);
        }
        LOG.debug("User-Agent: '"+userAgentHeader+"' converted to " + info);    
        return info;
    }

    CriteriaContext getCriteriaContext(StudyIdentifier studyId) {
        return new CriteriaContext.Builder()
            .withStudyIdentifier(studyId)
            .withLanguages(getLanguagesFromAcceptLanguageHeader())
            .withClientInfo(getClientInfoFromUserAgentHeader())
            .withIpAddress(getRemoteAddress())
            .build();
    }
    
    CriteriaContext getCriteriaContext(UserSession session) {
        checkNotNull(session);
        
        return new CriteriaContext.Builder()
            .withLanguages(session.getParticipant().getLanguages())
            .withClientInfo(getClientInfoFromUserAgentHeader())
            .withHealthCode(session.getHealthCode())
            .withIpAddress(session.getIpAddress())
            .withUserId(session.getId())
            .withUserDataGroups(session.getParticipant().getDataGroups())
            .withUserSubstudyIds(session.getParticipant().getSubstudyIds())
            .withStudyIdentifier(session.getStudyIdentifier())
            .build();
    }

    protected @Nonnull <T> T parseJson(Class<? extends T> clazz) {
        try {
            return MAPPER.readValue(request().getInputStream(), clazz);
        } catch (Throwable ex) {
            if (Throwables.getRootCause(ex) instanceof InvalidEntityException) {
                throw (InvalidEntityException)Throwables.getRootCause(ex);
            }
            throw new InvalidEntityException("Error parsing JSON in request body: " + ex.getMessage());    
        }
    }
    
    /**
     * Retrieves the metrics object from the cache. Can be null if the metrics is not in the cache.
     */
    Metrics getMetrics() {
        return BridgeUtils.getRequestContext().getMetrics();
    }

    /** The user's IP Address, as reported by Amazon. Package-scoped for unit tests. */
    String getRemoteAddress() {
        String forwardHeader = request().getHeader(X_FORWARDED_FOR_HEADER);
        return (forwardHeader == null) ? request().getRemoteAddr() : forwardHeader;
    }

    // Helper method to parse an IP address from a raw string, as specified by getRemoteAddress().
    private static String parseIpAddress(String fullIpAddressString) {
        if (isBlank(fullIpAddressString)) {
            // Canonicalize unspecified IP address to null.
            return null;
        }

        // Remote address comes from the X-Forwarded-For header. Since we're behind Amazon, this is almost always
        // 2 IP addresses, separated by a comma and a space. The second is an Amazon router. The first one is probably
        // the real IP address.
        //
        // Note that this isn't fool-proof. X-Forwarded-For can be spoofed. Also, double-proxies might exist, or the
        // first IP address might simply resolve to 192.168.X.1. In local, this is probably just 127.0.0.1. But at
        // least this is an added layer of defense vs not IP-locking at all.
        String[] ipAddressArray = fullIpAddressString.split(",");
        return ipAddressArray[0];
    }

    /** Writes the user's account ID, internal session ID, and study ID to the metrics. */
    protected void writeSessionInfoToMetrics(UserSession session) {
        Metrics metrics = getMetrics();
        if (metrics != null && session != null) {
            metrics.setSessionId(session.getInternalSessionToken());
            metrics.setUserId(session.getId());
            metrics.setStudy(session.getStudyIdentifier().getIdentifier());
        }
    }
    
    /** Combines metrics logging with the setting of the session token as a cookie in local
     * environments (useful for testing).
     */
    protected void setCookieAndRecordMetrics(UserSession session) {
        writeSessionInfoToMetrics(session);  
        RequestInfo requestInfo = getRequestInfoBuilder(session)
                .withSignedInOn(DateUtils.getCurrentDateTime()).build();
        cacheProvider.updateRequestInfo(requestInfo);
        // only set cookie in local environment
        if (bridgeConfig.getEnvironment() == Environment.LOCAL) {
            String sessionToken = session.getSessionToken();
            Cookie cookie = makeSessionCookie(sessionToken, BRIDGE_SESSION_EXPIRE_IN_SECONDS);
            response().addCookie(cookie);
        }
    }
    
    protected RequestInfo.Builder getRequestInfoBuilder(UserSession session) {
        checkNotNull(session);
        
        RequestInfo.Builder builder = new RequestInfo.Builder();
        // If any timestamps exist, retrieve and preserve them in the returned requestInfo
        RequestInfo requestInfo = cacheProvider.getRequestInfo(session.getId());
        if (requestInfo != null) {
            builder.copyOf(requestInfo);
        }
        builder.withUserId(session.getId());
        builder.withClientInfo(getClientInfoFromUserAgentHeader());
        builder.withUserAgent(request().getHeader(USER_AGENT));
        builder.withLanguages(session.getParticipant().getLanguages());
        builder.withUserDataGroups(session.getParticipant().getDataGroups());
        builder.withUserSubstudyIds(session.getParticipant().getSubstudyIds());
        builder.withTimeZone(session.getParticipant().getTimeZone());
        builder.withStudyIdentifier(session.getStudyIdentifier());
        return builder;
    }

    /**
     * Helper method to add warning message as an HTTP header.
     * @param msg
     */
    void addWarningMessage(String msg) {
        if (response().getHeaderNames().contains(BRIDGE_API_STATUS_HEADER)) {
            String previousWarning = response().getHeader(BRIDGE_API_STATUS_HEADER);
            response().setHeader(BRIDGE_API_STATUS_HEADER, previousWarning + "; " + msg);
        } else {
            response().setHeader(BRIDGE_API_STATUS_HEADER, msg);
        }
    }
    
    protected Cookie makeSessionCookie(String sessionToken, int expireInSeconds) {
        Cookie cookie = new Cookie(SESSION_TOKEN_HEADER, sessionToken);
        cookie.setMaxAge(expireInSeconds);
        cookie.setPath("/");
        cookie.setDomain("localhost");
        cookie.setHttpOnly(false);
        cookie.setSecure(false);
        return cookie;
    }    
}
