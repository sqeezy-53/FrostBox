# Contributing

Read [architecture](docs/ARCHITECTURE.md), [build tiers](docs/BUILDING.md),
[known issues](docs/KNOWN_ISSUES.md) and [branch workflow](docs/DEVELOPMENT.md).

Use Kotlin/Compose and C++20. Reuse the existing process manager and profile store.
Keep UI, IPC, Wine integration and native execution separated. Do not rewrite
qualified Wine/Box64/DXVK paths or change the base ZIP without specific evidence.

Create a feature/fix branch from develop, make a small testable change, run relevant
checks and open a PR. Explain observable behavior, limitations and exact validation.
Distinguish built, host tested, device tested, physical input and gameplay verified.
No root, SELinux changes, system-wide input-injection bypass or fake metrics.

Do not submit game files, prefixes, saves, keys, tokens, private paths, device
serials or raw logs. Use sanitized diagnostics. Preserve upstream authorship and
license headers. Independently written code is contributed under MIT; Wine-derived
changes retain LGPL-2.1-or-later. No CLA is required.

Report security issues privately using [SECURITY.md](SECURITY.md).
