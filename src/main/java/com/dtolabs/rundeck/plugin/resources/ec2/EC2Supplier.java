package com.dtolabs.rundeck.plugin.resources.ec2;

import software.amazon.awssdk.services.ec2.Ec2Client;

/**
 * Interface for supplying Ec2Client instances
 */
public interface EC2Supplier {
    /**
     * Return an Ec2Client for the default region
     *
     * @return Ec2Client
     */
    Ec2Client getEC2ForDefaultRegion();

    /**
     * Return an Ec2Client for the specified region
     *
     * @param region region name
     * @return Ec2Client
     */
    Ec2Client getEC2ForRegion(String region);

    /**
     * Return an Ec2Client for the specified endpoint
     *
     * @param endpoint endpoint URL
     * @return Ec2Client
     */
    Ec2Client getEC2ForEndpoint(String endpoint);
}
