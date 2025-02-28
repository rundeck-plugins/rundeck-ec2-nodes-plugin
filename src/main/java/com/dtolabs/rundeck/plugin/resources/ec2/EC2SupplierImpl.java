package com.dtolabs.rundeck.plugin.resources.ec2;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2ClientBuilder;


/**
 * Implementation of EC2Supplier, uses the AWS SDK to create AmazonEC2 clients via the AmazonEC2ClientBuilder
 */
public class EC2SupplierImpl implements EC2Supplier {
    final private AWSCredentials credentials;
    final private ClientConfiguration clientConfiguration;
    final private Region defaultRegion;

    /**
     * Create an instance with the specified credentials and client configuration
     *
     * @param credentials         AWS credentials
     * @param clientConfiguration client configuration
     * @param region              default region
     */
    public EC2SupplierImpl(AWSCredentials credentials, ClientConfiguration clientConfiguration, Region region) {
        this.credentials = credentials;
        this.clientConfiguration = clientConfiguration;
        this.defaultRegion = region;
    }

    @Override
    public AmazonEC2 getEC2ForDefaultRegion() {
        return getEC2ForRegion(null);
    }

    /**
     * Return an AmazonEC2 client for the specified region, if the region is null, the default region is used
     *
     * @param region region name
     * @return AmazonEC2 client
     */
    @Override
    public AmazonEC2 getEC2ForRegion(String region) {
        if (null == region) {
            region = defaultRegion.getName();
        }
        AmazonEC2ClientBuilder builder = AmazonEC2ClientBuilder.standard().withRegion(region).withClientConfiguration(clientConfiguration);
        if (null != credentials) {
            builder.withCredentials(new AWSStaticCredentialsProvider(credentials));
        }
        return builder.build();
    }

    @Override
    public AmazonEC2 getEC2ForEndpoint(String endpoint) {
        if (null == endpoint) {
            return getEC2ForDefaultRegion();
        }
        AwsClientBuilder.EndpointConfiguration endpointConfiguration = new AwsClientBuilder.EndpointConfiguration(endpoint, null);
        AmazonEC2ClientBuilder amazonEC2ClientBuilder = AmazonEC2ClientBuilder.standard().withEndpointConfiguration(endpointConfiguration);
        if (null != credentials) {
            amazonEC2ClientBuilder.withCredentials(new AWSStaticCredentialsProvider(credentials));
        }
        if (null != clientConfiguration) {
            amazonEC2ClientBuilder.withClientConfiguration(clientConfiguration);
        }
        return amazonEC2ClientBuilder.build();
    }
}
