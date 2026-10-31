# Security policy

dscc-morpheus-plugin is a community project, not an HPE product. Please don't contact HPE support about it.

## Reporting a vulnerability

**Please report security problems privately, not as a public issue.**

Use GitHub's private vulnerability reporting: on this repository, open **Security → Report a vulnerability** ([direct link](https://github.com/grahamfairbank/dscc-morpheus-plugin/security/advisories/new)).

Please include:

- the plugin version and your Morpheus version;
- what you found and how to reproduce it;
- what an attacker could do with it, as you understand it.

Don't include real credentials, tokens, array serial numbers, WWNs or other details of your environment. Replace them with example values.

You should get a reply within a week. Fixes are released as a new version with a note in the [changelog](CHANGELOG.md); you'll be credited there unless you'd rather not be.

## Supported versions

Only the latest release gets security fixes. Upgrade to it before reporting, if you can.

## What's in scope

- The plugin jar: how it handles the DSCC client secret and tokens, what it shows to which Morpheus users, and what it sends to the browser.

Out of scope: Morpheus itself, HPE GreenLake and DSCC. Report those to HPE.

## How the plugin is built to be safe

The [Security section of the README](README.md#security) describes the design: read-only access to DSCC and Morpheus, one GreenLake API client per appliance (use a dedicated account with a read-only Data Services role), tenant checks on every tab, an optional permission, HTML-escaped output, and limits on what DSCC responses the plugin will read.
