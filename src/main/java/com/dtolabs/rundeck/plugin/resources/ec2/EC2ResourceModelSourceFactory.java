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
* Created: 9/1/11 4:27 PM
* 
*/
package com.dtolabs.rundeck.plugin.resources.ec2;

import com.dtolabs.rundeck.core.common.Framework;
import com.dtolabs.rundeck.core.plugins.Plugin;
import com.dtolabs.rundeck.core.plugins.configuration.*;
import com.dtolabs.rundeck.core.resources.ResourceModelSource;
import com.dtolabs.rundeck.core.resources.ResourceModelSourceFactory;
import com.dtolabs.rundeck.plugins.util.DescriptionBuilder;
import org.rundeck.app.spi.Services;

import java.io.File;
import java.util.*;

/**
 * <p>EC2ResourceModelSourceFactory is the factory that can create a {@link ResourceModelSource} based on a configuration.</p>
 * <p>The configuration properties are:</p>
 * <ul>
 *   <li>endpoint: the AWS endpoint to use, or blank for the default (us-east-1)</li>
 *   <li>filter: A set of ";" separated query filters ("filter=value") for the AWS EC2 API, see
 *       <a href="http://docs.amazonwebservices.com/AWSEC2/latest/APIReference/ApiReference-query-DescribeInstances.html">DescribeInstances</a></li>
 *   <li>mappingParams: A set of ";" separated mapping entries</li>
 *   <li>runningOnly: if "true", automatically filter the instances by "instance-state-name=running"</li>
 *   <li>accessKey: API AccessKey value</li>
 *   <li>secretKey: API SecretKey value</li>
 *   <li>mappingFile: Path to a java properties-formatted mapping definition file.</li>
 *   <li>refreshInterval: Time in seconds used as minimum interval between calls to the AWS API.</li>
 *   <li>useDefaultMapping: if "true", base all mapping definitions off the default mapping provided.</li>
 * </ul>
 * @author Greg Schueler <a href="mailto:greg@rundeck.com">greg@rundeck.com</a>
 */
@Plugin(name = "aws-ec2", service = "ResourceModelSource")
public class EC2ResourceModelSourceFactory implements ResourceModelSourceFactory, Describable {
    public static final String PROVIDER_NAME = "aws-ec2";
    private Framework framework;

    public static final String ENDPOINT = "endpoint";
    public static final String FILTER_PARAMS = "filter";
    public static final String MAPPING_PARAMS = "mappingParams";
    public static final String RUNNING_ONLY = "runningOnly";
    public static final String ACCESS_KEY = "accessKey";
    public static final String SECRET_KEY = "secretKey";
    public static final String SECRET_KEY_STORAGE_PATH = "secretKeyStoragePath";
    public static final String ROLE_ARN = "assumeRoleArn";
    public static final String ROLE_ARN_COMBINED_WITH_EXT_ID = "assumeRoleArnCombinedWithExternalId";
    public static final String EXTERNAL_ID = "externalId";
    public static final String REGION = "region";
    public static final String MAPPING_FILE = "mappingFile";
    public static final String REFRESH_INTERVAL = "refreshInterval";
    public static final String SYNCHRONOUS_LOAD = "synchronousLoad";
    public static final String USE_DEFAULT_MAPPING = "useDefaultMapping";
    public static final String HTTP_PROXY_HOST = "httpProxyHost";
    public static final String HTTP_PROXY_PORT = "httpProxyPort";
    public static final String HTTP_PROXY_USER = "httpProxyUser";
    public static final String HTTP_PROXY_PASS = "httpProxyPass";
    public static final String MAX_RESULTS = "pageResults";

    public EC2ResourceModelSourceFactory() {

    }
    public EC2ResourceModelSourceFactory(final Framework framework) {
    }

    public ResourceModelSource createResourceModelSource(Services services, final Properties configuration) throws ConfigurationException {
        final EC2ResourceModelSource ec2ResourceModelSource = new EC2ResourceModelSource(configuration, services);
        ec2ResourceModelSource.validate();
        return ec2ResourceModelSource;
    }

    public ResourceModelSource createResourceModelSource(Properties configuration) throws ConfigurationException {
        return null;
    }

    static Description DESC = DescriptionBuilder.builder()

    public static final Map<String, Object> PASSWORD_OPTIONS = Collections.singletonMap(StringRenderingConstants.DISPLAY_TYPE_KEY, StringRenderingConstants.DisplayType.PASSWORD);

    public static final Description DESC = DescriptionBuilder.builder()
            .name(PROVIDER_NAME)
            .title("AWS EC2 Resources")
            .description("Produces nodes from AWS EC2")

            .property(PropertyUtil.string(ACCESS_KEY, "Access Key", "AWS Access Key", false, null))
            .property(
                    PropertyUtil.string(
                            SECRET_KEY,
                            "Secret Key",
                            "AWS Secret Key. Required if Access Key is used and Secret Key Storage Path is blank.\nIf `Access Key` is not used, then the IAM profile will be used.",
                            false,
                            null,
                            null,
                            null,
                            PASSWORD_OPTIONS
                    )
            )
            .property(
                    PropertyUtil.string(
                            SECRET_KEY_STORAGE_PATH,
                            "Secret Key Storage Path",
                            "Key Storage Path for AWS Secret Key. Required if Access Key is used and Secret Key is blank.\nIf `Access Key` is not used, then the IAM profile will be used.",
                            false,
                            null,
                            null,
                            null,
                            Map.of(
                                StringRenderingConstants.SELECTION_ACCESSOR_KEY, StringRenderingConstants.SelectionAccessor.STORAGE_PATH,
                                StringRenderingConstants.STORAGE_PATH_ROOT_KEY, "keys",
                                StringRenderingConstants.STORAGE_FILE_META_FILTER_KEY, "Rundeck-data-type=password"
                            )
                    )
            )
            .property(
                    PropertyUtil.string(
                            ROLE_ARN,
                            "Assume Role ARN",
                            "IAM Role ARN to assume, if using IAM Profile only.\n\nSee [IAM Roles](https://docs.aws.amazon.com/IAM/latest/UserGuide/id_roles.html).",
                            false,
                            null,
                            null,
                            null
                    )
            )
                                                .property(PropertyUtil.bool(
                                                        SYNCHRONOUS_LOAD,
                                                        "Synchronous Loading",
                                                        "Do not use internal async loading behavior.\n\n" +
                                                        "Note: Rundeck 2.6.3+ uses an asynchronous nodes cache by " +
                                                        "default. You should enable this if you are using the " +
                                                        "rundeck nodes cache.",
                                                        false,
                                                        "true"
                                                ))
            .property(PropertyUtil.integer(REFRESH_INTERVAL, "Async Refresh Interval",
                    "Unless using Synchronous Loading, minimum time in seconds between API requests to AWS (default is 30)", false, "30"))
                                                .property(PropertyUtil.string(
                                                        FILTER_PARAMS,
                                                        "Filter Params",
                                                        "AWS EC2 filters, in the form `Filter=Value`.\n\nYou can "
                                                        + "specify multiple filters by separating them with `;`, and "
                                                        + "you can specify multiple values by separating them with `,"
                                                        + "`.  See [AWS DescribeInstances](https://docs.aws.amazon.com/AWSEC2/latest/APIReference/API_DescribeInstances.html) for more information about filters.\n\n"
                                                        + "Example: `tag:MyTag=Some Tag Value;instance-type=m1.small,m1.large`",
                                                        false,
                                                        null
                                                ))
            .property(PropertyUtil.string(ENDPOINT, "Endpoint", "AWS EC2 Endpoint to specify region, or blank for default. Include comma-separated list of endpoints to integrate with multiple regions.\n\n" +
                    "Example: `https://ec2.us-west-1.amazonaws.com, https://ec2.us-east-1.amazonaws.com` This would retrieve instances from the `US-WEST-1` and `US-EAST-1` regions.\n" +
                            "Optionally use `ALL_REGIONS` to automatically pull in instances from all regions that the AWS credentials (or IAM Role) have access to.",
                    false,
                    null))
            .property(PropertyUtil.string(REGION, "Region", "AWS EC2 region.",
                    false,
                    null))
            .property(PropertyUtil.string(HTTP_PROXY_HOST, "HTTP Proxy Host", "HTTP Proxy Host Name, or blank for default", false, null))
            .property(PropertyUtil.integer(HTTP_PROXY_PORT, "HTTP Proxy Port", "HTTP Proxy Port, or blank for 80", false, "80"))
            .property(PropertyUtil.string(HTTP_PROXY_USER, "HTTP Proxy User", "HTTP Proxy User Name, or blank for default", false, null))
            .property(
                    PropertyUtil.string(
                            HTTP_PROXY_PASS,
                            "HTTP Proxy Password",
                            "HTTP Proxy Password, or blank for default",
                            false,
                            null,
                            null,
                            null,
                            Collections.singletonMap("displayType", (Object) StringRenderingConstants.DisplayType.PASSWORD)
                    )
            )
            .property(PropertyUtil.string(MAPPING_PARAMS, "Mapping Params",
                    "Property mapping definitions. Specify multiple mappings in the form " +
                            "\"attributeName.selector=selector\" or \"attributeName.default=value\", " +
                            "separated by \";\"",
                    false, null))
            .property(PropertyUtil.string(MAPPING_FILE, "Mapping File", "Property mapping File", false, null,
                    new PropertyValidator() {
                        public boolean isValid(final String s) throws ValidationException {
                            if (!new File(s).isFile()) {
                                throw new ValidationException("File does not exist: " + s);
                            }
                            return true;
                        }
                    }))
            .property(PropertyUtil.bool(USE_DEFAULT_MAPPING, "Use Default Mapping",
                    "Start with default mapping definition. (Defaults will automatically be used if no others are " +
                            "defined.)",
                    false, "true"))
            .property(PropertyUtil.bool(RUNNING_ONLY, "Only Running Instances",
                    "Include Running state instances only. If false, all instances will be returned that match your " +
                            "filters.",
                    false, "true"))
            .property(PropertyUtil.integer(MAX_RESULTS, "Max API Results",
                    "Max number of reservations returned per AWS API call.",
                    false, "100"))

            .build();

    public Description getDescription() {
        return DESC;
    }
}
