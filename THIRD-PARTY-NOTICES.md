# Third-party notices

Folio's own code is licensed under the MIT License (see `LICENSE`). The runnable jar
(`target/folio.jar`) also contains the following libraries, which keep their own licences.

| Library | Used for | Licence |
|---|---|---|
| [FlatLaf](https://github.com/JFormDesigner/FlatLaf) | the dark look and feel | [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) |
| [JNA](https://github.com/java-native-access/jna) (`jna`, `jna-platform`) | the native Windows folder dialog | Dual-licensed LGPL 2.1 or [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0); Folio uses it under Apache 2.0 |

The libraries' licence texts and notices are inside their own jars (`META-INF/`), and
the shaded `folio.jar` keeps them.

## Test-only material (not in the jar)

- JUnit 5, under the Eclipse Public License 2.0.
- `src/test/resources/epubs/` holds two sample books from the IDPF epub3-samples project under
  CC BY-SA 3.0; see `src/test/resources/epubs/NOTICE.md`.
