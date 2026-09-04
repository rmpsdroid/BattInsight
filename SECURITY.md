# Security Policy

## Supported versions

BattInsight is **pre-release**. There is no published release and no version is supported
for production use. Security fixes are applied to the `main` branch.

## Reporting a vulnerability

Please report suspected vulnerabilities through **GitHub's private vulnerability reporting**
on this repository (Security → Report a vulnerability). That keeps the report private until
a fix is available.

**Please do not open a public issue for a security problem.**

If private reporting is unavailable to you, open a public issue containing only that you
have a security report and a way to reach you — no details.

### What helps

- What the issue is and why it is a security problem
- Steps to reproduce
- Android version, device and build where observed
- Any suggested fix

### What to expect

This is a small project with no dedicated security team. Reports will be acknowledged as
soon as reasonably possible and handled in the open once fixed. No bounty is offered.

## Scope

In scope: anything in this repository — application source, build configuration, CI
workflows, dependency handling.

Particularly relevant given what this application does:

- Unintended exposure of collected battery, usage or package data
- Anything causing data to leave the device (the application declares no `INTERNET`
  permission; a path that circumvents that is a valid report)
- Unsafe shell command construction
- Overly broad exported components, file providers or intent handling
- Committed secrets or credentials

Out of scope: vulnerabilities in Android itself, in Shizuku, or in third-party
dependencies — please report those upstream, though telling us is welcome so we can pin or
work around them.

## The two security models

BattInsight can operate in either of two arrangements. Which one is in use is the user's
choice, and the application shows it at all times.

**Live Shizuku.** BattInsight holds none of the three elevated Android permissions.
Privileged commands run in a process Shizuku owns and starts, and BattInsight's own UID
remains ordinary. Verified on Android 16: battery statistics were acquired through Shizuku
while `DUMP`, `PACKAGE_USAGE_STATS` and `INTERACT_ACROSS_USERS` were all denied to
BattInsight, and the application's own process was refused in the same run.

**Independent granted-app.** BattInsight holds all three permissions until they are
revoked, and works with Shizuku absent. They are granted only after an explicit
confirmation naming each one, one at a time, with each result verified against the
platform's own permission state before the next is attempted, and the whole sequence
followed by an acquisition that has to actually succeed.

Neither arrangement is substituted for the other without the user acting.

## How privileged execution is constrained

No API anywhere in the application executes a caller-supplied string. Two sealed whitelists
exist, and only they produce an argument vector:

- read-only probes: `id`, `id -Z`, `dumpsys batterystats --proto`, `dumpsys batterystats -c`;
- setup actions: `pm grant` and `pm revoke`, for exactly three permissions, against
  BattInsight's **own** package name fixed at compile time.

What crosses the Shizuku Binder is an *identifier*, never a command. The remote service
resolves it against its own copy of the whitelist and refuses anything unrecognised before
a process is created. There is no package parameter on that interface, so it cannot be asked
to change another application's permissions. Both properties are enforced by tests that scan
the production source, not merely documented here.

## What this project will not do

Recorded here because they are recurring hazards in this category of application:

- It will never modify `hidden_api_policy`, which downgrades non-SDK interface enforcement
  for every application on the device.
- It will never require root for data collection. No root mode is offered.
- It will never use a signing key inherited from another project.
- It will never change an app-operation. Usage-access app-ops are read and never written.
- It will never grant, revoke or otherwise alter permissions belonging to another package.
- It will never bundle, download or install Shizuku. Shizuku is an independent project;
  BattInsight can open its official website in the user's browser, and nothing more.
