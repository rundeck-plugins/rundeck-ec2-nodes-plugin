package com.dtolabs.rundeck.plugin.resources.ec2;

import com.amazonaws.services.ec2.AmazonEC2;

/**
 * Interface for supplying AmazonEC2 clients
 */
public interface EC2Supplier {
    /**
     * Return an AmazonEC2 client for the default region
     *
     * @return AmazonEC2 client
     */
    AmazonEC2 getEC2ForDefaultRegion();

    /**
     * Return an AmazonEC2 client for the specified region
     *
     * @param region region name
     * @return AmazonEC2 client
     */
    AmazonEC2 getEC2ForRegion(String region);

    /**
     * Return an AmazonEC2 client for the specified endpoint
     *
     * @param endpoint endpoint URL
     * @return AmazonEC2 client
     */
    AmazonEC2 getEC2ForEndpoint(String endpoint);
}
