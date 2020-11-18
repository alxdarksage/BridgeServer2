package org.sagebionetworks.bridge.spring.controllers;

import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.WORKER;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

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

import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.GuidVersionHolder;
import org.sagebionetworks.bridge.models.ResourceList;
import org.sagebionetworks.bridge.models.StatusMessage;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.services.SchedulePlanService;

@CrossOrigin
@RestController
public class SchedulePlanController extends BaseController {

    static final StatusMessage DELETE_MSG = new StatusMessage("Schedule plan deleted.");
    private SchedulePlanService schedulePlanService;
    
    @Autowired
    void setSchedulePlanService(SchedulePlanService schedulePlanService) {
        this.schedulePlanService = schedulePlanService;
    }
    
    @GetMapping(path = {"/v1/apps/{appId}/scheduleplans", "/v3/studies/{appId}/scheduleplans"})
    public ResourceList<SchedulePlan> getSchedulePlansForWorker(@PathVariable String appId,
            @RequestParam(defaultValue = "false") boolean includeDeleted) {
        getAuthenticatedSession(WORKER);
        App app = appService.getApp(appId);
        
        List<SchedulePlan> plans = schedulePlanService.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT,
                app.getIdentifier(), includeDeleted);
        return new ResourceList<>(plans);
    }

    @GetMapping("/v3/scheduleplans")
    public ResourceList<SchedulePlan> getSchedulePlans(@RequestParam(defaultValue = "false") boolean includeDeleted) {
        UserSession session = getAdministrativeSession();
        String appId = session.getAppId();

        // We don't filter plans when we return a list of all of them for developers.
        List<SchedulePlan> plans = schedulePlanService.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, appId,
                includeDeleted);
        return new ResourceList<>(plans);
    }

    @PostMapping("/v3/scheduleplans")
    @ResponseStatus(HttpStatus.CREATED)
    public GuidVersionHolder createSchedulePlan() {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        App app = appService.getApp(session.getAppId());

        DynamoSchedulePlan planForm = DynamoSchedulePlan.fromJson(parseJson(JsonNode.class));
        SchedulePlan plan = schedulePlanService.createSchedulePlan(app, planForm);
        return new GuidVersionHolder(plan.getGuid(), plan.getVersion());
    }

    @GetMapping("/v3/scheduleplans/{guid}")
    public SchedulePlan getSchedulePlan(@PathVariable String guid) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        String appId = session.getAppId();
        
        return schedulePlanService.getSchedulePlan(appId, guid);
    }

    @PostMapping("/v3/scheduleplans/{guid}")
    public GuidVersionHolder updateSchedulePlan(@PathVariable String guid) {
        UserSession session = getAuthenticatedSession(DEVELOPER);
        App app = appService.getApp(session.getAppId());

        DynamoSchedulePlan planForm = DynamoSchedulePlan.fromJson(parseJson(JsonNode.class));
        planForm.setGuid(guid);
        SchedulePlan plan = schedulePlanService.updateSchedulePlan(app, planForm);
        
        return new GuidVersionHolder(plan.getGuid(), plan.getVersion());
    }
    
    @DeleteMapping("/v3/scheduleplans/{guid}")
    public StatusMessage deleteSchedulePlan(@PathVariable String guid,
            @RequestParam(defaultValue = "false") boolean physical) {
        UserSession session = getAuthenticatedSession(DEVELOPER, ADMIN);
        String appId = session.getAppId();
        
        if (physical && session.isInRole(ADMIN)) {
            schedulePlanService.deleteSchedulePlanPermanently(appId, guid);
        } else {
            schedulePlanService.deleteSchedulePlan(appId, guid);
        }
        return DELETE_MSG;
    }
}
