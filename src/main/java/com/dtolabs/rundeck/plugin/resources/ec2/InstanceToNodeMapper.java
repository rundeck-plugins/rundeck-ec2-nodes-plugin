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

import com.dtolabs.rundeck.core.common.INodeEntry;
import com.dtolabs.rundeck.core.common.NodeEntryImpl;
import com.dtolabs.rundeck.core.common.NodeSetImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.AvailabilityZone;
import software.amazon.awssdk.services.ec2.model.DescribeAvailabilityZonesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeImagesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeImagesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesResponse;
import software.amazon.awssdk.services.ec2.model.DescribeRegionsResponse;
import software.amazon.awssdk.services.ec2.model.Filter;
import software.amazon.awssdk.services.ec2.model.Image;
import software.amazon.awssdk.services.ec2.model.Instance;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;
import software.amazon.awssdk.services.ec2.model.Region;
import software.amazon.awssdk.services.ec2.model.Reservation;
import software.amazon.awssdk.services.ec2.model.Tag;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * InstanceToNodeMapper produces Rundeck node definitions from EC2 Instances
 *
 * @author Greg Schueler <a href="mailto:greg@dtosolutions.com">greg@dtosolutions.com</a>
 */
class InstanceToNodeMapper {
    static final Logger         logger = LoggerFactory.getLogger(InstanceToNodeMapper.class);
    private ArrayList<String> filterParams;
    private String endpoint;
    private String region;
    private boolean runningStateOnly = true;
    private Properties mapping;
    private final int maxResults;
    private final EC2Supplier ec2Supplier;

    private static final String[] extraInstanceMappingAttributes= {"imageName","region"};

    /**
     * Create with the credentials and mapping definition
     */
    InstanceToNodeMapper(final EC2Supplier ec2Supplier, final Properties mapping, final int maxResults) {
        this.ec2Supplier = ec2Supplier;
        this.mapping = mapping;
        this.maxResults = maxResults;
    }


    /**
     * Perform the query and return the set of instances
     *
     */
    public NodeSetImpl performQuery(boolean queryNodeInstancesInParallel) {
        final NodeSetImpl nodeSet = new NodeSetImpl();

        Set<Ec2Instance> instances = new HashSet<>();

        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(buildFilters())
                .maxResults(maxResults)
                .build();

        if(getEndpoint() != null) {
            ExecutorService executor = null;
            Collection<Future<Set<Ec2Instance>>> futures = new LinkedList<Future<Set<Ec2Instance>>>();
            Set<Callable<Set<Ec2Instance>>> tasks = new HashSet<>();
            List<String> endpoints = determineEndpoints();
            for (String endpoint : endpoints) {
                if(queryNodeInstancesInParallel) {
                    if(executor == null){
                        logger.info("Creating thread pool for {} regions", endpoints.size() );
                        executor = Executors.newFixedThreadPool(endpoints.size());
                    }
                    tasks.add(new Callable<Set<Ec2Instance>>() {
                        @Override
                        public Set<Ec2Instance> call() throws Exception {
                            return getInstancesByRegion(endpoint);
                        };
                    });
                }else{
                    instances.addAll(getInstancesByRegion(endpoint));
                }
            }
            if(queryNodeInstancesInParallel) {
                try {
                    logger.info("Querying {} regions in parallel", endpoints.size() );
                    futures = executor.invokeAll(tasks);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                } finally {
                    try {
                        for (Future<Set<Ec2Instance>> future : futures) {
                            if (future != null) {
                                instances.addAll(future.get());
                            }
                        }
                        logger.info("Finished querying {} regions in parallel", endpoints.size() );
                        executor.shutdown();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    } catch (ExecutionException e) {
                        throw new RuntimeException(e);
                    }
                }
                try {
                    // Wait for 90 seconds for all tasks to finish
                    logger.info("Waiting for {} seconds for all tasks to finish", 90);
                    executor.awaitTermination(90, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // Restore interrupted status
                    logger.warn("Thread interrupted while waiting for tasks to finish", ignored);
                    Thread.currentThread().interrupt();
                } finally {
                    // Force shutdown if not already done
                    logger.warn("Forcing shutdown of thread pool");
                    executor.shutdownNow();
                }
            }
        }
        else if(region != null){
            Ec2Client ec2ForRegion = ec2Supplier.getEC2ForRegion(region);


            DescribeAvailabilityZonesResponse zones = ec2ForRegion.describeAvailabilityZones();

            final Set<Ec2Instance> newInstances = addExtraMappingAttribute(ec2ForRegion, query(ec2ForRegion, request), zones);

            if (newInstances != null && !newInstances.isEmpty()) {
                instances.addAll(newInstances);
            }
        }
        else{
            Ec2Client ec2 = ec2Supplier.getEC2ForDefaultRegion();
            DescribeAvailabilityZonesResponse zones = ec2.describeAvailabilityZones();

            instances = addExtraMappingAttribute(ec2, query(ec2, request), zones);
        }
        mapInstances(nodeSet, instances);
        return nodeSet;
    }

    private List<String> determineEndpoints() {
        ArrayList<String> endpoints = new ArrayList<>();
        if (getEndpoint().equals("ALL_REGIONS")) {

            //Retrieve dynamic list of EC2 regions from AWS
            DescribeRegionsResponse regionsResult = ec2Supplier.getEC2ForDefaultRegion().describeRegions();
            for (Region region : regionsResult.regions()) {
                endpoints.add(region.endpoint());
            }

        } else {
            try {
                //Use comma-separated list of region supplied by user
                endpoints.addAll(Arrays.asList(getEndpoint().replaceAll("\\s+", "").split(",")));
            } catch (NullPointerException e) {
                throw new IllegalArgumentException("Failed to parse endpoint: Region cannot be empty");
            }
        }
        return endpoints;
    }

    private Set<Ec2Instance> getInstancesByRegion(String endpoint) {
        Set<Ec2Instance> allInstances = new HashSet<>();
        Ec2Client ec2 = ec2Supplier.getEC2ForEndpoint(endpoint);
        DescribeAvailabilityZonesResponse zones = ec2.describeAvailabilityZones();
        final List<Filter> filters = buildFilters();

        DescribeInstancesRequest request = DescribeInstancesRequest.builder()
                .filters(filters)
                .maxResults(maxResults)
                .build();

        final Set<Ec2Instance> newInstances = addExtraMappingAttribute(ec2, query(ec2, request), zones);

        if (newInstances != null && !newInstances.isEmpty()) {
            allInstances.addAll(newInstances);
        }

        return allInstances;
    }

    private Set<Ec2Instance> query(final Ec2Client ec2, final DescribeInstancesRequest request) {
        //create "running" filter
        final Set<Ec2Instance> instances = new HashSet<>();

        String token = null;
        do {
            final DescribeInstancesRequest pagedRequest = request.toBuilder().nextToken(token).build();

            final DescribeInstancesResponse describeInstancesResponse = ec2.describeInstances(pagedRequest);

            token = describeInstancesResponse.nextToken();

            instances.addAll(examineResult(describeInstancesResponse));
        } while(token != null);

        return instances;
    }

    private Set<Ec2Instance> examineResult(DescribeInstancesResponse describeInstancesResponse) {
        final List<Reservation> reservations = describeInstancesResponse.reservations();
        final Set<Ec2Instance> instances = new HashSet<>();

        for (final Reservation reservation : reservations) {
            for (final Instance instance : reservation.instances()) {
                instances.add(Ec2Instance.builder(instance));
            }
        }
        return instances;
    }

    private List<Filter> buildFilters() {
        final List<Filter> filters = new ArrayList<>();
        if (isRunningStateOnly()) {
            filters.add(Filter.builder()
                    .name("instance-state-name")
                    .values(InstanceStateName.RUNNING.toString())
                    .build());
        }

        if (null != getFilterParams()) {
            for (final String filterParam : getFilterParams()) {
                final String[] x = filterParam.split("=", 2);
                if (!"".equals(x[0]) && !"".equals(x[1])) {
                    filters.add(Filter.builder().name(x[0]).values(x[1].split(",")).build());
                }
            }
        }
        return filters;
    }

    private void mapInstances(final NodeSetImpl nodeSet, final Set<Ec2Instance> instances) {
        for (final Ec2Instance inst : instances) {
            final INodeEntry iNodeEntry;
            try {
                iNodeEntry = InstanceToNodeMapper.instanceToNode(inst, mapping);
                if (null != iNodeEntry) {
                    nodeSet.putNode(iNodeEntry);
                }
            } catch (GeneratorException e) {
                logger.error("Generator error",e);
            }
        }
    }

    /**
     * Convert an AWS EC2 Instance to a RunDeck INodeEntry based on the mapping input
     */
    @SuppressWarnings("unchecked")
    static INodeEntry instanceToNode(final Ec2Instance inst, final Properties mapping) throws GeneratorException {
        final NodeEntryImpl node = new NodeEntryImpl();

        //evaluate single settings.selector=tags/* mapping
        if ("tags/*".equals(mapping.getProperty("attributes.selector"))) {
            //iterate through instance tags and generate settings
            for (final Tag tag : inst.instance().tags()) {
                if (null == node.getAttributes()) {
                    node.setAttributes(new HashMap<>());
                }
                node.getAttributes().put(tag.key(), tag.value());
            }
        }
        if (null != mapping.getProperty("tags.selector")) {
            final String selector = mapping.getProperty("tags.selector");
            final String value = applySelector(inst, selector, mapping.getProperty("tags.default"), true);
            if (null != value) {
                final String[] values = value.split(",");
                final HashSet<String> tagset = new HashSet<>();
                for (final String s : values) {
                    tagset.add(s.trim());
                }
                if (null == node.getTags()) {
                    node.setTags(tagset);
                } else {
                    final Set<String> orig = new HashSet<String>(node.getTags());
                    orig.addAll(tagset);
                    node.setTags(orig);
                }
            }
        }
        if (null == node.getTags()) {
            node.setTags(new HashSet<String>());
        }
        final Set<String> orig = new HashSet<String>(node.getTags());
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
                    node.setAttributes(new HashMap<>());
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
                    node.setAttributes(new HashMap<>());
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
                    node.setAttributes(new HashMap<>());
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
        if (null == name || name.isEmpty()) {
            name = node.getHostname();
        }
        if (null == name || name.isEmpty()) {
            name = inst.instanceId();
        }
        node.setNodename(name);

        // Set ssh port on hostname if not 22
        String sshport = node.getAttributes().get("sshport");
        if (sshport != null && !sshport.isEmpty() && !sshport.equals("22")) {
            node.setHostname(node.getHostname() + ":" + sshport);
        }



        return node;
    }

    /**
     * Return the result of the selector applied to the instance, otherwise return the defaultValue. The selector can be
     * a comma-separated list of selectors
     */
    public static String applySelector(final Ec2Instance inst, final String selector, final String defaultValue) throws
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
    public static String applySelector(final Ec2Instance inst, final String selector, final String defaultValue,
                                       final boolean tagMerge) throws
        GeneratorException {

        if (null != selector) {
            for (final String selPart : selector.split(",")) {
                if (tagMerge) {
                    final StringBuilder sb = new StringBuilder();
                    for (final String subPart : selPart.split(Pattern.quote("|"))) {
                        final String val = applyMultiSelector(inst, subPart.split(Pattern.quote("+")));
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
                    final String val = applyMultiSelector(inst, selPart.split(Pattern.quote("+")));
                    if (null != val) {
                        return val;
                    }
                }
            }
        }
        return defaultValue;
    }

    private static final Pattern quoted = Pattern.compile("^(['\"])(.+)\\1$");

    /**
     * Return conjoined multiple selector and literal values only if some selector value matches, otherwise null.
     * Apply multiple selectors and separators to determine the value, the selector values are conjoined
     * in order if they resolve to a non-blank value. If a selector is a quoted string, the contents are
     * conjoined literally
     *
     * @param inst
     * @param selectors
     *
     * @return conjoined selector values with literal separators, if some selector was resolved, otherwise null
     *
     * @throws GeneratorException
     */
    static String applyMultiSelector(final Ec2Instance inst, final String... selectors) throws
            GeneratorException
    {
        StringBuilder sb = new StringBuilder();
        boolean hasVal = false;
        for (String selector : selectors) {
            Matcher matcher = quoted.matcher(selector);
            if (matcher.matches()) {
                sb.append(matcher.group(2));
            } else {
                String val = applySingleSelector(inst, selector);
                if (null != val && !val.isEmpty()) {
                    hasVal = true;
                    sb.append(val);
                }
            }
        }

        return hasVal ? sb.toString() : null;
    }
    static String applySingleSelector(final Ec2Instance inst, final String selector) throws
        GeneratorException {
        if (null != selector && selector.startsWith("tags/")) {
            final String tag = selector.substring("tags/".length());
            final List<Tag> tags = inst.instance().tags();
            for (final Tag tag1 : tags) {
                if (tag.equals(tag1.key())) {
                    return tag1.value();
                }
            }
        } else if (null != selector && !selector.isEmpty()) {
            return resolveProperty(inst, selector);
        }

        return null;
    }

    /**
     * Resolve a dot-separated property selector against the instance, preserving the historical
     * BeanUtils behavior used with the AWS SDK v1 model. Each path segment is resolved by invoking
     * the matching AWS SDK v2 fluent accessor; the String accessor variant (e.g. {@code
     * architectureAsString()}) is preferred so enum-valued fields keep returning their raw wire
     * value. The extra mapping attributes (imageName, region) are read from the {@link Ec2Instance}
     * wrapper itself. An unknown property results in a {@link GeneratorException}, matching the
     * previous behavior.
     */
    private static String resolveProperty(final Ec2Instance inst, final String selector) throws GeneratorException {
        Object current = inst;
        for (final String segment : selector.split("\\.")) {
            if (null == current) {
                return null;
            }
            current = invokeSegment(current, segment);
        }
        return stringify(current);
    }

    private static Object invokeSegment(Object target, final String name) throws GeneratorException {
        if (target instanceof Ec2Instance) {
            final Ec2Instance ec2 = (Ec2Instance) target;
            if ("imageName".equals(name)) {
                return ec2.imageName();
            }
            if ("region".equals(name)) {
                return ec2.region();
            }
            target = ec2.instance();
            if (null == target) {
                return null;
            }
        }
        final Method accessor = findAccessor(target.getClass(), name);
        if (null == accessor) {
            throw new GeneratorException(
                    new NoSuchMethodException("No EC2 property '" + name + "' on " + target.getClass().getName()));
        }
        try {
            return accessor.invoke(target);
        } catch (Exception e) {
            throw new GeneratorException(e);
        }
    }

    /**
     * Find a no-arg accessor on the AWS SDK model class for the given property name. Prefer the
     * {@code <name>AsString} variant generated for enum fields so the raw string value is returned.
     */
    private static Method findAccessor(final Class<?> type, final String name) {
        Method method = lookupAccessor(type, name + "AsString");
        if (null == method) {
            method = lookupAccessor(type, name);
        }
        return method;
    }

    private static Method lookupAccessor(final Class<?> type, final String name) {
        try {
            final Method method = type.getMethod(name);
            // Accept any no-arg, value-returning accessor so selectors can traverse the object
            // graph (including JDK value types such as java.time.Instant), but reject methods
            // declared on Object (toString/hashCode/getClass/wait/notify) so unknown selectors
            // still fail as they did with the legacy BeanUtils resolution.
            if (method.getParameterCount() == 0
                    && method.getReturnType() != void.class
                    && !Object.class.equals(method.getDeclaringClass())) {
                return method;
            }
        } catch (NoSuchMethodException ignored) {
            // not a property accessor
        }
        return null;
    }

    private static String stringify(final Object value) {
        if (null == value) {
            return null;
        }
        if (value instanceof String) {
            return (String) value;
        }
        return value.toString();
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

    public void setRegion(final String region) {
        this.region = region;
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

    public Set<Ec2Instance> addExtraMappingAttribute(Ec2Client ec2, Set<Ec2Instance> instances, DescribeAvailabilityZonesResponse zones) {
        for(String extraAttribute: extraInstanceMappingAttributes){
            if(mappingHasExtraAttribute(extraAttribute)){
                if(extraAttribute.equals("imageName")){
                    instances = addingImageName(ec2, instances);
                }
                if(extraAttribute.equals("region")){
                    instances = addingRegion(instances, zones);
                }
            }
        }
        return instances;
    }

    public boolean mappingHasExtraAttribute(String extraAttribute){
        if(mapping.containsValue(extraAttribute)){
            return true;
        }else{
            for (String key : mapping.stringPropertyNames()) {
                if(mapping.getProperty(key).contains(extraAttribute)){
                    return true;
                }
            }
        }
        return false;
    }

    public Set<Ec2Instance> addingImageName(Ec2Client ec2, Set<Ec2Instance> originalInstances) {
        Map<String,Image> ec2Images = new HashMap<>();
        Set<String> imagesList = originalInstances.stream()
                .map(i -> i.instance().imageId())
                .collect(Collectors.toSet());
        logger.debug("Image list: {}", imagesList);
        try{
            DescribeImagesRequest describeImagesRequest = DescribeImagesRequest.builder()
                    .imageIds(imagesList)
                    .build();

            DescribeImagesResponse result = ec2.describeImages(describeImagesRequest);

            for(Image image :result.images()){
                ec2Images.put(image.imageId(),image);
            }
        }catch(Exception e){
            logger.error("error getting image info: {}", e.getMessage(), e);
        }

        for (final Ec2Instance inst : originalInstances) {
            String imageId = inst.instance().imageId();
            if(ec2Images.containsKey(imageId)){
                Image image = ec2Images.get(imageId);
                inst.setImageName(image.name());
            }else{
                inst.setImageName("Not found");
                logger.debug("Image not found {}", imageId);
            }
        }

        return originalInstances;
    }

    public Set<Ec2Instance> addingRegion(Set<Ec2Instance> originalInstances, DescribeAvailabilityZonesResponse zones){
        for (final Ec2Instance inst : originalInstances) {
            if (null == inst.instance().placement()) {
                continue;
            }
            String region = getRegionAvailableZone(inst.instance().placement().availabilityZone(), zones);
            if(region!=null){
                inst.setRegion(region);
            }
        }

        return originalInstances;
    }

    private String getRegionAvailableZone(String availableZone, DescribeAvailabilityZonesResponse zones){

        String region = null;

        if (null == zones || null == availableZone) {
            return null;
        }

        for(AvailabilityZone zone : zones.availabilityZones()) {
            if (zone.zoneName().equals(availableZone)){
                region = zone.regionName();
            }
        }


        return region;
    }

}
