package com.calmapps.calmmusic.ui

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.ui.Modifier
import androidx.compose.ui.node.DelegatableNode

/**
 * Standard Material ripple is an animated pulse -- visible motion that
 * ghosts on e-ink. MMD's own ButtonMMD is already ripple-free (it uses a
 * dedicated NoRippleInteractionSource internally), but MMD has no
 * IconButtonMMD, and most raw `.clickable(...)` call sites in this app
 * don't set `indication = null` explicitly. Providing this app-wide via
 * `CompositionLocalProvider(LocalIndication provides NoRippleIndication)`
 * (see MainActivity's CalmMusic() and SystemOverlayService) covers those
 * gaps without touching every call site individually.
 */
object NoRippleIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode {
        return object : Modifier.Node() {}
    }

    override fun equals(other: Any?): Boolean = other === this
    override fun hashCode(): Int = System.identityHashCode(this)
}
