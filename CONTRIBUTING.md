# Contributing to dscc-morpheus-plugin

**Pull requests are welcome.** This is a community project, and it gets better with outside help: bug fixes, support for more array families or DSCC regions, new read-only views, documentation and translations.

> This is an unofficial project, not an HPE product (see [NOTICE.md](NOTICE.md)). Contributions are reviewed by the maintainers, not by HPE.

## Before you start

- **Bugs and ideas:** open an [issue](../../issues/new/choose) first if the change is large, so we can agree on the approach.
- **Small fixes:** open a pull request directly.
- **Security issues** (for example, a way around the permission or tenant checks): please don't open a public issue. Use GitHub's **private vulnerability reporting** (Security → Report a vulnerability) instead.

## Build and test

You only need Docker:

```bash
tools/build.sh
```

This builds and tests one jar into `dist/`. It's compiled against Plugin API 1.4.1 (Morpheus 9.0), and the whole test suite is repeated against 1.5.0 (Morpheus 9.1). Every change must keep both test runs green, and must not use anything that's missing from 1.4.1.

Maintainers: before a release, run `tools/check-plugin-core.sh`. It reports any change in HPE's published morpheus-plugin-core or morpheus-docs that could affect compatibility, such as a new Plugin API version or changes to classes this plugin uses.

Useful opt-in checks:
- `PREVIEW_OUT=<dir>` renders the three tabs to HTML from the test data (`PreviewSpec`); `tools/screenshots.py` makes the README images from them.
- `LOAD_JARS=<jar>` loads a renamed build through the plugin API's own loader (`PluginLoadSpec`).

The tab templates are generated: edit `tools/templates/*.txt` and run `tools/templates.py`; never edit `src/main/resources/renderer/hbs/dscc-*.hbs` by hand.

## Guidelines

- **Keep it read-only.** The plugin only reads DSCC (GET) and Morpheus. Anything that would write to an array or to Morpheus needs a design discussion in an issue first.
- **The plugin never runs commands on hosts.**
- **Tests:** add or update Spock specs for behavior changes. The test data in `Fixtures.groovy` is synthetic (made-up names, WWNs and serials), so keep it that way.
- **No real data in issues, PRs, fixtures or screenshots.** Redact WWNs, array serial numbers, array, volume, host, cluster and tenant names, IPs, GreenLake workspace and API client IDs, and tokens. Use solid boxes on screenshots, not blur.
- **Licenses:**
  - Don't copy code or text from sources whose license doesn't allow it.
  - The Morpheus plugin API is compile-only and must never be bundled into the jar.
  - Cite new sources in [NOTICE.md](NOTICE.md).
- **Style:** follow the existing code. Keep comments short and explain *why*.
- Update [CHANGELOG.md](CHANGELOG.md) and, for user-visible changes, the README or [docs/INSTALL.md](docs/INSTALL.md).

## License of contributions

By contributing, you agree that your contribution is licensed under the project's [MIT license](LICENSE).
