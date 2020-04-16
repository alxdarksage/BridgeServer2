package org.sagebionetworks.bridge.dao;

import java.util.List;

import org.sagebionetworks.bridge.models.schedules.CompoundActivityDefinition;

/** DAO for basic CRUD and list operations for compound activity definitions. */
public interface CompoundActivityDefinitionDao {
    /** Creates a compound activity definition. */
    CompoundActivityDefinition createCompoundActivityDefinition(CompoundActivityDefinition compoundActivityDefinition);

    /** Deletes a compound activity definition. */
    void deleteCompoundActivityDefinition(String appId, String taskId);

    /** Deletes all compound activity definitions in the specified study. Used when we physically delete a study. */
    void deleteAllCompoundActivityDefinitionsInStudy(String appId);

    /** List all compound activity definitions in a study. */
    List<CompoundActivityDefinition> getAllCompoundActivityDefinitionsInStudy(String appId);

    /** Get a compound activity definition by ID. */
    CompoundActivityDefinition getCompoundActivityDefinition(String appId, String taskId);

    /** Update a compound activity definition. */
    CompoundActivityDefinition updateCompoundActivityDefinition(CompoundActivityDefinition compoundActivityDefinition);
}
