# Development workflow

Source-control history starts with the actual Foundation snapshot. Earlier dated
reports/checkpoint archives are evidence, not fabricated historical Git commits.
`main` is the reviewed alpha source baseline, not a stable gaming release.
`develop` is active development; create `feature/*` and `fix/*` only for real tasks.

Use a small commit per tested change, push the branch and open a pull request.
Do not require another maintainer's approval in this single-maintainer repository.
Required checks/branch rules depend on GitHub private-repository plan availability;
never bypass unavailable protection by claiming it is enabled.

Version names remain explicit alpha/checkpoint names. The current full existing
APK is versionCode18 / `0.7.0-alpha-x86-library`, selected by `withEmpireBay`.
The menu checkpoint label is evidence scope, not a different manifest version.
Tags must point to corresponding source commits. Do not tag old milestones onto
new source or call a shell-only build the full gaming APK.

[Release gates](RELEASING.md) · [changelog](../CHANGELOG.md).
