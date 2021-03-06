package org.sagebionetworks.bridge.models.notifications;

/** Supported notification protocols, for use with AWS SNS. */
public enum NotificationProtocol {
    /** Push notifications, using an endpoint ARN as generated by SNS. */
    APPLICATION("application"),

    /** SMS notifications, using a (as of now) US phone number. */
    SMS("sms");

    private final String awsName;

    NotificationProtocol(String awsName) {
        this.awsName = awsName;
    }

    /** AWS name for the protocol. */
    public String getAwsName() {
        return awsName;
    }
}
