package org.sagebionetworks.bridge.services;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.BridgeConstants.NEGATIVE_OFFSET_ERROR;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.testng.Assert.assertSame;

import java.util.List;
import java.util.Optional;

import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.RequestContext;
import org.sagebionetworks.bridge.dao.AccountDao;
import org.sagebionetworks.bridge.dao.ExternalIdDao;
import org.sagebionetworks.bridge.exceptions.BadRequestException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.PagedResourceList;
import org.sagebionetworks.bridge.models.accounts.Account;
import org.sagebionetworks.bridge.models.accounts.AccountId;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifier;
import org.sagebionetworks.bridge.models.accounts.ExternalIdentifierInfo;
import org.sagebionetworks.bridge.models.apps.App;
import org.sagebionetworks.bridge.models.studies.Enrollment;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

public class ExternalIdServiceTest {

    private static final String ID = "AAA";
    private static final String STUDY_ID = "studyId";
    
    private App app;
    private ExternalIdentifier extId;
    
    @Mock
    private ExternalIdDao mockExternalIdDao;
    
    @Mock
    private StudyService mockStudyService;
    
    @Mock
    private AccountDao mockAccountDao;
    
    @InjectMocks
    private ExternalIdService externalIdService;

    @BeforeMethod
    public void before() {
        MockitoAnnotations.initMocks(this);
        
        RequestContext.set(new RequestContext.Builder()
                .withCallerAppId(TEST_APP_ID).build());
        app = App.create();
        app.setIdentifier(TEST_APP_ID);
        extId = ExternalIdentifier.create(TEST_APP_ID, ID);
        extId.setStudyId(STUDY_ID);
    }
    
    @AfterMethod
    public void after() {
        RequestContext.set(null);
    }
    
    @Test
    public void getExternalIds() {
        List<ExternalIdentifierInfo> list = ImmutableList.of(new ExternalIdentifierInfo(null, null, true),
                new ExternalIdentifierInfo(null, null, true));
        PagedResourceList<ExternalIdentifierInfo> page = new PagedResourceList<>(list, 100);
        
        when(mockExternalIdDao.getPagedExternalIds(TEST_APP_ID, STUDY_ID, "idFilter", 10, 50))
            .thenReturn(page);
        
        PagedResourceList<ExternalIdentifierInfo> retValue = externalIdService.getPagedExternalIds(TEST_APP_ID, STUDY_ID, "idFilter", 10, 50);
        assertSame(retValue, page);
        
        verify(mockExternalIdDao).getPagedExternalIds(TEST_APP_ID, STUDY_ID, "idFilter", 10, 50);
    }
    
    @Test
    public void getExternalIdsNullParams() {
        List<ExternalIdentifierInfo> list = ImmutableList.of(new ExternalIdentifierInfo(null, null, true),
                new ExternalIdentifierInfo(null, null, true));
        PagedResourceList<ExternalIdentifierInfo> page = new PagedResourceList<>(list, 100);
        
        when(mockExternalIdDao.getPagedExternalIds(TEST_APP_ID, STUDY_ID, null, null, null)).thenReturn(page);
        
        PagedResourceList<ExternalIdentifierInfo> retValue = externalIdService.getPagedExternalIds(TEST_APP_ID, STUDY_ID, null, null, null);
        assertSame(retValue, page);
        
        verify(mockExternalIdDao).getPagedExternalIds(TEST_APP_ID, STUDY_ID, null, null, null);
    }
    
    @Test(expectedExceptions = BadRequestException.class, expectedExceptionsMessageRegExp = NEGATIVE_OFFSET_ERROR)
    public void getPagedExternalIdsNegativeOffset() {
        externalIdService.getPagedExternalIds(TEST_APP_ID, STUDY_ID, null, -5, null);
    }
    
    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = ExternalIdService.PAGE_SIZE_ERROR)
    public void getPagedExternalIdsPageTooSmall() {
        externalIdService.getPagedExternalIds(TEST_APP_ID, STUDY_ID, null, null, 0);
    }
    
    @Test(expectedExceptions = BadRequestException.class, 
            expectedExceptionsMessageRegExp = ExternalIdService.PAGE_SIZE_ERROR)
    public void getPagedExternalIdsPageTooLarge() {
        externalIdService.getPagedExternalIds(TEST_APP_ID, STUDY_ID, null, null, 10000);
    }
    
    @Test
    public void deleteExternalIdPermanently() {
        AccountId accountId = AccountId.forExternalId(TEST_APP_ID, ID);
        when(mockAccountDao.getAccount(accountId)).thenReturn(Optional.of(Account.create()));
        
        externalIdService.deleteExternalIdPermanently(app, extId);
        
        verify(mockExternalIdDao).deleteExternalId(extId);
    }

    @Test(expectedExceptions = EntityNotFoundException.class)
    public void deleteExternalIdPermanentlyMissingThrows() {
        AccountId accountId = AccountId.forExternalId(TEST_APP_ID, ID);
        when(mockAccountDao.getAccount(accountId)).thenReturn(Optional.empty());
        
        externalIdService.deleteExternalIdPermanently(app, extId);
    }

    @Test(expectedExceptions = EntityNotFoundException.class)
    public void deleteExternalIdPermanentlyOutsideStudiesThrows() {
        RequestContext.set(new RequestContext.Builder()
                .withCallerAppId(TEST_APP_ID)
                .withOrgSponsoredStudies(ImmutableSet.of("studyA", "studyB")).build());
        
        Enrollment en = Enrollment.create(TEST_APP_ID, "studyC", "userId");
        
        Account account = Account.create();
        account.setId("userId");
        account.getEnrollments().add(en);
        
        AccountId accountId = AccountId.forExternalId(TEST_APP_ID, ID);
        when(mockAccountDao.getAccount(accountId)).thenReturn(Optional.of(account));
        
        externalIdService.deleteExternalIdPermanently(app, extId);
    }
}
