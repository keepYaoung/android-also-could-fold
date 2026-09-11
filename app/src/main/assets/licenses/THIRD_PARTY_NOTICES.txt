# Third-party notices

Our application and compositor code remain under [MIT](LICENSE). Third-party code
is not relicensed by the repository's root license.

- **LibADB Android BC 3.3.0**: vendored from osservatorionessuno/libadb-android-bc,
  commit `41da19e73a50368dd925808a9814d879cd8c8226`, derived from Muntashir Al-Islam's
  LibADB Android. We select Apache-2.0 from its dual license; additional BSD-3-Clause
  and MIT notices apply to identified files. Original copyright notices (including
  Cameron Gutman, Sam Palmer, Google Inc., 南宫雪珊, and Muntashir Al-Islam) are retained.
  See [provenance and modifications](third_party/libadb/UPSTREAM.md) and
  [license texts](third_party/libadb/LICENSES/). APK assets retain the source notices.
- **Bouncy Castle 1.84** (bcprov, bctls, bcutil and bcpkix): Copyright © 2000–2026
  The Legion of the Bouncy Castle Inc.; [Bouncy Castle license](https://www.bouncycastle.org/licence.html).
- **Kotlin standard library** and **AndroidX** dependencies: Apache-2.0.
  Android/Gradle build tools retain their respective licenses.

License texts and notices are included in `app/src/main/assets/licenses/` and can
be read using the app's open-source notices button. The vendored library's optional
GPL license text is preserved for provenance; this distribution selects Apache-2.0.
