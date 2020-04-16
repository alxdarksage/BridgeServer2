package org.sagebionetworks.bridge.dao;

import java.util.List;

import org.sagebionetworks.bridge.models.upload.UploadSchema;

/** DAO for upload schemas. This encapsulates standard CRUD operations as well as list operations. */
public interface UploadSchemaDao {
    /**
     * Creates the given schema revision. If the schema revision already exists, this throws a
     * ConcurrentModificationException.
     */
    UploadSchema createSchemaRevision(UploadSchema schema);

    /** Deletes the given schemas by marking them deleted. */
    void deleteUploadSchemas(List<UploadSchema> schemaList);
    
    /** Deletes the given schemas by removing them from the database. */
    void deleteUploadSchemasPermanently(List<UploadSchema> schemaList);

    /** Returns all revisions of all schemas in the specified app. */
    List<UploadSchema> getAllUploadSchemasAllRevisions(String appId, boolean includeDeleted);

    /** Fetch all revisions of a single upload schema. */
    List<UploadSchema> getUploadSchemaAllRevisionsById(String appId, String schemaId, boolean includeDeleted);

    /** Fetches the upload schema for the specified app, schema ID, and revision. */
    UploadSchema getUploadSchemaByIdAndRevision(String appId, String schemaId, int revision);

    /**
     * DAO method for fetching upload schemas. This method fetches an upload schema for the specified app and schema
     * ID. If there is more than one revision of the schema, this fetches the latest revision.
     */
    UploadSchema getUploadSchemaLatestRevisionById(String appId, String schemaId);

    /** Updates the given schema revision as is. Detects and throws ConcurrentModificationExceptions. */
    UploadSchema updateSchemaRevision(UploadSchema schema);
}
