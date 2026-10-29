---
name: browser-cursor-control
when_to_use: Whenever you click or tap in the browser — choosing between element indices and raw coordinates, aiming precisely, scrolling targets into view, and recovering from missed taps.
---

# Browser Cursor Control

Your "cursor" is virtual: an on-screen marker the user can watch while
you work. Where it lands decides whether the right thing gets clicked.
Aim deliberately.

## The coordinate system

- Coordinates are **CSS pixels in the WebView viewport**: origin (0, 0)
  at the top-left of the visible page area, x growing right, y growing
  down. They are the same coordinates the observation reports for each
  element's centre (`x`, `y`) and the same space screenshots show.
- Element bounds in an observation (`width`, `height`) let you judge
  target size: a 40x40 target is easy; a 12px text link is not.
- Coordinates are viewport-relative, not document-relative: after
  scrolling, the same element sits at different coordinates. Indices
  survive scrolling; raw coordinates do not.

## Indices first, coordinates last

- **Default: `click_element(index)`.** It scrolls the element into view,
  focuses it, and clicks its true centre — no aiming error, and it works
  on elements currently off-screen.
- **Use `tap(x, y)` only** when there is no element to index: canvas,
  image maps, video overlays, or a spot you located in a screenshot.
  Take the coordinates from the element bounds or the screenshot —
  never estimate from memory of "about a third down".

## Before you click: verify the target

1. Read the observation line for the index: tag, label, href. Does it
   describe the thing you mean to press? Labels lie on icon buttons —
   cross-check with position and neighbours.
2. Check the centre rule: clicks land on the element's centre. Avoid
   clicking near an element's edge, where a neighbouring control may
   overlap (dense toolbars, table rows).
3. If two elements share a label ("Delete" on every row), disambiguate
   by the surrounding text excerpt and by order — or do not click and
   ask. A wrong-row click is a real-world harm.

## Scroll into view first

Off-screen elements are marked "(off-screen, will scroll into view)" in
the observation. `click_element` scrolls for you — trust it, then verify
from the fresh observation that the page moved and the action landed.
When you want the element framed before acting (e.g. for a screenshot),
use `scroll_to_element(index)`: it centres the element and re-observes.
For coordinate taps, scroll first (`scroll_down` / `scroll_up` move in
screen-sized chunks and report the new position), observe again, and
only then read coordinates: a tap aimed with pre-scroll coordinates
hits whatever moved into that spot.

## Hover, long-press, drag — the honest limits

- There is **no true hover** on a touch WebView: menus that open on
  mouse-over usually also open on tap — click the parent item by index
  and re-observe for the submenu.
- **Long-press and drag gestures are not tools you have.** Sliders,
  drag-sort lists, and press-and-hold checks cannot be automated
  reliably here: scroll and index-click what you can, and for the rest
  stop and ask the user to Take control. Claiming you long-pressed
  something you cannot long-press is fabrication.

## Precision rules

- One click per intention. Double-clicking a submit or buy control can
  duplicate the action.
- After clicking, always read the result before clicking anything else:
  the click result plus the new observation say what was hit (tag and
  label are echoed back) and where you are now.
- Small targets (pagination numbers, × icons, checkboxes): prefer the
  index route; if only a tap is possible, zoom by scrolling the target
  to mid-viewport first, then tap its exact reported centre.

## When a tap or click misses

1. Do not repeat the same coordinates or index blindly — the state that
   made it miss is still there.
2. Re-observe. Compare: what element is actually at those coordinates /
   under that index now? A banner, popup, or sticky header often
   intercepts the spot.
3. Dismiss the interceptor (its own close element), scroll the target
   to mid-viewport, and retry once by index.
4. Missed twice? Stop guessing: screenshot, record what the page shows,
   and either change strategy or report the blocker to the user.
