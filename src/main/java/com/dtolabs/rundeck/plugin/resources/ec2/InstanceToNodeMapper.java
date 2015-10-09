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
* NodeGenerator.java
* 
* User: Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
* Created: Oct 18, 2010 7:03:37 PM
* 
*/
package com.dtolabs.rundeck.plugin.resources.ec2;

import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.DefaultAWSCredentialsProviderChain;
import com.amazonaws.services.ec2.AmazonEC2AsyncClient;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.common.INodeSet;
import com.dtolabs.rundeck.core.common.NodeEntryImpl;
import com.dtolabs.rundeck.core.common.NodeSetImpl;
import org.apache.commons.beanutils.BeanUtils;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * InstanceToNodeMapper produces Rundeck node definitions from EC2 Instances
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
class InstanceToNodeMapper {
    static final Logger logger = Logger.getLogger(InstanceToNodeMapper.class);
    final AWSCredentials credentials;
    private ClientConfiguration clientConfiguration;
    private ExecutorService executorService = Executors.newSingleThreadExecutor();
    private ArrayList<String> filterParams;
    private String endpoint;
    private boolean runningStateOnly = true;
    private Properties mapping;

    /**
     * Create with the credentials and mapping definition
     */
    InstanceToNodeMapper(final AWSCredentials credentials, final Properties mapping, final ClientConfiguration clientConfiguration) {
        this.credentials = credentials;
        this.mapping = mapping;
        this.clientConfiguration = clientConfiguration;
    }

    /**
     * Perform the query and return the set of instances
     *
     */
    public INodeSet performQuery() {
        final NodeSetImpl nodeSet = new NodeSetImpl();
        
        final AmazonEC2Client ec2 ;
        if(null!=credentials) {
            ec2 = new AmazonEC2Client(credentials, clientConfiguration);
        } else{
            ec2 = new AmazonEC2Client(clientConfiguration);
        }
        if (null != getEndpoint()) {
            ec2.setEndpoint(getEndpoint());
        }
        final ArrayList<Filter> filters = buildFilters();
        final Set<Instance> instances = query(ec2, new DescribeInstancesRequest().withFilters(filters));


        mapInstances(nodeSet, instances);
        return nodeSet;
    }

    /**
     * Perform the query asynchronously and return the set of instances
     *
     */
    public Future<INodeSet> performQueryAsync() {
        
        final AmazonEC2AsyncClient ec2 ;
        if(null!=credentials){
            ec2= new AmazonEC2AsyncClient(credentials, clientConfiguration, executorService);
        } else{
            ec2 = new AmazonEC2AsyncClient(new DefaultAWSCredentialsProviderChain(), clientConfiguration, executorService);
        }
        if (null != getEndpoint()) {
            ec2.setEndpoint(getEndpoint());
        }
        final ArrayList<Filter> filters = buildFilters();

        final Future<DescribeInstancesResult> describeInstancesRequest = ec2.describeInstancesAsync(
            new DescribeInstancesRequest().withFilters(filters));

        return new Future<INodeSet>() {

            public boolean cancel(boolean b) {
                return describeInstancesRequest.cancel(b);
            }

            public boolean isCancelled() {
                return describeInstancesRequest.isCancelled();
            }

            public boolean isDone() {
                return describeInstancesRequest.isDone();
            }

            public INodeSet get() throws InterruptedException, ExecutionException {
                DescribeInstancesResult describeInstancesResult = describeInstancesRequest.get();

                final NodeSetImpl nodeSet = new NodeSetImpl();
                final Set<Instance> instances = examineResult(describeInstancesResult);

                mapInstances(nodeSet, instances);
                return nodeSet;
            }

            public INodeSet get(final long l, final TimeUnit timeUnit) throws InterruptedException, ExecutionException,
                TimeoutException {
                DescribeInstancesResult describeInstancesResult = describeInstancesRequest.get(l, timeUnit);

                final NodeSetImpl nodeSet = new NodeSetImpl();
                final Set<Instance> instances = examineResult(describeInstancesResult);

                mapInstances(nodeSet, instances);
                return nodeSet;
            }
        };
    }

    private Set<Instance> query(final AmazonEC2Client ec2, final DescribeInstancesRequest request) {
        //create "running" filter

        final DescribeInstancesResult describeInstancesRequest = ec2.describeInstances(request);

        return examineResult(describeInstancesRequest);
    }

    private Set<Instance> examineResult(DescribeInstancesResult describeInstancesRequest) {
        final List<Reservation> reservations = describeInstancesRequest.getReservations();
        final Set<Instance> instances = new HashSet<Instance>();

        for (final Reservation reservation : reservations) {
            instances.addAll(reservation.getInstances());
        }
        return instances;
    }

    private ArrayList<Filter> buildFilters() {
        final ArrayList<Filter> filters = new ArrayList<Filter>();
        if (isRunningStateOnly()) {
            final Filter filter = new Filter("instance-state-name").withValues(InstanceStateName.Running.toString());
            filters.add(filter);
        }

        if (null != getFilterParams()) {
            for (final String filterParam : getFilterParams()) {
                final String[] x = filterParam.split("=", 2);
                if (!"".equals(x[0]) && !"".equals(x[1])) {
                    filters.add(new Filter(x[0]).withValues(x[1]));
                }
            }
        }
        return filters;
    }

    private void mapInstances(final NodeSetImpl nodeSet, final Set<Instance> instances) {
        for (final Instance inst : instances) {
            final INodeEntry iNodeEntry;
            try {
                iNodeEntry = InstanceToNodeMapper.instanceToNode(inst, mapping);
                if (null != iNodeEntry) {
                    nodeSet.putNode(iNodeEntry);
                }
            } catch (GeneratorException e) {
                logger.error(e);
            }
        }
    }

    /**
     * Convert an AWS EC2 Instance to a RunDeck INodeEntry based on the mapping input
     */
    @SuppressWarnings("unchecked")
    static INodeEntry instanceToNode(final Instance inst, final Properties mapping) throws GeneratorException {
        final NodeEntryImpl node = new NodeEntryImpl();

        //evaluate single settings.selector=tags/* mapping
        if ("tags/*".equals(mapping.getProperty("attributes.selector"))) {
            //iterate through instance tags and generate settings
            for (final Tag tag : inst.getTags()) {
                if (null == node.getAttributes()) {
                    node.setAttributes(new HashMap<String, String>());
                }
                node.getAttributes().put(tag.getKey(), tag.getValue());
            }
        }
        if (null != mapping.getProperty("tags.selector")) {
            final String selector = mapping.getProperty("tags.selector");
            final String value = applySelector(inst, selector, mapping.getProperty("tags.default"), true);
            if (null != value) {
                final String[] values = value.split(",");
                final HashSet<String> tagset = new HashSet<String>();
                for (final String s : values) {
                    tagset.add(s.trim());
                }
                if (null == node.getTags()) {
                    node.setTags(tagset);
                } else {
                    final HashSet orig = new HashSet(node.getTags());
                    orig.addAll(tagset);
                    node.setTags(orig);
                }
            }
        }
        if (null == node.getTags()) {
            node.setTags(new HashSet());
        }
        final HashSet orig = new HashSet(node.getTags());
        //apply specific tag selectors
        final Pattern tagPat = Pattern.compile("^tag\\.(.+?)\\.selector$");
        //evaluate tag selectors
        for (final Object o : mapping.keySet()) {
            final String key = (String) o;
            final String selector = mapping.getProperty(key);
            //split selector by = if present
            final String[] selparts = selector.split("=");
            final Matcher m = tagPat.matcher(key);
            if (m.matches()) {
                final String tagName = m.group(1);
                if (null == node.getAttributes()) {
                    node.setAttributes(new HashMap<String, String>());
                }
                final String value = applySelector(inst, selparts[0], null);
                if (null != value) {
                    if (selparts.length > 1 && !value.equals(selparts[1])) {
                        continue;
                    }
                    //use add the tag if the value is not null
                    orig.add(tagName);
                }
            }
        }
        node.setTags(orig);

        //apply default values which do not have corresponding selector
        final Pattern attribDefPat = Pattern.compile("^([^.]+?)\\.default$");
        //evaluate selectors
        for (final Object o : mapping.keySet()) {
            final String key = (String) o;
            final String value = mapping.getProperty(key);
            final Matcher m = attribDefPat.matcher(key);
            if (m.matches() && (!mapping.containsKey(key + ".selector") || "".equals(mapping.getProperty(
                key + ".selector")))) {
                final String attrName = m.group(1);
                if (null == node.getAttributes()) {
                    node.setAttributes(new HashMap<String, String>());
                }
                if (null != value) {
                    node.getAttributes().put(attrName, value);
                }
            }
        }

        final Pattern attribPat = Pattern.compile("^([^.]+?)\\.selector$");
        //evaluate selectors
        for (final Object o : mapping.keySet()) {
            final String key = (String) o;
            final String selector = mapping.getProperty(key);
            final Matcher m = attribPat.matcher(key);
            if (m.matches()) {
                final String attrName = m.group(1);
                if(attrName.equals("tags")){
                    //already handled
                    continue;
                }
                if (null == node.getAttributes()) {
                    node.setAttributes(new HashMap<String, String>());
                }
                final String value = applySelector(inst, selector, mapping.getProperty(attrName + ".default"));
                if (null != value) {
                    //use nodename-settingname to make the setting unique to the node
                    node.getAttributes().put(attrName, value);
                }
            }
        }
//        String hostSel = mapping.getProperty("hostname.selector");
//        String host = applySelector(inst, hostSel, mapping.getProperty("hostname.default"));
//        if (null == node.getHostname()) {
//            System.err.println("Unable to determine hostname for instance: " + inst.getInstanceId());
//            return null;
//        }
        String name = node.getNodename();
        if (null == name || "".equals(name)) {
            name = node.getHostname();
        }
        if (null == name || "".equals(name)) {
            name = inst.getInstanceId();
        }
        node.setNodename(name);

        // Set ssh port on hostname if not 22
        String sshport = node.getAttributes().get("sshport");
        if (sshport != null && !sshport.equals("") && !sshport.equals("22")) {
            node.setHostname(node.getHostname() + ":" + sshport);
        }

        return node;
    }

    /**
     * Return the result of the selector applied to the instance, otherwise return the defaultValue. The selector can be
     * a comma-separated list of selectors
     */
    public static String applySelector(final Instance inst, final String selector, final String defaultValue) throws
        GeneratorException {
        return applySelector(inst, selector, defaultValue, false);
    }

    /**
     * Return the result of the selector applied to the instance, otherwise return the defaultValue. The selector can be
     * a comma-separated list of selectors.
     * @param inst the instance
     * @param selector the selector string
     * @param defaultValue a default value to return if there is no result from the selector
     * @param tagMerge if true, allow | separator to merge multiple values
     */
    public static String applySelector(final Instance inst, final String selector, final String defaultValue,
                                       final boolean tagMerge) throws
        GeneratorException {

        if (null != selector) {
            for (final String selPart : selector.split(",")) {
                if (tagMerge) {
                    final StringBuilder sb = new StringBuilder();
                    for (final String subPart : selPart.split(Pattern.quote("|"))) {
                        final String val = applySingleSelector(inst, subPart);
                        if (null != val) {
                            if (sb.length() > 0) {
                                sb.append(",");
                            }
                            sb.append(val);
                        }
                    }
                    if (sb.length() > 0) {
                        return sb.toString();
                    }
                } else {
                    final String val = applySingleSelector(inst, selPart);
                    if (null != val) {
                        return val;
                    }
                }
            }
        }
        return defaultValue;
    }

    private static String applySingleSelector(final Instance inst, final String selector) throws
        GeneratorException {
        if (null != selector && !"".equals(selector) && selector.startsWith("tags/")) {
            final String tag = selector.substring("tags/".length());
            final List<Tag> tags = inst.getTags();
            for (final Tag tag1 : tags) {
                if (tag.equals(tag1.getKey())) {
                    return tag1.getValue();
                }
            }
        } else if (null != selector && !"".equals(selector)) {
            try {
                final String value = BeanUtils.getProperty(inst, selector);
                if (null != value) {
                    return value;
                }
            } catch (Exception e) {
                throw new GeneratorException(e);
            }
        }

        return null;
    }

    /**
     * Return the list of "filter=value" filters
     */
    public ArrayList<String> getFilterParams() {
        return filterParams;
    }

    /**
     * Return the endpoint
     */
    public String getEndpoint() {
        return endpoint;
    }

    /**
     * Return true if runningStateOnly
     */
    public boolean isRunningStateOnly() {
        return runningStateOnly;
    }

    /**
     * If true, the an automatic "running" state filter will be applied
     */
    public void setRunningStateOnly(final boolean runningStateOnly) {
        this.runningStateOnly = runningStateOnly;
    }

    /**
     * Set the list of "filter=value" filters
     */
    public void setFilterParams(final ArrayList<String> filterParams) {
        this.filterParams = filterParams;
    }

    /**
     * Set the region endpoint to use.
     */
    public void setEndpoint(final String endpoint) {
        this.endpoint = endpoint;
    }

    public Properties getMapping() {
        return mapping;
    }

    public void setMapping(Properties mapping) {
        this.mapping = mapping;
    }

    public static class GeneratorException extends Exception {
        public GeneratorException() {
        }

        public GeneratorException(final String message) {
            super(message);
        }

        public GeneratorException(final String message, final Throwable cause) {
            super(message, cause);
        }

        public GeneratorException(final Throwable cause) {
            super(cause);
        }
    }

}
