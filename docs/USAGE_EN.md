# HyperHand — Usage Guide

> 中文版：[USAGE.md](USAGE.md)
>
> Applies to 3.4.x and later. For internals (gesture algorithm, hook points, the full parameter
> table) see [README](../README.md); for release notes see [CHANGELOG](../CHANGELOG.md).

HyperHand is an LSPosed module for Xiaomi phones (HyperOS / MIUI, Android 13+). It does three things.
So far it has only been fully tested on the Xiaomi 14 with HyperOS 3 (Android 16); other devices and
OS versions may need adaptation:

| Feature | In one line |
|---|---|
| One-handed mode enhancement | A larger, more forgiving swipe-down zone at the bottom edge |
| Volume-key mapping | Map volume key short / long / double / multi press to media and volume actions |
| Launcher menu slide-select | Hold an icon, drag to a menu item, release to pick it |

---

## 1. Install and enable

1. Make sure your framework supports **libxposed API 102** — Vector 2.x or a recent LSPosed.
   Older LSPosed 1.9.x only goes up to 101 and will silently ignore this module.
2. Install the APK, then **enable HyperHand** in your framework and select the scopes you need:
   - `system` (system framework / `system_server`): required for key mapping;
   - `com.android.systemui`: required for one-handed mode enhancement;
   - `com.miui.home`: required for launcher slide-select and gesture arbitration.

   The module currently uses `staticScope=false`, so scopes are not forcibly selected for you.
3. Inject the new code:

   ```bash
   adb shell am crash com.android.systemui   # restart System UI
   adb shell am crash com.miui.home          # restart Launcher (needed by slide-select)
   ```

   No adb? Just reboot the phone.

The app has three tabs at the bottom — **Overview / Settings / About**. Ordinary settings are saved and
applied immediately; key-mapping rules must be saved in the editor. Root is not needed for daily use,
except for the optional "Restart scopes" action.

---

## 2. One-handed mode enhancement

**How**: swipe down from the bottom edge of the screen.

- The trigger zone is **56dp** tall by default — wider than the system's 25dp, so most of what your
  thumb can reach is inside it.
- Both slow drags and quick flicks work: slow drags need "long enough + a clean path", quick flicks
  are judged by peak velocity. Each path computes its own confidence score.
- Swiping down from **above** the zone, or from the status bar, still opens the notification / control
  centre — the module steps aside for the system gesture.

**Three trigger modes** (Settings → Trigger style):

| Mode | Feel |
|---|---|
| Natural | Velocity + distance combined; the default |
| Sensitive | Lower thresholds, a light flick is enough |
| Anti-mistouch | Higher thresholds; scrolling near the bottom stays reliable |

**Leaving one-handed mode**: tap above the shrunken area, swipe up on the capsule, or let it time out —
all native behaviour, the module doesn't rewrite it.

**Appearance**: Settings → "Blurred wallpaper background" swaps the backdrop for a blurred wallpaper
with adjustable radius and dim. "Debug → Preview overlay" enters one-handed mode immediately so you can
see the result.

---

## 3. Volume-key mapping

**Turn it on**: Settings → "Key mapping" → enable the master switch.

**Create a rule**: open "Rules and keys" → "New rule":

1. **Trigger** — tap "Add trigger key", pick the physical key (volume up / volume down) and press type
   (short / long / double / repeat) in the dialog, then confirm. Nothing is added until you confirm.
2. **Action** — tap "Add action", pick the action (previous / next / play / pause / play-pause /
   pass through).
3. **Conditions** (optional) — add state conditions such as "app is playing" or "screen locked";
   multiple conditions can be joined with AND / OR.
4. Name it, set the priority, save.

Each row in the list shows a switch on the right. **Long-press the row** for a menu: duplicate / delete.
Deleting asks for confirmation.

**Verify**: go back to the home screen and press the key. For long press, hold for at least 500ms —
`adb shell input keyevent --longpress` only holds 300ms on some ROMs, which is not enough to trigger.

**Two hidden things**:

- Tap the footer line `HyperHand x.y.z · Key mapping` at the bottom of the list **5 times**
  (less than 0.8s apart) to open **Status** — hook state, rule count, latest decision.
- If you leave the editor with unsaved changes, it asks "Discard changes?".

Matching: for the same key, rules are tried from highest priority down; the first one whose conditions
all pass wins.

---

## 4. Launcher menu slide-select

**How**: long-press an icon on the home screen → keep your finger down → drag onto the target menu
item → release to run it.

- It uses the native long-press menu; the module only takes over the "finger moving → release" part.
- The angle threshold is generous by default, so diagonal drags still select. Turn off
  "Enable slide-select" in Settings if you don't want it.
- Restart the Launcher after changing it (`adb shell am crash com.miui.home`).

---

## 5. Other settings

| Where | What it does |
|---|---|
| Personalization → Language | Follow system / 简体中文 (default) / 繁體中文 / English; the UI refreshes immediately |
| Show advanced parameters | Expands thresholds, trigger zone, appearance and algorithm constants |
| Restore defaults | Resets everything to the "Natural" preset; explicit values you set before are cleared too |
| Restart scopes | Re-injects the hooks; requires root. Offers system framework, System UI and launcher |
| About → Debug | Shows the last gesture's distance / velocity / confidence and why it was rejected |

---

## 6. FAQ

**Q: Installed, but nothing happens.**
Check in order: ① does the framework support libxposed API 102; ② is the module enabled;
③ did you `am crash` System UI; ④ does `adb logcat -s HyperHand` show `attached to SystemUI`.

**Q: I wanted one-handed mode but got the notification centre.**
Most likely the Launcher process wasn't restarted, so the gate isn't active. Run
`adb shell am crash com.miui.home`.

**Q: Slow drags trigger it too / I want it harder to trigger.**
Switch to "Anti-mistouch", or raise the minimum confidence to 0.62–0.70 and the minimum distance to
16–22dp under "Show advanced parameters". To disable the slow-drag path only, turn that switch off.

**Q: The volume-key mapping doesn't work.**
Open Status first: is the hook active, is the rule count right? Then check the rule's conditions —
e.g. "selected app is playing" won't pass if that app isn't playing.

**Q: Does it break swipe-up-to-home, recents, or the back gesture?**
No. The module only observes touch events, never consumes them, and never changes any window's
touchable region.

More in the [README FAQ](../README.md#常见问题) (Chinese).

---

## 7. Reverting / uninstalling

- **Temporarily disable**: turn off the relevant master switch (one-handed / key mapping / slide-select).
  System behaviour comes back immediately.
- **Reset everything**: Settings → "Restore defaults", or disable the module in your framework and reboot.
- **Uninstall**: disable the module first, then uninstall the APK. Leftover `Settings.Global` keys
  (prefix `oh_tuner_`) are harmless.

---

## 8. Links

- [README](../README.md) — full feature tour, gesture algorithm, parameter table, build steps
- [CHANGELOG](../CHANGELOG.md) — release notes
- [MIT License](../LICENSE)
- [Issues](https://github.com/zPan2327/HyperHand/issues)
