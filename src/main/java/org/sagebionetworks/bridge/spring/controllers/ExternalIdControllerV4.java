package org.sagebionetworks.bridge.spring.controllers;

import static org.sagebionetworks.bridge.BridgeConstants.API_DEFAULT_PAGE_SIZE;
import static org.sagebionetworks.bridge.BridgeUtils.getIntOrDefault;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.RESEARCHER;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.sagebionetworks.bridge.BridgeUtils;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.StatusMessage;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifierInfo;
import org.sagebionetworks.bridge.models.accounts.GeneratedPassword;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.services.ExternalIdService;

@CrossOrigin
@RestController
public class ExternalIdControllerV4 extends BaseController {

    private ExternalIdService externalIdService;
    
    @Autowired
    final void setExternalIdService(ExternalIdService externalIdService) {
        this.externalIdService = externalIdService;
    }
    
    @GetMapping("/v4/externalids")
    public PagedResourceList<ExternalIdentifierInfo> getExternalIdentifiers(
            @RequestParam(required = false) String offsetKey, 
            @RequestParam(required = false) String offsetBy,
            @RequestParam(required = false) String pageSize,
            @RequestParam(required = false) String idFilter) {
        UserSession session = getAuthenticatedSession(DEVELOPER, RESEARCHER);
        
        if (offsetBy == null && offsetKey != null) {
            offsetBy = offsetKey;
        }
        int offsetByInt = BridgeUtils.getIntOrDefault(offsetBy, 0);
        int pageSizeInt = getIntOrDefault(pageSize, API_DEFAULT_PAGE_SIZE);
        
        return accountService.getAccountSummariesWithExternalIds(session.getAppId(), idFilter, offsetByInt,
                pageSizeInt);
    }

    @PostMapping("/v4/externalids")
    @ResponseStatus(HttpStatus.CREATED)
    public StatusMessage createExternalIdentifier() {
        getAuthenticatedSession(DEVELOPER, RESEARCHER);
        
        ExternalIdentifier externalIdentifier = parseJson(ExternalIdentifier.class);
        externalIdService.createExternalId(externalIdentifier, false);
        
        return new StatusMessage("External identifier created.");
    }
    
    @DeleteMapping("/v4/externalids/{externalId}")
    public StatusMessage deleteExternalIdentifier(@PathVariable String externalId) {
        UserSession session = getAuthenticatedSession(ADMIN);
        App app = appService.getApp(session.getAppId());
        
        ExternalIdentifier externalIdentifier = ExternalIdentifier.create(app.getIdentifier(), externalId);
        externalIdService.deleteExternalIdPermanently(app, externalIdentifier);
        
        return new StatusMessage("External identifier deleted.");
    }
    
    @PostMapping(path = {"/v3/externalids/{externalId}/password", "/v3/externalIds/{externalId}/password"})
    public GeneratedPassword generatePassword(@PathVariable String externalId) throws Exception {
        UserSession session = getAuthenticatedSession(RESEARCHER);
        App app = appService.getApp(session.getAppId());
        
        return authenticationService.generatePassword(app, externalId);
    }
}
