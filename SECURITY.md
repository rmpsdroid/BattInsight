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

## What this project will not do

Recorded here because they are recurring hazards in this category of application:

- It will never modify `hidden_api_policy`, which downgrades non-SDK interface enforcement
  for every application on the device.
- It will never require root for data collection.
- It will never use a signing key inherited from another project.
