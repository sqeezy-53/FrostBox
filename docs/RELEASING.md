# Release gates

A binary prerelease is deferred until the APK is mapped to the exact source
commit/build flags, its manifest/signature/SHA are recorded, and corresponding
Wine/FreeType sources, local modifications, notices and practical rebuild/replace
instructions are ready for the same package. A source-only private repository
can be published before this binary audit is complete.

1. Build/test from a clean source commit with explicit runtime package manifests.
2. Record source commit, manifest version, toolchain and package SHA256 identities.
3. Run relevant physical-device gates; list fixtures/game/manual limits separately.
4. Audit licenses and corresponding source for every bundled library/module/font.
5. Exclude games, PhysX MSI, prefixes, saves, secrets and private evidence.
6. Create an explicit alpha tag and GitHub prerelease only for that qualified state.
7. Attach APK, SHA256SUMS, required sources/notices and scoped release notes.
8. Verify downloaded release asset hashes and repository visibility remains PRIVATE.

Debug artifacts are not stable releases. Existing local APKs/source/evidence are
preserved; no historical tag is invented for them. CI artifacts are source-only
shell builds and must be labelled as such.

## Foundation binary privacy blocker

The APK text/manifest audit detected no credentials or personal paths in the
reviewed small metadata files. A deeper scan of nested runtime binaries found
local compiler/build paths in **1,420 entries**, mainly Wine PE assets. Therefore
the current full APK is a local validation checkpoint, not a GitHub release asset.
Create a separate privacy-safe distribution package and audit exact corresponding
sources before upload. Preserve the qualified Wine ZIP byte-for-byte and do not
change existing prefix identities silently. This block does not affect source
repository publication.
