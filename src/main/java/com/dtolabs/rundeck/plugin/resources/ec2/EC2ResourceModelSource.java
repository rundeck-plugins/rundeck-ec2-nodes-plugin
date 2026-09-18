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

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.StsClientBuilder;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;
import com.dtolabs.rundeck.core.common.INodeSet;
import com.dtolabs.rundeck.core.plugins.configuration.ConfigurationException;
import com.dtolabs.rundeck.core.resources.ResourceModelSource;
import com.dtolabs.rundeck.core.resources.ResourceModelSourceErrors;
import com.dtolabs.rundeck.core.resources.ResourceModelSourceException;
import com.dtolabs.rundeck.core.storage.keys.KeyStorageTree;
import org.rundeck.app.spi.Services;
import org.rundeck.storage.api.PathUtil;
import org.rundeck.storage.api.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.dtolabs.rundeck.plugin.resources.ec2.EC2ResourceModelSourceFactory.SYNCHRONOUS_LOAD;

/**
 * <p>
 * EC2ResourceModelSource produces nodes by querying the AWS EC2 API to list instances.
 * </p>
 * <p>
 * The RunDeck node definitions are created from the instances on a mapping system to convert properties of the amazon
 * instances to attributes defined on the nodes.
 * </p>
 * <p>
 * The EC2 requests are performed asynchronously, so the first request to {@link #getNodes()} will return null, and
 * subsequent requests may return the data when it's available.
 * </p>
 * @author Greg Schueler <a href="mailto:greg@rundeck.com">greg@rundeck.com</a>
 */
public class EC2ResourceModelSource implements ResourceModelSource, ResourceModelSourceErrors, Closeable {
    static  Logger logger = LoggerFactory.getLogger(EC2ResourceModelSource.class);
    private String accessKey;
    private String secretKey;
    private String secretKeyStoragePath;
    long refreshInterval = 30000;
    // volatile: written from both the background refresh thread and getNodes() itself
    volatile long lastRefresh = 0;
    String filterParams;
    String endpoint;
    String httpProxyHost;
    int httpProxyPort = 80;
    String httpProxyUser;
    String httpProxyPass;
    String region;
    String mappingParams;
    File mappingFile;
    Services services;
    boolean useDefaultMapping = true;
    boolean runningOnly = false;
    boolean queryAsync = true;
    boolean queryNodeInstancesInParallel = false;
    Future<INodeSet> futureResult = null;
    final Properties mapping = new Properties();
    final String assumeRoleArn;
    final String assumeRoleArnCombinedWithExtId;

    final String externalId;
    int pageResults;

    SdkHttpClient httpClient;

    INodeSet iNodeSet;
    static final Properties defaultMapping = new Properties();
    InstanceToNodeMapper mapper;

    ExecutorService executor;

    /**
     * Message from the most recent failed background (async) refresh, or null if the last refresh
     * succeeded. Exposed via {@link #getModelSourceErrors()} so a stale node cache resulting from
     * repeated background failures (e.g. expired credentials) is surfaced instead of failing silently.
     */
    private volatile String lastQueryError;

    static {
        final String mapping = "nodename.selector=tags/Name,instanceId\n"
                               + "hostname.selector=publicDnsName,privateIpAddress\n"
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
                               + "region.selector=region\n"
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

    public EC2ResourceModelSource(final Properties configuration, final Services services) {
        this.services = services;
        this.accessKey = configuration.getProperty(EC2ResourceModelSourceFactory.ACCESS_KEY);
        this.secretKey = configuration.getProperty(EC2ResourceModelSourceFactory.SECRET_KEY);
        this.region = configuration.getProperty(EC2ResourceModelSourceFactory.REGION);
        this.secretKeyStoragePath = configuration.getProperty(EC2ResourceModelSourceFactory.SECRET_KEY_STORAGE_PATH);
        this.endpoint = configuration.getProperty(EC2ResourceModelSourceFactory.ENDPOINT);
        this.pageResults = parseIntOrDefault(
                configuration.getProperty(EC2ResourceModelSourceFactory.MAX_RESULTS),
                EC2ResourceModelSourceFactory.MAX_RESULTS_DEFAULT,
                EC2ResourceModelSourceFactory.MAX_RESULTS
        );
        this.httpProxyHost = configuration.getProperty(EC2ResourceModelSourceFactory.HTTP_PROXY_HOST);
        this.assumeRoleArn = configuration.getProperty(EC2ResourceModelSourceFactory.ROLE_ARN);
        this.assumeRoleArnCombinedWithExtId = configuration.getProperty(EC2ResourceModelSourceFactory.ROLE_ARN_COMBINED_WITH_EXT_ID);
        this.externalId = configuration.getProperty(EC2ResourceModelSourceFactory.EXTERNAL_ID);

        // Callers going through EC2ResourceModelSourceFactory have already had the configuration
        // validated before this constructor runs. Other callers that construct this class directly
        // (e.g. rundeckpro's own factory) must call validate() themselves after construction.
        this.executor = Executors.newFixedThreadPool(1);

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
            logger.info("[debug] runningOnly:" + runningOnly);
        }


        try {
            this.httpClient = buildHttpClient();

            queryAsync = !("true".equals(configuration.getProperty(SYNCHRONOUS_LOAD)) || refreshInterval <= 0);

            this.queryNodeInstancesInParallel = Boolean.parseBoolean(configuration.getProperty(EC2ResourceModelSourceFactory.QUERY_NODE_INSTANCES_IN_PARALLEL, "false"));

            final ArrayList<String> params = new ArrayList<String>();
            if (null != filterParams) {
                Collections.addAll(params, filterParams.split(";"));
            }
            loadMapping();

            // createEc2Supplier() resolves credentials, which for an assumed role makes a real STS
            // AssumeRole call -- if that (or anything else below) throws, this partially-initialized
            // instance is never returned to any caller, so releaseResources() must be called here to
            // free the HTTP client (and executor) already allocated above; otherwise nothing else ever
            // would (see the catch block below).
            mapper = new InstanceToNodeMapper(createEc2Supplier(), mapping, pageResults);
            mapper.setFilterParams(params);
            mapper.setEndpoint(endpoint);
            mapper.setRegion(region);
            mapper.setRunningStateOnly(runningOnly);
        } catch (RuntimeException e) {
            // Calls the private releaseResources() rather than the public, overridable close():
            // invoking an overridable method here would run it on a "this" that may still be under
            // construction from a subclass's point of view, before the subclass's own fields have
            // initialized.
            releaseResources();
            throw e;
        }
    }


    /**
     * Parse an integer property, falling back to a default (silently) if it is missing or blank, or
     * (logging a warning) if it is present but not a valid integer -- rather than letting a raw
     * {@link NumberFormatException} escape from the constructor.
     */
    private static int parseIntOrDefault(String value, int defaultValue, String propName) {
        if (null == value || "".equals(value)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            logger.warn(propName + " value is not valid: " + value);
            return defaultValue;
        }
    }

    /**
     * Build a shared HTTP client, applying HTTP proxy configuration when supplied. The same client
     * is reused for the EC2 clients and the STS client so proxy settings apply consistently.
     * <p>
     * Deliberately still on the deprecated Apache HTTP client (4.x), not {@code apache5-client}: see
     * the {@code pluginLibs} comment in {@code build.gradle} -- 4.x is the one actually bundled into
     * the plugin jar (apache5-client is excluded to keep it small), so switching this import without
     * also flipping that dependency would reference a client not present at runtime.
     */
    @SuppressWarnings("deprecation")
    private SdkHttpClient buildHttpClient() {
        ApacheHttpClient.Builder builder = ApacheHttpClient.builder();
        if (null != httpProxyHost && !"".equals(httpProxyHost)) {
            ProxyConfiguration.Builder proxy = ProxyConfiguration.builder()
                    .endpoint(URI.create("http://" + httpProxyHost + ":" + httpProxyPort));
            if (null != httpProxyUser && !"".equals(httpProxyUser)) {
                proxy.username(httpProxyUser);
            }
            if (null != httpProxyPass && !"".equals(httpProxyPass)) {
                proxy.password(httpProxyPass);
            }
            builder.proxyConfiguration(proxy.build());
        }
        return builder.build();
    }

    protected AwsCredentials createCredentials() {
        if (null != accessKey && null != secretKeyStoragePath) {
            KeyStorageTree keyStorage = services.getService(KeyStorageTree.class);
            String secretKey = getPasswordFromKeyStorage(secretKeyStoragePath, keyStorage);
            return AwsBasicCredentials.create(accessKey.trim(), secretKey.trim());
        } else if (null != accessKey && null != secretKey) {
            return AwsBasicCredentials.create(accessKey.trim(), secretKey.trim());
        }

        AwsCredentials credentials = null;
        if (this.externalId != null && this.assumeRoleArnCombinedWithExtId != null) {
            credentials = createAwsCredentials(null, this.assumeRoleArnCombinedWithExtId, this.externalId);
        }

        if (assumeRoleArn != null) {
            AwsCredentialsProvider provider = null;
            if (credentials != null) {
                provider = StaticCredentialsProvider.create(credentials);
            }

            return createAwsCredentials(provider, assumeRoleArn, null);
        }
        return credentials;
    }


    private EC2SupplierImpl createEc2Supplier() {
        return new EC2SupplierImpl(
                createCredentials(),
                httpClient,
                // Use old default us-east-1 for AWS EC2, to maintain default behavior for existing configurations
                Region.US_EAST_1
        );
    }

    private AwsCredentials createAwsCredentials(AwsCredentialsProvider provider, String assumeRoleArn, String externalId) {
        StsClientBuilder stsBuilder = StsClient.builder()
                .httpClient(httpClient)
                // STS global endpoint, matching the v1 default behavior
                .region(Region.AWS_GLOBAL);

        if (provider != null) {
            stsBuilder.credentialsProvider(provider);
        }

        try (StsClient stsClient = stsBuilder.build()) {
            AssumeRoleRequest.Builder requestBuilder = AssumeRoleRequest.builder()
                    .roleArn(assumeRoleArn)
                    .roleSessionName("RundeckEC2ResourceModelSourceSession");
            if (externalId != null) {
                requestBuilder.externalId(externalId);
            }
            AssumeRoleResponse assumeRoleResult = stsClient.assumeRole(requestBuilder.build());
            Credentials assumeCredentials = assumeRoleResult.credentials();
            return AwsSessionCredentials.create(
                    assumeCredentials.accessKeyId(),
                    assumeCredentials.secretAccessKey(),
                    assumeCredentials.sessionToken()
            );
        }
    }

    public synchronized INodeSet getNodes() throws ResourceModelSourceException {
        checkFuture();

        // Return cached results if not time to refresh
        if (!needsRefresh()) {
            if (null != iNodeSet) {
                logger.info("Returning " + iNodeSet.getNodeNames().size() + " cached nodes from EC2");
            }
            return iNodeSet;
        }

        /**
         * Rundeck now executes getNodes() in a thread pool by default.
         * If queryAync is false(default now) or this is the first fetch we just block here.
         */
        if (lastRefresh > 0 && queryAsync && null == futureResult) {
            // lastRefresh is stamped in the finally below, at completion time, not here at submission.
            futureResult = executor.submit(() -> {
                try {
                    INodeSet result = mapper.performQuery(queryNodeInstancesInParallel);
                    lastQueryError = joinQueryErrors(mapper.getQueryErrors());
                    return result;
                } catch (Exception e) {
                    String message = e.getMessage();
                    logger.warn("Error performing query: " + message, e);
                    // recorded at completion time, on the executor thread
                    lastQueryError = (null != message && !message.isEmpty()) ? message : e.toString();
                    throw e;
                } finally {
                    // stamped at completion time, on the executor thread
                    lastRefresh = System.currentTimeMillis();
                }
            });
            logger.debug("Started background EC2 node refresh");
        } else if (!queryAsync || lastRefresh < 1) {
            //always perform synchronous query the first time
            try {
                iNodeSet = mapper.performQuery(queryNodeInstancesInParallel);
                lastQueryError = joinQueryErrors(mapper.getQueryErrors());
            } finally {
                // stamped even on failure, so a broken config doesn't retry with no cooldown
                lastRefresh = System.currentTimeMillis();
            }
            if (null != iNodeSet) {
                logger.info("Read " + iNodeSet.getNodeNames().size() + " nodes from EC2");
            }
        } else {
            // queryAsync is true and a previous refresh is still in flight (futureResult != null):
            // skip starting another one until it completes, rather than overlapping refreshes.
            logger.debug("Skipping EC2 node refresh: a previous background query is still in progress");
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
                logger.debug("Interrupted", e);
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                // error and timestamp already recorded by the task itself
            } finally {
                futureResult = null;
            }
        }
    }

    /**
     * @return the error from the most recent failed background refresh, if any, so that repeated
     * async failures (e.g. expired/invalid credentials) are not swallowed silently while stale
     * cached nodes continue to be served.
     */
    @Override
    public List<String> getModelSourceErrors() {
        String error = lastQueryError;
        return null != error ? Collections.singletonList(error) : Collections.emptyList();
    }

    /**
     * Joins per-region query errors (e.g. one denied region under an ALL_REGIONS/multi-endpoint
     * configuration) into a single message for {@link #lastQueryError}, or null if there were none.
     * Nodes from other, successfully-queried regions are still returned by {@link #getNodes()}; this
     * just makes sure the partial failure isn't lost silently.
     */
    private static String joinQueryErrors(List<String> errors) {
        return (null == errors || errors.isEmpty()) ? null : String.join("; ", errors);
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
                logger.warn("Error loading mapping file",e);
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

    /**
     * Validate this instance's configuration. EC2ResourceModelSourceFactory validates the
     * {@link Properties} before construction (see its {@code validateConfiguration}) so it never
     * needs to call this, but other callers that construct this class directly -- e.g. rundeckpro's
     * own factory -- must call this themselves after construction to get the same check.
     */
    public void validate() throws ConfigurationException {
        if (null != accessKey && null == secretKey && null == secretKeyStoragePath) {
            // a rejected instance is never handed to anything that would close() it
            releaseResources();
            throw new ConfigurationException("secretKey is required for use with accessKey");
        }
    }

    static String getPasswordFromKeyStorage(String path, KeyStorageTree storage) {
        try{
            String key = new String(storage.readPassword(path));
            return key;
        }catch (Exception e){
            throw StorageException.readException(
                    PathUtil.asPath(path),
                    "error accessing key storage at " + path + ": " + e.getMessage(),
                    e
            );
        }

    }

    /**
     * Release the background refresh thread pool and the shared HTTP client. Rundeck core closes any
     * {@link ResourceModelSource} that implements {@link Closeable} when the source is unloaded (e.g.
     * on project config reload), so without this override each reload of this node source would
     * permanently leak a live thread and an HTTP connection pool.
     */
    @Override
    public void close() {
        releaseResources();
    }

    /**
     * Actual cleanup logic, kept private (non-overridable) so it is safe to call from the
     * constructor's failure path -- unlike the public {@link #close()}, which a subclass could
     * override and which would then run on a "this" that may still be under construction from that
     * subclass's point of view, before its own fields have initialized.
     */
    private void releaseResources() {
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                logger.warn("EC2 node source background refresh thread did not terminate promptly on close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (null != httpClient) {
            httpClient.close();
        }
    }
}
