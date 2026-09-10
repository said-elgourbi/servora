package com.servora.android.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Servora colour tokens.
 *
 * Every value mirrors the palette in `docs/design/android-design-system.md`, keeping the
 * Material 3 role names the design system already documents. A new colour belongs in the
 * design system first rather than being invented here (`dev.md` §1).
 */

// Primary — buttons, focus, links.
internal val PrimaryLight = Color(0xFF2B5EA7)
internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Color(0xFFC5D9F5)
internal val OnPrimaryContainerLight = Color(0xFF0D2F62)

// Secondary — quiet fills, active navigation.
internal val SecondaryLight = Color(0xFFF0F2F5)
internal val OnSecondaryLight = Color(0xFF394460)
internal val SecondaryContainerLight = Color(0xFFE0E4EC)
internal val OnSecondaryContainerLight = Color(0xFF2E3A5A)

// Tertiary — infrequent accents.
internal val TertiaryLight = Color(0xFF4A6080)
internal val OnTertiaryLight = Color(0xFFFFFFFF)
internal val TertiaryContainerLight = Color(0xFFDAE5F2)
internal val OnTertiaryContainerLight = Color(0xFF1A2E44)

// Canvas and surfaces.
internal val BackgroundLight = Color(0xFFF1F3F8)
internal val OnBackgroundLight = Color(0xFF131720)
internal val SurfaceLight = Color(0xFFFFFFFF)
internal val OnSurfaceLight = Color(0xFF131720)
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
internal val SurfaceContainerLowLight = Color(0xFFF7F8FA)
internal val SurfaceContainerLight = Color(0xFFEEF0F5)
internal val SurfaceContainerHighLight = Color(0xFFE6E9EE)
internal val SurfaceContainerHighestLight = Color(0xFFDFE3E9)
internal val SurfaceVariantLight = Color(0xFFF0F2F5)

// Secondary text — muted labels and supporting copy (`onSurfaceVariant`).
internal val OnSurfaceVariantLight = Color(0xFF52617A)

// Borders.
internal val OutlineLight = Color(0xFFD0D5DD)
internal val OutlineVariantLight = Color(0xFFE4E7EC)

// Error.
internal val ErrorLight = Color(0xFFC62828)
internal val OnErrorLight = Color(0xFFFFFFFF)
internal val ErrorContainerLight = Color(0xFFFFDAD6)
internal val OnErrorContainerLight = Color(0xFF410002)

// Success — not a Material 3 role; exposed through [ServoraStateColors].
internal val SuccessLight = Color(0xFF2E7D32)
internal val OnSuccessLight = Color(0xFFFFFFFF)
internal val SuccessContainerLight = Color(0xFFB7F0B1)
internal val OnSuccessContainerLight = Color(0xFF002204)

// Dark scheme (same roles, dark values from the design system).
internal val PrimaryDark = Color(0xFF6B9BD9)
internal val OnPrimaryDark = Color(0xFF001B3D)
internal val PrimaryContainerDark = Color(0xFF1E3A5F)
internal val OnPrimaryContainerDark = Color(0xFFC5D9F5)

internal val SecondaryDark = Color(0xFF1A1A1A)
internal val OnSecondaryDark = Color(0xFFB8C4D8)
internal val SecondaryContainerDark = Color(0xFF2A2A2A)
internal val OnSecondaryContainerDark = Color(0xFFD0DAE8)

internal val TertiaryDark = Color(0xFF8FAFC8)
internal val OnTertiaryDark = Color(0xFF001525)
internal val TertiaryContainerDark = Color(0xFF1E3044)
internal val OnTertiaryContainerDark = Color(0xFFB8CCE4)

internal val BackgroundDark = Color(0xFF000000)
internal val OnBackgroundDark = Color(0xFFE8EAF0)
internal val SurfaceDark = Color(0xFF0A0A0A)
internal val OnSurfaceDark = Color(0xFFE8EAF0)
internal val SurfaceContainerLowestDark = Color(0xFF050505)
internal val SurfaceContainerLowDark = Color(0xFF0F0F0F)
internal val SurfaceContainerDark = Color(0xFF141414)
internal val SurfaceContainerHighDark = Color(0xFF1A1A1A)
internal val SurfaceContainerHighestDark = Color(0xFF212121)
internal val SurfaceVariantDark = Color(0xFF1A1A1A)
internal val OnSurfaceVariantDark = Color(0xFFA8B4CC)

internal val OutlineDark = Color(0xFF3A3A3A)
internal val OutlineVariantDark = Color(0xFF2A2A2A)

internal val ErrorDark = Color(0xFFFF6B6B)
internal val OnErrorDark = Color(0xFF3D0000)
internal val ErrorContainerDark = Color(0xFF5C1A1A)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)

internal val SuccessDark = Color(0xFF6BD98B)
internal val OnSuccessDark = Color(0xFF003D1A)
internal val SuccessContainerDark = Color(0xFF1A4A2A)
internal val OnSuccessContainerDark = Color(0xFFB7F0B1)