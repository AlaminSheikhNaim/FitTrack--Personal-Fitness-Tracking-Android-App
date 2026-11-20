package com.example.fittrack

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas

// Light mode: every screen is drawn through a "negative with the hue kept" filter, so dark cards
// become light cards and white text becomes dark text while green stays green, red stays red.
// Applying the same filter twice gives back the original, which is used to keep photos natural.
private val LIGHT_FILTER = ColorFilter.colorMatrix(
    ColorMatrix(
        floatArrayOf(
            0.574f, -1.43f, -0.144f, 0f, 255f,
            -0.426f, -0.43f, -0.144f, 0f, 255f,
            -0.426f, -1.43f, 0.856f, 0f, 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
)

fun Modifier.lightFilter(on: Boolean): Modifier =
    if (!on) this else this.drawWithContent {
        drawIntoCanvas { canvas ->
            val paint = Paint().apply { colorFilter = LIGHT_FILTER }
            canvas.saveLayer(Rect(0f, 0f, size.width, size.height), paint)
            drawContent()
            canvas.restore()
        }
    }
