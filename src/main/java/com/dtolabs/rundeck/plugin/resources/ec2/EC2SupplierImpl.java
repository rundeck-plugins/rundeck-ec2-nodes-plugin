package com.dtolabs.rundeck.plugin.resources.ec2;

import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.Ec2ClientBuilder;

import java.net.URI;

/**
 * Implementation of EC2Supplier, uses the AWS SDK v2 to create Ec2Client instances via the Ec2ClientBuilder
 */
public class EC2SupplierImpl implements EC2Supplier {
    final private AwsCredentials credentials;
    final private SdkHttpClient httpClient;
    final private Region defaultRegion;

    /**
     * Create an instance with the specified credentials and shared HTTP client
     *
     * @param credentials AWS credentials, or null to use the default provider chain
     * @param httpClient  shared HTTP client (carries any proxy configuration), or null for default
     * @param region      default region
     */
    public EC2SupplierImpl(AwsCredentials credentials, SdkHttpClient httpClient, Region region) {
        this.credentials = credentials;
        this.httpClient = httpClient;
        this.defaultRegion = region;
    }

    @Override
    public Ec2Client getEC2ForDefaultRegion() {
        return getEC2ForRegion(null);
    }

    /**
     * Return an Ec2Client for the specified region, if the region is null, the default region is used
     *
     * @param region region name
     * @return Ec2Client
     */
    @Override
    public Ec2Client getEC2ForRegion(String region) {
        Region resolvedRegion = (null == region) ? defaultRegion : Region.of(region);
        Ec2ClientBuilder builder = Ec2Client.builder().region(resolvedRegion);
        applyCommon(builder);
        return builder.build();
    }

    @Override
    public Ec2Client getEC2ForEndpoint(String endpoint) {
        if (null == endpoint) {
            return getEC2ForDefaultRegion();
        }
        // AWS SDK v2 requires a signing region even when overriding the endpoint, so derive
        // it from the endpoint host (e.g. https://ec2.us-west-1.amazonaws.com -> us-west-1).
        Region signingRegion = regionFromEndpoint(endpoint);
        Ec2ClientBuilder builder = Ec2Client.builder()
                .region(signingRegion)
                .endpointOverride(URI.create(endpoint));
        applyCommon(builder);
        return builder.build();
    }

    private void applyCommon(Ec2ClientBuilder builder) {
        if (null != httpClient) {
            builder.httpClient(httpClient);
        }
        if (null != credentials) {
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }
    }

    /**
     * Derive an AWS region from an EC2 endpoint URL, falling back to the default region when the
     * region cannot be determined.
     */
    private Region regionFromEndpoint(String endpoint) {
        try {
            String host = URI.create(endpoint).getHost();
            if (null != host) {
                String[] parts = host.split("\\.");
                // ec2.<region>.amazonaws.com
                if (parts.length >= 2 && "ec2".equalsIgnoreCase(parts[0])) {
                    return Region.of(parts[1]);
                }
            }
        } catch (RuntimeException ignored) {
            // fall through to default
        }
        return defaultRegion;
    }
}
