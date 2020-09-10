package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.sagebionetworks.bridge.AuthUtils.checkOrgMembershipAndThrow;
import static org.sagebionetworks.bridge.BridgeConstants.API_MAXIMUM_PAGE_SIZE;
import static org.sagebionetworks.bridge.BridgeConstants.API_MINIMUM_PAGE_SIZE;
import static org.sagebionetworks.bridge.BridgeConstants.NEGATIVE_OFFSET_ERROR;
import static org.sagebionetworks.bridge.BridgeConstants.PAGE_SIZE_ERROR;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.models.ResourceList.OFFSET_BY;
import static org.sagebionetworks.bridge.models.ResourceList.PAGE_SIZE;
import static org.sagebionetworks.bridge.util.BridgeCollectors.toImmutableSet;

import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.collect.ImmutableSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.cache.CacheKey;
import org.sagebionetworks.bridge.cache.CacheProvider;
import org.sagebionetworks.bridge.dao.SponsorDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.organizations.Organization;
import org.sagebionetworks.bridge.models.studies.Study;

@Component
public class SponsorService {
    
    private static final TypeReference<Set<String>> STRING_SET_TYPE_REF = new TypeReference<Set<String>>() {};

    static final String NOT_A_SPONSOR_MSG = "Organization '%s' is not a sponsor of study '%s'";

    private OrganizationService organizationService;
    
    private StudyService studyService;
    
    private SponsorDao sponsorDao;
    
    private CacheProvider cacheProvider;
    
    @Autowired
    final void setOrganizationService(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }
    
    @Autowired
    final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    
    @Autowired
    final void setSponsorDao(SponsorDao sponsorDao) {
        this.sponsorDao = sponsorDao;
    }
    
    @Autowired
    final void setCacheProvider(CacheProvider cacheProvider) {
        this.cacheProvider = cacheProvider;
    }
    
    public Set<String> getSponsoredStudyIds(String appId, String orgId) {
        checkNotNull(appId);

        RequestContext rc = BridgeUtils.getRequestContext();
        if (rc.isInRole(ADMIN) || isBlank(orgId)) {
            return ImmutableSet.of();
        }
        // It's a pain to cache, but this will be accessed for every request.
        CacheKey cacheKey = CacheKey.studyIdAccessList(appId, orgId);
        
        Set<String> cached = cacheProvider.getObject(cacheKey, STRING_SET_TYPE_REF);
        if (cached != null) {
            return cached;
        }
        Optional<Organization> opt = organizationService.getOrganizationOpt(appId, orgId);
        if (!opt.isPresent()) {
            return ImmutableSet.of();
        }
        cached = sponsorDao.getSponsoredStudies(appId, orgId, null, null).getItems().stream()
                .map(Study::getIdentifier).collect(toImmutableSet());
        cacheProvider.setObject(cacheKey, cached);
        return cached;
    }
    
    public PagedResourceList<Organization> getStudySponsors(String appId, String studyId, Integer offsetBy, Integer pageSize) {
        checkNotNull(appId);
        checkNotNull(studyId);
        
        if (offsetBy != null && offsetBy < 0) {
            throw new BadRequestException(NEGATIVE_OFFSET_ERROR);
        }
        if (pageSize != null && (pageSize < API_MINIMUM_PAGE_SIZE || pageSize > API_MAXIMUM_PAGE_SIZE)) {
            throw new BadRequestException(PAGE_SIZE_ERROR);
        }
        // Throw an exception if this doesn't exist.
        studyService.getStudy(appId, studyId, true);
        
        return sponsorDao.getStudySponsors(appId, studyId, offsetBy, pageSize)
                .withRequestParam(OFFSET_BY, offsetBy)
                .withRequestParam(PAGE_SIZE, pageSize);
    }

    public PagedResourceList<Study> getSponsoredStudies(String appId, String orgId, Integer offsetBy, Integer pageSize) {
        checkNotNull(appId);
        checkNotNull(orgId);
        
        if (offsetBy != null && offsetBy < 0) {
            throw new BadRequestException(NEGATIVE_OFFSET_ERROR);
        }
        if (pageSize != null && (pageSize < API_MINIMUM_PAGE_SIZE || pageSize > API_MAXIMUM_PAGE_SIZE)) {
            throw new BadRequestException(PAGE_SIZE_ERROR);
        }
        // Throw an exception if this doesn't exist.
        organizationService.getOrganization(appId, orgId);
        
        return sponsorDao.getSponsoredStudies(appId, orgId, offsetBy, pageSize)
                .withRequestParam(OFFSET_BY, offsetBy)
                .withRequestParam(PAGE_SIZE, pageSize);
    }

    public void addStudySponsor(String appId, String studyId, String orgId) {
        checkNotNull(appId);
        checkNotNull(studyId);
        checkNotNull(orgId);
        
        checkOrgMembershipAndThrow(orgId);
        
        // The database constraints are thrown and converted to EntityNotFoundExceptions
        // if either the organization or the study do not exist, or if the org already
        // sponsors the study, that is also caught as a constraint violation.
        
        sponsorDao.addStudySponsor(appId, studyId, orgId);  
        cacheProvider.removeObject( CacheKey.studyIdAccessList(appId, orgId) );
    }

    public void removeStudySponsor(String appId, String studyId, String orgId) {
        checkNotNull(appId);
        checkNotNull(studyId);
        checkNotNull(orgId);
        
        checkOrgMembershipAndThrow(orgId);

        if (sponsorDao.doesOrganizationSponsorStudy(appId, studyId, orgId)) {
            // Currently we allow you to remove the last sponsor from a study. There is no 
            // database constraint that prevents this.
            sponsorDao.removeStudySponsor(appId, studyId, orgId);
            cacheProvider.removeObject( CacheKey.studyIdAccessList(appId, orgId) );
        } else {
            // Either one of the two entities is missing, or if they both exist, the org
            // does not sponsor this study. So one way or another, an exception must be 
            // thrown.
            studyService.getStudy(appId, studyId, true);
            organizationService.getOrganization(appId, orgId);
            throw new BadRequestException(String.format(NOT_A_SPONSOR_MSG, orgId, studyId));
        }
    }
}
