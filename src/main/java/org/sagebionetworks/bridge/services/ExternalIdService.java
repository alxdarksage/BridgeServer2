package org.sagebionetworks.bridge.services;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Optional;
import java.util.Set;

import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountId;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.models.studies.Enrollment;
import org.sagebionetworks.bridge.validators.ExternalIdValidator;
import org.sagebionetworks.bridge.validators.Validate;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.google.common.collect.Iterables;

/**
 * Service for managing external IDs. These methods can be called whether or not strict validation of IDs is enabled. 
 * If it's enabled, reservation and assignment will work as expected, otherwise these silently do nothing. The external 
 * ID will be associated via the Enrollment collection, thus assignment of an external ID associates an account 
 * to a study (and removing an external ID removes that assignment).
 */
@Component
public class ExternalIdService {
    
    private AppService appService;
    
    private AccountService accountService;
    
    private ParticipantService participantService;
    
    private StudyService studyService;

    @Autowired
    public final void setAppService(AppService appService) {
        this.appService = appService;
    }
    
    @Autowired
    public final void setAccountService(AccountService accountService) {
        this.accountService = accountService;
    }
    
    @Autowired
    public final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    
    @Autowired
    public final void setStudyService(StudyService studyService) {
        this.studyService = studyService;
    }
    
    public Optional<ExternalIdentifier> getExternalId(String appId, String externalId) {
        checkNotNull(appId);
        
        ExternalIdentifier id = ExternalIdentifier.create(appId, externalId);
        if (!StringUtils.isBlank(externalId)) {
            AccountId accountId = AccountId.forExternalId(appId, externalId);
            Account account = accountService.getAccount(accountId);
            if (account != null) {
                id.setHealthCode(account.getHealthCode());
                for (Enrollment en : account.getEnrollments()) {
                    if (en.getExternalId().equals(externalId)) {
                        id.setStudyId(en.getStudyId());
                        return Optional.of(id);         
                    }
                }
            }
        }
        return Optional.empty();
    }
    
    public void createExternalId(ExternalIdentifier externalId, boolean isV3) {
        checkNotNull(externalId);
        
        String appId = RequestContext.get().getCallerAppId();
        externalId.setAppId(appId);
        
        // In this one  case, we can default the value for the caller and avoid an error. Any other situation
        // is going to generate a validation error
        Set<String> callerStudyIds = RequestContext.get().getOrgSponsoredStudies();
        if (externalId.getStudyId() == null && callerStudyIds.size() == 1) {
            externalId.setStudyId( Iterables.getFirst(callerStudyIds, null) );
        }
        
        ExternalIdValidator validator = new ExternalIdValidator(studyService, isV3);
        Validate.entityThrowingException(validator, externalId);

        Enrollment en = Enrollment.create(externalId.getStudyId(), externalId.getIdentifier());
        StudyParticipant participant = new StudyParticipant.Builder()
                .withEnrollment(en)
                .build();
        
        App app = appService.getApp(appId);
        participantService.createParticipant(app, participant, false);
    }
    
    /**
     * This will delete the enrollment the external ID references (to simply remove
     * the external ID, use unassignExternalId).
     */
    public void deleteExternalIdPermanently(App app, ExternalIdentifier externalId) {
        checkNotNull(app);
        checkNotNull(externalId);
        
        AccountId accountId = AccountId.forExternalId(app.getIdentifier(), externalId.getIdentifier());
        Account account = accountService.getAccount(accountId);
        if (account != null) {
            Enrollment found = null;
            for (Enrollment en : account.getEnrollments()) {
                if (en.getExternalId().equals(externalId.getIdentifier())) {
                    found = en;
                    break;
                }
            }
            if (found != null) {
                account.getEnrollments().remove(found);
            }
            if (account.getEnrollments().isEmpty()) {
                accountService.deleteAccount(accountId);
            } else {
                accountService.updateAccount(account, null);
            }
        }
    }
    
    public void unassignExternalId(Account account, String externalId) {
        checkNotNull(account);
        checkNotNull(account.getAppId());
        checkNotNull(account.getHealthCode());
        
        if (account != null) {
            for (Enrollment en : account.getEnrollments()) {
                if (en.getExternalId().equals(externalId)) {
                    en.setExternalId(null);
                    break;
                }
            }
            accountService.updateAccount(account, null);
        }
    }
}
