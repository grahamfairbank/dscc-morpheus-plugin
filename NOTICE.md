# NOTICE: sources, attributions, disclaimer and trademarks

## Unofficial project

dscc-morpheus-plugin is an **independent, unofficial community project**. It is **not** a product of Hewlett Packard Enterprise (HPE) or Morpheus Data, LLC. It is not developed, reviewed, endorsed, sponsored, certified or supported by HPE or Morpheus Data.

The software is provided "as is", without warranty of any kind (see [LICENSE](LICENSE)). Use it at your own risk. Do not contact HPE support about it.

The plugin only reads. It never changes anything on an array, in HPE GreenLake Data Services Cloud Console (DSCC) or in Morpheus, and it doesn't connect to the hypervisor hosts. Anything it reports (for example a LUN that Morpheus doesn't link to a VM or datastore) is information, not advice: check in DSCC before you reclaim or change any storage.

## Sources and how they were used

This plugin was written from scratch. **No source code, documentation text or images from the third-party sources below are copied into this project.** It interoperates with the products they describe, and they were used only as references, within their published terms:

| Source | Owner / license | How it was used |
|---|---|---|
| [grahamfairbank/com-morpheus-plugin](https://github.com/grahamfairbank/com-morpheus-plugin) | Same author; MIT license | The sibling project. Its architecture, build and test tooling (`tools/build.sh`, `tools/check-plugin-core.sh`, the jar load test), GreenLake sign-in code, settings handling, tab and renderer patterns, and safeguards were reused and adapted. |
| [HewlettPackard/morpheus-plugin-core](https://github.com/HewlettPackard/morpheus-plugin-core) (`morpheus-plugin-api` 1.4.1 and 1.5.0 from Maven Central; tags `rel-1.4.1` and `rel-1.5.0`) | Morpheus Data, LLC, under the Morpheus Data Plugin Core Source License (PSL) | Used, as the PSL permits, **solely to develop and compile this plugin**. It is a compile-time (`provided`) dependency and is **not bundled in, or redistributed with,** the plugin jar. Its published documentation and published source were read to learn the plugin and data model interfaces (tab providers, global UI components, the renderer, compute servers, storage volumes and datastores). |
| [HewlettPackard/morpheus-docs](https://github.com/HewlettPackard/morpheus-docs) (branch `dev-9.1`) | HPE; no license file published | Read for product facts: which Plugin API version each release uses (`release_notes/compatibility.rst`, also checked by `tools/check-plugin-core.sh`), the CSP-nonce pattern for plugin scripts (`getting_started/guides/catalog_layout.rst`), and log locations (`troubleshooting/Attaching_Logs.rst`). Cited by link and paraphrased; no text copied. |
| [HPE GreenLake Data Services storage API](https://console-us1.data.cloud.hpe.com/doc/api/v1/) (API reference and OpenAPI document `storage-api.json`) | HPE | The public API documentation this plugin calls: storage systems for every device type (3PAR, Primera and Alletra 9000; Alletra Storage MP B10000; Nimble Storage and Alletra 5000/6000), volumes, capacity and health fields, paging and regional console addresses. |
| [HPE GreenLake authentication guide](https://developer.greenlake.hpe.com/docs/greenlake/guides/public/authentication/authentication) | HPE | The OAuth2 client-credentials token flow and the workspace issuer URL. |
| [jullienl/HPE-COM-PowerShell-Library](https://github.com/jullienl/HPE-COM-PowerShell-Library) | Lionel Jullien; MIT license | Indirectly, through the GreenLake sign-in code reused from com-morpheus-plugin: reference for the token endpoints (legacy SSO and workspace issuer URLs). No code copied. |
| [builtbyfood/gfs2-rescue](https://github.com/builtbyfood/gfs2-rescue) and [builtbyfood/morpheus-vm-folders](https://github.com/builtbyfood/morpheus-vm-folders) | Community authors; no license published | Design ideas only, by way of com-morpheus-plugin: a plugin renderer of its own, and loading the plugin's script through a global UI component. No code copied. |

### Test data

The test data in `src/test/groovy/.../Fixtures.groovy` is **synthetic**. It follows the shapes of DSCC and Morpheus 9.0.1 API responses measured on the author's own lab, but every array, volume and datastore name, WWN, serial number and ID in it is made up. The optional real-data test (`RealDataSpec`) reads files from a local, git-ignored folder; no real data is in this repository.

### Screenshots

The screenshots in `docs/images` show the author's own HPE GreenLake workspace and Morpheus appliance. Workspace, client, tenant, user, cluster, host, VM, array and datastore names and IDs are replaced with consistent example values (for example `my-workspace`, `cluster-a`, `mp-array-a`); any value that looks like a real ID is made up. The README's main tab images are rendered from the synthetic test data. The HPE GreenLake and Morpheus user interfaces they show belong to HPE.

### What the plugin jar contains

Only this project's own compiled classes, four page templates, one script and one icon. It contains no third-party code. The Morpheus plugin API, Groovy and other runtime libraries are supplied by the Morpheus appliance at run time.

## Trademarks

This project refers to the following names only to describe what it works with (nominative use). No affiliation or endorsement is implied.

- **HPE** and **Hewlett Packard Enterprise**, and the HPE logo
- **HPE GreenLake** and **GreenLake**
- **HPE GreenLake Data Services Cloud Console** and **Data Services Cloud Console** (abbreviated "DSCC" in this project), and **HPE GreenLake Data Services**
- **HPE Compute Ops Management** (abbreviated "COM" in this project)
- **HPE Alletra**, including **HPE Alletra Storage MP**, **HPE Alletra Storage MP B10000**, **HPE Alletra 9000**, **HPE Alletra 6000** and **HPE Alletra 5000**
- **HPE Primera** and **Primera**
- **HPE 3PAR**, **3PAR** and **HPE 3PAR StoreServ**
- **HPE Nimble Storage** and **Nimble Storage**
- **HPE Morpheus**, **Morpheus**, **HPE Morpheus VM Essentials** and **HPE VM Essentials** (abbreviated "VME" in this project)

These are trademarks or registered trademarks of Hewlett Packard Enterprise Company, or of Morpheus Data, LLC (an HPE company), in the United States and other countries.

**Linux** is the registered trademark of Linus Torvalds in the U.S. and other countries. **Python** is a trademark of the Python Software Foundation. **Windows** is a trademark of the Microsoft group of companies, and **macOS** is a trademark of Apple Inc. **GitHub** is a trademark of GitHub, Inc. Other product and company names that appear in data shown by the plugin (for example array, drive or host model names) are trademarks of their respective owners.

The plugin's icon is an original drawing and does not use any HPE logo.
