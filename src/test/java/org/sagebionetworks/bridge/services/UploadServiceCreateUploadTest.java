package org.sagebionetworks.bridge.services;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.sagebionetworks.bridge.BridgeConstants.API_APP_ID;
import static org.sagebionetworks.bridge.TestConstants.TEST_APP_ID;
import static org.testng.Assert.assertEquals;

import java.net.URL;

import com.amazonaws.auth.AWSSessionCredentials;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import org.apache.commons.codec.binary.Base64;
import org.joda.time.DateTime;
import org.joda.time.DateTimeUtils;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import org.sagebionetworks.bridge.config.BridgeConfig;
import org.sagebionetworks.bridge.dao.UploadDao;
import org.sagebionetworks.bridge.dao.UploadDedupeDao;
import org.sagebionetworks.bridge.dynamodb.DynamoUpload2;
import org.sagebionetworks.bridge.json.BridgeObjectMapper;
import org.sagebionetworks.bridge.models.accounts.StudyParticipant;
import org.sagebionetworks.bridge.models.upload.UploadRequest;
import org.sagebionetworks.bridge.models.upload.UploadSession;
import org.sagebionetworks.bridge.models.upload.UploadStatus;
import org.sagebionetworks.bridge.validators.UploadValidator;

public class UploadServiceCreateUploadTest {
    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_CONTENT_TYPE = "text/plain";
    private static final String TEST_HEALTH_CODE = "test-healthcode";
    private static final String TEST_ORIGINAL_UPLOAD_ID = "original-upload";
    private static final String TEST_PRESIGNED_URL = "http://www.example.com/";
    private static final String TEST_UPLOAD_ID = "test-upload-id";
    private static final String TEST_UPLOAD_MD5 = Base64.encodeBase64String("test-md5".getBytes());

    // Timezone matters. UploadService pulls the DateTime in UTC, and the equals() check (which is used by Mockito)
    // checks for timezone as well.
    private static final DateTime TEST_UPLOAD_REQUESTED_ON = DateTime.parse("2016-02-16T12:25:11Z");

    private static final DynamoUpload2 TEST_UPLOAD;
    static {
        TEST_UPLOAD = new DynamoUpload2();
        TEST_UPLOAD.setUploadId(TEST_UPLOAD_ID);
    }

    // We have to create this from JSON for some reason.
    private static final String TEST_UPLOAD_REQUEST_JSON = "{\n" +
            "   \"name\":\"test-upload\",\n" +
            "   \"contentLength\":42,\n" +
            "   \"contentMd5\":\"" + TEST_UPLOAD_MD5 + "\",\n" +
            "   \"contentType\":\"" + TEST_CONTENT_TYPE + "\"\n" +
            "}";

    private static final StudyParticipant TEST_USER = new StudyParticipant.Builder()
            .withHealthCode(TEST_HEALTH_CODE).build();

    private UploadDedupeDao mockUploadDedupeDao;
    private UploadDao mockUploadDao;
    private ArgumentCaptor<GeneratePresignedUrlRequest> presignedUrlRequestArgumentCaptor;
    private UploadRequest uploadRequest;
    private UploadService svc;

    @BeforeMethod
    public void setup() throws Exception {
        // mock now
        DateTimeUtils.setCurrentMillisFixed(TEST_UPLOAD_REQUESTED_ON.getMillis());

        // make test request
        uploadRequest = BridgeObjectMapper.get().readValue(TEST_UPLOAD_REQUEST_JSON, UploadRequest.class);

        // mock config
        BridgeConfig mockConfig = mock(BridgeConfig.class);
        when(mockConfig.getProperty(UploadService.CONFIG_KEY_UPLOAD_BUCKET)).thenReturn(TEST_BUCKET);

        // mock upload DAOs. (The tests will mock the calls, since they vary with each test.)
        mockUploadDao = mock(UploadDao.class);
        mockUploadDedupeDao = mock(UploadDedupeDao.class);

        // mock upload credentials service
        AWSSessionCredentials mockCredentials = mock(AWSSessionCredentials.class);
        UploadSessionCredentialsService mockCredentialsSvc = mock(UploadSessionCredentialsService.class);
        when(mockCredentialsSvc.getSessionCredentials()).thenReturn(mockCredentials);

        // mock presigned URL call
        presignedUrlRequestArgumentCaptor = ArgumentCaptor.forClass(GeneratePresignedUrlRequest.class);
        AmazonS3 mockS3UploadClient = mock(AmazonS3.class);
        when(mockS3UploadClient.generatePresignedUrl(presignedUrlRequestArgumentCaptor.capture())).thenReturn(new URL(
                TEST_PRESIGNED_URL));

        // set up service
        svc = new UploadService();
        svc.setConfig(mockConfig);
        svc.setValidator(new UploadValidator());
        svc.setUploadDao(mockUploadDao);
        svc.setUploadDedupeDao(mockUploadDedupeDao);
        svc.setUploadSessionCredentialsService(mockCredentialsSvc);
        svc.setS3UploadClient(mockS3UploadClient);
    }

    @AfterMethod
    public void cleanup() {
        DateTimeUtils.setCurrentMillisSystem();
    }

    @Test
    public void isNotDupe() {
        // mock upload DAO
        when(mockUploadDao.createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, null)).thenReturn(TEST_UPLOAD);

        // mock upload dedupe DAO
        when(mockUploadDedupeDao.getDuplicate(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON)).thenReturn(
                null);

        testUpload(TEST_APP_ID, TEST_UPLOAD_ID);

        // verify we created and registered the dupe
        verify(mockUploadDao).createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, null);
        verify(mockUploadDedupeDao).registerUpload(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON,
                TEST_UPLOAD_ID);
    }

    @Test
    public void isDupeOfIncompleteUpload() {
        // mock upload DAO
        DynamoUpload2 originalUpload = new DynamoUpload2();
        originalUpload.setUploadId(TEST_ORIGINAL_UPLOAD_ID);
        originalUpload.setStatus(UploadStatus.REQUESTED);
        when(mockUploadDao.getUpload(TEST_ORIGINAL_UPLOAD_ID)).thenReturn(originalUpload);

        // mock upload dedupe DAO
        when(mockUploadDedupeDao.getDuplicate(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON)).thenReturn(
                TEST_ORIGINAL_UPLOAD_ID);

        testUpload(TEST_APP_ID, TEST_ORIGINAL_UPLOAD_ID);

        // verify we never create or register a dupe
        verify(mockUploadDao, never()).createUpload(any(), eq(TEST_APP_ID), any(), any());
        verify(mockUploadDedupeDao, never()).registerUpload(any(), any(), any(), any());
    }

    @Test
    public void isDupeOfCompleteUpload() {
        // mock upload DAO
        when(mockUploadDao.createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, TEST_ORIGINAL_UPLOAD_ID))
                .thenReturn(TEST_UPLOAD);

        DynamoUpload2 originalUpload = new DynamoUpload2();
        originalUpload.setUploadId(TEST_ORIGINAL_UPLOAD_ID);
        originalUpload.setStatus(UploadStatus.SUCCEEDED);
        when(mockUploadDao.getUpload(TEST_ORIGINAL_UPLOAD_ID)).thenReturn(originalUpload);

        // mock upload dedupe DAO
        when(mockUploadDedupeDao.getDuplicate(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON)).thenReturn(
                TEST_ORIGINAL_UPLOAD_ID);

        testUpload(TEST_APP_ID, TEST_UPLOAD_ID);

        // verify we create the upload, but we don't register a dupe
        verify(mockUploadDao).createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, TEST_ORIGINAL_UPLOAD_ID);
        verify(mockUploadDedupeDao, never()).registerUpload(any(), any(), any(), any());
    }

    @Test
    public void dupeWhitelisted() {
        // If the current app is whitelisted for dupes, then all 3 cases (not dupe, incomplete dupe, complete dupe)
        // look exactly the same. We have no way of knowing whether something is a dupe or not, because we never look.

        // mock config with dupe app whitelist
        BridgeConfig mockConfig = mock(BridgeConfig.class);
        when(mockConfig.getProperty(UploadService.CONFIG_KEY_UPLOAD_BUCKET)).thenReturn(TEST_BUCKET);
        svc.setConfig(mockConfig);

        // mock upload DAO
        when(mockUploadDao.createUpload(uploadRequest, API_APP_ID, TEST_HEALTH_CODE, null)).thenReturn(TEST_UPLOAD);

        testUpload(API_APP_ID, TEST_UPLOAD_ID);

        // verify we created and registered the dupe; verify we never queried for dupes
        verify(mockUploadDedupeDao, never()).getDuplicate(any(), any(), any());
        verify(mockUploadDao).createUpload(uploadRequest, API_APP_ID, TEST_HEALTH_CODE, null);
        verify(mockUploadDedupeDao).registerUpload(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON,
                TEST_UPLOAD_ID);
    }

    @Test
    public void exceptionGettingDupe() {
        // Throwing on dedupe logic shouldn't fail the upload.

        // mock upload DAO
        when(mockUploadDao.createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, null)).thenReturn(TEST_UPLOAD);

        // mock upload dedupe DAO
        when(mockUploadDedupeDao.getDuplicate(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON)).thenThrow(
                RuntimeException.class);

        testUpload(TEST_APP_ID, TEST_UPLOAD_ID);

        // verify we created and registered the dupe
        verify(mockUploadDao).createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, null);
        verify(mockUploadDedupeDao).registerUpload(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON,
                TEST_UPLOAD_ID);
    }

    @Test
    public void exceptionRegistering() {
        // Throwing on dedupe logic shouldn't fail the upload.

        // mock upload DAO
        when(mockUploadDao.createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, null)).thenReturn(TEST_UPLOAD);

        // mock upload dedupe DAO
        when(mockUploadDedupeDao.getDuplicate(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON)).thenReturn(
                null);
        doThrow(RuntimeException.class).when(mockUploadDedupeDao).registerUpload(TEST_HEALTH_CODE, TEST_UPLOAD_MD5,
                TEST_UPLOAD_REQUESTED_ON, TEST_UPLOAD_ID);

        testUpload(TEST_APP_ID, TEST_UPLOAD_ID);

        // verify we created and registered the dupe (even if we threw immediately after registration)
        verify(mockUploadDao).createUpload(uploadRequest, TEST_APP_ID, TEST_HEALTH_CODE, null);
        verify(mockUploadDedupeDao).registerUpload(TEST_HEALTH_CODE, TEST_UPLOAD_MD5, TEST_UPLOAD_REQUESTED_ON,
                TEST_UPLOAD_ID);
    }

    private void testUpload(String appId, String expectedUploadId) {
        // execute and validate
        UploadSession uploadSession = svc.createUpload(appId, TEST_USER, uploadRequest);
        assertEquals(uploadSession.getId(), expectedUploadId);
        assertEquals(uploadSession.getUrl(), TEST_PRESIGNED_URL);

        // Verify pre-signed URL args
        // This is already tested thoroughly in UploadServiceTest, so just test basic data flow that aren't constants
        // or config, namely Upload ID, MD5, and Content Type.
        GeneratePresignedUrlRequest presignedUrlRequest = presignedUrlRequestArgumentCaptor.getValue();
        assertEquals(presignedUrlRequest.getBucketName(), TEST_BUCKET);
        assertEquals(presignedUrlRequest.getContentMd5(), TEST_UPLOAD_MD5);
        assertEquals(presignedUrlRequest.getContentType(), TEST_CONTENT_TYPE);
        assertEquals(presignedUrlRequest.getKey(), expectedUploadId);

        // Expiration is computed using Java Date instead of Joda Date, which is messy, so we're not going to test that
    }
}
