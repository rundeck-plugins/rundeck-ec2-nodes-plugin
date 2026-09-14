package com.dtolabs.rundeck.plugin.resources.ec2

import software.amazon.awssdk.auth.credentials.AwsCredentials
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.common.ProjectManager
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException
import com.dtolabs.rundeck.core.storage.keys.KeyStorageTree
import org.rundeck.app.spi.Services
import org.rundeck.storage.api.StorageException
import spock.lang.Specification
import spock.lang.Unroll

class EC2ResourceModelSourceSpec extends Specification {

    @Unroll
    def "getModelSourceErrors reflects a failed background refresh as soon as it completes, with no further getNodes() call, even when the exception has #description message"() {
        given: "a constructed source whose mapper will fail the next query with a #description-message exception"
        def config = createDefaultConfig()
        config.setProperty(EC2ResourceModelSourceFactory.ACCESS_KEY, "an-access-key")
        config.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY, "a-secret-key")
        config.setProperty(EC2ResourceModelSourceFactory.SYNCHRONOUS_LOAD, "false")
        EC2ResourceModelSource rms = ec2ResourceModelSource(Mock(Services), config)
        rms.mapper = Mock(InstanceToNodeMapper) {
            performQuery(_) >> { throw cause }
        }
        // simulate an already-completed prior refresh so the next getNodes() call takes the async
        // (background executor) path rather than the synchronous first-fetch path
        rms.lastRefresh = 1L

        when: "getNodes() submits the background query; we only wait for that submitted task itself to finish, not for another getNodes() call"
        rms.getNodes()
        try {
            rms.futureResult.get()
        } catch (Exception ignored) {
            // expected: the task is expected to fail; get() here is only used to block until it's done
        }

        then: "the failure is already visible via getModelSourceErrors(), with no additional getNodes()/checkFuture() call involved"
        def errors = rms.getModelSourceErrors()
        errors.size() == 1
        errors[0] != null
        !errors[0].isEmpty()
        errors[0].contains("RuntimeException")

        where:
        description | cause
        "no"        | new RuntimeException()
        "blank"     | new RuntimeException("")
    }

    def "constructor validates configuration before allocating resources or contacting Services"() {
        given: "an access key configured without its secret key or storage path -- invalid per validate()"
        def config = createDefaultConfig()
        config.setProperty(EC2ResourceModelSourceFactory.ACCESS_KEY, "an-access-key")
        def services = Mock(Services)

        when:
        ec2ResourceModelSource(services, config)

        then:
        ConfigurationException ex = thrown()
        ex.message.contains("secretKey is required")
        // proves construction failed before any credential/key-storage resolution was attempted,
        // i.e. before the HTTP client or background executor would otherwise have been allocated
        0 * services._
    }

    def "constructor failure after resource allocation still shuts down the executor, without going through the overridable close()"() {
        given: "a key storage lookup that fails, so createCredentials() throws from within the constructor"
        CapturingEC2ResourceModelSource.captured = null
        def config = createDefaultConfig()
        config.setProperty(EC2ResourceModelSourceFactory.ACCESS_KEY, "an-access-key")
        config.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY_STORAGE_PATH, "keys/missing")
        def services = Mock(Services) {
            getService(KeyStorageTree.class) >> Mock(KeyStorageTree) {
                readPassword("keys/missing") >> { throw new IOException("not found") }
            }
        }

        when:
        new CapturingEC2ResourceModelSource(config, services)

        then: "construction fails, but the instance it failed on (captured just before the throw) shows its executor was still shut down by the constructor's own cleanup path"
        thrown(StorageException)
        CapturingEC2ResourceModelSource.captured != null
        CapturingEC2ResourceModelSource.captured.executor.isShutdown()
    }

    def "user configured access credentials prefer key storage"() {
        given: "a user's plugin config"
        //Define good and bad keys and paths
        def validAccessKey = "validAccessKey"
        def validSecretKey = "validSecretKey"
        def validKeyPath = "keys/validKeyPath"
        def badPath = "keys/badPath"
        def badPass = "myNetflixPassword"

        // Mock services and Key Storage return of passwords
        def serviceWithGoodPass = mockServicesWithPassword(validKeyPath, validSecretKey)
        def serviceWithBadPass = mockServicesWithPassword(badPath, badPass)

        // Create a default config object (these are the settings the user would setup via the Plugin UI)
        def defaultConfig = createDefaultConfig()
        defaultConfig.setProperty(EC2ResourceModelSourceFactory.ACCESS_KEY, validAccessKey)

        // Create a working config from the defaults
        def workingConfig = new Properties()
        workingConfig.putAll(defaultConfig)
        // Send a bad key to ensure key path takes precedence and succeeds
        workingConfig.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY, badPass)
        workingConfig.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY_STORAGE_PATH, validKeyPath)

        // Create a failing config from the defaults
        def failingConfig = new Properties()
        failingConfig.putAll(defaultConfig)
        // Send a valid key to ensure storage path takes precedence and fails
        failingConfig.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY, validSecretKey)
        failingConfig.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY_STORAGE_PATH, badPath)

        // Create objects using actual ResourceModelSource and Factory
        EC2ResourceModelSource workingRms = ec2ResourceModelSource(serviceWithGoodPass, workingConfig)
        EC2ResourceModelSource failingRms = ec2ResourceModelSource(serviceWithBadPass, failingConfig)

        when: "we check the access keys of the resource model source objects"
        // Instead of using getNodes, which would all be highly mocked, just check that we got as far as setting
        // proper credentials right before the point we would call to AWS
        def workingRmsPass = workingRms.createCredentials().secretAccessKey()
        def failingRmsPass = failingRms.createCredentials().secretAccessKey()

        then: "we see that the proper keys from the key storage or the inline key have been derived"
        workingRmsPass == validSecretKey
        failingRmsPass == badPass
    }
    def "fail properly when invalid key path is provided"() {
        given: "User plugin config that uses an invalid key path"
        //Define good and bad keys and paths
        def validAccessKey = "validAccessKey"
        def goodKeyPath = "keys/validKeyPath"
        def badPath = "keys/badPath"
        def badPass = "myNetflixPassword"

        // Mock services and Key Storage return of passwords
        def serviceWithBadPass = mockServicesWithPassword(badPath, null)

        // Create a default config object (these are the settings the user would setup via the Plugin UI)
        def config = createDefaultConfig()
        config.setProperty(EC2ResourceModelSourceFactory.ACCESS_KEY, validAccessKey)
        config.setProperty(EC2ResourceModelSourceFactory.SECRET_KEY_STORAGE_PATH, badPath)

        when: "user attempts to create EC2ResourceModelSource instance using invalid path"
        def failingRms = ec2ResourceModelSource(serviceWithBadPass, config)

        then: "expect a StorageException#readException to be returned"
        StorageException ex = thrown()
        ex.message.contains("error accessing key storage at ${badPath}")
    }
    //
    // Private Methods
    //
    private def createDefaultConfig() {
        def configuration = new Properties()
        def assumeRoleArn = "arn:aws:iam::123456789012:role/fake-test-arn"
        def endpoint = "ALL_REGIONS"
        def pageResults = "100"
        def proxyPortStr = "80"
        def refreshStr = "30"
        def useDefaultMapping = "true"
        def runningOnly = "true"

        configuration.setProperty(EC2ResourceModelSourceFactory.ROLE_ARN, assumeRoleArn)
        configuration.setProperty(EC2ResourceModelSourceFactory.ENDPOINT, endpoint);
        configuration.setProperty(EC2ResourceModelSourceFactory.MAX_RESULTS, pageResults);
        configuration.setProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_PORT, proxyPortStr);
        configuration.setProperty(EC2ResourceModelSourceFactory.REFRESH_INTERVAL, refreshStr);
        configuration.setProperty(EC2ResourceModelSourceFactory.USE_DEFAULT_MAPPING, useDefaultMapping)
        configuration.setProperty(EC2ResourceModelSourceFactory.RUNNING_ONLY, runningOnly)

        return configuration
    }

    private def ec2ResourceModelSource(Services services, Properties configuration) {
        def framework = Mock(Framework)
        def factory = new EC2ResourceModelSourceFactory(framework)

        return factory.createResourceModelSource(services, configuration)
    }

    private def mockServicesWithPassword(String path, String password) {
        def storageTree = Mock(KeyStorageTree) {
            readPassword(path) >> {
                return password.bytes
            }
        }

        def services = Mock(Services) {
            getService(KeyStorageTree.class) >> storageTree
        }

    }
}

/**
 * Captures a reference to "this" from within {@link #createCredentials()}, before delegating to the
 * real implementation -- which, in the test that uses this class, is expected to throw. Overriding
 * createCredentials() (rather than close()) to capture the instance is deliberately safe to do from
 * still-under-construction state: it touches no fields of its own, so the base class constructor's
 * own subsequent cleanup (calling its private, non-overridable releaseResources() -- see
 * EC2ResourceModelSource) can be observed afterward directly on the captured instance, with no
 * reliance on any overridable cleanup method being invoked mid-construction.
 */
class CapturingEC2ResourceModelSource extends EC2ResourceModelSource {
    static EC2ResourceModelSource captured

    CapturingEC2ResourceModelSource(Properties configuration, Services services) throws ConfigurationException {
        super(configuration, services)
    }

    @Override
    protected AwsCredentials createCredentials() {
        captured = this
        return super.createCredentials()
    }
}
