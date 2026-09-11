# Root feasibility — SM-F966N / F966NKSSCBZH3

Checked 2026-09-11 on the connected device. No unlock, reboot, partition write,
firmware flash, permission change or security-policy change was performed.

## Observed state

- Model: SM-F966N, carrier ID KOO.
- Firmware/bootloader: F966NKSSCBZH3.
- Build fingerprint:
  `samsung/q7qksx/q7q:16/BP4A.251205.006/F966NKSSCBZH3_OKRCBZH3:user/release-keys`.
- `ro.boot.flash.locked=1`.
- `ro.boot.vbmeta.device_state=locked`.
- `ro.boot.verifiedbootstate=green`.
- `ro.boot.other.locked=1`.
- `ro.boot.warranty_bit=0` (Android property; Download Mode not inspected).
- OEM unlock supported/allowed properties queried returned empty; an empty
  property alone is not proof of unsupported unlocking.

## Direct firmware evidence

Pulled the installed `/system/priv-app/SecSettings/SecSettings.apk` read-only into
ignored `build/system-inspection/SecSettings.apk`. Examined its DEX implementation
of `com.android.settings.development.OemUnlockPreferenceController.isAvailable()`.

The method returns false when `ro.boot.other.locked` equals `"1"`, regardless of
later OEM lock manager checks. Additional branches test the FRP partition property
and KnoxGuard custom-ROM restriction. The current device matches the explicit
other.locked rejection branch. Extracted disassembly is kept locally at
`build/system-inspection/oem-unlock-controller.txt`.

This confirms that the normal Settings OEM-unlock path is unavailable on this
exact firmware. It does not prove that no exploit or future manufacturer-supported
route can ever exist. No verified alternative route for this exact build was
established. Do not flash patched images against the currently locked bootloader
or assume that showing the Settings toggle would unlock the bootloader itself.

## Consequences if a supported route is later established

Magisk's official Samsung installation instructions require bootloader unlocking
and data wipes for the initial installation. They state that installing Magisk
trips the Knox Warranty Bit irreversibly. Samsung documents loss of Knox-backed
services including Samsung Pay and Secure Folder after the bit is tripped.
Keeping the One UI launcher does not preserve those services after rooting.

Any eventual destructive execution must follow a concrete, verified device/build
procedure, verified backup/recovery preparation, and explicit informed approval
for data erasure and irreversible Knox changes. No such execution was attempted.

## Sources

- [Magisk official Samsung installation](https://topjohnwu.github.io/Magisk/install.html#samsung-devices)
- [Samsung Knox FAQ](https://docs.samsungknox.com/admin/knox-platform-for-enterprise/faq/)
- Installed OEM Settings DEX and ADB property readings above.
