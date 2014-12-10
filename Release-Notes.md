Release Notes
=========

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
