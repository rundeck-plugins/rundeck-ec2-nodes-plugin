package com.dtolabs.rundeck.plugin.resources.ec2

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.ec2.Ec2Client
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Regression coverage for RUN-4795: AWS's DescribeRegions API (used by the ALL_REGIONS
 * endpoint option) returns bare hostnames with no URI scheme, which used to reach
 * Ec2ClientBuilder#endpointOverride() unmodified and throw:
 * "NullPointerException: The URI scheme of endpointOverride must not be null."
 */
class EC2SupplierImplSpec extends Specification {

    def credentials = AwsBasicCredentials.create("AKIAEXAMPLE", "secretExampleKey")

    @Unroll
    def "getEC2ForEndpoint builds a client for endpoint #endpoint"() {
        given:
        def supplier = new EC2SupplierImpl(credentials, null, Region.US_EAST_1)

        when:
        Ec2Client client = supplier.getEC2ForEndpoint(endpoint)

        then:
        noExceptionThrown()
        client != null

        where:
        endpoint << [
                'ec2.us-west-1.amazonaws.com',           // bare hostname, as returned by AWS DescribeRegions (ALL_REGIONS)
                'https://ec2.us-west-1.amazonaws.com',   // fully-qualified URL, as required for the documented comma-separated endpoint list
        ]
    }
}
