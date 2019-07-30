package org.sagebionetworks.bridge.validators;

import static org.sagebionetworks.bridge.TestConstants.TIMESTAMP;
import static org.sagebionetworks.bridge.TestConstants.USER_ID;
import static org.sagebionetworks.bridge.TestUtils.assertValidatorMessage;
import static org.sagebionetworks.bridge.models.templates.TemplateType.EMAIL_RESET_PASSWORD;
import static org.sagebionetworks.bridge.models.templates.TemplateType.SMS_ACCOUNT_EXISTS;

import org.apache.commons.lang3.RandomStringUtils;
import org.joda.time.DateTime;
import org.testng.annotations.Test;
import org.thymeleaf.util.StringUtils;

import org.sagebionetworks.bridge.TestConstants;
import org.sagebionetworks.bridge.models.studies.MimeType;
import org.sagebionetworks.bridge.models.templates.TemplateRevision;

public class TemplateRevisionValidatorTest {
    
    private static final TemplateRevisionValidator INSTANCE = new TemplateRevisionValidator(EMAIL_RESET_PASSWORD);
    private static final String TEMPLATE_GUID = "oneTemplateGuid";
    private static final DateTime CREATED_ON = TestConstants.TIMESTAMP;
    private static final String STORAGE_PATH = TEMPLATE_GUID + "." + CREATED_ON.toString();
    
    @Test
    public void valid() {
        TemplateRevision revision = createValidTemplate();
        Validate.entityThrowingException(INSTANCE, revision);
    }

    @Test
    public void validateTemplateGuid() {
        TemplateRevision revision = createValidTemplate();
        revision.setTemplateGuid(null);
        
        assertValidatorMessage(INSTANCE, revision, "templateGuid", "cannot be blank");
    }
    
    @Test
    public void validateCreatedOn() {
        TemplateRevision revision = createValidTemplate();
        revision.setCreatedOn(null);
        
        assertValidatorMessage(INSTANCE, revision, "createdOn", "cannot be null");
    }
    
    @Test
    public void validateCreatedBy() { 
        TemplateRevision revision = createValidTemplate();
        revision.setCreatedBy(null);
        
        assertValidatorMessage(INSTANCE, revision, "createdBy", "cannot be blank");
    }
    
    @Test
    public void validateStoragePath() { 
        TemplateRevision revision = createValidTemplate();
        revision.setStoragePath(null);
        
        assertValidatorMessage(INSTANCE, revision, "storagePath", "cannot be blank");
    }
    
    @Test
    public void validateSubjectLength() {
        TemplateRevision revision = createValidTemplate();
        revision.setSubject(RandomStringUtils.random(251));
        
        assertValidatorMessage(INSTANCE, revision, "subject", "cannot be more than 250 characters");
    }
    
    @Test
    public void validateMimeType() {
        TemplateRevision revision = createValidTemplate();
        revision.setMimeType(null);
        
        assertValidatorMessage(INSTANCE, revision, "mimeType", "cannot be null");
    }
    
    @Test
    public void validateSubject() {
        TemplateRevision revision = createValidTemplate();
        revision.setSubject(null);
        
        assertValidatorMessage(INSTANCE, revision, "subject", "cannot be blank");
    }
    
    @Test
    public void validateSmsDocumentContentTooLong() {
        TemplateRevision revision = createValidTemplate();
        revision.setDocumentContent(StringUtils.randomAlphanumeric(161));

        TemplateRevisionValidator validator = new TemplateRevisionValidator(SMS_ACCOUNT_EXISTS);
        assertValidatorMessage(validator, revision, "documentContent", "cannot be more than 160 characters");
    }

    @Test
    public void validateEmailSubjectTooLong() {
        TemplateRevision revision = createValidTemplate();
        revision.setSubject(StringUtils.randomAlphanumeric(251));
        
        assertValidatorMessage(INSTANCE, revision, "subject", "cannot be more than 250 characters");
    }
    
    @Test
    public void validateEmailDocumentContent() {
        TemplateRevision revision = createValidTemplate();
        revision.setDocumentContent("");
        
        assertValidatorMessage(INSTANCE, revision, "documentContent", "cannot be blank");
    }
    
    @Test
    public void validateSmsDocumentContent() {
        TemplateRevision revision = createValidTemplate();
        revision.setDocumentContent("");
        
        TemplateRevisionValidator validator = new TemplateRevisionValidator(SMS_ACCOUNT_EXISTS);
        assertValidatorMessage(validator, revision, "documentContent", "cannot be blank");
    }
    
    @Test
    public void validateEmailDocumentContentMissingVariable() {
        TemplateRevision revision = createValidTemplate();
        revision.setDocumentContent("This has some content");
        
        assertValidatorMessage(INSTANCE, revision, "documentContent",
                "must contain one of these template variables: ${url}, ${resetPasswordUrl}");
    }
    
    @Test
    public void validateSmsDocumentContentMissingVariable() {
        TemplateRevision revision = createValidTemplate();
        revision.setDocumentContent("This has some content");
     
        TemplateRevisionValidator validator = new TemplateRevisionValidator(SMS_ACCOUNT_EXISTS);
        assertValidatorMessage(validator, revision, "documentContent",
                "must contain one of these template variables: ${token}, ${resetPasswordUrl}");
    }
    
    private TemplateRevision createValidTemplate() {
        TemplateRevision revision = TemplateRevision.create();
        revision.setTemplateGuid(TEMPLATE_GUID);
        revision.setCreatedOn(TIMESTAMP);
        revision.setCreatedBy(USER_ID);
        revision.setStoragePath(STORAGE_PATH);
        revision.setMimeType(MimeType.HTML);
        revision.setSubject("This is a subject");
        revision.setDocumentContent("This is some ${url} content.");
        return revision;
    }
    
}
