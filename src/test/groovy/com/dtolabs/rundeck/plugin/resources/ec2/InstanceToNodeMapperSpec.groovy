package com.dtolabs.rundeck.plugin.resources.ec2


import com.amazonaws.services.ec2.AmazonEC2Client
import com.amazonaws.services.ec2.model.AvailabilityZone
import com.amazonaws.services.ec2.model.DescribeAvailabilityZonesResult
import com.amazonaws.services.ec2.model.DescribeImagesResult
import com.amazonaws.services.ec2.model.DescribeInstancesResult
import com.amazonaws.services.ec2.model.DescribeRegionsResult
import com.amazonaws.services.ec2.model.Image
import com.amazonaws.services.ec2.model.Instance
import com.amazonaws.services.ec2.model.InstanceState
import com.amazonaws.services.ec2.model.InstanceStateName
import com.amazonaws.services.ec2.model.Placement
import com.amazonaws.services.ec2.model.Region
import com.amazonaws.services.ec2.model.Reservation
import com.amazonaws.services.ec2.model.Tag
import spock.lang.Specification
import spock.lang.Unroll

/**
 * @author greg
 * @since 12/16/16
 */
class InstanceToNodeMapperSpec extends Specification {
    def "single selector valid properties"() {
        given:
        def i = mkInstance()

        when:
        def result = InstanceToNodeMapper.applySingleSelector(i, selector)

        then:
        result == expect

        where:
        selector           | expect
        'instanceId'       | 'aninstanceId'
        'architecture'     | 'anarch'
        'state.name'       | 'running'
        'privateIpAddress' | '127.0.9.9'
        'publicDnsName'    | null
    }

    @Unroll
    def "single selector invalid properties #selector"() {
        given:
        def i = mkInstance()

        when:
        def result = InstanceToNodeMapper.applySingleSelector(i, selector)

        then:
        result == null
        InstanceToNodeMapper.GeneratorException e = thrown()
        e != null

        where:
        selector       | _
        'fromtom'      | _
        'bigglesworth' | _
        'badapple'     | _
    }

    def "single selector tags"() {
        given:
        def i = mkInstance()

        when:
        def result = InstanceToNodeMapper.applySingleSelector(i, selector)

        then:
        result == expect

        where:
        selector       | expect
        'tags/Name'    | 'bob'
        'tags/env'     | 'PROD'
        'tags/missing' | null
    }

    @Unroll
    def "apply selector #selector"() {
        given:
        def i = mkInstance()
        when:
        def result = InstanceToNodeMapper.applySelector(i, selector, defVal)
        then:
        result == expect

        where:
        selector                       | defVal      | expect
        'instanceId,publicDnsName'     | null        | 'aninstanceId'
        'publicDnsName,instanceId'     | null        | 'aninstanceId'
        'privateIpAddress,instanceId'  | null        | '127.0.9.9'
        'instanceId,privateIpAddress'  | null        | 'aninstanceId'
        'publicDnsName,privateDnsName' | null        | null
        'publicDnsName,privateDnsName' | 'a default' | 'a default'
    }

    def "apply selector multipart"() {
        given:
        def i = mkInstance()
        when:
        def result = InstanceToNodeMapper.applySelector(i, selector, defVal)
        then:
        result == expect

        where:
        selector                            | defVal      | expect
        'instanceId+publicDnsName'          | null        | 'aninstanceId'
        'instanceId+\'_\'+publicDnsName'    | null        | 'aninstanceId_'
        'instanceId+"_"+publicDnsName'      | null        | 'aninstanceId_'
        'publicDnsName+instanceId'          | null        | 'aninstanceId'
        'publicDnsName+\'_\'+instanceId'    | null        | '_aninstanceId'
        'publicDnsName+"/"+instanceId'      | null        | '/aninstanceId'
        'privateIpAddress+instanceId'       | null        | '127.0.9.9aninstanceId'
        'privateIpAddress+\'_\'+instanceId' | null        | '127.0.9.9_aninstanceId'
        'privateIpAddress+"::"+instanceId'  | null        | '127.0.9.9::aninstanceId'
        'instanceId+privateIpAddress'       | null        | 'aninstanceId127.0.9.9'
        'instanceId+"-"+privateIpAddress'   | null        | 'aninstanceId-127.0.9.9'
        'publicDnsName+instanceId'          | null        | 'aninstanceId'
        'publicDnsName+"-"+instanceId'      | null        | '-aninstanceId'
        'publicDnsName+privateDnsName'      | null        | null
        'publicDnsName+"_"+privateDnsName'  | null        | null
        'publicDnsName+privateDnsName'      | 'a default' | 'a default'
        'publicDnsName+"_"+privateDnsName'  | 'a default' | 'a default'
        'tags/Name+"-"+instanceId'          | null        | 'bob-aninstanceId'
    }

    def "apply selector merged tags"() {
        given:
        def i = mkInstance()
        when:
        def result = InstanceToNodeMapper.applySelector(i, selector, defVal, true)
        then:
        result == expect

        where:
        selector                       | defVal      | expect
        'tags/Name|tags/env'           | null        | 'bob,PROD'
        'publicDnsName|instanceId'     | null        | 'aninstanceId'
        'privateIpAddress|instanceId'  | null        | '127.0.9.9,aninstanceId'
        'instanceId|privateIpAddress'  | null        | 'aninstanceId,127.0.9.9'
        'publicDnsName|instanceId'     | null        | 'aninstanceId'
        'publicDnsName|privateDnsName' | null        | null
        'publicDnsName|privateDnsName' | 'a default' | 'a default'
    }

    def "apply selector merged tags multi"() {
        given:
        def i = mkInstance()
        when:
        def result = InstanceToNodeMapper.applySelector(i, selector, defVal, true)
        then:
        result == expect

        where:
        selector                            | defVal | expect
        'instanceId|tags/Name+"_"+tags/env' | null   | 'aninstanceId,bob_PROD'
    }

    def "extra mapping image"() {
        given:

        Instance instance = mkInstance()
        Image image = mkImage()
        Reservation reservertion = Mock(Reservation){
            getInstances()>>[instance]
        }
        AmazonEC2Client ec2 = Mock(AmazonEC2Client){
            describeInstances(_) >> Mock(DescribeInstancesResult){
                getReservations() >> [reservertion]
            }
            describeImages(_) >> Mock(DescribeImagesResult){
                getImages()>>[image]
            }
            describeAvailabilityZones()>>Mock(DescribeAvailabilityZonesResult){
                getAvailabilityZones()>>[]
            }
        }
        EC2Supplier supplier = Mock(EC2Supplier) {
            0 * getEC2ForDefaultRegion()
            1 * getEC2ForRegion(_) >> ec2
            0 * getEC2ForEndpoint(_)
        }


        int pageResults = 100
        Properties mapping = new Properties()
        mapping.put("ami_image.selector",mapperValue)
        def mapper = new InstanceToNodeMapper(supplier, mapping, pageResults);
        mapper.setRegion("us-west-1")

        when:
        def instances = mapper.performQuery(false)
        then:
        instances!=null
        instances.getNode("aninstanceId").getAttributes().containsKey(expected)

        where:
        mapperValue | expected
        'imageName' | "ami_image"
        'imageId+"-"+imageName' | "ami_image"
    }

    def "extra mapping not calling image list"() {
        given:

        Instance instance = mkInstance()
        Image image = mkImage()
        Reservation reservertion = Mock(Reservation){
            getInstances()>>[instance]
        }
        AmazonEC2Client ec2 = Mock(AmazonEC2Client){
            describeInstances(_) >> Mock(DescribeInstancesResult){
                getReservations() >> [reservertion]
            }
            describeImages(_) >> Mock(DescribeImagesResult){
                getImages()>>[image]
            }
            describeAvailabilityZones()>>Mock(DescribeAvailabilityZonesResult){
                getAvailabilityZones()>>[]
            }
        }
        EC2Supplier supplier = Mock(EC2Supplier) {
            0 * getEC2ForDefaultRegion()
            1 * getEC2ForRegion(_) >> ec2
            0 * getEC2ForEndpoint(_)
        }
        int pageResults = 100
        Properties mapping = new Properties()
        mapping.put("nodename.selector","instanceId")
        def mapper = new InstanceToNodeMapper(supplier, mapping, pageResults);
        mapper.setRegion("us-west-1")
        when:
        def instances = mapper.performQuery(false)
        then:
        instances!=null
        0*ec2.describeImages(_)
    }

    def "region added to the node attributes with region specified"() {
        given:

        Instance instance = mkInstance()
        Image image = mkImage()
        List<AvailabilityZone> zones = new ArrayList<>()
        AvailabilityZone zone1 = new AvailabilityZone()
        zone1.setRegionName(region)
        zone1.setZoneName("us-east-1a")

        Reservation reservertion = Mock(Reservation){
            getInstances()>>[instance]
        }
        AmazonEC2Client ec2 = Mock(AmazonEC2Client){
            describeInstances(_) >> Mock(DescribeInstancesResult){
                getReservations() >> [reservertion]
            }
            describeImages(_) >> Mock(DescribeImagesResult){
                getImages()>>[image]
            }
            describeAvailabilityZones()>>Mock(DescribeAvailabilityZonesResult){
                getAvailabilityZones()>>[zone1]
            }
        }

        EC2Supplier supplier = Mock(EC2Supplier) {
            0 * getEC2ForDefaultRegion()
            1 * getEC2ForRegion(region) >> ec2
            0 * getEC2ForEndpoint(_)
        }

        int pageResults = 100
        Properties mapping = new Properties()
        mapping.put("region.selector","region")
        def mapper = new InstanceToNodeMapper(supplier, mapping, pageResults);
        mapper.setRegion(region)
        when:
        def instances = mapper.performQuery(false)
        then:
        instances!=null
        instances.getNode("aninstanceId").getAttributes().containsKey("region")
        instances.getNode("aninstanceId").getAttributes().get("region") == region

        where:
        region << ['us-east-1','us-west-2']
    }

    def "region added to the node attributes with endpoint(s) specified"() {
        given:

        Image image = mkImage()

        EC2Supplier supplier = Mock(EC2Supplier) {
            0 * getEC2ForDefaultRegion()
            0 * getEC2ForRegion()

            _ * getEC2ForEndpoint({ it in endpoints }) >> { args ->
                AvailabilityZone zone1 = new AvailabilityZone()
                def region=regions[endpoints.indexOf(args[0])]
                zone1.setRegionName(region)
                zone1.setZoneName("${region}a")
                Mock(AmazonEC2Client) {
                    describeInstances(_) >> Mock(DescribeInstancesResult) {
                        getReservations() >> [Mock(Reservation){
                            getInstances()>>[mkInstance(region)]
                        }]
                    }
                    describeImages(_) >> Mock(DescribeImagesResult) {
                        getImages() >> [image]
                    }
                    describeAvailabilityZones() >> Mock(DescribeAvailabilityZonesResult) {
                        getAvailabilityZones() >> [zone1]
                    }
                }
            }

            0 * _(*_)
        }

        int pageResults = 100
        Properties mapping = new Properties()
        mapping.put("region.selector","region")
        def mapper = new InstanceToNodeMapper(supplier, mapping, pageResults);
        mapper.setEndpoint(endpoints.join(', '))
        when:
        def instances = mapper.performQuery(false)
        then:
        instances!=null
        instances.getNode("aninstanceId").getAttributes().containsKey("region")
        instances.getNode("aninstanceId").getAttributes().get("region") in regions

        where:
        endpoints | regions
        ['https://ec2.us-east-1.amazonaws.com'] | ['us-east-1']
        ['https://ec2.us-west-2.amazonaws.com'] | ['us-west-2']
        ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com'] | ['us-west-1','us-east-1']
    }
    def "region added to the node attributes with ALL_REGIONS specified"() {
        given:

        Image image = mkImage()

        EC2Supplier supplier = Mock(EC2Supplier) {
            1 * getEC2ForDefaultRegion() >> Mock(AmazonEC2Client) {
                1 * describeRegions() >> Mock(DescribeRegionsResult) {
                    1 * getRegions() >> regions.collect({ region ->
                        Mock(Region) {
                            _ * getRegionName() >> region
                            _ * getEndpoint() >> "https://ec2.${region}.amazonaws.com"
                        }
                    })
                }
            }
            0 * getEC2ForRegion()

            _ * getEC2ForEndpoint({ it in endpointsFound }) >> { args ->
                AvailabilityZone zone1 = new AvailabilityZone()
                def region=regions[endpointsFound.indexOf(args[0])]
                zone1.setRegionName(region)
                zone1.setZoneName("${region}a")
                Mock(AmazonEC2Client) {
                    describeInstances(_) >> Mock(DescribeInstancesResult) {
                        getReservations() >> [Mock(Reservation){
                            getInstances()>>[mkInstance(region)]
                        }]
                    }
                    describeImages(_) >> Mock(DescribeImagesResult) {
                        getImages() >> [image]
                    }
                    describeAvailabilityZones() >> Mock(DescribeAvailabilityZonesResult) {
                        getAvailabilityZones() >> [zone1]
                    }
                }
            }

            0 * _(*_)
        }

        int pageResults = 100
        Properties mapping = new Properties()
        mapping.put("region.selector","region")
        def mapper = new InstanceToNodeMapper(supplier, mapping, pageResults);
        mapper.setEndpoint(endpoint)
        when:
        def instances = mapper.performQuery(false)
        then:
        instances!=null
        instances.getNode("aninstanceId").getAttributes().containsKey("region")
        instances.getNode("aninstanceId").getAttributes().get("region") in regions

        where:
        endpoint       | endpointsFound|regions
        'ALL_REGIONS' | ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']|['us-west-1', 'us-east-1']

    }

    //
    // Private Methods
    //
    private static Instance mkInstance(String region='us-east-1') {
        Instance i = new Instance()
        i.withTags(new Tag('Name', 'bob'), new Tag('env', 'PROD'))
        i.setInstanceId("aninstanceId")
        i.setArchitecture("anarch")
        i.setImageId("ami-something")
        i.setPlacement(new Placement("${region}a"))

        def state = new InstanceState()
        state.setName(InstanceStateName.Running)
        i.setState(state)
        i.setPrivateIpAddress('127.0.9.9')
        return i
    }

    private static Image mkImage(){
        Image image = new Image()
        image.setImageId("ami-something")
        image.setName("AMISomething")
        return image
    }
}
