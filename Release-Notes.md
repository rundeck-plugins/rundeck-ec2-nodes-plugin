Release Notes
=========

1.5.4
-----

Date: 2017-02-23

Changes:

* Fix [#61](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/61) add synchronousLoad to disable asynchronous behavior

1.5.3
-----

Date: 2016-12-16

Changes:

* fix [#44](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/44) default hostname selector should fallback to privateIpAddress
* fix [#10](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/10) add conjoined selector values using `+` and quoted literals

1.5.2
---

Date: 2016-03-04

Changes:

* fix metadata for plugin file version [#38](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/38)
* add support for assumeRole for IAM profile [#40](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/pull/40)


1.5.1
---

Date: 10/15/2015

Changes:

* add support for setting ssh port [#33](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/pull/33)

1.5
---

Date: 12/10/2014

Changes:

* Fix issue of adding http proxy support [Issue #14](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/14)
* Make AWS access key and secret optional, and use IAM profile instead [#20](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/20)
* Edit link for nodes updated for new AWS console URL scheme
* Use Password field input for AWS SecretKey and Proxy Password
* Fix use of "|" in `tags.selector` mapping [#18](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/18)
* Fix: Errant whitespace in project.properties can cause AWS auth to fail [#13](https://github.com/rundeck-plugins/rundeck-ec2-nodes-plugin/issues/13)

1.4
---

Date: 1/24/2014

Changes:

* Fix log4j problem with Rundeck 1.6
* works with Rundeck 1.6/2.x

1.3
---

Date: 2/22/2013

Changes:

* Update to work with Rundeck 1.5
* Fix incorrect description of Mapping Params config property

1.2
---

Date: 11/16/2011

Changes:

* Added "merge" feature to `tags.selector` mapping

1.1
---

Date: 10/5/2011

Changes:

* Fix issue executing on nodes [Issue #1](https://github.com/gschueler/rundeck-ec2-nodes-plugin/issues/1)

1.0
---

Initial release

Date: 9/9/2011
