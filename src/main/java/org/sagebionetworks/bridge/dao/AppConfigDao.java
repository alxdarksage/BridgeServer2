package org.sagebionetworks.bridge.dao;

import java.util.List;

import org.sagebionetworks.bridge.models.appconfig.AppConfig;

public interface AppConfigDao {
    
    /**
     * Get all the configuration objects for the app.
     */
    List<AppConfig> getAppConfigs(String appId, boolean includeDeleted);
    
    /**
     * Get a specific configuration object for this app.
     */
    AppConfig getAppConfig(String appId, String guid);
    
    /**
     * Create a configuration object. If the object already exists, 
     * a copy will be created.
     */
    AppConfig createAppConfig(AppConfig appConfig);
    
    /**
     * Update an existing config.
     */
    AppConfig updateAppConfig(AppConfig appConfig);
    
    /**
     * Delete an individual config by marking it as deleted. The record 
     * will not be returned from the APIs but it is still in the database.
     */
    void deleteAppConfig(String appId, String guid);
    
    /**
     * Permanently delete an individual config. The record is deleted 
     * from the database. 
     */
    void deleteAppConfigPermanently(String appId, String guid);
    
}
