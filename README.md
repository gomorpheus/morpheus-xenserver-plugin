# Morpheus XCP-ng Plugin

This plugin provides a full integration between [XCP-ng / Citrix XenServer](https://xcp-ng.org) and [Morpheus](https://morpheusdata.com). It enables cloud inventory sync, VM provisioning, VM import, hypervisor console access, and snapshot-based backups from within the Morpheus platform.

## Requirements

| Component | Minimum Version |
|-----------|----------------|
| Morpheus | 9.0.0 |

## Installation

1. Download the latest `.jar` from the [Releases](https://github.com/gomorpheus/morpheus-xenserver-plugin/releases) page, or [build it yourself](#building).
2. In Morpheus, navigate to **Administration → Integrations → Plugins**.
3. Click **Browse** and upload the `.jar` file.
4. The **XCP-ng** cloud type will appear after the plugin loads.

## Configuration

When adding an XCP-ng cloud in Morpheus (**Infrastructure → Clouds → Add Cloud**), provide the following:

| Field | Description |
|-------|-------------|
| **API URL** | XCP-ng or XenServer pool master endpoint |
| **Custom Port** | Optional API port override |
| **Credentials** | Select local credentials or a stored username/password credential |
| **Username** | XCP-ng or XenServer username |
| **Password** | XCP-ng or XenServer password |
| **Inventory Existing Instances** | Inventory existing virtual machines |
| **Enable Hypervisor Console** | Enable VNC console access through the hypervisor |

Credentials can also be stored as a Morpheus [Credential](https://docs.morpheusdata.com/en/latest/administration/credentials/credentials.html) and selected at cloud setup time.

## Features

### Cloud Sync

The following resources are discovered and kept in sync from XCP-ng or XenServer:

- **Hosts** — XCP-ng hypervisor hosts
- **Images** — VM templates and virtual images available for provisioning
- **Networks** — XCP-ng networks exposed to Morpheus
- **Datastores** — storage repositories available to the pool
- **Pools** — XCP-ng resource pools
- **Virtual Machines** — managed and unmanaged VMs, including power state and metadata

Any additions, updates, and removals in XCP-ng or XenServer are automatically reflected in Morpheus on the next sync cycle.

### Provisioning

Virtual machines can be provisioned into XCP-ng or XenServer directly from Morpheus using standard instance types and layouts. Supported operations include:

- Create, start, stop, and delete VMs
- Resize CPU, memory, disks, and network interfaces
- Clone from selected VM images and snapshots
- Import existing workloads into Morpheus-managed images
- Provision Linux, Windows, Docker host, and Kubernetes node server types
- Use cloud-init customization and optional hypervisor console access

### Backups

XCP-ng VM snapshots are supported via the Morpheus backup framework. Supported operations include:

- Create VM snapshot backups
- Copy snapshot backups to Morpheus backup storage
- Download exported backup archives
- Delete backup snapshots and exported archives
- Restore snapshots to existing or new workloads

## Building

```bash
./gradlew shadowJar
```

The plugin JAR will be written to `build/libs/`.

## License

Copyright 2022 Morpheus Data, LLC. Licensed under the [Apache License, Version 2.0](LICENSE).
