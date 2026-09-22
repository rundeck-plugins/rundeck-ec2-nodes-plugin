package com.dtolabs.rundeck.plugin.resources.ec2


import software.amazon.awssdk.services.ec2.Ec2Client
import software.amazon.awssdk.services.ec2.model.AvailabilityZone
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesResponse
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse
import software.amazon.awssdk.services.ec2.model.Image
import software.amazon.awssdk.services.ec2.model.Instance
import software.amazon.awssdk.services.ec2.model.InstanceState
import software.amazon.awssdk.services.ec2.model.InstanceStateName
import software.amazon.awssdk.services.ec2.model.Placement
import software.amazon.awssdk.services.ec2.model.Region
import software.amazon.awssdk.services.ec2.model.Reservation
import software.amazon.awssdk.services.ec2.model.Tag
import spock.lang.Specification
import spock.util.concurrent.PollingConditions
import spock.lang.Unroll

/**
 * @author greg
 * @since 12/16/16
 */
class InstanceToNodeMapperSpec extends Specification {
    @Unroll
    def "detailOf falls back to toString() for a #description message"() {
        expect:
        InstanceToNodeMapper.detailOf(cause) == expected

        where:
        description       | cause                              | expected
        "normal"          | new RuntimeException("boom")       | "boom"
        "missing"         | new RuntimeException()             | new RuntimeException().toString()
        "blank"           | new RuntimeException("")           | new RuntimeException("").toString()
        "whitespace-only" | new RuntimeException("   ")        | new RuntimeException("   ").toString()
    }

    def "single selector valid properties"() {
        given:
        def i = Ec2Instance.builder(mkInstance())

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
        def i = Ec2Instance.builder(mkInstance())

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
        def i = Ec2Instance.builder(mkInstance())

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
        def i = Ec2Instance.builder(mkInstance())
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
        def i = Ec2Instance.builder(mkInstance())
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
        def i = Ec2Instance.builder(mkInstance())
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
        def i = Ec2Instance.builder(mkInstance())
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
        DescribeInstancesResponse instancesResponse = DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(instance).build())
                .build()
        Ec2Client ec2 = Mock(Ec2Client){
            describeInstances(_) >> instancesResponse
            describeImages(_) >> DescribeImagesResponse.builder().images(image).build()
            describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
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
        DescribeInstancesResponse instancesResponse = DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(instance).build())
                .build()
        Ec2Client ec2 = Mock(Ec2Client){
            describeInstances(_) >> instancesResponse
            describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
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
        AvailabilityZone zone1 = AvailabilityZone.builder()
                .regionName(region)
                .zoneName("us-east-1a")
                .build()

        DescribeInstancesResponse instancesResponse = DescribeInstancesResponse.builder()
                .reservations(Reservation.builder().instances(instance).build())
                .build()
        Ec2Client ec2 = Mock(Ec2Client){
            describeInstances(_) >> instancesResponse
            describeImages(_) >> DescribeImagesResponse.builder().images(image).build()
            describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().availabilityZones(zone1).build()
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
            0 * getEC2ForRegion(_)

            _ * getEC2ForEndpoint({ it in endpoints }) >> { args ->
                def region = regions[endpoints.indexOf(args[0])]
                AvailabilityZone zone1 = AvailabilityZone.builder()
                        .regionName(region)
                        .zoneName("${region}a".toString())
                        .build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(mkInstance(region)).build())
                            .build()
                    describeImages(_) >> DescribeImagesResponse.builder().images(image).build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().availabilityZones(zone1).build()
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

    def "parallel query (queryNodeInstancesInParallel=true) aggregates results from all endpoints"() {
        given: "the same multi-endpoint setup, queried in parallel"
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        def regions = ['us-west-1', 'us-east-1']
        EC2Supplier supplier = Mock(EC2Supplier) {
            0 * getEC2ForDefaultRegion()
            0 * getEC2ForRegion(_)
            _ * getEC2ForEndpoint({ it in endpoints }) >> { args ->
                def region = regions[endpoints.indexOf(args[0])]
                def instance = mkInstance(region).toBuilder()
                        .instanceId("aninstanceId-${region}".toString())
                        .build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(instance).build())
                            .build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))

        when:
        def instances = mapper.performQuery(true)

        then: "both regions' instances are present"
        instances != null
        instances.getNodeNames().size() == 2
        instances.getNode("aninstanceId-us-west-1") != null
        instances.getNode("aninstanceId-us-east-1") != null
    }

    def "one region denied under multiple endpoints still returns the other region's nodes"() {
        given: "us-west-1 denied (e.g. an IAM policy restricting ec2:* to us-east-1), us-east-1 working"
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint('https://ec2.us-west-1.amazonaws.com') >> {
                throw new RuntimeException("UnauthorizedOperation: not authorized for us-west-1")
            }
            getEC2ForEndpoint('https://ec2.us-east-1.amazonaws.com') >> {
                def instance = mkInstance('us-east-1').toBuilder().instanceId("aninstanceId-us-east-1").build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(instance).build())
                            .build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))

        when:
        def instances = mapper.performQuery(false)

        then: "the working region's node is still returned, not discarded"
        instances != null
        instances.getNode("aninstanceId-us-east-1") != null
        instances.getNodeNames().size() == 1

        and: "the denied region's failure is reported rather than lost silently"
        mapper.getQueryErrors().size() == 1
        mapper.getQueryErrors()[0].contains("us-west-1")
    }

    def "one region denied under parallel querying still returns the other region's nodes"() {
        given: "the same denied/working split, queried in parallel"
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint('https://ec2.us-west-1.amazonaws.com') >> {
                throw new RuntimeException("UnauthorizedOperation: not authorized for us-west-1")
            }
            getEC2ForEndpoint('https://ec2.us-east-1.amazonaws.com') >> {
                def instance = mkInstance('us-east-1').toBuilder().instanceId("aninstanceId-us-east-1").build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(instance).build())
                            .build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))

        when:
        def instances = mapper.performQuery(true)

        then: "the working region's node is still returned, not discarded"
        instances != null
        instances.getNode("aninstanceId-us-east-1") != null
        instances.getNodeNames().size() == 1

        and: "the denied region's failure is reported rather than lost silently"
        mapper.getQueryErrors().size() == 1
        mapper.getQueryErrors()[0].contains("us-west-1")
    }

    def "every region denied under multiple endpoints throws rather than returning an empty result"() {
        given: "both us-west-1 and us-east-1 denied, sequential querying"
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint('https://ec2.us-west-1.amazonaws.com') >> {
                throw new RuntimeException("UnauthorizedOperation: not authorized for us-west-1")
            }
            getEC2ForEndpoint('https://ec2.us-east-1.amazonaws.com') >> {
                throw new RuntimeException("UnauthorizedOperation: not authorized for us-east-1")
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))

        when: "a total outage happens, unlike an ordinary partial failure there is nothing to preserve"
        mapper.performQuery(false)

        then: "the failure propagates instead of yielding an empty-but-successful result"
        RuntimeException ex = thrown()
        ex.message.contains("us-west-1")
        ex.message.contains("us-east-1")
    }

    def "every region denied under parallel querying throws rather than returning an empty result"() {
        given: "the same total-outage setup, queried in parallel"
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint('https://ec2.us-west-1.amazonaws.com') >> {
                throw new RuntimeException("UnauthorizedOperation: not authorized for us-west-1")
            }
            getEC2ForEndpoint('https://ec2.us-east-1.amazonaws.com') >> {
                throw new RuntimeException("UnauthorizedOperation: not authorized for us-east-1")
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))

        when:
        mapper.performQuery(true)

        then:
        RuntimeException ex = thrown()
        ex.message.contains("us-west-1")
        ex.message.contains("us-east-1")
    }

    def "isolation still applies when a region's failure is not a recognized AWS SDK exception"() {
        given: "us-west-1 fails with a plugin-side bug (e.g. an NPE), us-east-1 working"
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint('https://ec2.us-west-1.amazonaws.com') >> {
                throw new NullPointerException()
            }
            getEC2ForEndpoint('https://ec2.us-east-1.amazonaws.com') >> {
                def instance = mkInstance('us-east-1').toBuilder().instanceId("aninstanceId-us-east-1").build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(instance).build())
                            .build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))

        when:
        def instances = mapper.performQuery(false)

        then: "the working region's node is still returned; a non-AWS failure doesn't abort the batch"
        instances != null
        instances.getNode("aninstanceId-us-east-1") != null
        instances.getNodeNames().size() == 1

        and: "the failure is still reported"
        mapper.getQueryErrors().size() == 1
        mapper.getQueryErrors()[0].contains("us-west-1")
    }

    def "an interrupted region query does not discard already-successful results from other regions during parallel querying"() {
        given: "one endpoint that hangs until released (to be interrupted), and a second that returns a node immediately"
        def startedLatch = new java.util.concurrent.CountDownLatch(1)
        def releaseLatch = new java.util.concurrent.CountDownLatch(1)
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint('https://ec2.us-west-1.amazonaws.com') >> {
                Mock(Ec2Client) {
                    describeAvailabilityZones() >> {
                        startedLatch.countDown()
                        releaseLatch.await()
                        DescribeAvailabilityZonesResponse.builder().build()
                    }
                }
            }
            getEC2ForEndpoint('https://ec2.us-east-1.amazonaws.com') >> {
                def instance = mkInstance('us-east-1').toBuilder().instanceId("aninstanceId-us-east-1").build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(instance).build())
                            .build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet()
        def instances = null
        Thread queryThread = new Thread({ -> instances = mapper.performQuery(true) })

        when: "the query starts, and once us-west-1's call is blocked, only its own worker thread is interrupted"
        queryThread.start()
        boolean reachedBlockedRegionCall = startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        new PollingConditions(timeout: 5).eventually {
            assert workersBlockedOnReleaseLatch(threadsBefore).size() == 1
        }
        Thread blockedWorker = workersBlockedOnReleaseLatch(threadsBefore)[0]
        blockedWorker.interrupt()
        queryThread.join(5000)

        then: "the blocked region's own worker thread was found and interrupted, not the calling thread"
        reachedBlockedRegionCall
        blockedWorker != null
        !queryThread.isAlive()

        and: "us-east-1's already-successful result is not discarded just because us-west-1's worker was interrupted"
        instances != null
        instances.getNode("aninstanceId-us-east-1") != null
        instances.getNodeNames().size() == 1

        cleanup: "release the mock call in case the interrupt was somehow missed"
        releaseLatch.countDown()
    }

    def "every region interrupted under parallel querying throws rather than returning an empty result"() {
        given: "both endpoints hang until released, so both can be interrupted before either succeeds"
        def startedLatch = new java.util.concurrent.CountDownLatch(2)
        def releaseLatch = new java.util.concurrent.CountDownLatch(1)
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint(_) >> {
                Mock(Ec2Client) {
                    describeAvailabilityZones() >> {
                        startedLatch.countDown()
                        releaseLatch.await()
                        DescribeAvailabilityZonesResponse.builder().build()
                    }
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet()
        Throwable caught = null
        Thread queryThread = new Thread({ ->
            try {
                mapper.performQuery(true)
            } catch (Throwable t) {
                caught = t
            }
        })

        when: "the query starts, and once both regions' calls are blocked, both worker threads are interrupted"
        queryThread.start()
        boolean bothReachedBlockedCall = startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        new PollingConditions(timeout: 5).eventually {
            assert workersBlockedOnReleaseLatch(threadsBefore).size() == 2
        }
        workersBlockedOnReleaseLatch(threadsBefore).each { it.interrupt() }
        queryThread.join(5000)

        then: "both regions were actually reached and interrupted before either could succeed"
        bothReachedBlockedCall
        !queryThread.isAlive()

        and: "the total failure propagates instead of yielding an empty-but-successful result, even though no per-region error was ever recorded"
        caught != null

        cleanup: "release the mock call in case an interrupt was somehow missed"
        releaseLatch.countDown()
    }

    def "parallel query still terminates promptly, without leaking its inner thread pool, when interrupted mid-flight"() {
        given: "one endpoint that hangs until released, and a second that returns immediately"
        def startedLatch = new java.util.concurrent.CountDownLatch(1)
        def releaseLatch = new java.util.concurrent.CountDownLatch(1)
        def endpoints = ['https://ec2.us-west-1.amazonaws.com', 'https://ec2.us-east-1.amazonaws.com']
        EC2Supplier supplier = Mock(EC2Supplier) {
            getEC2ForEndpoint(_) >> {
                Mock(Ec2Client) {
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().build()
                    describeInstances(_) >> {
                        startedLatch.countDown()
                        releaseLatch.await()
                        DescribeInstancesResponse.builder().build()
                    }
                }
            }
        }
        def mapper = new InstanceToNodeMapper(supplier, new Properties(), 100)
        mapper.setEndpoint(endpoints.join(', '))
        Throwable caught = null
        Thread queryThread = new Thread({ ->
            try {
                mapper.performQuery(true)
            } catch (Throwable t) {
                caught = t
            }
        })
        Set<Thread> threadsBefore = Thread.getAllStackTraces().keySet()

        when: "the query is started, then interrupted once blocked inside a region call"
        queryThread.start()
        boolean reachedBlockedRegionCall = startedLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
        queryThread.interrupt()
        queryThread.join(5000)

        then: "the region call was actually reached before interrupting"
        reachedBlockedRegionCall

        and: "the interrupted thread finishes without hanging"
        !queryThread.isAlive()
        caught != null

        and: "the inner region pool was shut down too, not left alive with idle worker threads"
        new PollingConditions(timeout: 5).eventually {
            assert leakedPoolThreads(threadsBefore).isEmpty()
        }

        cleanup: "release the mock call"
        releaseLatch.countDown()
    }

    /** Names of executor pool worker threads still alive that did not exist in the snapshot. */
    private static List<String> leakedPoolThreads(Set<Thread> before) {
        Thread.getAllStackTraces().keySet()
                .findAll { !(it in before) && it.alive && it.name.startsWith("pool-") }*.name
    }

    /**
     * The new pool worker threads, if any, that are actually blocked inside {@code
     * releaseLatch.await()} -- not just any new pool thread in {@code WAITING} state, since an idle
     * worker parked on the pool's internal work queue is in that same state and would otherwise be
     * matched instead.
     */
    private static List<Thread> workersBlockedOnReleaseLatch(Set<Thread> before) {
        Thread.getAllStackTraces().findAll { thread, trace ->
            !(thread in before) && thread.name.startsWith("pool-") &&
                    trace.any { it.className == 'java.util.concurrent.CountDownLatch' && it.methodName == 'await' }
        }.keySet() as List<Thread>
    }
    def "region added to the node attributes with ALL_REGIONS specified"() {
        given:

        Image image = mkImage()

        EC2Supplier supplier = Mock(EC2Supplier) {
            1 * getEC2ForDefaultRegion() >> Mock(Ec2Client) {
                1 * describeRegions() >> DescribeRegionsResponse.builder()
                        .regions(regions.collect({ r ->
                            // Real AWS DescribeRegions responses return a bare hostname with no
                            // URI scheme (e.g. "ec2.us-west-1.amazonaws.com"), not a full URL.
                            Region.builder()
                                    .regionName(r)
                                    .endpoint("ec2.${r}.amazonaws.com".toString())
                                    .build()
                        }))
                        .build()
            }
            0 * getEC2ForRegion(_)

            _ * getEC2ForEndpoint({ it in endpointsFound }) >> { args ->
                def region = regions[endpointsFound.indexOf(args[0])]
                AvailabilityZone zone1 = AvailabilityZone.builder()
                        .regionName(region)
                        .zoneName("${region}a".toString())
                        .build()
                Mock(Ec2Client) {
                    describeInstances(_) >> DescribeInstancesResponse.builder()
                            .reservations(Reservation.builder().instances(mkInstance(region)).build())
                            .build()
                    describeImages(_) >> DescribeImagesResponse.builder().images(image).build()
                    describeAvailabilityZones() >> DescribeAvailabilityZonesResponse.builder().availabilityZones(zone1).build()
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
        'ALL_REGIONS' | ['ec2.us-west-1.amazonaws.com', 'ec2.us-east-1.amazonaws.com']|['us-west-1', 'us-east-1']

    }

    //
    // Private Methods
    //
    private static Instance mkInstance(String region='us-east-1') {
        return Instance.builder()
                .tags(
                        Tag.builder().key('Name').value('bob').build(),
                        Tag.builder().key('env').value('PROD').build()
                )
                .instanceId("aninstanceId")
                .architecture("anarch")
                .imageId("ami-something")
                .placement(Placement.builder().availabilityZone("${region}a".toString()).build())
                .state(InstanceState.builder().name(InstanceStateName.RUNNING).build())
                .privateIpAddress('127.0.9.9')
                .build()
    }

    private static Image mkImage(){
        return Image.builder()
                .imageId("ami-something")
                .name("AMISomething")
                .build()
    }
}
