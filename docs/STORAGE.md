# Storage recovery

Measured baseline before Foundation changes: **74.61GiB allocated private app data**.
Main causes: chained full runtime overlay copies and retained diagnostic Wine
prefixes. `games` (~21.3GiB) includes imported content and mutable prefixes;
`horizon-wow64` (~20.6GiB) includes both runtime roots and diagnostic `run-*` data.
Other large categories include input, WSI, display and audio overlays.

No cleanup is implied by these directory names. Prefixes, saves, imported content,
qualified runtime versions and unknown files are protected. Dry-run must identify
owners, active references, live processes and reconstructibility before mutation.
Do not follow symlinks. Physical allocated usage must account for hardlinks rather
than summing logical file lengths.

The existing Storage screen currently analyzes usage; its totals predate hardlink
accounting. Runtime immutable sharing, idle-only reconciliation, cleanup leases,
accurate allocated totals and bounded diagnostic retention are tracked work.
No user prefix deletion is authorized by this design. No reclaimed-space result is
claimed until measured on the device and Play/Stop is rechecked.
