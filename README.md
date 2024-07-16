# Morpheus XCP-ng Plugin
This library provides an integration between XCP-ng and Morpheus. A `CloudProvider` (for syncing the Cloud related objects), a `ProvisionProvider` (for provisioning into XCP-ng), and a `BackupProvider` (for vm snapshots and backups) are implemented in this plugin.

### Requirements
XCP-ng - Version 8.2 or greater

### Building
`./gradlew shadowJar`

### Configuration
The following options are required when setting up a Morpheus Cloud to a Nutanix Prism Central environment using this plugin:
1. API URL: XCP-ng host URL (i.e. https://10.100.10.100)
2. Custom Port (optional, defaults to 443)
2. Username
3. Password

#### Features
Cloud sync: hosts, images, networks, datastores, pools, and virtual machines are fetched from XCP-ng and inventoried in Morpheus. Any additions, updates, and removals to these objects are reflected in Morpheus.

Provisioning: Virtual machines can be provisioned from Morpheus via this plugin.
