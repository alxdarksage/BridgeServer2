package org.sagebionetworks.bridge.dynamodb;

import static com.amazonaws.services.dynamodbv2.model.ComparisonOperator.EQ;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.sagebionetworks.bridge.models.surveys.SurveyElementConstants.SURVEY_QUESTION_TYPE;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotEquals;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertSame;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import java.util.List;

import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBMapper;
import com.amazonaws.services.dynamodbv2.datamodeling.DynamoDBQueryExpression;
import com.amazonaws.services.dynamodbv2.datamodeling.PaginatedQueryList;
import com.amazonaws.services.dynamodbv2.datamodeling.QueryResultPage;
import com.amazonaws.services.dynamodbv2.model.Condition;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;

import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.exceptions.BridgeServiceException;
import org.sagebionetworks.bridge.exceptions.ConcurrentModificationException;
import org.sagebionetworks.bridge.exceptions.EntityNotFoundException;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolder;
import org.sagebionetworks.bridge.models.GuidCreatedOnVersionHolderImpl;
import org.sagebionetworks.bridge.models.surveys.BloodPressureConstraints;
import org.sagebionetworks.bridge.models.surveys.IntegerConstraints;
import org.sagebionetworks.bridge.models.surveys.Survey;
import org.sagebionetworks.bridge.models.surveys.SurveyElement;
import org.sagebionetworks.bridge.models.surveys.SurveyElementConstants;
import org.sagebionetworks.bridge.models.surveys.SurveyInfoScreen;
import org.sagebionetworks.bridge.models.surveys.SurveyQuestion;
import org.sagebionetworks.bridge.models.surveys.SurveyRule;
import org.sagebionetworks.bridge.models.surveys.UIHint;
import org.sagebionetworks.bridge.models.upload.UploadSchema;
import org.sagebionetworks.bridge.services.UploadSchemaService;

public class DynamoSurveyDaoTest extends Mockito {
    static final long CREATED_ON = TestConstants.CREATED_ON.getMillis();
    static final int SCHEMA_REV = 42;
    static final String GUID = "oneGuid";
    static final String SURVEY_ID = "test-survey";
    static final GuidCreatedOnVersionHolder SURVEY_KEY = new GuidCreatedOnVersionHolderImpl(GUID, CREATED_ON);
    static final String IDENTIFIER_PREFIX = "identifier:";
    static final String SURVEY_IDENTIFIER = IDENTIFIER_PREFIX + SURVEY_ID;
    static final GuidCreatedOnVersionHolder SURVEY_IDENTIFIER_KEYS = new GuidCreatedOnVersionHolderImpl(SURVEY_IDENTIFIER, CREATED_ON);
    
    @Mock
    DynamoDBMapper mockSurveyMapper;
    
    @Mock
    DynamoDBMapper mockSurveyElementMapper;
    
    @Mock
    UploadSchemaService mockUploadSchemaService;
    
    @Mock
    PaginatedQueryList<DynamoSurveyElement> mockElementQueryList;
    
    @Mock
    QueryResultPage<DynamoSurvey> mockResultsPage;
    
    @Mock
    QueryResultPage<DynamoSurveyElement> mockElementResultsPage;
    
    @Captor
    ArgumentCaptor<DynamoDBQueryExpression<DynamoSurvey>> queryCaptor;
    
    @Captor
    ArgumentCaptor<DynamoDBQueryExpression<DynamoSurveyElement>> elementQueryCaptor;
    
    @Captor
    ArgumentCaptor<Survey> surveyCaptor;
    
    @Captor
    ArgumentCaptor<List<DynamoSurveyElement>> elementsCaptor;
    
    @InjectMocks
    @Spy
    DynamoSurveyDao dao;
    
    Survey survey;
    
    @BeforeMethod
    public void beforeMethod() {
        MockitoAnnotations.initMocks(this);
        // These are not injected correctly
        dao.setSurveyMapper(mockSurveyMapper);
        dao.setSurveyElementMapper(mockSurveyElementMapper);
        
        doReturn(GUID).when(dao).generateGuid();
        DateTimeUtils.setCurrentMillisFixed(CREATED_ON);

        survey = new DynamoSurvey(GUID, CREATED_ON);
        survey.setIdentifier(SURVEY_ID);
        survey.setAppId(TEST_APP_ID);
        
        // mock schema dao
        UploadSchema schema = UploadSchema.create();
        schema.setRevision(SCHEMA_REV);

        when(mockUploadSchemaService.createUploadSchemaFromSurvey(TEST_APP_ID, survey, true)).thenReturn(
                schema);
    }
    
    @AfterMethod
    public void afterMethod() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    @Test
    public void getSurveyGuidForIdentifier() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setGuid(GUID);
        mockSurveyMapper(survey);

        String result = dao.getSurveyGuidForIdentifier(TEST_APP_ID, SURVEY_ID);
        assertEquals(result, GUID);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);
        Condition rangeKeyCondition = query.getRangeKeyConditions().get("identifier");
        assertEquals(rangeKeyCondition.getComparisonOperator(), EQ.name());
        assertEquals(rangeKeyCondition.getAttributeValueList().get(0).getS(), SURVEY_ID);
        assertFalse(query.isConsistentRead());
        assertEquals(query.getLimit(), new Integer(1));
    }

    public void getSurveyGuidForIdentifier_NoSurvey() {
        // Mock query.
        when(mockResultsPage.getResults()).thenReturn(ImmutableList.of());
        when(mockSurveyMapper.queryPage(eq(DynamoSurvey.class), any())).thenReturn(mockResultsPage);

        // Execute.
        String surveyGuid = dao.getSurveyGuidForIdentifier(TEST_APP_ID, SURVEY_ID);
        assertNull(surveyGuid);

        // Query is verified in previous test.
    }

    @Test
    public void updateSurveySucceedsOnUndeletedSurvey() {
        DynamoSurvey existing = new DynamoSurvey(GUID, CREATED_ON);
        existing.setDeleted(true);
        
        List<Survey> results = Lists.newArrayList(existing);
        doReturn(results).when(mockResultsPage).getResults();
        doReturn(mockResultsPage).when(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), any());
        
        survey.setDeleted(false);
        survey.setName("New title");
        Survey updatedSurvey = dao.updateSurvey(TEST_APP_ID, survey);
        
        assertEquals(updatedSurvey.getName(), "New title");
    }
    
    @Test
    public void publishSurvey() {
        // populate the survey with at least one question
        SurveyQuestion surveyQuestion = new DynamoSurveyQuestion();
        surveyQuestion.setIdentifier("int");
        surveyQuestion.setConstraints(new IntegerConstraints());

        survey.setElements(ImmutableList.of(surveyQuestion));

        // execute and validate
        Survey retval = dao.publishSurvey(TEST_APP_ID, survey, true);
        assertTrue(retval.isPublished());
        assertEquals(retval.getModifiedOn(), CREATED_ON);
        assertEquals(retval.getSchemaRevision().intValue(), SCHEMA_REV);

        verify(mockSurveyMapper).save(same(retval));
    }

    @Test
    public void publishSurveyWithInfoScreensOnly() {
        // populate the survey with an info screen and no questions
        SurveyInfoScreen infoScreen = new DynamoSurveyInfoScreen();
        infoScreen.setIdentifier("test-info-screen");
        infoScreen.setTitle("Test Info Screen");
        infoScreen.setPrompt("This info screen doesn't do anything, other than not being a question.");

        survey.setElements(ImmutableList.of(infoScreen));

        // same test as above, except we *don't* call through to the upload schema DAO
        Survey retval = dao.publishSurvey(TEST_APP_ID, survey, true);
        assertTrue(retval.isPublished());
        assertEquals(retval.getModifiedOn(), CREATED_ON);
        assertNull(retval.getSchemaRevision());

        verify(mockSurveyMapper).save(same(retval));
        verify(mockUploadSchemaService, never()).createUploadSchemaFromSurvey(any(), any(), anyBoolean());
    }

    @Test
    public void deleteSurveyAlsoDeletesSchema() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        survey.setGuid(GUID);
        survey.setIdentifier(SURVEY_ID);
        survey.setCreatedOn(CREATED_ON);
        mockSurveyMapper(survey);
        
        // Execute
        dao.deleteSurveyPermanently(TEST_APP_ID, SURVEY_KEY);

        // Validate backends
        verify(dao).deleteAllElements(GUID, CREATED_ON);
        verify(mockSurveyMapper).delete(survey);
        verify(mockUploadSchemaService).deleteUploadSchemaByIdPermanently(TEST_APP_ID, SURVEY_ID);
    }
    
    @Test
    public void deleteSurveyPermanentlyNoSurvey() {
        List<Survey> results = Lists.newArrayList();
        doReturn(results).when(mockResultsPage).getResults();
        doReturn(mockResultsPage).when(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), any());
        
        GuidCreatedOnVersionHolder keys = new GuidCreatedOnVersionHolderImpl("keys", DateTime.now().getMillis());
        dao.deleteSurveyPermanently(TEST_APP_ID, keys);
        
        verify(mockSurveyMapper, never()).delete(any());
    }
    
    @Test
    public void updateSurveyUndeleteExistingDeletedOK() {
        DynamoSurvey existing = new DynamoSurvey(GUID, CREATED_ON);
        existing.setIdentifier(SURVEY_ID);
        existing.setAppId(TEST_APP_ID);
        existing.setDeleted(true);
        existing.setPublished(false);
        
        List<Survey> results = Lists.newArrayList(existing);
        doReturn(results).when(mockResultsPage).getResults();
        doReturn(mockResultsPage).when(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), any());
        
        survey.setDeleted(false);
        survey.getElements().add(SurveyInfoScreen.create());
        dao.updateSurvey(TEST_APP_ID, survey);

        verify(mockSurveyMapper).save(surveyCaptor.capture());
        assertFalse(surveyCaptor.getValue().isDeleted());
        assertEquals(surveyCaptor.getValue().getElements().size(), 1);
    }
    
    @Test
    public void createSurvey() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        SurveyElement element = new DynamoSurveyElement();
        survey.getElements().add(element);

        Survey result = dao.createSurvey(survey);
        assertSame(result, survey);

        verify(mockSurveyElementMapper).query(eq(DynamoSurveyElement.class), elementQueryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurveyElement> query = elementQueryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getSurveyCompoundKey(), GUID + ":" + Long.toString(CREATED_ON));
        
        verify(mockSurveyElementMapper).batchSave(elementsCaptor.capture());
        assertEquals(elementsCaptor.getValue().size(), 1);
        assertSame(elementsCaptor.getValue().get(0), survey.getElements().get(0));
        
        verify(mockSurveyMapper).save(surveyCaptor.capture());
        assertSame(surveyCaptor.getValue(), survey);
    }
    
    @SuppressWarnings("unchecked")
    @Test
    public void createSurveyConditionalCheckFailedException() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        mockSurveyMapper(survey);
        mockSurveyElementMapper();
        when(mockSurveyElementMapper.query(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementQueryList);

        doThrow(new ConditionalCheckFailedException("")).when(mockSurveyMapper).save(any());
        
        try {
            dao.createSurvey(survey);
            fail("Should have thrown exceptions");
        } catch(ConcurrentModificationException e) {
            verify(mockSurveyElementMapper, times(2)).batchDelete(any(List.class));
        }
    }
    
    @Test(expectedExceptions = BridgeServiceException.class)
    public void createSurveyUnknownException() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        doThrow(new IllegalArgumentException("")).when(mockSurveyMapper).save(any());
        
        dao.createSurvey(survey);
    }
    
    @Test
    public void updateSurvey() {
        DynamoSurvey survey = new DynamoSurvey();
        // Some fields are copied over to the existing survey because they are mutable.
        // verify this.
        survey.setName("Updated Name");
        survey.getElements().clear();
        survey.setCopyrightNotice("Updated Copyright Notice");
        survey.setDeleted(true);
        survey.setVersion(3L);
        
        survey.setGuid(GUID);
        survey.setSchemaRevision(3);
        
        DynamoSurvey saved = new DynamoSurvey();
        saved.setGuid(GUID);
        saved.setCreatedOn(CREATED_ON);
        
        List<DynamoSurvey> surveyList = ImmutableList.of(saved);
        mockSurveyMapper(surveyList);
        
        Survey result = dao.updateSurvey(TEST_APP_ID, survey);
        // Some fields are changed by update
        assertEquals(result.getModifiedOn(), CREATED_ON);
        assertNull(result.getSchemaRevision());
        
        verify(mockSurveyElementMapper).query(eq(DynamoSurveyElement.class), elementQueryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurveyElement> query = elementQueryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getSurveyCompoundKey(), GUID + ":" + Long.toString(CREATED_ON));
        
        verify(mockSurveyElementMapper).batchSave(elementsCaptor.capture());
        assertTrue(elementsCaptor.getValue().isEmpty());
        
        verify(mockSurveyMapper).save(surveyCaptor.capture());
        Survey updated = surveyCaptor.getValue();
        assertEquals(updated.getName(), "Updated Name");
        assertEquals(updated.getCopyrightNotice(), "Updated Copyright Notice");
        assertTrue(updated.isDeleted());
        assertEquals(updated.getVersion(), new Long(3L));
    }
    
    @Test
    public void versionSurvey() {
        DynamoSurvey survey = new DynamoSurvey();
        mockSurveyElementMapper();
        // Mess up some things to verify they are changed by versioning
        survey.setPublished(true);
        survey.setDeleted(true);
        survey.setVersion(100L);
        survey.setCreatedOn(0L);
        survey.setModifiedOn(0L);
        survey.setSchemaRevision(2);
        survey.setElements(ImmutableList.of());
        mockSurveyMapper(survey);
        
        Survey result = dao.versionSurvey(TEST_APP_ID, SURVEY_KEY);
        
        assertFalse(result.isPublished());
        assertFalse(result.isDeleted());
        assertNull(result.getVersion());
        assertEquals(result.getCreatedOn(), CREATED_ON);
        assertEquals(result.getModifiedOn(), CREATED_ON);
        assertNull(result.getSchemaRevision());
        
        verify(mockSurveyElementMapper).batchSave(result.getElements());
        verify(mockSurveyMapper).save(result);
    }

    @Test
    public void publishSurveySchemaRevision() {
        DynamoSurvey survey = new DynamoSurvey();
        // In order to create a schema, there has to be one question in the survey
        DynamoSurveyQuestion element = new DynamoSurveyQuestion();
        element.setType(SurveyElementConstants.SURVEY_QUESTION_TYPE);
        survey.getElements().add(element);
        
        DynamoUploadSchema savedSchema = new DynamoUploadSchema();
        savedSchema.setRevision(3);
        when(mockUploadSchemaService.createUploadSchemaFromSurvey(TEST_APP_ID, survey, true)).thenReturn(savedSchema);
        
        Survey result = dao.publishSurvey(TEST_APP_ID, survey, true);
        assertTrue(survey.isPublished());
        assertEquals(result.getModifiedOn(), CREATED_ON);
        assertEquals(result.getSchemaRevision(), new Integer(3));
        
        verify(mockUploadSchemaService).createUploadSchemaFromSurvey(TEST_APP_ID, survey, true);
    }

    @Test
    public void publishSurveyNoSchemaRevision() {
        DynamoSurvey survey = new DynamoSurvey();
        // In order to create a schema, there has to be one question in the survey
        DynamoSurveyQuestion element = new DynamoSurveyQuestion();
        element.setType(SurveyElementConstants.SURVEY_QUESTION_TYPE);
        survey.getElements().add(element);
        
        DynamoUploadSchema savedSchema = new DynamoUploadSchema();
        savedSchema.setRevision(3);
        when(mockUploadSchemaService.createUploadSchemaFromSurvey(TEST_APP_ID, survey, false)).thenReturn(savedSchema);
        
        Survey result = dao.publishSurvey(TEST_APP_ID, survey, false);
        assertTrue(survey.isPublished());
        assertEquals(result.getModifiedOn(), CREATED_ON);
        assertEquals(result.getSchemaRevision(), new Integer(3));
        
        verify(mockUploadSchemaService).createUploadSchemaFromSurvey(TEST_APP_ID, survey, false);
    }
    
    @Test
    public void publishSurveyNoQuestions() {
        DynamoSurvey survey = new DynamoSurvey();
        
        Survey result = dao.publishSurvey(TEST_APP_ID, survey, true);
        assertTrue(survey.isPublished());
        assertEquals(result.getModifiedOn(), CREATED_ON);
        assertNull(result.getSchemaRevision());
        
        verify(mockUploadSchemaService, never()).createUploadSchemaFromSurvey(any(), any(), anyBoolean());
    }    
    
    @Test
    public void publishSurveyAlreadyPublished() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setPublished(true);
        dao.publishSurvey(TEST_APP_ID, survey, true);
        
        verify(mockUploadSchemaService, never()).createUploadSchemaFromSurvey(any(), any(), anyBoolean());
        verify(mockSurveyMapper, never()).save(any());
    }
    
    @Test(expectedExceptions = ConcurrentModificationException.class)
    public void publishSurveyFailsOnSave() {
        doThrow(new ConditionalCheckFailedException("")).when(mockSurveyMapper).save(any());
        
        DynamoSurvey survey = new DynamoSurvey();
        dao.publishSurvey(TEST_APP_ID, survey, true);
    }

    @Test
    public void deleteSurvey() {
        DynamoSurvey survey = new DynamoSurvey();
        mockSurveyMapper(survey);
        
        dao.deleteSurvey(survey);
        
        verify(mockSurveyMapper).save(surveyCaptor.capture());
        assertTrue(surveyCaptor.getValue().isDeleted());
        
        // EntityNotFoundException cases are handled by the service. This DAO expected survey would 
        // always exist in methods where it is passed in.
    }

    @Test
    public void deleteSurveyPermanently() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        survey.setGuid(GUID);
        survey.setIdentifier(SURVEY_ID);
        mockSurveyMapper(survey);
        
        dao.deleteSurveyPermanently(TEST_APP_ID, SURVEY_KEY);
        
        verify(mockSurveyMapper).delete(survey);
        verify(mockUploadSchemaService).deleteUploadSchemaByIdPermanently(TEST_APP_ID, SURVEY_ID);
    }
    
    @Test
    public void deleteSurveyPermanentlyNotFound() {
        mockSurveyMapper();
        
        dao.deleteSurveyPermanently(TEST_APP_ID, SURVEY_KEY);
        
        verify(mockSurveyMapper, never()).delete(any());
        verify(mockUploadSchemaService, never()).deleteUploadSchemaByIdPermanently(any(), any());
    }
    
    @Test
    public void deleteSurveyPermanentlySchemaNotFound() {
        doThrow(new EntityNotFoundException(UploadSchema.class)).when(mockUploadSchemaService)
                .deleteUploadSchemaByIdPermanently(any(), any());        
        
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        survey.setGuid(GUID);
        survey.setIdentifier(SURVEY_ID);
        mockSurveyMapper(survey);
        
        dao.deleteSurveyPermanently(TEST_APP_ID, SURVEY_KEY);
        
        verify(mockSurveyMapper).delete(survey);
        verify(mockUploadSchemaService).deleteUploadSchemaByIdPermanently(TEST_APP_ID, SURVEY_ID);
    }

    @Test
    public void getSurveyIncludeElements() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setGuid(GUID);
        survey.setCreatedOn(CREATED_ON);
        mockSurveyMapper(survey);
        
        // There is a question that should be retrieved.
        DynamoSurveyQuestion element = new DynamoSurveyQuestion();
        element.setType(SurveyElementConstants.SURVEY_QUESTION_TYPE);
        element.setUiHint(UIHint.BLOODPRESSURE);
        element.setConstraints(new BloodPressureConstraints());
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of(element));
        
        Survey result = dao.getSurvey(TEST_APP_ID, SURVEY_KEY, true);
        assertSame(result, survey);
        assertEquals(result.getElements().size(), 1);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertFalse(query.isScanIndexForward());
        assertEquals(query.getHashKeyValues().getGuid(), GUID);
        assertEquals(query.getHashKeyValues().getCreatedOn(), CREATED_ON);
        
        verify(mockSurveyElementMapper).queryPage(eq(DynamoSurveyElement.class), elementQueryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurveyElement> elementQuery = elementQueryCaptor.getValue();
        assertEquals(elementQuery.getHashKeyValues().getSurveyCompoundKey(), GUID + ":" + CREATED_ON);
    }

    @Test
    public void getSurveyExcludeElements() {
        DynamoSurvey survey = new DynamoSurvey();
        mockSurveyMapper(survey);
        
        // There is a question that should be retrieved.
        DynamoSurveyQuestion element = new DynamoSurveyQuestion();
        element.setType(SurveyElementConstants.SURVEY_QUESTION_TYPE);
        element.setUiHint(UIHint.BLOODPRESSURE);
        element.setConstraints(new BloodPressureConstraints());
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of(element));
        
        Survey result = dao.getSurvey(TEST_APP_ID, SURVEY_KEY, false);
        assertSame(result, survey);
        assertEquals(result.getElements().size(), 0);
        
        verify(mockSurveyElementMapper, never()).queryPage(eq(DynamoSurveyElement.class), any());
    }
    
    @Test
    public void getSurveyNotFound() {
        mockSurveyMapper();
        
        // This does not throw an EntityNotFoundException, that happens in the service
        assertNull(dao.getSurvey(TEST_APP_ID, SURVEY_KEY, true));
    }
    
    @Test
    public void getSurveyGuidForIdentifierNotFound() {
        mockSurveyMapper();

        String result = dao.getSurveyGuidForIdentifier(TEST_APP_ID, SURVEY_ID);
        assertNull(result);
    }
    
    @Test
    public void getSurveyAllVersionsIncludeDeleted() {
        List<DynamoSurvey> surveyList = ImmutableList.of(new DynamoSurvey(), new DynamoSurvey());
        mockSurveyMapper(surveyList);
        
        List<Survey> results = dao.getSurveyAllVersions(TEST_APP_ID, GUID, true);
        assertEquals(results, surveyList);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getGuid(), GUID);
        assertNull(query.getRangeKeyConditions());
        assertFalse(query.isScanIndexForward());
        
        assertEquals(query.getQueryFilter().size(), 1);
        verifyAppIdQueryCondition(query);
    }
    
    @Test
    public void getSurveyAllVersionsExcludeDeleted() {
        List<DynamoSurvey> surveyList = ImmutableList.of(new DynamoSurvey(), new DynamoSurvey());
        mockSurveyMapper(surveyList);
        
        List<Survey> results = dao.getSurveyAllVersions(TEST_APP_ID, GUID, false);
        assertEquals(results, surveyList);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        
        assertEquals(query.getQueryFilter().size(), 2);
        verifyAppIdQueryCondition(query);
        verifyIsDeletedQueryCondition(query);
    }
    
    @Test
    public void getSurveyMostRecentVersion() {
        DynamoSurvey persisted = new DynamoSurvey();
        mockSurveyMapper(persisted);
        mockSurveyElementMapper();

        Survey survey = dao.getSurveyMostRecentVersion(TEST_APP_ID, GUID);
        assertSame(survey, persisted);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getGuid(), GUID);
        assertNull(query.getRangeKeyConditions());
        assertFalse(query.isScanIndexForward());
        
        assertEquals(query.getQueryFilter().size(), 2);
        verifyAppIdQueryCondition(query);
        verifyIsDeletedQueryCondition(query);
    }
    
    @Test
    public void getSurveyMostRecentlyPublishedVersionIncludeElements() {
        DynamoSurvey persisted = new DynamoSurvey();
        mockSurveyMapper(persisted);
        
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of());
        
        Survey result = dao.getSurveyMostRecentlyPublishedVersion(TEST_APP_ID, GUID, true);
        assertSame(result, persisted);

        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getGuid(), GUID);
        
        assertEquals(query.getQueryFilter().size(), 3);
        verifyAppIdQueryCondition(query);
        verifyIsDeletedQueryCondition(query);
        verifyPublishedQueryCondition(query);
        
        verify(mockSurveyElementMapper).queryPage(eq(DynamoSurveyElement.class), any());
    }
    
    @Test
    public void getSurveyMostRecentlyPublishedVersionExcludeElements() {
        DynamoSurvey persisted = new DynamoSurvey();
        mockSurveyMapper(persisted);
        
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of());
        
        dao.getSurveyMostRecentlyPublishedVersion(TEST_APP_ID, GUID, false);
        
        verify(mockSurveyElementMapper, never()).queryPage(eq(DynamoSurveyElement.class), any());
    }
    
    @Test
    public void getAllSurveysMostRecentlyPublishedVersionIncludeDeleted() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setCreatedOn(CREATED_ON);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        // Survey 2A
        DynamoSurvey survey2A = new DynamoSurvey();
        survey2A.setGuid("guidTwo");
        survey2A.setCreatedOn(CREATED_ON);
        // Survey 2B
        DynamoSurvey survey2B = new DynamoSurvey();
        survey2B.setGuid("guidTwo");
        survey2B.setCreatedOn(CREATED_ON-2000L);
        
        mockSurveyMapper(survey1A, survey1B, survey2B, survey2A);
        
        List<Survey> results = dao.getAllSurveysMostRecentlyPublishedVersion(TEST_APP_ID, true);
        assertEquals(results.size(), 2);
        assertEquals(results.get(0).getCreatedOn(), CREATED_ON);
        assertEquals(results.get(1).getCreatedOn(), CREATED_ON);
        assertEquals(ImmutableSet.of(results.get(0).getGuid(), results.get(1).getGuid()),
                ImmutableSet.of(GUID, "guidTwo"));
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);
        
        assertEquals(query.getQueryFilter().size(), 1);
        verifyPublishedQueryCondition(query);
    }
    
    @Test
    public void getAllSurveysMostRecentlyPublishedVersionExcludeDeleted() {
        // Only verifying here that the deleted flag is set in the query
        mockSurveyMapper();
        
        dao.getAllSurveysMostRecentlyPublishedVersion(TEST_APP_ID, false);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        
        assertEquals(query.getQueryFilter().size(), 2);
        verifyPublishedQueryCondition(query);
        verifyIsDeletedQueryCondition(query);
    }
    
    @Test
    public void getAllSurveysMostRecentVersionIncludeDeleted() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setCreatedOn(CREATED_ON);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        // Survey 2A
        DynamoSurvey survey2A = new DynamoSurvey();
        survey2A.setGuid("guidTwo");
        survey2A.setCreatedOn(CREATED_ON);
        // Survey 2B
        DynamoSurvey survey2B = new DynamoSurvey();
        survey2B.setGuid("guidTwo");
        survey2B.setCreatedOn(CREATED_ON-2000L);
        
        mockSurveyMapper(survey1A, survey1B, survey2B, survey2A);
        
        List<Survey> results = dao.getAllSurveysMostRecentVersion(TEST_APP_ID, true);
        assertEquals(results.size(), 2);
        assertEquals(results.get(0).getCreatedOn(), CREATED_ON);
        assertEquals(results.get(1).getCreatedOn(), CREATED_ON);
        assertEquals(ImmutableSet.of(results.get(0).getGuid(), results.get(1).getGuid()),
                ImmutableSet.of(GUID, "guidTwo"));
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);

        assertNull(query.getQueryFilter());
    }

    @Test
    public void getAllSurveysMostRecentVersionExcludeDeleted() {
        // Only verifying here that the deleted flag is set in the query
        mockSurveyMapper();
        
        dao.getAllSurveysMostRecentVersion(TEST_APP_ID, false);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        
        assertEquals(query.getQueryFilter().size(), 1);
        verifyIsDeletedQueryCondition(query);
    }
    
    @Test(expectedExceptions = IllegalStateException.class, expectedExceptionsMessageRegExp = 
            "Calculated the need to query by secondary index, but appId is not set")
    public void secondaryIndexRequiresAppIdWithAppId() {
        dao.getAllSurveysMostRecentVersion(null, true);
    }
    
    @Test
    public void constraintRulesShiftedToQuestionAfterRules() {
        DynamoSurvey survey = new DynamoSurvey();
        mockSurveyMapper(survey);
        
        // This needs to be shifted to the after rules of the question itself
        BloodPressureConstraints constraints = new BloodPressureConstraints();
        constraints.getRules().add(new SurveyRule.Builder().withEndSurvey(true).build());
        SurveyQuestion question = new DynamoSurveyQuestion();
        question.setUiHint(UIHint.BLOODPRESSURE);
        question.setType(SURVEY_QUESTION_TYPE);
        question.setConstraints(constraints);
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of((DynamoSurveyElement)question));
        
        Survey result = dao.getSurvey(TEST_APP_ID, SURVEY_KEY, true);
        SurveyQuestion resultQuestion = result.getUnmodifiableQuestionList().get(0);
        SurveyRule rule = resultQuestion.getAfterRules().get(0);
        assertTrue(rule.getEndSurvey());
    }
    
    @Test
    public void constraintRulesOnQuestionOverwriteConstraintRules() {
        DynamoSurvey survey = new DynamoSurvey();
        mockSurveyMapper(survey);
        
        // This needs to be shifted to the after rules of the question itself
        BloodPressureConstraints constraints = new BloodPressureConstraints();
        // This does not have endSurvey as a rule, but it will be replaced
        constraints.getRules().add(new SurveyRule.Builder().build());
        SurveyQuestion question = new DynamoSurveyQuestion();
        question.setAfterRules(ImmutableList.of(new SurveyRule.Builder().withEndSurvey(true).build()));
        question.setUiHint(UIHint.BLOODPRESSURE);
        question.setType(SURVEY_QUESTION_TYPE);
        question.setConstraints(constraints);
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of((DynamoSurveyElement)question));
        
        Survey result = dao.getSurvey(TEST_APP_ID, SURVEY_KEY, true);
        SurveyQuestion resultQuestion = result.getUnmodifiableQuestionList().get(0);
        SurveyRule rule = resultQuestion.getConstraints().getRules().get(0);
        assertTrue(rule.getEndSurvey());
    }
    
    @Test
    public void getSurveyIncludeElementsByIdentifer() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setAppId(TEST_APP_ID);
        survey.setGuid(GUID);
        survey.setIdentifier(SURVEY_ID);
        survey.setCreatedOn(CREATED_ON);
        mockSurveyMapper(survey);
        
        // There is a question that should be retrieved.
        DynamoSurveyQuestion element = new DynamoSurveyQuestion();
        element.setType(SurveyElementConstants.SURVEY_QUESTION_TYPE);
        element.setUiHint(UIHint.BLOODPRESSURE);
        element.setConstraints(new BloodPressureConstraints());
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of(element));
        
        Survey result = dao.getSurvey(TEST_APP_ID, SURVEY_IDENTIFIER_KEYS, true);
        assertSame(result, survey);
        assertEquals(result.getElements().size(), 1);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);
        
        Condition rangeKeyCondition = query.getRangeKeyConditions().get("identifier");
        assertEquals(rangeKeyCondition.getComparisonOperator(), EQ.name());
        assertEquals(rangeKeyCondition.getAttributeValueList().get(0).getS(), SURVEY_ID);
        
        assertEquals(query.getQueryFilter().size(), 1);
        verifyCreatedOnQueryCondition(query);
        
        verify(mockSurveyElementMapper).queryPage(eq(DynamoSurveyElement.class), elementQueryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurveyElement> elementQuery = elementQueryCaptor.getValue();
        assertEquals(elementQuery.getHashKeyValues().getSurveyCompoundKey(), GUID + ":" + CREATED_ON);
    }
    
    @Test
    public void getSurveyExcludeElementsByIdentifier() {
        DynamoSurvey survey = new DynamoSurvey();
        survey.setGuid(GUID);
        survey.setAppId(TEST_APP_ID);
        survey.setIdentifier(SURVEY_ID);
        survey.setCreatedOn(CREATED_ON);
        mockSurveyMapper(survey);
        
        Survey result = dao.getSurvey(TEST_APP_ID, SURVEY_IDENTIFIER_KEYS, false);
        assertSame(result, survey);
        assertEquals(result.getElements().size(), 0);
        
        verify(mockSurveyElementMapper, never()).queryPage(eq(DynamoSurveyElement.class), any());
    }
    
    @Test
    public void getSurveyAllVersionsIncludeDeletedByIdentifier() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setIdentifier(SURVEY_ID);
        survey1A.setCreatedOn(CREATED_ON);
        survey1A.setDeleted(true);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setIdentifier(SURVEY_ID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        survey1B.setDeleted(true);
        mockSurveyMapper(survey1B, survey1A);
        mockSurveyElementMapper();
        
        List<Survey> results = dao.getSurveyAllVersions(TEST_APP_ID, SURVEY_IDENTIFIER, true);
        assertEquals(results, ImmutableList.of(survey1A, survey1B));
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);

        Condition rangeKeyCondition = query.getRangeKeyConditions().get("identifier");
        assertEquals(rangeKeyCondition.getComparisonOperator(), EQ.name());
        assertEquals(rangeKeyCondition.getAttributeValueList().get(0).getS(), SURVEY_ID);
        
        assertNull(query.getQueryFilter());
    }
    
    @Test
    public void getSurveyAllVersionsExcludeDeletedByIdentifier() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setIdentifier(SURVEY_ID);
        survey1A.setCreatedOn(CREATED_ON);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setIdentifier(SURVEY_ID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        mockSurveyMapper(survey1B, survey1A);
        mockSurveyElementMapper();
        
        List<Survey> results = dao.getSurveyAllVersions(TEST_APP_ID, SURVEY_IDENTIFIER, false);
        assertEquals(results, ImmutableList.of(survey1A, survey1B));
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);

        Condition rangeKeyCondition = query.getRangeKeyConditions().get("identifier");
        assertEquals(rangeKeyCondition.getComparisonOperator(), EQ.name());
        assertEquals(rangeKeyCondition.getAttributeValueList().get(0).getS(), SURVEY_ID);
        
        assertEquals(query.getQueryFilter().size(), 1);
        verifyIsDeletedQueryCondition(query);
    }
    
    @Test
    public void getSurveyMostRecentVersionByIdentifier() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setIdentifier(SURVEY_ID);
        survey1A.setCreatedOn(CREATED_ON);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setIdentifier(SURVEY_ID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        mockSurveyMapper(survey1B, survey1A);
        mockSurveyElementMapper();

        Survey survey = dao.getSurveyMostRecentVersion(TEST_APP_ID, SURVEY_IDENTIFIER);
        assertSame(survey, survey1A);
        
        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);

        Condition rangeKeyCondition = query.getRangeKeyConditions().get("identifier");
        assertEquals(rangeKeyCondition.getComparisonOperator(), EQ.name());
        assertEquals(rangeKeyCondition.getAttributeValueList().get(0).getS(), SURVEY_ID);
        
        assertEquals(query.getQueryFilter().size(), 1);
        verifyIsDeletedQueryCondition(query);
    }
    
    @Test
    public void getSurveyMostRecentlyPublishedVersionIncludeElementsByIdentifier() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setIdentifier(SURVEY_ID);
        survey1A.setCreatedOn(CREATED_ON);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setIdentifier(SURVEY_ID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        mockSurveyMapper(survey1B, survey1A);
        mockSurveyElementMapper();
        
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of());
        
        Survey result = dao.getSurveyMostRecentlyPublishedVersion(TEST_APP_ID, SURVEY_IDENTIFIER, true);
        assertSame(result, survey1A);

        verify(mockSurveyMapper).queryPage(eq(DynamoSurvey.class), queryCaptor.capture());
        DynamoDBQueryExpression<DynamoSurvey> query = queryCaptor.getValue();
        assertEquals(query.getHashKeyValues().getAppId(), TEST_APP_ID);

        Condition rangeKeyCondition = query.getRangeKeyConditions().get("identifier");
        assertEquals(rangeKeyCondition.getComparisonOperator(), EQ.name());
        assertEquals(rangeKeyCondition.getAttributeValueList().get(0).getS(), SURVEY_ID);
        
        assertEquals(query.getQueryFilter().size(), 2);
        verifyIsDeletedQueryCondition(query);
        verifyPublishedQueryCondition(query);
        
        verify(mockSurveyElementMapper).queryPage(eq(DynamoSurveyElement.class), any());
    }
    
    @Test
    public void getSurveyMostRecentlyPublishedVersionExcludeElementsByIdentifier() {
        // Survey 1A
        DynamoSurvey survey1A = new DynamoSurvey();
        survey1A.setGuid(GUID);
        survey1A.setIdentifier(SURVEY_ID);
        survey1A.setCreatedOn(CREATED_ON);
        // Survey 1B
        DynamoSurvey survey1B = new DynamoSurvey();
        survey1B.setGuid(GUID);
        survey1B.setIdentifier(SURVEY_ID);
        survey1B.setCreatedOn(CREATED_ON-2000L);
        mockSurveyMapper(survey1B, survey1A);
        mockSurveyElementMapper();
        
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of());
        
        dao.getSurveyMostRecentlyPublishedVersion(TEST_APP_ID, SURVEY_IDENTIFIER, false);
        
        verify(mockSurveyElementMapper, never()).queryPage(eq(DynamoSurveyElement.class), any());
    }
    
    private void mockSurveyMapper(List<DynamoSurvey> surveys) {
        when(mockSurveyMapper.queryPage(eq(DynamoSurvey.class), any())).thenReturn(mockResultsPage);
        when(mockResultsPage.getResults()).thenReturn(surveys);
    }
    
    private void mockSurveyMapper(DynamoSurvey... surveys) {
        when(mockSurveyMapper.queryPage(eq(DynamoSurvey.class), any())).thenReturn(mockResultsPage);
        if (surveys.length > 0) {
            when(mockResultsPage.getResults()).thenReturn(ImmutableList.copyOf(surveys));
        } else {
            when(mockResultsPage.getResults()).thenReturn(ImmutableList.of());
        }
    }
    
    @SuppressWarnings("unchecked")
    private void mockSurveyElementMapper(DynamoSurveyElement... elements) {
        when(mockSurveyElementMapper.batchDelete(any(List.class))).thenReturn(ImmutableList.of());
        when(mockSurveyElementMapper.queryPage(eq(DynamoSurveyElement.class), any())).thenReturn(mockElementResultsPage);
        when(mockElementResultsPage.getResults()).thenReturn(ImmutableList.of());
    }
    
    private void verifyIsDeletedQueryCondition(DynamoDBQueryExpression<DynamoSurvey> query) {
        Condition deletedCond = query.getQueryFilter().get("deleted");
        assertEquals(deletedCond.getComparisonOperator(), EQ.name());
        assertEquals(deletedCond.getAttributeValueList().get(0).getN(), "0");
    }

    private void verifyAppIdQueryCondition(DynamoDBQueryExpression<DynamoSurvey> query) {
        Condition appIdCond = query.getQueryFilter().get("studyKey");
        assertEquals(appIdCond.getComparisonOperator(), EQ.name());
        assertEquals(appIdCond.getAttributeValueList().get(0).getS(), TEST_APP_ID);
    }
    
    private void verifyPublishedQueryCondition(DynamoDBQueryExpression<DynamoSurvey> query) {
        Condition appIdCond = query.getQueryFilter().get("published");
        assertEquals(appIdCond.getComparisonOperator(), EQ.name());
        assertEquals(appIdCond.getAttributeValueList().get(0).getN(), "1");
    }
    
    private void verifyCreatedOnQueryCondition(DynamoDBQueryExpression<DynamoSurvey> query) {
        Condition createdOnCond = query.getQueryFilter().get("versionedOn");
        assertEquals(createdOnCond.getComparisonOperator(), EQ.name());
        assertNotEquals(createdOnCond.getAttributeValueList().get(0).getN(), new Long(0));
    }
}
