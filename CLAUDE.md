# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Current state

This repository is a fresh, essentially empty project. As of now it contains only a
`README.md` stub and a `.gitignore`. There is no source code, build system, dependency
manifest, or tests yet.

The `.gitignore` is configured for **Java** (ignores `*.class`, `*.jar`, `*.war`, BlueJ
`*.ctxt` files, JVM crash logs), which indicates this is intended to be a Java project.
No build tool (Maven `pom.xml` or Gradle `build.gradle`) has been chosen yet.

## Notes for future instances

- When the first real code is added, update this file with the actual build, test, lint,
  and run commands, and document the high-level architecture once there are multiple files
  worth explaining.
- If setting up the project from scratch, confirm the intended build tool (Maven vs.
  Gradle) and Java version with the user before scaffolding, since neither has been
  established.
