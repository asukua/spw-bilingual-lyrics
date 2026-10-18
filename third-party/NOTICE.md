# Third-party software

The plugin uses **Jaudiotagger 3.0.1**, by Paul Taylor and contributors, including
Entagged Audio Tag library code copyright (c) 2003–2005 Raphaël Slinckx.
It is licensed under LGPL 2.1 or later. The library is unmodified and distributed
as a separate `lib/jaudiotagger-3.0.1.jar` inside the `.spmod` ZIP archive.

The full LGPL text and corresponding published source JAR are included here and
in `classes/META-INF/third-party/` of the plugin. To replace or debug the library,
unzip the `.spmod`, replace its separate library JAR with a compatible build,
and rezip with `classes/` and `lib/` at the archive root. You may modify the library
and reverse engineer this plugin for debugging such modifications as permitted
by its license.

- Project: https://www.jthink.net/jaudiotagger/
- Source artifact: https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1-sources.jar
- Binary artifact: https://repo.maven.apache.org/maven2/net/jthink/jaudiotagger/3.0.1/jaudiotagger-3.0.1.jar

**Gradle Wrapper** is distributed in this source project under Apache 2.0.
Its scripts/JAR and checksum-pinned distribution settings follow the official
SPW API example. The wrapper is a build tool and is not included in `.spmod`.
See https://github.com/gradle/gradle and `gradle-LICENSE.txt`.

The SPW Workshop API and Kotlin/PF4J host dependencies are compile-only and
are provided by Salt Player. They are not redistributed in the plugin.
