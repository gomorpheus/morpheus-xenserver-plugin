# Morpheus XCP-ng Plugin

The Morpheus XCP-ng Plugin integrates Morpheus with XCP-ng (and XenServer) hypervisors to provide virtual machine provisioning, snapshot-based backup, and cloud synchronisation. The plugin communicates with the host using the XenAPI SDK over XML-RPC.

## Table of Contents

- [Features](#features)
- [Requirements](#requirements)
- [Repository structure](#repository-structure)
- [Building the plugin](#building-the-plugin)
- [License](#license)
- [Installing](#installing)
- [Detailed Usage Steps](#detailed-usage-steps)
- [API Endpoints](#api-endpoints)

---

## Features

### Virtual Machine Provisioning

Provision and decommission virtual machines on XCP-ng hosts and pools from Morpheus. Supports template selection, network and storage configuration, CPU/memory sizing, and hypervisor console access.

### Backup via Snapshots

Back up and restore VMs using XCP-ng/XenServer snapshots managed through the Morpheus backup framework.

### Cloud Sync

Morpheus synchronises the following XCP-ng resources for inventory:

- Virtual machines
- Hosts
- Networks
- Storage repositories (datastores)
- VM templates and disk images
- Snapshots

---

## Requirements

| Requirement | Version |
|-------------|---------|
| Morpheus | 9.0.0 or later |
| Java | 11 or later |
| Gradle | Use the included Gradle wrapper (`./gradlew`) |

Additional prerequisites:

- A running XCP-ng host or pool master accessible over HTTPS (port 443) from the Morpheus appliance
- A XenServer/XCP-ng user account with pool operator or admin permissions
- Network access from the Morpheus appliance to the XCP-ng pool master on port 443

---

## Repository structure

```
src/main/groovy/com/morpheusdata/xen/
├── XenserverPlugin.groovy                  - Plugin entry point; registers all providers
├── XenserverCloudProvider.groovy           - CloudProvider implementation; sync and cloud lifecycle
├── XenserverProvisionProvider.groovy       - ProvisionProvider implementation; VM lifecycle
├── XenserverBackupProvider.groovy          - BackupProvider implementation
├── XenserverBackupTypeProvider.groovy      - Snapshot-based backup type
├── XenserverBackupExecutionProvider.groovy - Backup execution
├── XenserverBackupRestoreProvider.groovy   - Restore from snapshot
├── datasets/
│   └── VirtualImageDatasetProvider.groovy  - Dataset provider for image/template selection
├── sync/
│   ├── DatastoresSync.groovy               - Syncs storage repositories
│   ├── HostSync.groovy                     - Syncs hosts
│   ├── ImagesSync.groovy                   - Syncs VM templates and disk images
│   ├── NetworkSync.groovy                  - Syncs networks
│   ├── PoolSync.groovy                     - Syncs resource pools
│   └── VirtualMachineSync.groovy           - Syncs VMs
└── util/
    └── XenComputeUtility.groovy            - XenAPI session management and shared operations
src/main/groovy/com/xensource/xenapi/
    CustomDateDeserializer.groovy           - Date deserialiser for XenAPI responses
src/main/resources/i18n/                   - Internationalisation message bundles
src/main/resources/scribe/                 - Seed/migration scripts
src/test/groovy/                            - Tests
build.gradle, gradle.properties             - Build configuration and plugin metadata
```

---

## Building the plugin

Run the following command to compile and package the plugin jar:

```bash
./gradlew clean build
```

The packaged jar will be written to `build/libs/`.

To execute tests, use the following command:

```bash
./gradlew test
```

---

## License

This project is licensed under the Apache License 2.0.

See the [LICENSE](LICENSE) file for details.

---

## Installing

1. Build the plugin (see [Building the plugin](#building-the-plugin)) or download a released jar.
2. In Morpheus, navigate to **Administration > Integrations > Plugins**.
3. Click **Add** and upload the `morpheus-xenserver-plugin-<version>.jar` from `build/libs/`.
4. Navigate to **Infrastructure > Clouds > Add** and select **XCP-ng** to configure the integration.

---

## Detailed Usage Steps

### Adding an XCP-ng Cloud

1. Go to **Infrastructure > Clouds > Add**.
2. Select **XCP-ng** as the cloud type.
3. Enter a **Name**, the **API URL** (the XCP-ng pool master hostname or IP), and optionally a **Custom Port**.
4. Provide credentials (Username and Password) or select a stored credential.
5. Optionally enable **Inventory Existing Instances** and **Enable Hypervisor Console**.
6. Save. Morpheus connects to the XCP-ng host and begins syncing resources.

### Provisioning a Virtual Machine

1. Go to **Provisioning > Instances > Add**.
2. Select an XCP-ng-backed instance type.
3. Choose the target **Group**, **Cloud**, network, storage repository, and template.
4. Configure CPU, memory, and disk sizing, then provision.

### Taking a Backup

1. From an instance detail page, navigate to the **Backups** tab.
2. Click **Backup Now** to create an XCP-ng snapshot.

### Restoring from a Snapshot

1. From the instance **Backups** tab, select a completed snapshot entry.
2. Click **Restore** and confirm.

---

## API Endpoints

This plugin communicates with XCP-ng/XenServer using the **XenAPI SDK** (`com.xensource.xenapi`), which uses **XML-RPC over HTTPS** (port 443) to the pool master. No REST endpoints are used. All operations (VM create/update/delete, snapshot, restore, sync) are performed via the XenAPI XML-RPC interface.
