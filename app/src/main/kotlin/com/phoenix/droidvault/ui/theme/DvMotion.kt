package com.phoenix.droidvault.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/**
 * Motion tokens — how long things take, and what shape the motion has.
 *
 * **There were fourteen literal durations in the UI and they were not fourteen
 * decisions.** `120, 130, 140, 150, 160, 180, 200, 220, 250, 350, 400, 800, 900,
 * 1200` — fifty-six `tween(NNN)` call sites, and the neighbours inside the first
 * cluster are not distinguishable by anyone, including the person who typed them.
 * 130 against 150 is a difference nobody has ever perceived and everybody can
 * feel as *sloppiness*: it reads as a codebase where each screen was tuned
 * separately and none of the tunings were reconciled.
 *
 * Five tokens, named for the **job** rather than the number, because a number is
 * what you already had. Each is the value the cluster's members were nearest to,
 * and the reconciliation was measured rather than asserted: of 55 paired call
 * sites, 21 keep their duration exactly, 33 move by 10-50 ms, and **one** moves
 * by 100 ms — a single ambient loop, from [pulse]'s 800 to its 900. Nothing
 * gains or loses a feel it was not asked to gain or lose. Changing the feel is a
 * later phase's job; naming what already happens is this one's job, and the two
 * are kept apart on purpose — a motion pass that quietly retunes every animation
 * is a pass nobody can review, because the before/after diff is the whole app
 * moving.
 *
 * ## Why the loops are their own tokens
 *
 * An ambient animation that *finishes* looks broken, so it must never share a
 * token with something that is allowed to arrive. The three loops in the app had
 * three unrelated periods for the same reason — a logo fading, a progress marker
 * breathing and a spinner turning are not the same gesture — and collapsing them
 * to one value would have made two of the three feel wrong.
 *
 * [pulse] is also the single place the reconciliation cost something real: the
 * shimmer ran at 800 ms and the working indicator at 900, and one token means one
 * of them moves. The shimmer took the 100 ms. That is the exception rather than
 * the rule, and it is deliberate — a breath that stops and restarts on a visibly
 * different rhythm from the one beside it is the thing you notice, and 100 ms on
 * a repeating cycle is below the threshold of noticing on its own.
 *
 * `spin` is **linear on purpose**: a rotation that eases stalls visibly at the
 * top and the bottom of every turn, which is exactly what makes a slow spinner
 * read as a stalled one. The two breathing loops do ease, because a breath that
 * snapped between its extremes looks like a frame drop.
 */
object DvMotion {

    /**
     * A control's own state — a chip tinting, an icon changing, a toggle
     * settling. Fast enough to read as a direct consequence of the finger, which
     * is the whole point: the motion is confirmation, not decoration.
     *
     * Was 120/130/140/150/160.
     */
    const val quick = 150

    /**
     * A surface arriving, swapping or resizing — `AnimatedVisibility`,
     * `AnimatedContent`, `animateContentSize`. Slower than [quick] because these
     * move the reader's eye and a fast one reads as a flicker rather than a
     * change.
     *
     * Was 180/200/220/250.
     */
    const val standard = 200

    /**
     * A large container changing size. [standard] is wrong for these: when a
     * whole card grows or collapses, the content inside it slides too, and the
     * two motions have to arrive together or the card appears to change its mind.
     *
     * Was 350/400.
     */
    const val emphasised = 400

    /**
     * One half-cycle of a breathing loop — a fade, a gentle scale in and out.
     * Used with `RepeatMode.Reverse`, so the full cycle is twice this. It must
     * read as continuous: too fast and it becomes a pulse of attention rather
     * than a sign of life.
     *
     * Was 800/900.
     */
    const val pulse = 900

    /**
     * One full rotation. Linear, and separate from [pulse] because a turn has a
     * natural period a breath does not.
     */
    const val spin = 1200

    /** The standard curve for motion that arrives and stops. */
    val easing: Easing = FastOutSlowInEasing

    /**
     * For motion that *leaves*. Fast at the start, linear into the destination,
     * so it decelerates the whole way and stops without a bounce-back.
     *
     * This one is not a style preference: it is what makes a screen that is
     * sliding away feel released rather than dragged. Three sites used it and
     * they were all "the user has already moved on" — a submenu sliding out and a
     * tab pager being cut to its new page.
     */
    val exitEasing: Easing = FastOutLinearInEasing

    /**
     * For rotation only. An eased rotation decelerates into the top of its turn
     * and accelerates out of the bottom, and that hitch is what a user reads as
     * "it stopped".
     */
    val rotationEasing: Easing = LinearEasing

    // ---- curves, so a duration token and its easing cannot drift apart.

    /** Arrives and stops, the ordinary case. */
    fun <T> settle(durationMillis: Int = standard): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = easing)

    /**
     * Leaves: leaves fast, arrives slow. See [exitEasing] for why this is not
     * interchangeable with [settle].
     */
    fun <T> decelerate(durationMillis: Int = standard): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = exitEasing)

    /** A breath: ease out to the far value and ease back. */
    fun <T> breathe(durationMillis: Int = pulse): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = easing)

    /**
     * One full turn. The only spec here that is not [easing], and the only reason
     * [rotationEasing] exists.
     *
     * Named `rotate()` rather than reusing [spin] so that a call site reads as
     * "this is a rotation" and not as "this is the number 1200".
     *
     * The period is a parameter because the app has two spinners and they were
     * never the same speed — 1200 ms for the refresh arrow, 900 ms for the
     * working indicator. Forcing them equal would be a feel change smuggled in
     * with a rename, so the curve is enforced and the period stays the site's
     * choice.
     */
    fun <T> rotate(durationMillis: Int = spin): TweenSpec<T> =
        tween(durationMillis = durationMillis, easing = rotationEasing)

    /**
     * Springs, which are physics and deliberately have **no** duration — a spring
     * is not a duration plus a curve, and writing it as one is how a
     * "springy" animation ends up feeling like a slow tween.
     *
     * Two shapes existed across seven call sites. They are named because the
     * difference is visible and the names say which one a new site wants:
     */
    object Springs {
        /**
         * Arrives without overshooting. The default for anything a user is
         * reading or tapping: a bounce on a label is a bounce on the words.
         */
        fun <T> settle(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMedium
        )

        /**
         * A small deliberate overshoot, for something physical being placed
         * rather than read. Use sparingly — every extra site that reaches for
         * this is a site where the app is suddenly showing off.
         */
        fun <T> overshoot(): SpringSpec<T> = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    }
}