package com.anarky.showtrack.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// Material's defaults are 4/8/12/16/28dp. These are rounder at the small end and, crucially,
// deliberately NOT pill-shaped at the large end: Material's `extraLarge` = 28dp makes a full-width
// 52dp button a lozenge, which is the single most dated-looking thing about a stock Compose form.
val Shapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(18.dp),
        extraLarge = RoundedCornerShape(24.dp),
    )
