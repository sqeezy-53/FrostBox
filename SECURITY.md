# Security policy

FrostBox is an experimental alpha. Wine runs with the app's privileges; it is not
a security sandbox for malicious Windows programs. SAF import does not execute
files. Launch requires explicit action. Minimal Android permissions and same-UID
IPC are part of the design; never disable SELinux or use root as a compatibility fix.

Do not post credentials, imported files, prefixes, saves, device serials, private
paths or unredacted logcat in public reports. Runtime shutdown/cleanup must verify
ownership and process identity and must not follow arbitrary symlinks.

For a vulnerability, use GitHub private vulnerability reporting if enabled, or
contact the repository owner through GitHub before sharing exploit details.
Do not publish exploit-bearing game archives or secrets in an ordinary Issue.
If private reporting is unavailable, request a private channel without disclosing
the vulnerability publicly. No dedicated security support SLA is promised.

Only the current development branch receives fixes. Historical checkpoints are
preserved for engineering reproduction, not maintained stable products.
