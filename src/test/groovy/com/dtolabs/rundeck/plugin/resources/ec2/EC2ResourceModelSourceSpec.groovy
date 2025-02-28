package com.dtolabs.rundeck.plugin.resources.ec2

import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.IRundeckProject
import com.dtolabs.rundeck.core.common.ProjectManager
import com.dtolabs.rundeck.core.storage.keys.KeyStorageTree
import org.rundeck.app.spi.Services
import org.rundeck.storage.api.StorageException
import spock.lang.Specification

class EC2ResourceModelSourceSpec extends Specification {
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
        def workingRmsPass = workingRms.createCredentials().getAWSSecretKey()
        def failingRmsPass = failingRms.createCredentials().getAWSSecretKey()

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
