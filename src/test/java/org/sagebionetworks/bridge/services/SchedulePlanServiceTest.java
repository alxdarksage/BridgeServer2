package org.sagebionetworks.bridge.services;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.fail;

import java.util.List;
import java.util.Set;

import org.joda.time.DateTime;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.TestUtils;
import org.sagebionetworks.bridge.dao.SchedulePlanDao;
import org.sagebionetworks.bridge.dynamodb.DynamoSchedulePlan;
import org.sagebionetworks.bridge.dynamodb.DynamoApp;
import org.sagebionetworks.bridge.exceptions.InvalidEntityException;
import org.sagebionetworks.bridge.models.ClientInfo;
import org.sagebionetworks.bridge.models.Criteria;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.models.schedules.Activity;
import org.sagebionetworks.bridge.models.schedules.CriteriaScheduleStrategy;
import org.sagebionetworks.bridge.models.schedules.Schedule;
import org.sagebionetworks.bridge.models.schedules.ScheduleCriteria;
import org.sagebionetworks.bridge.models.schedules.SchedulePlan;
import org.sagebionetworks.bridge.models.schedules.ScheduleType;
import org.sagebionetworks.bridge.models.schedules.SimpleScheduleStrategy;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.models.surveys.TestSurvey;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

public class SchedulePlanServiceTest {

    private App app;
    private String surveyGuid1;
    private String surveyGuid2;
    private SchedulePlanService service;
    
    private SchedulePlanDao mockSchedulePlanDao;
    private SurveyService mockSurveyService;
    private StudyService mockStudyService;
    
    @BeforeMethod
    public void before() {
        app = new DynamoApp();
        app.setIdentifier(TEST_APP_ID);
        app.setTaskIdentifiers(ImmutableSet.of("tapTest", "taskGuid", "CCC"));
        app.setDataGroups(ImmutableSet.of("AAA"));
        
        mockSchedulePlanDao = mock(SchedulePlanDao.class);
        mockSurveyService = mock(SurveyService.class);
        mockStudyService = mock(StudyService.class);
        
        service = new SchedulePlanService();
        service.setSchedulePlanDao(mockSchedulePlanDao);
        service.setSurveyService(mockSurveyService);
        service.setStudyService(mockStudyService);
        
        Survey survey1 = new TestSurvey(SchedulePlanServiceTest.class, false);
        survey1.setIdentifier("identifier1");
        Survey survey2 = new TestSurvey(SchedulePlanServiceTest.class, false);
        survey2.setIdentifier("identifier2");
        when(mockSurveyService.getSurveyMostRecentlyPublishedVersion(any(), any(), anyBoolean())).thenReturn(survey1);
        when(mockSurveyService.getSurvey(eq(TEST_APP_ID), any(), eq(false), eq(true))).thenReturn(survey2);
        surveyGuid1 = survey1.getGuid();
        surveyGuid2 = survey2.getGuid();
        
    }
    
    @Test(expectedExceptions = InvalidEntityException.class)
    public void createSchedulePlanWithoutStrategy() {
        SchedulePlan schedulePlan = constructSchedulePlan();
        schedulePlan.setStrategy(null);

        // The key thing here is that we make it to validation.
        service.createSchedulePlan(app, schedulePlan);
    }
    
    @Test(expectedExceptions = InvalidEntityException.class)
    public void updateSchedulePlanWithoutStrategy() {
        SchedulePlan schedulePlan = constructSchedulePlan();
        schedulePlan.setStrategy(null);
        
        when(mockSchedulePlanDao.getSchedulePlan(eq(TEST_APP_ID), any())).thenReturn(schedulePlan);
        
        // The key thing here is that we make it to validation.
        service.updateSchedulePlan(app, schedulePlan);
    }
    
    @Test
    public void getSchedulePlan() {
        SchedulePlan schedulePlan = SchedulePlan.create();
        when(mockSchedulePlanDao.getSchedulePlan(TEST_APP_ID, "oneGuid")).thenReturn(schedulePlan);
        
        SchedulePlan result = service.getSchedulePlan(TEST_APP_ID, "oneGuid");
        assertSame(result, schedulePlan);
        
        verify(mockSchedulePlanDao).getSchedulePlan(TEST_APP_ID, "oneGuid");
    }
    
    @Test
    public void surveyReferenceIdentifierFilledOutOnCreate() {
        SchedulePlan plan = constructSchedulePlan();
        
        ArgumentCaptor<SchedulePlan> spCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
        
        service.createSchedulePlan(app, plan);
        verify(mockSurveyService).getSurveyMostRecentlyPublishedVersion(any(), any(), anyBoolean());
        verify(mockSurveyService).getSurvey(eq(TEST_APP_ID), any(), eq(false), eq(true));
        verify(mockSchedulePlanDao).createSchedulePlan(any(), spCaptor.capture());
        
        List<Activity> activities = spCaptor.getValue().getStrategy().getAllPossibleSchedules().get(0).getActivities();
        assertEquals(activities.get(0).getSurvey().getIdentifier(), "identifier1");
        assertNotNull(activities.get(1).getTask());
        assertEquals(activities.get(2).getSurvey().getIdentifier(), "identifier2");
    }
    
    @Test
    public void surveyReferenceIdentifierFilledOutOnUpdate() {
        SchedulePlan plan = constructSchedulePlan();
        
        ArgumentCaptor<SchedulePlan> spCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
        when(mockSchedulePlanDao.getSchedulePlan(app.getIdentifier(), plan.getGuid())).thenReturn(plan);
        when(mockSchedulePlanDao.updateSchedulePlan(any(), any())).thenReturn(plan);
        
        service.updateSchedulePlan(app, plan);
        verify(mockSurveyService).getSurveyMostRecentlyPublishedVersion(any(), any(), anyBoolean());
        verify(mockSurveyService).getSurvey(eq(TEST_APP_ID), any(), eq(false), eq(true));
        verify(mockSchedulePlanDao).getSchedulePlan(app.getIdentifier(), plan.getGuid());
        verify(mockSchedulePlanDao).updateSchedulePlan(any(), spCaptor.capture());
        
        List<Activity> activities = spCaptor.getValue().getStrategy().getAllPossibleSchedules().get(0).getActivities();
        assertEquals(activities.get(0).getSurvey().getIdentifier(), "identifier1");
        assertNotNull(activities.get(1).getTask());
        assertEquals(activities.get(2).getSurvey().getIdentifier(), "identifier2");
    }

    @Test
    public void doNotUseIdentifierFromClient() {
        // The survey GUID/createdOn identify a survey, but the identifier from the client can just be 
        // mismatched by the client, so ignore it and look it up from the DB using the primary keys.
        Activity activity = new Activity.Builder().withGuid("guid").withLabel("A survey activity")
                .withPublishedSurvey("junkIdentifier", surveyGuid1).build();
        SchedulePlan plan = constructSchedulePlan();
        plan.getStrategy().getAllPossibleSchedules().get(0).getActivities().set(0, activity);
        
        when(mockSchedulePlanDao.getSchedulePlan(app.getIdentifier(), plan.getGuid())).thenReturn(plan);
        
        // Verify that this was set.
        String identifier = plan.getStrategy().getAllPossibleSchedules().get(0).getActivities().get(0)
                .getSurvey().getIdentifier();
        assertEquals(identifier, "junkIdentifier");
        
        ArgumentCaptor<SchedulePlan> spCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
        when(mockSchedulePlanDao.updateSchedulePlan(any(), any())).thenReturn(plan);
        
        service.updateSchedulePlan(app, plan);
        verify(mockSurveyService).getSurveyMostRecentlyPublishedVersion(any(), any(), anyBoolean());
        verify(mockSurveyService).getSurvey(eq(TEST_APP_ID), any(), eq(false), eq(true));
        verify(mockSchedulePlanDao).getSchedulePlan(app.getIdentifier(), plan.getGuid());
        verify(mockSchedulePlanDao).updateSchedulePlan(any(), spCaptor.capture());
        
        // It was not used.
        identifier = spCaptor.getValue().getStrategy().getAllPossibleSchedules().get(0).getActivities().get(0)
                .getSurvey().getIdentifier();
        assertNotEquals(identifier, "junkIdentifier");
        
    }
    
    @Test
    public void verifyCreateDoesNotUseProvidedGUIDs() throws Exception {
        SchedulePlan plan = constructSchedulePlan();
        plan.setVersion(2L);
        plan.setGuid("AAA");
        Set<String> existingActivityGUIDs = Sets.newHashSet();
        for (Schedule schedule : plan.getStrategy().getAllPossibleSchedules()) {
            for (Activity activity : schedule.getActivities()) {
                existingActivityGUIDs.add(activity.getGuid());
            }
        }
        
        ArgumentCaptor<SchedulePlan> spCaptor = ArgumentCaptor.forClass(SchedulePlan.class);
        service.createSchedulePlan(app, plan);
        
        verify(mockSchedulePlanDao).createSchedulePlan(any(), spCaptor.capture());
        
        SchedulePlan updatedPlan = spCaptor.getValue();
        assertNotEquals(updatedPlan.getGuid(), "AAA");
        assertNotEquals(updatedPlan.getVersion(), new Long(2L));
        for (Schedule schedule : plan.getStrategy().getAllPossibleSchedules()) {
            for (Activity activity : schedule.getActivities()) {
                assertFalse( existingActivityGUIDs.contains(activity.getGuid()) );
            }
        }
        
    }
    @Test
    public void schedulePlanSetsAppIdOnCreate() {
        DynamoApp anotherApp = getAnotherApp();
        SchedulePlan plan = constructSimpleSchedulePlan();
        // Just pass it back, the service should set the appId
        when(mockSchedulePlanDao.createSchedulePlan(any(), any())).thenReturn(plan);
        
        plan = service.createSchedulePlan(anotherApp, plan);
        assertEquals(plan.getAppId(), "another-app");
    }
    
    @Test
    public void schedulePlanSetsAppIdOnUpdate() {
        DynamoApp anotherApp = getAnotherApp();
        SchedulePlan plan = constructSimpleSchedulePlan();
        // Just pass it back, the service should set the appId
        when(mockSchedulePlanDao.getSchedulePlan(anotherApp.getIdentifier(), plan.getGuid())).thenReturn(plan);
        when(mockSchedulePlanDao.updateSchedulePlan(any(), any())).thenReturn(plan);
        
        plan = service.updateSchedulePlan(anotherApp, plan);
        assertEquals(plan.getAppId(), "another-app");
    }
    
    @Test
    public void validatesOnCreate() {
        // Check that 1) validation is called and 2) the app's enumerations are used in the validation
        SchedulePlan plan = constructorInvalidSchedulePlan();
        try {
            service.createSchedulePlan(app, plan);
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertEquals(
                    e.getErrors().get("strategy.scheduleCriteria[0].schedule.activities[0].task.identifier").get(0),
                    "strategy.scheduleCriteria[0].schedule.activities[0].task.identifier 'DDD' is not in enumeration: tapTest, taskGuid, CCC");
            assertEquals(e.getErrors().get("strategy.scheduleCriteria[0].criteria.allOfGroups").get(0),
                    "strategy.scheduleCriteria[0].criteria.allOfGroups 'FFF' is not in enumeration: AAA");
            assertEquals(e.getErrors().get("strategy.scheduleCriteria[0].criteria.noneOfStudyIds").get(0),
                    "strategy.scheduleCriteria[0].criteria.noneOfStudyIds 'studyD' is not in enumeration: <empty>");
        }
    }

    @Test
    public void validatesOnUpdate() {
        // Check that 1) validation is called and 2) the app's enumerations are used in the validation
        SchedulePlan plan = constructorInvalidSchedulePlan();
        when(mockSchedulePlanDao.getSchedulePlan(app.getIdentifier(), plan.getGuid())).thenReturn(plan);
        try {
            service.updateSchedulePlan(app, plan);
            fail("Should have thrown exception");
        } catch(InvalidEntityException e) {
            assertEquals(
                    e.getErrors().get("strategy.scheduleCriteria[0].schedule.activities[0].task.identifier").get(0),
                    "strategy.scheduleCriteria[0].schedule.activities[0].task.identifier 'DDD' is not in enumeration: tapTest, taskGuid, CCC");
            assertEquals(e.getErrors().get("strategy.scheduleCriteria[0].criteria.allOfGroups").get(0),
                    "strategy.scheduleCriteria[0].criteria.allOfGroups 'FFF' is not in enumeration: AAA");
            assertEquals(e.getErrors().get("strategy.scheduleCriteria[0].criteria.noneOfStudyIds").get(0),
                    "strategy.scheduleCriteria[0].criteria.noneOfStudyIds 'studyD' is not in enumeration: <empty>");
        }
    }
    
    @Test
    public void getSchedulePlansExcludeDeleted() throws Exception {
        List<SchedulePlan> plans = Lists.newArrayList(SchedulePlan.create());
        when(mockSchedulePlanDao.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TEST_APP_ID, false)).thenReturn(plans);
        
        List<SchedulePlan> returned = service.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TEST_APP_ID, false);
        assertEquals(returned, plans);
        
        verify(mockSchedulePlanDao).getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TEST_APP_ID, false);
    }
    
    @Test
    public void getSchedulePlansIncludeDeleted() throws Exception {
        List<SchedulePlan> plans = Lists.newArrayList(SchedulePlan.create());
        when(mockSchedulePlanDao.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TEST_APP_ID, true)).thenReturn(plans);
        
        List<SchedulePlan> returned = service.getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TEST_APP_ID, true);
        assertEquals(returned, plans);
        
        verify(mockSchedulePlanDao).getSchedulePlans(ClientInfo.UNKNOWN_CLIENT, TEST_APP_ID, true);
    }
    
    @Test
    public void deleteSchedulePlan() {
        service.deleteSchedulePlan(TEST_APP_ID, "planGuid");
        
        verify(mockSchedulePlanDao).deleteSchedulePlan(TEST_APP_ID, "planGuid");
    }
    
    @Test
    public void deleteSchedulePlanPermanently() {
        service.deleteSchedulePlanPermanently(TEST_APP_ID, "planGuid");
        
        verify(mockSchedulePlanDao).deleteSchedulePlanPermanently(TEST_APP_ID, "planGuid");
    }
    
    private SchedulePlan constructorInvalidSchedulePlan() {
        Schedule schedule = new Schedule();
        schedule.addActivity(new Activity.Builder().withTask("DDD").build());
        
        Criteria criteria = TestUtils.createCriteria(null, null, Sets.newHashSet("FFF"), null);
        criteria.setNoneOfStudyIds(ImmutableSet.of("studyD"));
        ScheduleCriteria scheduleCriteria = new ScheduleCriteria(schedule, criteria);
        
        CriteriaScheduleStrategy strategy = new CriteriaScheduleStrategy();
        strategy.addCriteria(scheduleCriteria);
        
        SchedulePlan plan = new DynamoSchedulePlan();
        plan.setStrategy(strategy);
        return plan;
    }
    
    private DynamoApp getAnotherApp() {
        DynamoApp anotherApp = new DynamoApp();
        anotherApp.setIdentifier("another-app");
        anotherApp.setTaskIdentifiers(Sets.newHashSet("CCC"));
        return anotherApp;
    }
    
    private SchedulePlan constructSimpleSchedulePlan() {
        SchedulePlan plan = TestUtils.getSimpleSchedulePlan(TEST_APP_ID);
        plan.setLabel("Label");
        plan.setGuid("BBB");
        plan.getStrategy().getAllPossibleSchedules().get(0).setExpires("P3D");
        return plan;
    }
    
    private SchedulePlan constructSchedulePlan() {
        Schedule schedule = new Schedule();
        schedule.setScheduleType(ScheduleType.ONCE);
        // No identifier, which is the key here. This is valid, but we fill it out during saves as a convenience 
        // for the client. No longer required in the API.
        // Create a schedule plan with 3 activities to verify all activities are processed.
        schedule.addActivity(new Activity.Builder().withGuid("A").withLabel("Activity 1")
                .withPublishedSurvey(null, surveyGuid1).build());
        schedule.addActivity(new Activity.Builder().withGuid("B").withLabel("Activity 2").withTask("taskGuid").build());
        schedule.addActivity(new Activity.Builder().withGuid("C").withLabel("Activity 3")
                .withSurvey(null, surveyGuid2, DateTime.now()).build());
        
        SimpleScheduleStrategy strategy = new SimpleScheduleStrategy();
        strategy.setSchedule(schedule);
        
        SchedulePlan plan = new DynamoSchedulePlan();
        plan.setLabel("This is a label");
        plan.setStrategy(strategy);
        plan.setAppId(TEST_APP_ID);
        plan.setGuid("BBB");
        return plan;
    }
    
}
