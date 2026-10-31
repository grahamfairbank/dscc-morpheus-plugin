# Changelog

## 0.1.8 (2026-10-31)

Security hardening after an independent security review. No exploitable issue was found in the plugin; these changes tighten the defaults and the edges.

- **Removed `tools/create-readonly-user.py`.** It gave Read on every feature with a read level, which included **Health** (and with it the Morpheus log) and **Logs**. If you created a role with it, review that role in Administration → Roles: set Health and Logs to None, or delete the role and its user. A replacement that grants only what the HPE Storage tabs need is planned; the first version was held back from this release because, on Morpheus 9.0.1, the user it created could open only User Settings (see Known issues in the README).
- **Limits on DSCC responses:** a response over 32 MiB isn't read, and a full read of DSCC stops after 10 minutes, keeping the previous data.
- **Unexpected errors no longer show their text on the tab.** They show a reference number; the details are in the Morpheus log.
- **A failed refresh is visible:** the tabs keep the previous data and say the last refresh failed. After a failure the plugin waits a minute before trying again, even when it has older data to show.
- The plugin no longer keeps an extra plain copy of the client secret in memory (it compares settings by hash).
- Docs: the README says Morpheus administrators can see the client secret in the plugin settings, and when to rotate the API client. New [SECURITY.md](SECURITY.md) on how to report a vulnerability. New screenshots from Morpheus 9.0.1 on 0.1.8 (example values for every name and id): upgrading the plugin, the plugin's options, the role permission, the cluster tab's checks, a host tab with "VM runs here" and not-linked LUNs, and a VM tab.

## 0.1.7 (2026-10-30)

- **Clearer wording for LUNs Morpheus doesn't link to anything.** A read-only check on a Morpheus 9.0.1 appliance found that, of the LUNs Morpheus lists on its hosts, only the GFS2 datastore LUNs are referenced by anything else in Morpheus: no VM disk, instance, image or other datastore mentions the rest. That's what the plugin reports, but "No Morpheus VM or datastore uses it" read as "nothing uses it", which the plugin can't know. They now read:
  - host tab: **Not linked to a VM or datastore in Morpheus** (chip: "Not linked in Morpheus"), with a note on what it means;
  - cluster tab: **Array LUNs on the hosts that Morpheus doesn't link to a VM or datastore**, with a note that they may be used outside Morpheus, by another tenant, or be left over from deleted VMs.
- **"Used on array" doesn't show whether a LUN is in use.** On an Alletra MP, a running VM's disk can read 0 B or 1 KiB used. Both tabs now say so, so nobody reclaims a volume based on that column.
- Docs: the install guide's screenshots use consistent example values instead of blocked-out text (the same workspace ID and client ID in every step), and the DSCC region guidance says to use the region your workspace's Data Services is deployed in.

## 0.1.6 (2026-10-30)

- **Host tab: disks of VMs running on the host.** Morpheus 9.0.1 doesn't list a running VM's HPE Alletra B10000 Integration LUNs among its host's devices (measured: 27 running-VM disk LUNs, none on any host's list; none of the 99 listed host LUNs belongs to a VM disk). The host tab now adds those disks from the VMs running on it ("VM runs here"), so they show which VM uses them.
- **Cluster tab: new check, "Array LUNs the hosts see that no Morpheus VM or datastore uses".** Volumes still presented to the hosts that nothing in the cluster's tenant uses: often left behind by deleted VMs. Host LUNs like that are now labeled "No Morpheus VM or datastore uses it" instead of "Not used by Morpheus".

- **Docs:** README rewritten (what it's for, tour with screenshots from test data, security, how it works); step-by-step [install guide](docs/INSTALL.md) for first-time users; contributing guide and issue templates.
- **Read-only Morpheus login for storage teams:** `tools/create-readonly-user.py` is documented, with why it exists (a read-only role by hand is over a hundred permission dropdowns).
- The Morpheus and DSCC check scripts are no longer in the repository (kept by the maintainer for troubleshooting).
- `PreviewSpec` and `tools/screenshots.py` make the README screenshots from synthetic test data.
- **Real Morpheus and GreenLake screenshots** (from a production 9.0.1 appliance, with every name, id, address and person blocked out or cropped out) in the install guide and README.
- **Install guide corrections from a real run:** the API client is created under IAM and administration → API access with the service *GreenLake platform and most services*; the workspace ID is under Workspace details (and inside the client's Issuer URL). The read-only script's "could not set default access" notes on 9.0.1 are explained.
- Settings help text for the client ID and workspace ID now points to the right GreenLake pages.

## 0.1.5 (2026-10-29)

- **Fix: host and VM tabs still hidden on 9.0.1.** Morpheus 9.0.1 gives server and instance tab checks no viewer at all (no account, and no account on the user). With no viewer, the tab now follows the page: Morpheus already shows the page only to users who may see that server or instance (the same as com-morpheus-plugin's host tab). With a known viewer, the tenant check still applies.

## 0.1.4 (2026-10-29)

- **Fix: host and VM tabs hidden on 9.0.1 (root cause).** Morpheus 9.0.1 passes no account to server tab checks (log: `tenant (owner 1, viewer null)`), so the tenant check failed closed. The plugin now uses the logged-in user's account when the account is missing; with neither, it still fails closed.

## 0.1.3 (2026-10-29)

- **Fix: host and VM tabs still hidden on 9.0.1.** The tenant check read the owner from the bare server or instance object Morpheus passes to the tab check, before loading the full record. It now loads the full record first (a server's owner falls back to its cluster's owner).
- The plugin logs, at most every 10 minutes per page, why a host, VM or instance gets no HPE Storage tab (tenant, or no array LUN).

## 0.1.2 (2026-10-29)

- **Fix: no HPE Storage tab on host and VM pages (seen on 9.0.1).** The server object Morpheus hands a tab can carry volumes without the fields the plugin reads. Server and instance tabs now always load the server by id, and load a volume by id when it arrives bare.
- **Diagnostics:** with "Show the tab even without HPE array LUNs" on, a host or VM with no array LUN now shows what Morpheus gave the plugin (host device names; per disk: wwn, externalId, volume type, datastore type and LUN).

## 0.1.1 (2026-10-29)

- **Fix: host and VM tabs could stay hidden.** Morpheus can hand a tab a shallow server or instance (no type, volumes or workloads). A host then looked like a VM without array disks. The plugin now loads the full record when anything is missing, and logs a warning (instead of nothing) when a tab check fails.
- Cluster tab: the array table drops the Family column (the model says it) and adds **Data reduction** (deduplication and compression ratio, for example 3.3:1) for every array family.
- LUN ids are shorter (the 8 digits that differ between volumes) and have a **copy** button that copies the full id. A small script loaded with the page's CSP nonce handles the buttons; it only copies text.
- "Integrated storage" is now **HPE Alletra B10000 Integration** throughout.

## 0.1.0 (2026-10-29): first build, not released

- **HPE Storage tab** on cluster, hypervisor host, VM server and instance pages: maps each SAN LUN Morpheus uses to its HPE array volume through HPE GreenLake Data Services (DSCC), by WWN or Nimble serial number.
- Covers both kinds of array storage in Morpheus 9.0: GFS2 datastores on a LUN, and integrated storage where each VM disk has its own LUN (Alletra MP datastore).
- Cluster checks: array LUNs that not every host sees, and HPE array LUNs DSCC doesn't list (usually exported snapshots).
- All DSCC families: 3PAR / Primera / Alletra 9000 (device-type1), Alletra MP B10000 (device-type4), Nimble / Alletra 5000 / 6000 (device-type2).
- Sign-in through the GreenLake workspace issuer first; legacy SSO is the fallback. A "failed to authorize" answer is explained as a setup problem.
- Read-only, no web routes, no browser scripts. One jar for Morpheus 9.0 and later (Plugin API 1.4.1, tested on 1.5.0).
- Tools: read-only Morpheus user script, and read-only Morpheus and DSCC check scripts.
