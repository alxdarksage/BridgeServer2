package org.sagebionetworks.bridge;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sagebionetworks.bridge.BridgeConstants.CALLER_NOT_MEMBER_ERROR;
import static org.sagebionetworks.bridge.BridgeUtils.isEmpty;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.ORG_ADMIN;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;
import static org.sagebionetworks.bridge.Roles.WORKER;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.sagebionetworks.bridge.exceptions.UnauthorizedException;

/**
 * This is difficult to use and maintain. Some role checks should check for exclusion
 * (e.g. if I'm an ORG_ADMIN or ADMIN, which should apply...it varies). We've split 
 * up security checks between the controllers and now in the services (arguably they 
 * could all be in the services).
 */
public class AuthUtils {
    private static final Logger LOG = LoggerFactory.getLogger(AuthUtils.class);

    public static void checkSelfStudyResearcherOrAdmin(String userId, String studyId) {
        if (!(isSelf(userId) || isInRoles(studyId, ADMIN, RESEARCHER))) {
            throw new UnauthorizedException();
        }
    }
    
    /**
     * This check does not verify that the caller is in a specific study, so the
     * caller may still not have access rights to a particular object. The 
     * participants APIs, for example, only check the accessibility of accounts
     * at a deeper level during the query, but are otherwise globally available
     * to all administrative accounts. 
     */
    public static void checkSelfResearcherOrAdmin(String targetUserId) {
        if (!isSelfResearcherOrAdmin(targetUserId)) {
            throw new UnauthorizedException();
        }
    }
    
    public static boolean isSelfResearcherOrAdmin(String targetUserId) {
        return isSelf(targetUserId) || isInRoles(null, ADMIN, RESEARCHER);
    }
    
    /**
     * Check that the caller is a member of this organization. Note that this does not verify
     * the organization is in the caller's app...this can only be done by trying to load the 
     * organization with this ID, which includes the appId as part of the organization's 
     * primary key. (Passing in appId to this method is tautological since it comes from the 
     * caller's session, which is also where the appId in the RequestContext comes from. 
     */
    public static void checkOrgMembership(String orgId) {
        if (!isInOrganization(orgId)) {
            throw new UnauthorizedException("Caller is not a member of " + orgId);    
        }
    }
    
    /**
     * Verifies that the caller is a member of this organization, and that they are at least
     * an organizational admin. It's a stronger check than `checkOrgMembership`. 
     */
    public static boolean isOrgAdmin(String orgId) {
        return isInOrganization(orgId) && isInRoles(null, ADMIN, ORG_ADMIN);
    }
    
    public static void checkOrgAdmin(String orgId) {
        if (!isOrgAdmin(orgId)) {
            throw new UnauthorizedException("Caller is not an admin of " + orgId);
        }
    }
    
    /**
     * The same rules apply as checkOwnership, however you are examining the caller against a compound
     * value stored in the assessment's originId. The call succeeds if you're a superadmin (there are 
     * no shared app admins), or if the caller is in the app and organization that owns the shared 
     * assessment. 
     */
    public static void checkSharedAssessmentOwnership(String callerAppId, String guid, String ownerId) {
        checkNotNull(callerAppId);
        checkNotNull(guid);
        checkNotNull(ownerId);

        RequestContext context = RequestContext.get();
        if (context.isInRole(ADMIN)) {
            return;
        }
        String[] parts = ownerId.split(":", 2);
        // This happens in tests, we expect it to never happens in production. So log if it does.
        if (parts.length != 2) {
            LOG.error("Could not parse shared assessment ownerID, guid=" + guid + ", ownerId=" + ownerId);
            throw new UnauthorizedException();
        }
        String originAppId = parts[0];
        String originOrgId = parts[1];
        String callerOrgMembership = context.getCallerOrgMembership();
        if (originAppId.equals(callerAppId) && originOrgId.equals(callerOrgMembership)) {
            return;
        }
        throw new UnauthorizedException(CALLER_NOT_MEMBER_ERROR);
    }
    
    public static void checkStudyScopedToCaller(String studyId) {
        if (!isStudyScopedToCaller(studyId)) {
            throw new UnauthorizedException();
        }
    }
    
    public static final boolean isStudyScopedToCaller(String studyId) {
        RequestContext context = RequestContext.get();
        Set<String> callerStudies = context.getOrgSponsoredStudies();
        
        return context.isInRole(ADMIN, WORKER) || isEmpty(callerStudies) || callerStudies.contains(studyId);
    }
    
    /**
     * If the user is an admin or a superadmin, this returns true. For other roles, the 
     * caller must be in one of the roles, and they must be a member of an organization 
     * that gives them access to the study.
     */
    public static final boolean isInRoles(String studyId, Roles... roles) {
        Set<Roles> roleSet = ImmutableSet.copyOf(roles);
        RequestContext context = RequestContext.get();
        if (roleSet.contains(ADMIN) && context.isInRole(ADMIN)) {
            return true;
        }
        Set<String> sponsoredStudies = context.getOrgSponsoredStudies();
        boolean canAccessStudy = sponsoredStudies.isEmpty() || studyId == null 
                || sponsoredStudies.contains(studyId);
        return (canAccessStudy && context.isInRole(roleSet));
    }
    
    public static final boolean isInOrganization(String orgId) {
        RequestContext context = RequestContext.get();
        return context.isInRole(ADMIN) || orgId.equals(context.getCallerOrgMembership());
    }
    
    public static final boolean isSelf(String userId) {
        return userId != null && userId.equals(RequestContext.get().getCallerUserId());
    }
}