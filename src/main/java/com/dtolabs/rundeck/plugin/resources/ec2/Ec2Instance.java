package com.dtolabs.rundeck.plugin.resources.ec2;

import software.amazon.awssdk.services.ec2.model.Instance;

import java.util.Objects;

/**
 * Wraps an AWS SDK v2 {@link Instance} (which is final and immutable, so it cannot be subclassed)
 * and carries the extra mapping attributes (imageName, region) that are not part of the EC2
 * Instance model. The mapping selector resolver reads the extra attributes from this wrapper and
 * delegates all other property lookups to the underlying {@link Instance}.
 */
public class Ec2Instance {

    private final Instance instance;
    private String imageName;
    private String region;

    private Ec2Instance(Instance instance) {
        this.instance = instance;
    }

    /**
     * Create a wrapper around the given EC2 instance, or null if the instance is null.
     */
    public static Ec2Instance builder(Instance instance) {
        if (null == instance) {
            return null;
        }
        return new Ec2Instance(instance);
    }

    /**
     * The wrapped AWS SDK v2 instance.
     */
    public Instance instance() {
        return instance;
    }

    public String instanceId() {
        return null == instance ? null : instance.instanceId();
    }

    public String getImageName() {
        return imageName;
    }

    public String imageName() {
        return imageName;
    }

    public void setImageName(String imageName) {
        this.imageName = imageName;
    }

    public String getRegion() {
        return region;
    }

    public String region() {
        return region;
    }

    public void setRegion(String region) {
        this.region = region;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Ec2Instance)) {
            return false;
        }
        Ec2Instance that = (Ec2Instance) o;
        return Objects.equals(instance, that.instance);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(instance);
    }
}
