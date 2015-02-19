Rundeck EC2 Nodes Plugin
========================

Version: 1.5

This is a Resource Model Source plugin for [RunDeck][] 1.5+ that provides
Amazon EC2 Instances as nodes for the RunDeck server.

[RunDeck]: http://rundeck.org

NOTE: For Rundeck 1.4, you will need to use plugin [version 1.2][].

[version 1.2]: https://github.com/gschueler/rundeck-ec2-nodes-plugin/tree/v1.2

Installation
------------

Download from the [releases page](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/releases).

Put the `rundeck-ec2-nodes-plugin-1.3.jar` into your `$RDECK_BASE/libext` dir.

Usage
-----

You can configure the Resource Model Sources for a project either via the
RunDeck GUI, under the "Admin" page, or you can modify the `project.properties`
file to configure the sources.

See: [Resource Model Source Configuration](http://rundeck.org/1.5/manual/plugins.html#resource-model-source-configuration)

The provider name is: `aws-ec2`

Here are the configuration properties:

* `accessKey`: API AccessKey value
* `secretKey`: API SecretKey value
* `endpoint` - the AWS endpoint to use, or blank for the default (see [Amazon EC2 Regions and Endpoints](http://docs.aws.amazon.com/general/latest/gr/rande.html#ec2_region))
* `refreshInterval`: Time in seconds used as minimum interval between calls to the AWS API. (default 30)
* `filter` A set of ";" separated query filters ("$Name=$Value") for the AWS EC2 API, see below.
* `runningOnly`: if "true", automatically filter the * instances by "instance-state-name=running"
* `useDefaultMapping`: if "true", base all mapping definitions off the default mapping provided.
* `mappingParams`: A set of ";" separated mapping entries
* `mappingFile`: Path to a java properties-formatted mapping definition file.

## Filter definition

The syntax for defining filters uses `$Name=$Value[;$Name=$value[;...]]` for any of the allowed filter names (see [DescribeInstances][1] for the available filter Names).  *Note*: you do not need to specify `Filter.1.Name=$Name`, etc. as described in the EC2 API documentation, this will handled for you.  Simply list the Name = Value pairs, separated by `;`.

 [1]: http://docs.amazonwebservices.com/AWSEC2/latest/APIReference/ApiReference-query-DescribeInstances.html

Example: to filter based on a Tag named "MyTag" with a value of "Some Tag Value":

    tag:MyTag=Some Tag Value
    
Example: to filter *any* instance with a Tag named `MyTag`:

    tag-key=MyTag

Example combining matching a tag value and the instance type:

    tag:MyTag=Some Tag Value;instance-type=m1.small

Mapping Definition
----------

RunDeck Node attributes are configured by mapping EC2 Instance properties via a
mapping configuration.

The mapping declares the node attributes that will be set, and what their values
will be set to using a "selector" on properties of the EC2 Instance object.

Here is the default mapping:

    description.default=EC2 node instance
    editUrl.default=https://console.aws.amazon.com/ec2/home#s=Instances&selectInstance=${node.instanceId}
    hostname.selector=publicDnsName
    instanceId.selector=instanceId
    nodename.selector=tags/Name,instanceId
    osArch.selector=architecture
    osFamily.default=unix
    osFamily.selector=platform
    osName.default=Linux
    osName.selector=platform
    privateDnsName.selector=privateDnsName
    privateIpAddress.selector=privateIpAddress
    state.selector=state.name
    tag.pending.selector=state.name=pending
    tag.running.selector=state.name=running
    tag.shutting-down.selector=state.name=shutting-down
    tag.stopped.selector=state.name=stopped
    tag.stopping.selector=state.name=stopping
    tag.terminated.selector=state.name=terminated
    tags.default=ec2
    tags.selector=tags/Rundeck-Tags
    username.default=ec2-user
    username.selector=tags/Rundeck-User

Configuring the Mapping
-----------------------

You can configure your source to start with the above default mapping with the 
`useDefaultMapping` property.

You can then selectively change it either by setting the `mappingParams` or 
pointing to a new properties file with `mappingFile`.

For example, you can put this in the `mappingParams` field in the GUI to change 
the default tags for your nodes, remove the "stopping" tag selector, and add a
new "ami_id" selector:

    tags.default=mytag, mytag2;tag.stopping.selector=;ami_id.selector=imageId

Mapping format
---------------

The mapping consists of defining either a selector or a default for
the desired Node fields.  The "nodename" field is required, and will 
automatically be set to the instance ID if no other value is defined.

For purposes of the mapping definition, a `field selector` is either:

* An EC2 fieldname, or dot-separated field names
* "tags/" followed by a Tag name, e.g. "tags/My Tag"
* "tags/*" for use by the `attributes.selector` mapping

Selectors use the Apache [BeanUtils](http://commons.apache.org/beanutils/) to extract a property value from the AWS API
[Instance class](http://docs.amazonwebservices.com/AWSJavaSDK/latest/javadoc/com/amazonaws/services/ec2/model/Instance.html).
This means you can use dot-separated fieldnames to traverse the object graph.
E.g. "state.name" to specify the "name" field of the State property of the Instance.

format:

    # define a selector for "property":
    <attribute>.selector=<field selector>
    # define a default value for "property":
    <attribute>.default=<default value>
    # Special attributes selector to map all Tags to attributes
    attributes.selector=tags/*
    # The value for the tags selector will be treated as a comma-separated list of strings
    tags.selector=<field selector>
    # the default tags list
    tags.default=a,b,c
    # Define a single tag <name> which will be set if and only if the selector result is not empty
    tag.<name>.selector=<field selector>
    # Define a single tag <name> which will be set if the selector result equals the <value>
    tag.<name>.selector=<field selector>=<value>

Note, a ".selector" value can have multiple selectors defined, separated by commas,
and they will be evaluated in order with the first value available being used.  E.g. "nodename.selector=tags/Name,instanceId", which will look for a tag named "Name", otherwise use the instanceId.

You can also use the `<field selector>=<value>` feature to set a tag only if the field selector has a certain value.

### Tags selector

When defining field selector for the `tags` node property, the string value selected (if any) will
be treated as a comma-separated list of strings to use as node tags.  You could, for example, set a custom EC2 Tag on
an instance to contain this list of tags, in this example from the simplemapping.properties file:

    tags.selector=tags/Rundeck-Tags

So creating the "Rundeck-Tags" Tag on the EC2 Instance with a value of "alpha, beta" will result in the node having
those two node tags.

The tags.selector also supports a "merge" ability, so you can merge multiple Instance Tags into the RunDeck tags by separating multiple selectors with a "|" character:

    tags.selector=tags/Environment|tags/Role


Mapping EC2 Instances to Rundeck Nodes
=================

Rundeck node definitions specify mainly the pertinent data for connecting to and organizing the Nodes.  EC2 Instances have metadata that can be mapped onto the fields used for Rundeck Nodes.

Rundeck nodes have the following metadata fields:

* `nodename` - unique identifier
* `hostname` - IP address/hostname to connect to the node
* `username` - SSH username to connect to the node
* `description` - textual description
* `osName` - OS name
* `osFamily` - OS family: unix, windows, cygwin.
* `osArch` - OS architecture
* `osVersion` - OS version
* `tags` - set of labels for organization
* `editUrl` - URL to edit the definition of this node object
* `remoteUrl` - URL to edit the definition of this node object using Rundeck-specific integration

In addition, Nodes can have arbitrary attribute values.

EC2 Instance Field Selectors
-----------------

EC2 Instances have a set of metadata that can be mapped to any of the Rundeck node fields, or to Settings or tags for the node.

EC2 fields:

* amiLaunchIndex
* architecture
* clientToken
* imageId
* instanceId
* instanceLifecycle
* instanceType
* kernelId
* keyName
* launchTime
* license
* platform
* privateDnsName
* privateIpAddress
* publicDnsName
* publicIpAddress
* ramdiskId
* rootDeviceName
* rootDeviceType
* spotInstanceRequestId
* state
* stateReason
* stateTransitionReason
* subnetId
* virtualizationType
* vpcId
* `tags/*`

EC2 Instances can also have "Tags" which are key/value pairs attached to the Instance.  A common Tag is "Name" which could be a unique identifier for the Instance, making it a useful mapping to the Node's name field.  Note that EC2 Tags differ from Rundeck Node tags: Rundeck tags are simple string labels and are not key/value pairs.

Authenticating to EC2 Nodes with Rundeck
-----------

Once you get your EC2 Instances listed in Rundeck as Nodes, you may be wondering "Now how do I use this?"

Rundeck uses SSH by default with private key authentication, so in order to connect to your EC2 instances out
of the box you will need to configure Rundeck to use the right private SSH key to connect to your nodes,
which can be done in either of a few ways:

1. Copy your private key to the default location used by Rundeck which is `~/.ssh/id_rsa`
2. Copy your private key elsewhere, and override it on a project level. Change project.properties and set the `project.ssh-keypath` to point to the file.
3. Copy your private key elsewhere, and set the location as an attribute on your nodes (shown below)

To set the ssh keypath attribute on the EC2 Nodes produced by the plugin, you can modify your mapping configuration.

E.g. in the "Mapping Params" field, set:

`Mapping Params: ssh-keypath.default=/path/to/key`

This will set the "ssh-keypath" attribute on your EC2 Nodes, allowing correct private key ssh authentication.

The default mapping also configures a default `username` attribute to be `ec2-user`, but if you want to change the default set:

`Mapping Params: ssh-keypath.default=/path/to/key;username.default=my-username`

