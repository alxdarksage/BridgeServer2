package org.sagebionetworks.bridge.spring.controllers;

import static org.sagebionetworks.bridge.BridgeConstants.API_DEFAULT_PAGE_SIZE;
import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.ORG_ADMIN;

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
import org.sagebionetworks.bridge.models.VersionHolder;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.studies.Study;
import org.sagebionetworks.bridge.services.StudyService;

@CrossOrigin
@RestController
public class StudyController extends BaseController {

    static final StatusMessage DELETED_MSG = new StatusMessage("Study deleted.");
    private StudyService service;

    @Autowired
    final void setStudyService(StudyService studyService) {
        this.service = studyService;
    }

    @GetMapping(path = {"/v5/studies", "/v3/substudies"})
    public PagedResourceList<Study> getStudies(
            @RequestParam(required = false) String offsetBy, 
            @RequestParam(required = false) String pageSize,            
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        UserSession session = getAdminSession();

        int offsetByInt = BridgeUtils.getIntOrDefault(offsetBy, 0);
        int pageSizeInt = BridgeUtils.getIntOrDefault(pageSize, API_DEFAULT_PAGE_SIZE);

        return service.getStudies(session.getAppId(), offsetByInt, pageSizeInt, includeDeleted);
    }

    @PostMapping(path = {"/v5/studies", "/v3/substudies"})
    @ResponseStatus(HttpStatus.CREATED)
    public VersionHolder createStudy() {
        UserSession session = getAuthenticatedSession(ADMIN, ORG_ADMIN);

        Study study = parseJson(Study.class);
        
        return service.createStudy(session.getAppId(), study);
    }

    @GetMapping(path = {"/v5/studies/{id}", "/v3/substudies/{id}"})
    public Study getStudy(@PathVariable String id) {
        UserSession session = getAuthenticatedSession(ADMIN, ORG_ADMIN);

        return service.getStudy(session.getAppId(), id, true);
    }

    @PostMapping(path = {"/v5/studies/{id}", "/v3/substudies/{id}"})
    public VersionHolder updateStudy(@PathVariable String id) {
        UserSession session = getAuthenticatedSession(ADMIN);

        Study study = parseJson(Study.class);
        return service.updateStudy(session.getAppId(), study);
    }

    @DeleteMapping(path = {"/v5/studies/{id}", "/v3/substudies/{id}"})
    public StatusMessage deleteStudy(@PathVariable String id,
            @RequestParam(defaultValue = "false") boolean physical) {
        UserSession session = getAuthenticatedSession(ADMIN);

        if (physical) {
            service.deleteStudyPermanently(session.getAppId(), id);
        } else {
            service.deleteStudy(session.getAppId(), id);
        }
        return DELETED_MSG;
    }
}