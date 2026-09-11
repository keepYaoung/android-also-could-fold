# Vendored LibADB Android BC

Source: https://github.com/osservatorionessuno/libadb-android-bc
Tag: 3.3.0; commit: 41da19e73a50368dd925808a9814d879cd8c8226
Original project: https://github.com/MuntashirAkon/libadb-android

We use the Apache-2.0 option, with the additional BSD-3-Clause and MIT notices
retained in source and LICENSES. The fork uses Java Bouncy Castle rather than the
original native SPAKE2 dependency. Our build script is local. Local source change:
pairing socket reads time out after 10 seconds so a stale port cannot hang setup.
