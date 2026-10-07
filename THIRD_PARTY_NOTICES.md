# Third-party software notices

FijiCal Lite is not the full Fiji distribution. The audited v0.9 portable
contains ImageJ 1.x, Apache Groovy, and an Azul Zulu Java runtime.
Original third-party notices and licences must remain intact.

## ImageJ 1.54p (`app/ij-1.54p.jar`)

ImageJ 1.x is public-domain software, originally developed by Wayne Rasband
at the US National Institutes of Health, with contributions from the ImageJ
community. ImageJ's upstream disclaimer explains that it is not subject to
copyright protection under Title 17, Section 105 of the United States Code.
ImageJ 1.x is not the GPL-licensed Fiji distribution.

- Project: https://imagej.net/ij/
- Disclaimer: https://imagej.net/ij/disclaimer.html
- Source: https://github.com/imagej/ImageJ/tree/v1.54p

## Apache Groovy 4.0.28

`app/groovy-4.0.28.jar` and `app/groovy-json-4.0.28.jar` are Apache Groovy
components, licensed under the Apache License, Version 2.0.
Copyright notices and acknowledgements are supplied by the Apache Software
Foundation in the original Groovy distribution.

The core JAR contains `META-INF/LICENSE`, `META-INF/NOTICE`, and additional
licences for bundled ANTLR and ASM code under `META-INF/licenses/`.
Portable builds also copy these unmodified texts into `licenses/groovy/`
so that recipients do not need a JAR viewer. Keep both the JAR notices and
the extracted notices when redistributing.

- Project/licensing: https://groovy-lang.org/faq.html
- Source: https://github.com/apache/groovy/tree/GROOVY_4_0_28

## Azul Zulu / OpenJDK runtime and native launcher

The audited v0.9 runtime identifies itself as:

```text
java.vendor = Azul Systems, Inc.
java.vendor.version = Zulu21.42+19-CA
java.runtime.version = 21.0.7+6-LTS
```

The portable's jpackage metadata reports version 21.0.7; its OpenJDK native
launcher code is a separate third-party component, not original FijiCal code.
The native launcher vendor/build provenance has not been independently verified
from that metadata; verify it along with the runtime before releasing.
OpenJDK components use GPLv2, with the Classpath Exception where specified,
and other component-specific licences. Do not assume the exception applies
to every OpenJDK file. These terms are distinct from FijiCal's GPLv3-or-later.

The original runtime licences, exceptions, and third-party notices are
included under `runtime/legal/`. In particular, read
`runtime/legal/java.base/LICENSE`, `ADDITIONAL_LICENSE_INFO`, and
`ASSEMBLY_EXCEPTION`, plus the notices for each included module.
Keep the entire `runtime/legal/` tree when redistributing.

The Classpath Exception does not remove the source-distribution obligations
for the runtime itself. A generic OpenJDK repository or JDK `src.zip` is not
proof of complete corresponding source for this specific Azul build.

- Vendor terms: https://www.azul.com/products/core/openjdk-terms-of-use/
- Vendor version mapping: https://docs.azul.com/core/version-search
- Source/release requirements: [SOURCE_DISTRIBUTION.md](SOURCE_DISTRIBUTION.md)

## Lightroom Classic

Lightroom Classic is a separate Adobe product and is not included in the
portable package. Our export plug-in calls its installed SDK interfaces;
this project does not grant rights to Lightroom or Adobe's SDK materials.
