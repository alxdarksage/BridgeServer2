package org.sagebionetworks.bridge.spring.controllers;

import static org.sagebionetworks.bridge.AuthUtils.checkSelfResearcherOrAdmin;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.ORG_ADMIN;
import static org.springframework.http.MediaType.APPLICATION_JSON_UTF8_VALUE;

import com.fasterxml.jackson.databind.ObjectWriter;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.AccountSummarySearch;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.StatusMessage;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountSummary;
import org.sagebionetworks.bridge.models.accounts.IdentifierHolder;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.services.ParticipantService;
import org.sagebionetworks.bridge.services.UserAdminService;

@CrossOrigin
@RestController
public class MemberController extends BaseController {

    private ParticipantService participantService;
    
    private UserAdminService userAdminService;
    
    @Autowired
    final void setParticipantService(ParticipantService participantService) {
        this.participantService = participantService;
    }
    
    @Autowired
    final void setUserAdminService(UserAdminService userAdminService) {
        this.userAdminService = userAdminService;
    }
    
    @DeleteMapping("/v1/organizations/{orgId}/members/{userId}")
    public StatusMessage deleteMember(@PathVariable String orgId, @PathVariable String userId) {
        UserSession session = getAuthenticatedSession(ORG_ADMIN);
        
        // business logic about deleting test users has been moved to the service
        // and expanded there.
        userAdminService.deleteUser(session.getAppId(), userId);
        return new StatusMessage("User deleted.");
    }

    @PostMapping("/v1/organizations/{orgId}/members/search")
    public PagedResourceList<AccountSummary> searchForAccountSummaries(@PathVariable String orgId) {
        UserSession session = getAuthenticatedSession(ORG_ADMIN);
        App app = appService.getApp(session.getAppId());
        
        AccountSummarySearch search = parseJson(AccountSummarySearch.class);
        return participantService.getPagedAccountSummaries(app, search);
    }
    
    @PostMapping("/v1/organizations/{orgId}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentifierHolder createParticipant() {
        UserSession session = getAuthenticatedSession(ORG_ADMIN);
        App app = appService.getApp(session.getAppId());
        
        StudyParticipant participant = parseJson(StudyParticipant.class);
        return participantService.createParticipant(app, participant, true);
    }

    @GetMapping(path="/v1/organizations/{orgId}/members/{userId}", produces={APPLICATION_JSON_UTF8_VALUE})
    public String getParticipant(@PathVariable String orgId, @PathVariable String userId)
            throws Exception {
        UserSession session = getAuthenticatedSession(ORG_ADMIN);

        App app = appService.getApp(session.getAppId());

        // Do not allow lookup by health code if health code access is disabled. Allow it however
        // if the user is an administrator.
        if (!session.isInRole(ADMIN) && !app.isHealthCodeExportEnabled()
                && userId.toLowerCase().startsWith("healthcode:")) {
            throw new EntityNotFoundException(Account.class);
        }
        
        StudyParticipant participant = participantService.getParticipant(app, userId, false);
        
        ObjectWriter writer = (app.isHealthCodeExportEnabled() || session.isInRole(ADMIN)) ?
                StudyParticipant.API_WITH_HEALTH_CODE_WRITER :
                StudyParticipant.API_NO_HEALTH_CODE_WRITER;
        return writer.writeValueAsString(participant);
    }
    
    @PostMapping("/v1/organizations/{orgId}/members/{userId}")
    public StatusMessage updateParticipant(@PathVariable String orgId, @PathVariable String userId) {
        UserSession session = getAdministrativeSession();
        checkSelfResearcherOrAdmin(userId);
        App app = appService.getApp(session.getAppId());

        StudyParticipant participant = parseJson(StudyParticipant.class);
 
        // Force userId of the URL
        participant = new StudyParticipant.Builder().copyOf(participant).withId(userId).build();
        
        participantService.updateParticipant(app, participant);

        return new StatusMessage("Participant updated.");
    }
}
