/*
 * Copyright 2011 DTO Solutions, Inc. (http://dtosolutions.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

/*
* EC2ResourceModelSource.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: 9/1/11 4:34 PM
* 
*/
package com.dtolabs.rundeck.plugin.resources.ec2;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.BasicAWSCredentials;
import com.dtolabs.rundeck.core.common.*;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.core.resources.ResourceModelSource;
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException;
import org.apache.log4j.Logger;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * EC2ResourceModelSource produces nodes by querying the AWS EC2 API to list instances.
 * <p/>
 * The RunDeck node definitions are created from the instances on a mapping system to convert properties of the amazon
 * instances to attributes defined on the nodes.
 * <p/>
 * The EC2 requests are performed asynchronously, so the first request to {@link #getNodes()} will return null, and
 * subsequent requests may return the data when it's available.
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
public class EC2ResourceModelSource implements ResourceModelSource {
    static Logger logger = Logger.getLogger(EC2ResourceModelSource.class);
    private String accessKey;
    private String secretKey;
    long refreshInterval = 30000;
    long lastRefresh = 0;
    String filterParams;
    String endpoint;
    String httpProxyHost;
    int httpProxyPort = 80;
    String httpProxyUser;
    String httpProxyPass;
    String mappingParams;
    File mappingFile;
    boolean useDefaultMapping = true;
    boolean runningOnly = true;
    boolean queryAsync = true;
    Future<INodeSet> futureResult = null;
    final Properties mapping = new Properties();

    AWSCredentials credentials;
    ClientConfiguration clientConfiguration = new ClientConfiguration();;
    
    INodeSet iNodeSet;
    static final Properties defaultMapping = new Properties();
    InstanceToNodeMapper mapper;

    static {
        final String mapping = "nodename.selector=tags/Name,instanceId\n"
                               + "hostname.selector=publicDnsName\n"
                               + "sshport.default=22\n"
                               + "sshport.selector=tags/ssh_config_Port\n"
                               + "description.default=EC2 node instance\n"
                               + "osArch.selector=architecture\n"
                               + "osFamily.selector=platform\n"
                               + "osFamily.default=unix\n"
                               + "osName.selector=platform\n"
                               + "osName.default=Linux\n"
                               + "username.selector=tags/Rundeck-User\n"
                               + "username.default=ec2-user\n"
                               + "editUrl.default=https://console.aws.amazon.com/ec2/home#Instances:search=${node.instanceId}\n"
                               + "privateIpAddress.selector=privateIpAddress\n"
                               + "privateDnsName.selector=privateDnsName\n"
                               + "tags.selector=tags/Rundeck-Tags\n"
                               + "instanceId.selector=instanceId\n"
                               + "tag.running.selector=state.name=running\n"
                               + "tag.stopped.selector=state.name=stopped\n"
                               + "tag.stopping.selector=state.name=stopping\n"
                               + "tag.shutting-down.selector=state.name=shutting-down\n"
                               + "tag.terminated.selector=state.name=terminated\n"
                               + "tag.pending.selector=state.name=pending\n"
                               + "state.selector=state.name\n"
                               + "tags.default=ec2\n";
        try {

            final InputStream resourceAsStream = EC2ResourceModelSource.class.getClassLoader().getResourceAsStream(
                "defaultMapping.properties");
            if (null != resourceAsStream) {
                try {
                    defaultMapping.load(resourceAsStream);
                } finally {
                    resourceAsStream.close();
                }
            }else{
                //fallback in case class loader is misbehaving
                final StringReader stringReader = new StringReader(mapping);
                try {
                    defaultMapping.load(stringReader);
                } finally {
                    stringReader.close();
                }
            }

        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }

    public EC2ResourceModelSource(final Properties configuration) {
        this.accessKey = configuration.getProperty(EC2ResourceModelSourceFactory.ACCESS_KEY);
        this.secretKey = configuration.getProperty(EC2ResourceModelSourceFactory.SECRET_KEY);
        this.endpoint = configuration.getProperty(EC2ResourceModelSourceFactory.ENDPOINT);
        this.httpProxyHost = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_HOST);
        int proxyPort = 80;
        
        final String proxyPortStr = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_PORT);
        if (null != proxyPortStr && !"".equals(proxyPortStr)) {
            try {
                proxyPort = Integer.parseInt(proxyPortStr);
            } catch (NumberFormatException e) {
                logger.warn(EC2ResourceModelSourceFactory.HTTP_PROXY_PORT + " value is not valid: " + proxyPortStr);
            }
        }
        this.httpProxyPort = proxyPort;
        this.httpProxyUser = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_USER);
        this.httpProxyPass = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_PASS);

        this.filterParams = configuration.getProperty(EC2ResourceModelSourceFactory.FILTER_PARAMS);
        this.mappingParams = configuration.getProperty(EC2ResourceModelSourceFactory.MAPPING_PARAMS);
        final String mappingFilePath = configuration.getProperty(EC2ResourceModelSourceFactory.MAPPING_FILE);
        if (null != mappingFilePath) {
            mappingFile = new File(mappingFilePath);
        }
        int refreshSecs = 30;
        final String refreshStr = configuration.getProperty(EC2ResourceModelSourceFactory.REFRESH_INTERVAL);
        if (null != refreshStr && !"".equals(refreshStr)) {
            try {
                refreshSecs = Integer.parseInt(refreshStr);
            } catch (NumberFormatException e) {
                logger.warn(EC2ResourceModelSourceFactory.REFRESH_INTERVAL + " value is not valid: " + refreshStr);
            }
        }
        refreshInterval = refreshSecs * 1000;
        if (configuration.containsKey(EC2ResourceModelSourceFactory.USE_DEFAULT_MAPPING)) {
            useDefaultMapping = Boolean.parseBoolean(configuration.getProperty(
                EC2ResourceModelSourceFactory.USE_DEFAULT_MAPPING));
        }
        if (configuration.containsKey(EC2ResourceModelSourceFactory.RUNNING_ONLY)) {
            runningOnly = Boolean.parseBoolean(configuration.getProperty(
                EC2ResourceModelSourceFactory.RUNNING_ONLY));
        }
        if (null != accessKey && null != secretKey) {
            credentials = new BasicAWSCredentials(accessKey.trim(), secretKey.trim());
        }
        
        if (null != httpProxyHost && !"".equals(httpProxyHost)) {
            clientConfiguration.setProxyHost(httpProxyHost);
            clientConfiguration.setProxyPort(httpProxyPort);
            clientConfiguration.setProxyUsername(httpProxyUser);
            clientConfiguration.setProxyPassword(httpProxyPass);
        }
        
        initialize();
    }

    private void initialize() {
        final ArrayList<String> params = new ArrayList<String>();
        if (null != filterParams) {
            Collections.addAll(params, filterParams.split(";"));
        }
        loadMapping();
        mapper = new InstanceToNodeMapper(credentials, mapping, clientConfiguration);
        mapper.setFilterParams(params);
        mapper.setEndpoint(endpoint);
        mapper.setRunningStateOnly(runningOnly);
    }


    public synchronized INodeSet getNodes() throws ResourceModelSourceException {
        checkFuture();
        if (!needsRefresh()) {
            if (null != iNodeSet) {
                logger.info("Returning " + iNodeSet.getNodeNames().size() + " cached nodes from EC2");
            }
            return iNodeSet;
        }
        if (lastRefresh > 0 && queryAsync && null == futureResult) {
            futureResult = mapper.performQueryAsync();
            lastRefresh = System.currentTimeMillis();
        } else if (!queryAsync || lastRefresh < 1) {
            //always perform synchronous query the first time
            iNodeSet = mapper.performQuery();
            lastRefresh = System.currentTimeMillis();
        }
        if (null != iNodeSet) {
            logger.info("Read " + iNodeSet.getNodeNames().size() + " nodes from EC2");
        }
        return iNodeSet;
    }

    /**
     * if any future results are pending, check if they are done and retrieve the results
     */
    private void checkFuture() {
        if (null != futureResult && futureResult.isDone()) {
            try {
                iNodeSet = futureResult.get();
            } catch (InterruptedException e) {
                logger.debug(e);
            } catch (ExecutionException e) {
                logger.warn("Error performing query: " + e.getMessage(), e);
            }
            futureResult = null;
        }
    }

    /**
     * Returns true if the last refresh time was longer ago than the refresh interval
     */
    private boolean needsRefresh() {
        return refreshInterval < 0 || (System.currentTimeMillis() - lastRefresh > refreshInterval);
    }

    private void loadMapping() {
        if (useDefaultMapping) {
            mapping.putAll(defaultMapping);
        }
        if (null != mappingFile) {
            try {
                final FileInputStream fileInputStream = new FileInputStream(mappingFile);
                try {
                    mapping.load(fileInputStream);
                } finally {
                    fileInputStream.close();
                }
            } catch (IOException e) {
                logger.warn(e);
            }
        }
        if (null != mappingParams) {
            for (final String s : mappingParams.split(";")) {
                if (s.contains("=")) {
                    final String[] split = s.split("=", 2);
                    if (2 == split.length) {
                        mapping.put(split[0], split[1]);
                    }
                }
            }
        }
        if (mapping.size() < 1) {
            mapping.putAll(defaultMapping);
        }
    }

    public void validate() throws ConfigurationException {
        if (null != accessKey && null == secretKey) {
            throw new ConfigurationException("secretKey is required for use with accessKey");
        }

    }
}
