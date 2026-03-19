# AI Debug Guide For Visual Rendering Issues

This guide is for any AI assistant working on LibreShockwave rendering bugs. It captures a repeatable debugging workflow, the main places to inspect, and real examples that proved useful.

The goal is not to guess. The goal is to measure, isolate, trace, and verify.

## Core Rules

1. Start with a reproducible test and save baseline output.
2. Compare our output to a trusted reference image before changing code.
3. Add narrow, disposable debug logging or dumps only where needed.
4. Trace the exact bitmap or text member that produces the bad pixels.
5. Prefer generic pipeline fixes over sprite-specific or movie-specific patches.
6. Remove temporary diagnostics before committing.
7. Re-run the visual test after every meaningful change.

## Primary Test

Use the navigator screenshot test:

```bash
./gradlew :player-core:runNavigatorSSOTest
```

Artifacts are written to:

```text
player-core/build/navigator-sso/
```

Most useful files:

- `02_our_output.png`
- `05a_nav_region_ours.png`
- `05b_nav_region_ref.png`
- `05_nav_region_diff.png`
- `06_nav_side_by_side.png`
- `sprite_info.txt`

Typical metric line:

```text
Total: 388800 | Identical: 275903 (71.0%) | Close: 8104 (2.1%) | Different: 104793 (27.0%)
```

Record the baseline before changing anything.

## Debugging Workflow

### 1. Identify the symptom precisely

Do not accept vague reports like "it looks wrong". Restate the failure in pixel terms.

Examples:

- missing body text
- wrong position
- grey halo around anti-aliased text
- opaque background where the reference shows transparency
- underline missing

If the user gives a specific RGB value, treat that as a strong clue.

### 2. Compare ours vs reference

Read:

- `05a_nav_region_ours.png`
- `05b_nav_region_ref.png`
- `06_nav_side_by_side.png`

Decide which class of failure it is:

- missing element
- wrong color
- wrong alpha/transparency
- wrong position
- wrong size
- wrong compositing order

### 3. Use `sprite_info.txt`

This file is often the fastest way to narrow the problem to a specific sprite or dynamic bitmap.

Look for:

- channel
- type
- position
- ink
- blend
- baked size
- whether it is dynamic

Patterns that often matter:

- `MATTE`
- `BACKGROUND_TRANSPARENT`
- dynamic bitmaps with suspicious dimensions
- text sprites vs bitmap sprites

### 4. Trace upstream, not just final compositing

The rendering stack is usually:

```text
Lingo script
  -> member/image/text mutation
  -> image.copyPixels / fill / draw
  -> runtime bitmap
  -> SpriteBaker
  -> ink processing
  -> final frame compositing
```

If the wrong pixels already exist in an intermediate bitmap, fixing the final renderer is the wrong layer.

### 5. Add narrow diagnostics

Use `System.err` for temporary logging from JVM tests.

Good places:

- `CastMember.renderTextToImage(...)`
- `ImageMethodDispatcher.copyPixels(...)`
- `SpriteBaker`
- `BitmapCache.getProcessedDynamic(...)`
- ink processors

Only log for the exact dimensions or conditions you care about. Example:

```java
if (dest.getWidth() == 311 && dest.getHeight() == 162) {
    System.err.printf("[DEBUG-CP] srcBmp=%dx%d srcRect=%s destRect=%s ink=%s%n",
            src.getWidth(), src.getHeight(), srcRect, destRect, ink);
}
```

If needed, dump the bitmap to PNG and inspect its raw colors.

### 6. Prefer counting pixels over eyeballing

For color/alpha issues, inspect the actual bitmap contents.

Useful questions:

- Is `RGB 221,221,221` present at all?
- Is it opaque or already transparent?
- Is the bad color in the source bitmap or only after compositing?
- Does the label bitmap itself contain the background, or only the row strip behind it?

Simple scripts using Python or PowerShell pixel counts are often enough.

### 7. Verify the fix at the right layer

After a candidate fix:

1. run focused unit tests if possible
2. run `runNavigatorSSOTest`
3. inspect side-by-side output again
4. confirm the problematic color/alpha state is gone
5. confirm global metrics did not regress badly

### 8. Remove temporary diagnostics

Before finishing:

- remove conditional debug logging
- remove temporary PNG dumps
- remove one-off comments added only for the investigation

## Common Root Causes

### Text height and auto-size

Director text with `boxType = 0` is auto-sized. If code uses the stored rect height instead of actual content height, later layout can place content far below the visible area.

### Grayscale `copyPixels` remap

Director often builds UI by copying grayscale source images with `#color` and `#bgColor`. If this remap is applied blindly, it can:

- flatten already-colored images
- fill transparent text backgrounds with an opaque color
- destroy intended alpha masks

### Matte alpha handling

`MATTE` should remove border-connected matte/background pixels, but it must preserve existing source alpha. If code forces all surviving pixels to opaque, transparent fringe/background pixels become visible blocks.

### Background transparent alpha recovery

`BACKGROUND_TRANSPARENT` on 32-bit anti-aliased buffers often needs alpha recovery from RGB values blended against the background color. Exact color-keying alone is not enough.

## Worked Example 1: Missing Habborella Body Text

### Symptom

The title rendered, but the body text below it was missing.

### Investigation

1. Pixel comparison showed the body text existed in the reference but not in our output.
2. `CastMember.renderTextToImage()` logging showed:
   - title width `163`, height `480`
   - body width `230`, height `0`
3. `ImageMethodDispatcher.copyPixels()` logging showed a composite image where the body text started hundreds of pixels below the top, but the caller only read the top `56` pixels.

### Root cause

The title member had `boxType = 0` but was rendered with a stale stored height instead of auto-sized height. That pushed the body text far down in the composite.

### Fix

For `boxType = 0`, render with `height = 0` so the text renderer auto-sizes to content.

### Lesson

If text is missing, always inspect actual text bitmap dimensions before touching compositing code.

## Worked Example 2: Navigator `Open` Grey Background

### Symptom

The `Open` label had a slightly lighter grey block behind it that should not exist. The user specifically called out `RGB 221,221,221` and later clarified there should be no background behind `Open` at all.

### What did not work

- sprite-specific cleanup
- stage-specific cleanup
- final-frame postprocessing
- assuming the text glyph bitmap itself was bad

Those approaches were rejected or incorrect because the bug needed a generic pipeline fix.

### Investigation path

1. Use `sprite_info.txt` to narrow the area:
   - the visible row content came from dynamic `MATTE`/`BACKGROUND_TRANSPARENT` bitmaps
   - important intermediate sizes were `311x205`, `311x162`, `311x16`, and `33x10`
2. Add conditional logging in `ImageMethodDispatcher.copyPixels()` for those dimensions.
3. Dump and inspect the intermediate bitmaps.
4. Count actual pixel colors and alpha values instead of relying on visual guesses.

### Key findings

#### Finding 1: the `33x10` `Open` bitmap was clean

It was just black glyph pixels on a transparent background.

So the grey box was not in the text source itself.

#### Finding 2: the row builder copied `Open` with:

- `ink = BACKGROUND_TRANSPARENT`
- `bgColor = 0xDDDDDD`

That was the critical clue.

#### Finding 3: our grayscale remap logic in `ImageMethodDispatcher.copyPixels()` treated that as a normal foreground/background remap

That converted a transparent text background into an actual grey-filled bitmap before applying the ink.

In other words, we were manufacturing the grey panel ourselves.

#### Finding 4: `MATTE` handling also had a generic alpha bug

Both matte paths were forcing surviving pixels to opaque:

```java
result[i] = pixels[i] | 0xFF000000;
```

That turns already-transparent or semi-transparent pixels into visible artifacts.

### Generic fixes that worked

#### Fix A: preserve alpha in matte processing

Applied in:

- `player-core/.../InkProcessor.java`
- `sdk/.../Drawing.java`

Border-connected matte pixels still become transparent, but surviving pixels keep their original alpha instead of being forced opaque.

#### Fix B: preserve transparent grayscale text backgrounds during `copyPixels`

Applied in:

- `vm/.../ImageMethodDispatcher.java`

When all of these are true:

- source is grayscale
- source already contains transparency
- `ink == BACKGROUND_TRANSPARENT`
- `#bgColor` is provided
- no `#color` remap is provided

then treat the source as an alpha mask instead of remapping transparent background into an opaque `bgColor` fill.

### Result

After the fix:

- opaque `221`, `222`, `238`, and `239` greys were gone from the navigator crop
- navigator SSO metrics improved materially
- the fix was generic and not tied to navigator-specific channels or coordinates

### Lesson

If a label appears to have a colored box behind it, verify whether:

1. the label bitmap already contains the box
2. the row strip behind it contains the box
3. `copyPixels` remap is fabricating the box from `#bgColor`
4. a later matte step is making transparent pixels visible

Do not assume the visible artifact belongs to the text bitmap itself.

## Useful Commands

Run the navigator visual test:

```bash
./gradlew :player-core:runNavigatorSSOTest
```

Run focused tests:

```bash
./gradlew :player-core:test --tests "com.libreshockwave.player.ScriptModifiedBitmapTest"
./gradlew :player-core:test --tests "com.libreshockwave.player.render.pipeline.InkProcessorTest"
./gradlew :sdk:test --tests "com.libreshockwave.bitmap.DrawingMatteTest"
```

Search for important rendering code:

```bash
rg -n "copyPixels|renderTextToImage|BACKGROUND_TRANSPARENT|MATTE|InkProcessor|SpriteBaker" player-core vm sdk
```

## Reference Documentation

Director references in `/docs/` are authoritative when behavior is unclear:

- `drmx2004_scripting_ref.pdf`
- `drmx2004_getting_started.pdf`

Consult them when deciding how inks, text properties, `copyPixels`, `member.image`, or sprite behavior should work.
