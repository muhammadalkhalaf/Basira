package com.basira.app.presentation.theme

import androidx.compose.ui.unit.dp

/** Shared dimensions; touch targets are never below the 48 dp accessibility minimum. */
object Dimens {
    /** Minimum touch target. */
    val MinTouchTarget = 48.dp

    /** Height of secondary action buttons. */
    val ActionHeight = 64.dp

    /** Minimum height of the primary "describe" button. */
    val PrimaryActionHeight = 160.dp

    /** Screen edge padding. */
    val ScreenPadding = 16.dp

    /** Vertical gap between items. */
    val ItemSpacing = 12.dp

    /** Card inner padding. */
    val CardPadding = 16.dp

    /** Outline width used so controls are visible without relying on color. */
    val OutlineWidth = 2.dp
}
