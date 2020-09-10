package org.sagebionetworks.bridge.services;

import java.util.Set;

import com.google.common.collect.ImmutableSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;

@Component
public class RequestContextUpdateService {
    
    private SponsorService sponsorService;
    
    @Autowired
    final void setSponsorService(SponsorService sponsorService) {
        this.sponsorService = sponsorService;
    }

    public RequestContext updateFromSession(UserSession session) {
        RequestContext.Builder builder = BridgeUtils.getRequestContext().toBuilder();
        builder.withCallerAppId(session.getAppId());

        Set<String> orgSponsoredStudies = sponsorService.getSponsoredStudyIds(
                session.getAppId(), session.getParticipant().getOrgMembership());
        builder.withOrgSponsoredStudies(orgSponsoredStudies);
        
        StudyParticipant participant = session.getParticipant();
        builder.withCallerLanguages(participant.getLanguages());
        builder.withCallerOrgMembership(participant.getOrgMembership());
        builder.withCallerStudies(participant.getStudyIds());
        builder.withCallerRoles(participant.getRoles());
        builder.withCallerUserId(participant.getId());
        
        RequestContext reqContext = builder.build();
        BridgeUtils.setRequestContext(reqContext);
        return reqContext;
    }
    
    public RequestContext updateFromExternalId(ExternalIdentifier externalId) {
        RequestContext context = BridgeUtils.getRequestContext();
        
        RequestContext.Builder builder = context.toBuilder();
        builder.withCallerStudies(new ImmutableSet.Builder<String>()
                .addAll(context.getCallerStudies())
                .add(externalId.getStudyId()).build());
        
        RequestContext reqContext = builder.build();
        BridgeUtils.setRequestContext(reqContext);
        return reqContext;
    }
}
