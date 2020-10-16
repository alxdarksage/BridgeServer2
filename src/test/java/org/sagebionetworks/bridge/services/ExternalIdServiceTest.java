package org.sagebionetworks.bridge.services;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.sagebionetworks.bridge.TestConstants.TEST_STUDY_ID;
import static org.testng.Assert.assertEquals;

import java.util.Optional;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.models.studies.Enrollment;

public class ExternalIdServiceTest {

    private static final String ID = "AAA";
    private static final String HEALTH_CODE = "healthCode";
    
    private App app;
    
    @Mock
    private AppService mockAppService;
    
    @Mock
    private AccountService mockAccountService;
    
    @Mock
    private ParticipantService mockParticipantService;
        
    @Mock
    private StudyService studyService;
    
    @InjectMocks
    private ExternalIdService externalIdService;

    @BeforeMethod
    public void before() {
        MockitoAnnotations.initMocks(this);
        
        RequestContext.set(new RequestContext.Builder()
                .withCallerAppId(TEST_APP_ID).build());
        app = App.create();
        app.setIdentifier(TEST_APP_ID);
    }
    
    @AfterMethod
    public void after() {
        RequestContext.set(null);
    }
    
    @Test
    public void getExternalId() {
        Account account = Account.create();
        account.setHealthCode(HEALTH_CODE);
        Enrollment en = Enrollment.create(TEST_STUDY_ID, ID);
        account.getEnrollments().add(en);
        when(mockAccountService.getAccount(any())).thenReturn(account);
        
        Optional<ExternalIdentifier> retrieved = externalIdService.getExternalId(TEST_APP_ID, ID);
        assertEquals(retrieved.get().getAppId(), TEST_APP_ID);
        assertEquals(retrieved.get().getHealthCode(), HEALTH_CODE);
        assertEquals(retrieved.get().getIdentifier(), ID);
        assertEquals(retrieved.get().getStudyId(), TEST_STUDY_ID);
    }
    /*
    @Test
    public void getExternalIdNoExtIdReturnsEmptyOptional() {
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.empty());
        
        Optional<ExternalIdentifier> optionalId = externalIdService.getExternalId(TEST_APP_ID, ID);
        assertFalse(optionalId.isPresent());
    }

    @Test
    public void getExternalIdNullExtIdReturnsEmptyOptional() {
        Optional<ExternalIdentifier> optionalId = externalIdService.getExternalId(TEST_APP_ID, null);
        assertFalse(optionalId.isPresent());
    }
    
    @Test
    public void getExternalIds() {
        externalIdService.getExternalIds("offsetKey", 10, "idFilter", true);
        
        verify(externalIdDao).getExternalIds(TEST_APP_ID, "offsetKey", 10, "idFilter", true);
    }
    
    @Test
    public void getExternalIdsDefaultsPageSize() {
        externalIdService.getExternalIds(null, null, null, null);
        
        verify(externalIdDao).getExternalIds(TEST_APP_ID, null, BridgeConstants.API_DEFAULT_PAGE_SIZE,
                null, null);
    }
    
    @Test(expectedExceptions = BadRequestException.class)
    public void getExternalIdsUnderMinPageSizeThrows() {
        externalIdService.getExternalIds(null, 0, null, null);
    }
    
    @Test(expectedExceptions = BadRequestException.class)
    public void getExternalIdsOverMaxPageSizeThrows() {
        externalIdService.getExternalIds(null, 10000, null, null);
    }
    
    @Test
    public void createExternalId() {
        when(studyService.getStudy(TEST_APP_ID, STUDY_ID, false))
            .thenReturn(Study.create());
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.empty());
        
        externalIdService.createExternalId(extId, false);
        
        verify(externalIdDao).createExternalId(extId);
    }
    
    @Test
    public void createExternalIdEnforcesAppId() {
        when(studyService.getStudy(TEST_APP_ID, STUDY_ID, false))
            .thenReturn(Study.create());
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.empty());
        
        ExternalIdentifier newExtId = ExternalIdentifier.create("some-dumb-id", ID);
        newExtId.setStudyId(STUDY_ID);
        externalIdService.createExternalId(newExtId, false);
        
        // still matches and verifies
        verify(externalIdDao).createExternalId(extId);        
    }
    
    @Test
    public void createExternalIdSetsStudyIdIfMissingAndUnambiguous() {
        when(studyService.getStudy(TEST_APP_ID, STUDY_ID, false))
            .thenReturn(Study.create());
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.empty());

        RequestContext.set(new RequestContext.Builder()
                .withCallerAppId(TEST_APP_ID)
                .withOrgSponsoredStudies(STUDIES).build());

        ExternalIdentifier newExtId = ExternalIdentifier.create(TEST_APP_ID,
                extId.getIdentifier());
        externalIdService.createExternalId(newExtId, false);

        // still matches and verifies
        verify(externalIdDao).createExternalId(extId);
    }
    
    @Test(expectedExceptions = InvalidEntityException.class)
    public void createExternalIdDoesNotSetStudyIdAmbiguous() {
        extId.setStudyId(null); // not set by caller
        
        RequestContext.set(new RequestContext.Builder()
                .withCallerAppId(TEST_APP_ID)
                .withCallerEnrolledStudies(ImmutableSet.of(STUDY_ID, "anotherStudy")).build());
        
        externalIdService.createExternalId(extId, false);
    }

    @Test(expectedExceptions = InvalidEntityException.class)
    public void createExternalIdValidates() {
        externalIdService.createExternalId(ExternalIdentifier.create("nonsense", "nonsense"), false);
    }
    
    @Test(expectedExceptions = EntityAlreadyExistsException.class)
    public void createExternalIdAlreadyExistsThrows() {
        when(studyService.getStudy(TEST_APP_ID, STUDY_ID, false))
            .thenReturn(Study.create());
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.of(extId));
        extId.setStudyId(STUDY_ID);
        
        externalIdService.createExternalId(extId, false);
    }
    
    @Test
    public void deleteExternalIdPermanently() {
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.of(extId));
        
        externalIdService.deleteExternalIdPermanently(app, extId);
        
        verify(externalIdDao).deleteExternalId(extId);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class)
    public void deleteExternalIdPermanentlyMissingThrows() {
        when(externalIdDao.getExternalId(TEST_APP_ID, extId.getIdentifier())).thenReturn(Optional.empty());
        
        externalIdService.deleteExternalIdPermanently(app, extId);
    }
    
    @Test(expectedExceptions = EntityNotFoundException.class)
    public void deleteExternalIdPermanentlyOutsideStudiesThrows() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerAppId(TEST_APP_ID)
                .withOrgSponsoredStudies(STUDIES).build());        
        extId.setStudyId("someOtherId");
        when(externalIdDao.getExternalId(TEST_APP_ID, ID)).thenReturn(Optional.of(extId));
        
        externalIdService.deleteExternalIdPermanently(app, extId);
    }
    
    @Test
    public void unassignExternalId() {
        Account account = Account.create();
        account.setAppId(TEST_APP_ID);
        account.setHealthCode(HEALTH_CODE);
        
        externalIdService.unassignExternalId(account, ID);
        
        verify(externalIdDao).unassignExternalId(account, ID);
    }

    @Test
    public void unassignExternalIdNullDoesNothing() {
        Account account = Account.create();
        account.setAppId(TEST_APP_ID);
        account.setHealthCode(HEALTH_CODE);
        
        externalIdService.unassignExternalId(account, null);
        
        verify(externalIdDao, never()).unassignExternalId(account, ID);
    }
    */
}
