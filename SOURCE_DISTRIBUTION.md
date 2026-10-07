# Source distribution and release checklist

## Current status (2026-10-07)

The original v0.9 release was published before this licensing audit.
Adding notices to the source repository alone does not update that ZIP.
Do not describe that release as fully GPL-compliant based on this audit.

FijiCal's source and build script are in this repository. The remaining
release blocker is obtaining and verifying **complete corresponding source
for Azul Zulu 21.42.19 CA / OpenJDK 21.0.7+6**, including the native jpackage
launcher, vendor changes, and applicable build scripts. The vendor identity
was read from the bundled runtime, not inferred from the Java version alone.
The native launcher's packaging metadata reports jpackage 21.0.7 but does not
establish its vendor; verify its provenance separately or regenerate it from
the reviewed vendor JDK so that its source correspondence is known.

Azul's published licensing information describes a source-request process:
https://docs.azul.com/core/tpls/january-2024/zulu17_tpl.html
That older document is evidence of the vendor's process, NOT a source offer
for our exact Java 21 build and NOT a substitute for its source.
Request the matching complete source package from Azul, or separately approve
replacing the runtime and native launcher with a distribution whose complete
matching source can be obtained and redistributed. Do not silently substitute
generic upstream OpenJDK source for vendor-specific source.

## Before publishing a corrected portable

1. Verify the source package against the exact runtime/native-launcher build.
   Include vendor changes, native sources, and build scripts; a Java-library-only
   `src.zip` is insufficient. Record origin and SHA-256 in the release notes.
2. Run `build-portable.ps1` with that reviewed package as `-RuntimeSourceArchive`.
   This parameter is a release gate, not an automated legal/provenance review.
3. Publish the resulting runtime-source archive beside the portable ZIP, with
   equally accessible, no-charge downloads. Link it prominently in release notes.
4. The portable itself contains FijiCal's editable source and build instructions
   in `source/`, its full GPL text, and extracted Groovy notices. Include a link
   to the exact repository commit in release notes, not just the moving main branch.
5. Preserve all runtime/legal and dependency notices. Do not add licence fees,
   noncommercial restrictions, or restrictions on modifying/redistributing GPL code.
6. Verify the published downloads, then obtain the owner's explicit approval
   before any Git push. Replacing the existing release is a separate publication step.

## Rebuilding and installing modified FijiCal code

Use the portable baseline and a compatible JDK with the documented build script.
The Groovy scripts and Lightroom Lua files are directly editable. The Java
launcher is compiled from `launcher/src/org/fijical/lite/Main.java`; tests
are in `tests/`. The compiled launcher JAR replaces `app/FijiCalLauncher.jar`.
There is no signature, activation key, or restriction preventing modified code
from running in the portable app.

The baseline EXE and runtime are reused by the script, not built from FijiCal's
Java source. To regenerate these third-party components, first build the matching
JDK from the vendor's complete source using its own instructions. Use that JDK's
`jlink` to create the runtime, preserving its legal files, with these modules:

```text
java.base,java.compiler,java.datatransfer,java.xml,java.prefs,java.desktop,
java.logging,java.management,java.rmi,java.scripting,java.transaction.xa,
java.sql,jdk.unsupported
```

Then use that JDK's Windows `jpackage --type app-image` with name `FijiCal Lite`,
app version `0.9`, main JAR `FijiCalLauncher.jar`, main class
`org.fijical.lite.Main`, the `app/` dependencies as input, and the generated
runtime as `--runtime-image`. Use these Java options:

```text
-Dfile.encoding=UTF-8
-Dfijical.portable=true
-XX:MaxRAMPercentage=70.0
```

The project icon may be supplied or omitted. This is the observed packaging
configuration, not a claim of byte-for-byte reproduction of the old EXE.

GPL references: https://www.gnu.org/licenses/gpl-faq.html and
https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
