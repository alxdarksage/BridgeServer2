package org.sagebionetworks.bridge.spring.controllers;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.sagebionetworks.bridge.dynamodb.DynamoApp;
import org.sagebionetworks.bridge.dynamodb.DynamoParticipantData;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.ForwardCursorPagedResourceList;
import org.sagebionetworks.bridge.models.ParticipantData;
import org.sagebionetworks.bridge.models.StatusMessage;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountId;
import org.sagebionetworks.bridge.models.accounts.ConsentStatus;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.accounts.UserSession;
import org.sagebionetworks.bridge.models.subpopulations.SubpopulationGuid;
import org.sagebionetworks.bridge.services.AccountService;
import org.sagebionetworks.bridge.services.AppService;
import org.sagebionetworks.bridge.services.ParticipantDataService;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.sagebionetworks.bridge.Roles.ADMIN;
import static org.sagebionetworks.bridge.Roles.DEVELOPER;
import static org.sagebionetworks.bridge.Roles.WORKER;
import static org.sagebionetworks.bridge.TestConstants.IDENTIFIER;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.sagebionetworks.bridge.TestConstants.TEST_USER_ID;
import static org.sagebionetworks.bridge.TestUtils.assertCreate;
import static org.sagebionetworks.bridge.TestUtils.assertCrossOrigin;
import static org.sagebionetworks.bridge.TestUtils.assertDelete;
import static org.sagebionetworks.bridge.TestUtils.assertGet;
import static org.sagebionetworks.bridge.TestUtils.createJson;
import static org.sagebionetworks.bridge.TestUtils.mockRequestBody;
import static org.sagebionetworks.bridge.models.ResourceList.NEXT_PAGE_OFFSET_KEY;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;

public class ParticipantDataControllerTest extends Mockito {

    private static final String OFFSET_KEY = "anOffsetKey";
    private static final String PAGE_SIZE_STRING = "10";
    private static final int PAGE_SIZE_INT = 10;
    private static final AccountId OTHER_ACCOUNT_ID = AccountId.forId(TEST_APP_ID, TEST_USER_ID);

    @Mock
    ParticipantDataService mockParticipantDataService;

    @Mock
    AppService mockAppService;

    @Mock
    AccountService mockAccountService;

    @Mock
    Account mockAccount;

    @Mock
    Account mockOtherAccount;

    @Mock
    HttpServletRequest mockRequest;

    @Captor
    ArgumentCaptor<ParticipantData> participantDataCaptor;

    @InjectMocks
    @Spy
    ParticipantDataController controller;

    UserSession session;
    ParticipantData participantData;

    @BeforeMethod
    public void beforeMethod() {
        MockitoAnnotations.initMocks(this);

        DynamoApp app = new DynamoApp();
        app.setIdentifier(TEST_APP_ID);

        StudyParticipant participant = new StudyParticipant.Builder().withId(TEST_USER_ID)
                .withRoles(Sets.newHashSet(DEVELOPER)).build();

        doReturn(mockOtherAccount).when(mockAccountService).getAccount(OTHER_ACCOUNT_ID);

        ConsentStatus status = new ConsentStatus.Builder().withName("Name").withGuid(SubpopulationGuid.create("GUID"))
                .withConsented(true).withRequired(true).withSignedMostRecentConsent(true).build();
        Map<SubpopulationGuid, ConsentStatus> statuses = Maps.newHashMap();
        statuses.put(SubpopulationGuid.create(status.getSubpopulationGuid()), status);

        session = new UserSession(participant);
        session.setAppId(TEST_APP_ID);
        session.setAuthenticated(true);
        session.setConsentStatuses(statuses);

        participantData = createParticipantData("a", "b");

        doReturn(app).when(mockAppService).getApp(TEST_APP_ID);
        doReturn(TEST_USER_ID).when(mockAccount).getId();
        doReturn(session).when(controller).getSessionIfItExists();
        doReturn(session).when(controller).getAuthenticatedSession(ADMIN, WORKER);
        doReturn(session).when(controller).getAuthenticatedAndConsentedSession();

        doReturn(mockRequest).when(controller).request();
    }

    @Test
    public void verifyAnnotations() throws Exception {
        assertCrossOrigin(ParticipantDataController.class);
        assertGet(ParticipantDataController.class, "getAllDataForUser");
        assertGet(ParticipantDataController.class, "getDataByIdentifierForSelf");
        assertGet(ParticipantDataController.class, "getAllDataForAdminWorker");
        assertGet(ParticipantDataController.class, "getDataByIdentifierForAdminWorker");
        assertCreate(ParticipantDataController.class, "saveDataForSelf");
        assertCreate(ParticipantDataController.class, "saveDataForAdminWorker");
        assertDelete(ParticipantDataController.class, "deleteDataByIdentifier");
        assertDelete(ParticipantDataController.class, "deleteDataByIdentifier");
        assertDelete(ParticipantDataController.class, "deleteDataForAdmin");
    }

    @Test
    public void testGetAllDataForSelf() {
        doReturn(makeResults()).when(mockParticipantDataService).getAllParticipantData(TEST_USER_ID, OFFSET_KEY, PAGE_SIZE_INT);

        ForwardCursorPagedResourceList<String> result = controller.getAllDataForSelf(OFFSET_KEY, PAGE_SIZE_STRING);

        assertEquals(NEXT_PAGE_OFFSET_KEY, result.getNextPageOffsetKey());
        assertEquals(result.getItems().get(0), IDENTIFIER);
        assertEquals(result.getItems().get(1), IDENTIFIER);
    }

    @Test
    public void testGetDataByIdentifierForSelf() {
        doReturn(participantData).when(mockParticipantDataService).getParticipantData(session.getId(), IDENTIFIER);

        ParticipantData result = controller.getDataByIdentifierForSelf(IDENTIFIER);

        assertEquals(result.getUserId(), participantData.getUserId());
        assertEquals(result.getIdentifier(), participantData.getIdentifier());
        assertEquals(result.getData(), participantData.getData());
        assertEquals(result.getVersion(), participantData.getVersion());
        assertSame(result, participantData);
    }

    @Test
    public void testSaveDataForSelf() throws Exception {
        String json = createJson("{'userId':'aUserId', 'data':{'field1':'a','field2':'b'}}");
        mockRequestBody(mockRequest, json);

        StatusMessage result = controller.saveDataForSelf(IDENTIFIER);
        assertEquals(result.getMessage(), "Participant data saved.");

        verify(mockParticipantDataService).saveParticipantData(anyString(), anyString(),
                participantDataCaptor.capture());

        ParticipantData capture = participantDataCaptor.getValue();
        System.out.println(capture);

        assertNull(capture.getUserId());
        assertNull(capture.getIdentifier());
        assertNull(capture.getVersion());
        assertEquals(capture.getData(), participantData.getData());
    }

    @Test
    public void testDeleteDataByIdentifier() {
    }

    @Test
    public void testGetAllDataForAdminWorker() {
    }

    @Test
    public void testDeleteAllParticipantData() {
    }

    @Test
    public void testGetDataByIdentifierForAdminWorker() {
    }

    @Test
    public void testSaveDataRecordForAdminWorker() {
    }

    @Test
    public void testDeleteDataRecordForAdmin() {
    }

    @Test(expectedExceptions = EntityNotFoundException.class,
            expectedExceptionsMessageRegExp = ".*Account not found.*")
    public void testGetAllDataForAdminWorkerAccountNotFound() {
        doReturn(session).when(controller).getAuthenticatedSession(ADMIN, WORKER);
        reset(mockAccountService);
        controller.getAllDataForAdminWorker(TEST_APP_ID, TEST_USER_ID, OFFSET_KEY, PAGE_SIZE_STRING);
    }

    @Test(expectedExceptions = EntityNotFoundException.class,
            expectedExceptionsMessageRegExp = ".*Account not found.*")
    public void testDeleteAllParticipantDataAccountNotFound() {
        doReturn(session).when(controller).getAuthenticatedSession(ADMIN);
        reset(mockAccountService);
        controller.deleteAllParticipantDataForAdmin(TEST_APP_ID, TEST_USER_ID);
    }

    @Test(expectedExceptions = EntityNotFoundException.class,
            expectedExceptionsMessageRegExp = ".*Account not found.*")
    public void testGetDataByIdentifierForAdminWorkerAccountNotFound() {
        doReturn(session).when(controller).getAuthenticatedSession(ADMIN, WORKER);
        reset(mockAccountService);
        controller.getDataByIdentifierForAdminWorker(TEST_APP_ID, TEST_USER_ID, IDENTIFIER);
    }

    @Test(expectedExceptions =  EntityNotFoundException.class,
            expectedExceptionsMessageRegExp = ".*Account not found.*")
    public void testSaveDataForAdminWorker() {
        doReturn(session).when(controller).getAuthenticatedSession(ADMIN, WORKER);
        reset(mockAccountService);
        controller.saveDataForAdminWorker(TEST_APP_ID, TEST_USER_ID, IDENTIFIER);
    }

    @Test(expectedExceptions =  EntityNotFoundException.class,
            expectedExceptionsMessageRegExp = ".*Account not found.*")
    public void testDeleteDataForAdmin() {
        doReturn(session).when(controller).getAuthenticatedSession(ADMIN);
        reset(mockAccountService);
        controller.deleteDataForAdmin(TEST_APP_ID, TEST_USER_ID, IDENTIFIER);
    }

    private ForwardCursorPagedResourceList<ParticipantData> makeResults() {
        List<ParticipantData> list = Lists.newArrayList();
        list.add(createParticipantData("a", "b"));
        list.add(createParticipantData("c", "d"));

        return new ForwardCursorPagedResourceList<>(list, NEXT_PAGE_OFFSET_KEY);
    }

    private static DynamoParticipantData createParticipantData(String fieldValue1, String fieldValue2) {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("field1", fieldValue1);
        node.put("field2", fieldValue2);
        DynamoParticipantData participantData = new DynamoParticipantData();
        participantData.setUserId(TEST_USER_ID);
        participantData.setIdentifier(IDENTIFIER);
        participantData.setData(node);
        return participantData;
    }
}