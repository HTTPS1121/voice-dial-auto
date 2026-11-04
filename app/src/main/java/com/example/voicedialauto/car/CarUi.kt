package com.example.voicedialauto.car

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Header
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.graphics.drawable.IconCompat

/** Small helpers so screens can use the Header API on Car API 7+ and the deprecated setters below. */
internal object CarUi {
    fun icon(ctx: CarContext, res: Int, tint: CarColor? = null): CarIcon =
        CarIcon.Builder(IconCompat.createWithResource(ctx, res)).apply { tint?.let { setTint(it) } }.build()

    fun iconAction(ctx: CarContext, res: Int, tint: CarColor? = null, onClick: () -> Unit): Action =
        Action.Builder().setIcon(icon(ctx, res, tint)).setOnClickListener(onClick).build()

    @Suppress("DEPRECATION")
    fun MessageTemplate.Builder.header(ctx: CarContext, title: String, endAction: Action?): MessageTemplate.Builder =
        if (ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
            setHeader(Header.Builder().setTitle(title).apply { endAction?.let { addEndHeaderAction(it) } }.build())
        } else {
            setTitle(title).apply { endAction?.let { setActionStrip(ActionStrip.Builder().addAction(it).build()) } }
        }

    @Suppress("DEPRECATION")
    fun ListTemplate.Builder.header(ctx: CarContext, title: String, endAction: Action?): ListTemplate.Builder =
        if (ctx.carAppApiLevel >= CarAppApiLevels.LEVEL_7) {
            setHeader(
                Header.Builder().setTitle(title).setStartHeaderAction(Action.BACK)
                    .apply { endAction?.let { addEndHeaderAction(it) } }.build()
            )
        } else {
            setTitle(title).setHeaderAction(Action.BACK)
                .apply { endAction?.let { setActionStrip(ActionStrip.Builder().addAction(it).build()) } }
        }
}
