# Save Deleted Messages — RE Findings (Instagram v426.0.0.37.68)

Reverse-engineering notes for the `saveDeletedMessagesPatch`
(`patches/.../instagram/misc/dm/saveMessages/`). Captured by decompiling
`Instagram-v426.0.0.37.68-patches-v3.4.0.apk` with baksmali 2.5.2.

> **Status:** the patch currently fails at patch-apply time. Root cause is
> documented below. Functionally-correct hooks still require **on-device
> ObjectBrowser verification** (see CLAUDE.md "RE Expert Workflow" step 3) —
> static analysis alone cannot confirm obfuscated field names or the register
> that holds each target object.

---

## TL;DR — why the patch crashes

`DirectMessageItemRemovedFingerprint` matches on `strings = listOf("item_removed")`.
**That string does not exist anywhere in IG v426** (verified by grepping all 19
`classes*.dex`). A `Fingerprint` with an impossible string can never match, so
Morphe throws:

```
app.morphe.patcher.patch.PatchException: Failed to match the fingerprint:
  ...saveMessages.DirectMessageItemRemovedFingerprint
  at ...SaveDeletedMessagesPatch.kt:54
```

A single unmatched fingerprint aborts the **entire** patch session (all other
patches included), which is why the user could not patch at all.

---

## Method to reproduce / re-investigate

```bash
# 1. Pull baksmali (only org.smali:baksmali 2.5.2 is on a public mirror)
curl -sL -o baksmali.jar \
  https://bitbucket.org/JesusFreke/smali/downloads/baksmali-2.5.2.jar

# 2. Extract dex from any (even already-patched) Instagram APK — the original
#    obfuscated IG code is still present, our new hooks target unmodified methods.
unzip -o IG.apk 'classes*.dex' -d dex/

# 3. Grep raw dex for candidate strings BEFORE writing a fingerprint
grep -lao "item_removed" dex/*.dex      # <- returns nothing in v426
grep -hao "[A-Za-z_]*[Uu]nsend[A-Za-z_]*" dex/*.dex | sort | uniq -c | sort -rn

# 4. Only disassemble the dex you need
java -jar baksmali.jar d dex/classes6.dex -o smali6
```

**Key lesson:** `./gradlew buildAndroid` only validates that patches *compile*.
It does **not** load a target APK, so it never catches a fingerprint that fails
to match at runtime. Always grep the target dex for every string a fingerprint
relies on.

---

## Per-hook findings

### Hook 1 — capture incoming message (`onMessageReceived`)

- Current fingerprint: `strings = listOf("item_id","user_id","item_type")`,
  `returnType = "Ljava/lang/Object;"`.
- These three strings **co-occur in 12 of 19 dex files** → the fingerprint is
  far too generic and almost certainly matches the wrong method (or is
  ambiguous). It happened to resolve to *something*, so it didn't crash, but it
  is not verified to be the DM item parser.
- `onMessageReceived` appears as a substring in dex, but every hit is a
  false positive (e.g. `loadLiteSessionMessageReceived`,
  `DirectMessageSQLiteTable`). There is **no** clean `onMessageReceived`
  method anchor.
- **Next step:** find the real DirectItem deserializer. Candidate anchor classes
  present in v426: `DirectMessageSQLiteTable`, `DirectMessageLoader`,
  `DirectItemEnvironmentFactory`, `DirectItemEnvironment`. Confirm the parsed
  object's runtime type + field names with ObjectBrowser.

### Hook 2 — detect deletion (BROKEN)

- Current fingerprint string `"item_removed"` **does not exist** in v426. Remove it.
- The only real unsend anchor found is the class
  **`DirectUnsendMessageInteractor`** → obfuscated to **`LX/YvN;`** in
  `classes6.dex` (full smali saved under `smali/`).
  - It is logged as a literal `const-string "DirectUnsendMessageInteractor"`
    inside `LX/YvN;->A03(...)Z` (line ~269 of the saved smali).
  - **Caveat:** `YvN` handles the **local user unsending their own message**
    (the compose UI action). It does **not** observe *incoming* unsends from the
    other participant — which is the actually-useful "saved deleted DM" signal.
  - Relevant method signatures in `LX/YvN;`:
    ```
    A01(LX/YvN;Ljava/lang/String;)V   # message_id + "remove_message_successful"
    A02(LX/YvN;Ljava/lang/String;)V   # applies the removal locally
    ADy(MessageIdentifier;J)V         # "remove_message_initiation"
    Gqo(View;..MessageIdentifier;..)V # "unsend_message_attempt"
    Gqp(MessageIdentifier;)V
    ```
- The **remote-unsend / realtime removal** path goes through the obfuscated
  thread store (`DirectThreadStoreImpl`, also obfuscated — no method-name
  anchor). `removeMessages` / `deleteMessages` exist only as *strings*, not as
  method names, so there is **no static anchor** for the remote path. This hook
  genuinely needs live RE (ObjectBrowser on the realtime event handler).

### Hook 3 — compose-bar button (`onTextChanged`)

- Current fingerprint matches `onTextChanged(CharSequence,I,I,I)V` by name+params.
  This signature is implemented by **many** `TextWatcher`s app-wide → not unique
  to the DM compose bar. Risk of attaching the button to the wrong field.
- Real compose-bar anchors present in v426: `ComposerBar`, `row_thread_composer`,
  `thread_composer`, `composerController`. Use one of these to pin the correct
  class, then locate its `EditText` field with ObjectBrowser.

---

## String inventory

See `smali/unsend-strings-v426.txt` for the full count of every
`*unsend*` / `*removeMessage*` token found in the dex. Saved alongside the full
disassembly of `DirectUnsendMessageInteractor` (`LX/YvN;`).

---

## Recommended path forward

1. **Make speculative hooks fail-soft** — resolve via `Fingerprint.getMethodOrNull`
   and skip the injection when null, so a missing/obfuscation-shifted target
   degrades the feature gracefully instead of aborting the whole patch session.
   (Morphe exposes `getMethodOrNull(context)` / `getOriginalMethodOrNull`.)
2. **Verify on device** per CLAUDE.md workflow: run the dev build, ObjectBrowser
   the DM-parse object, the unsend handler, and the compose-bar TextWatcher, and
   record the obfuscated field names + register positions here.
3. Replace each generic fingerprint with a version anchored on a **unique** v426
   string (validated by `grep -lao` returning exactly one relevant dex).

These obfuscated names (`LX/YvN;`, field tags, etc.) are **version-specific** and
will change on every IG obfuscation run — always re-verify against the target.
