# GitHub Copilot

Use the nearest applicable `AGENTS.md` for workflow/review rules. For playback, protocol, and hardware facts, use `docs/WAM_PROTOCOL.md` and `docs/DEVELOPMENT_STATUS.md`; newest measurements override older assumptions. Apply matching `.github/instructions/*.instructions.md` files.

For code review:

- Flag concrete PR regressions: correctness, security/privacy, data loss, races, lifecycle/resource leaks, compatibility, and repository contract violations.
- Flag CI, build, lint, or test failures only if the diff causes them.
- Skip generic style/preference comments enforced by formatters or linters unless they reveal a defect.
- Skip docs-only, formatting, or cosmetic nits unless factually wrong, breaking generated/validated content, or risking security/releases.
- Prefer one precise comment per root cause: impact and smallest useful fix.
- Keep suggestions non-blocking. Block only concrete merge-risk defects.
- No actionable findings: no invented nits or filler praise.
- Write concise English review comments.
