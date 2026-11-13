package app.quotadeck

import org.junit.Assert.*
import org.junit.Test

class SwipeSiteCardTest {
 @Test fun leftDragIsBoundedToActionWidthAndRightDragCannotMovePastClosed() {
  assertEquals(-96f,clampedSiteSwipe(0f,-300f,96f),0f)
  assertEquals(0f,clampedSiteSwipe(-40f,100f,96f),0f)
  assertEquals(-60f,clampedSiteSwipe(-40f,-20f,96f),0f)
 }
 @Test fun shortAccidentalSwipeClosesAndDeliberateSwipeReveals() {
  assertFalse(siteSwipeRevealed(-47f,96f))
  assertTrue(siteSwipeRevealed(-48f,96f))
  assertTrue(siteSwipeRevealed(-96f,96f))
 }
 @Test fun rightSwipeCanCloseAlreadyRevealedAction() {
  val offset=clampedSiteSwipe(-96f,70f,96f)
  assertFalse(siteSwipeRevealed(offset,96f))
 }
}
