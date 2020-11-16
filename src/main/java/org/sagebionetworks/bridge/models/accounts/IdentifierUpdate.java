package org.sagebionetworks.bridge.models.accounts;

import org.sagebionetworks.bridge.models.BridgeEntity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class IdentifierUpdate implements BridgeEntity {
    
    private final SignIn signIn;
    private final String emailUpdate;
    private final Phone phoneUpdate;
    private final String synapseUserIdUpdate;
    
    @JsonCreator
    public IdentifierUpdate(@JsonProperty("signIn") SignIn signIn, @JsonProperty("emailUpdate") String emailUpdate,
            @JsonProperty("phoneUpdate") Phone phoneUpdate,
            @JsonProperty("synapseUserIdUpdate") String synapseUserIdUpdate) {
        this.signIn = signIn;
        this.emailUpdate = emailUpdate;
        this.phoneUpdate = phoneUpdate;
        this.synapseUserIdUpdate = synapseUserIdUpdate;
    }
    
    public SignIn getSignIn() {
        return signIn;
    }

    public String getEmailUpdate() {
        return emailUpdate;
    }

    public Phone getPhoneUpdate() {
        return phoneUpdate;
    }
    
    public String getSynapseUserIdUpdate() {
        return synapseUserIdUpdate;
    }
}
