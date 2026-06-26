package com.example.northstar.dash.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.example.northstar.dash.nav.GeoPoint

/**
 * Draws the navigation frame for the Tripper Dash (526 × 300).
 *
 * Layers: OSM tiles → route polyline → destination pin → rider marker → ETA pill →
 * GPS-health pill → notification banner. Paint/Path/Rect objects are reused across
 * frames to avoid per-frame allocation churn.
 */
class MapRenderer(private val tiles: TileProvider) {

    data class Frame(
        val centerLat: Double,
        val centerLng: Double,
        val zoom: Int,
        val panX: Float = 0f,
        val panY: Float = 0f,
        val headingUp: Boolean = false,
        val heading: Float = 0f,
        val riderLat: Double? = null,
        val riderLng: Double? = null,
        val destLat: Double? = null,
        val destLng: Double? = null,
        val destName: String? = null,
        val route: List<GeoPoint> = emptyList(),
        val maneuverText: String? = null,
        val remainingText: String? = null,
        val tilt3d: Boolean = false,
        val etaPrimary: String? = null,
        val etaSecondary: String? = null,
        val gpsWeak: Boolean = false,
        val gpsLost: Boolean = false,
    )

    private val bgColor   = Color.rgb(229, 227, 223)
    private val routeBlue = Color.rgb(66, 133, 244)
    private val googleRed = Color.rgb(234, 67, 53)

    private val tilePaint  = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(1.2f) })
    }
    private val routeCasing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
        strokeWidth = 11f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = routeBlue; style = Paint.Style.STROKE
        strokeWidth = 6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val dotPaint     = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f; isFakeBoldText = true }
    private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = routeBlue; textSize = 19f; isFakeBoldText = true }
    private val bannerPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(215, 13, 15, 17) }
    private val standbyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 64, 67); textSize = 22f; isFakeBoldText = true }

    private val etaBgPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(232, 20, 22, 26) }
    private val etaBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 255, 255, 255); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val etaBigPaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(126, 217, 87); textSize = 20f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }
    private val etaSmallPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(196, 201, 208); textSize = 12f; textAlign = Paint.Align.CENTER }
    private val gpsPillText    = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f; isFakeBoldText = true; textAlign = Paint.Align.CENTER }

    // Faixa de notificação (texto desenhado sobre o mapa — o painel não tem widget nativo pra isso)
    private val notifTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 19f; isFakeBoldText = true }
    private val notifTextPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(210, 214, 220); textSize = 16f }

    private val routePath = Path()
    private val riderPath = Path()
    private val tmpRect = RectF()
    private val pillRect = RectF()
    private val tileSrc = Rect()
    private val childDst = RectF()
    private val textBounds = Rect()
    private val tiltMatrix = Matrix()
    private val tiltSrc = FloatArray(8)
    private val tiltDst = FloatArray(8)

    fun draw(canvas: Canvas, f: Frame) {
        val w = canvas.width
        val h = canvas.height
        canvas.drawColor(bgColor)

        val rotate = f.headingUp
        val tilt = rotate && f.tilt3d
        val pivotY = if (rotate) (if (tilt) h * 0.74f else h * 0.66f) else h / 2f

        val ts = Mercator.TILE_SIZE
        val cx = Mercator.lngToTileX(f.centerLng, f.zoom) * ts + f.panX
        val cy = Mercator.latToTileY(f.centerLat, f.zoom) * ts + f.panY
        val left = cx - w / 2.0
        val top  = cy - pivotY

        fun sx(lng: Double) = (Mercator.lngToTileX(lng, f.zoom) * ts - left).toFloat()
        fun sy(lat: Double) = (Mercator.latToTileY(lat, f.zoom) * ts - top).toFloat()

        if (rotate) {
            canvas.save()
            if (tilt) {
                val inset = w * 0.18f
                tiltSrc[0] = 0f;          tiltSrc[1] = 0f
                tiltSrc[2] = w.toFloat(); tiltSrc[3] = 0f
                tiltSrc[4] = w.toFloat(); tiltSrc[5] = h.toFloat()
                tiltSrc[6] = 0f;          tiltSrc[7] = h.toFloat()
                tiltDst[0] = inset;          tiltDst[1] = 0f
                tiltDst[2] = w - inset;      tiltDst[3] = 0f
                tiltDst[4] = w.toFloat();    tiltDst[5] = h.toFloat()
                tiltDst[6] = 0f;             tiltDst[7] = h.toFloat()
                tiltMatrix.setPolyToPoly(tiltSrc, 0, tiltDst, 0, 4)
                canvas.concat(tiltMatrix)
            }
            canvas.rotate(-f.heading, w / 2f, pivotY)
        }

        // ── Tiles (padded when rotating so corners are covered) ──
        val pad = if (rotate) (maxOf(w, h) * 0.45).toInt() else 0
        val txMin = Math.floorDiv((left - pad).toInt(), ts)
        val tyMin = Math.floorDiv((top - pad).toInt(), ts)
        val txMax = Math.floorDiv((left + w + pad).toInt(), ts)
        val tyMax = Math.floorDiv((top + h + pad).toInt(), ts)
        for (tx in txMin..txMax) for (ty in tyMin..tyMax) {
            val dstL = (tx * ts - left).toFloat()
            val dstT = (ty * ts - top).toFloat()
            tmpRect.set(dstL, dstT, dstL + ts, dstT + ts)
            drawTileBestEffort(canvas, f.zoom, tx, ty, tmpRect)
        }

        // ── Road route polyline ──
        if (f.route.size >= 2) {
            routePath.reset()
            routePath.moveTo(sx(f.route[0].lng), sy(f.route[0].lat))
            for (i in 1 until f.route.size) routePath.lineTo(sx(f.route[i].lng), sy(f.route[i].lat))
            canvas.drawPath(routePath, routeCasing)
            canvas.drawPath(routePath, routePaint)
        }

        // ── Destination pin ──
        if (f.destLat != null && f.destLng != null) {
            val dx = sx(f.destLng); val dy = sy(f.destLat)
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 12f, dotPaint)
            dotPaint.color = googleRed; canvas.drawCircle(dx, dy, 9f, dotPaint)
            dotPaint.color = Color.WHITE; canvas.drawCircle(dx, dy, 3.5f, dotPaint)
        }

        // ── Rider marker — colour tracks GPS health ──
        if (f.riderLat != null && f.riderLng != null) {
            val rx = sx(f.riderLng); val ry = sy(f.riderLat)
            val markerColor = when {
                f.gpsLost -> Color.rgb(150, 154, 160)
                f.gpsWeak -> Color.rgb(251, 188, 5)
                else      -> routeBlue
            }
            val haloColor = Color.argb(60, Color.red(markerColor), Color.green(markerColor), Color.blue(markerColor))
            dotPaint.color = haloColor; canvas.drawCircle(rx, ry, 17f, dotPaint)
            if (rotate) {
                riderPath.reset()
                riderPath.moveTo(rx, ry - 11f)
                riderPath.lineTo(rx - 7f, ry + 7f)
                riderPath.lineTo(rx + 7f, ry + 7f)
                riderPath.close()
                canvas.save(); canvas.rotate(f.heading, rx, ry)
                dotPaint.color = Color.WHITE; canvas.drawCircle(rx, ry, 9f, dotPaint)
                dotPaint.color = markerColor; canvas.drawPath(riderPath, dotPaint)
                canvas.restore()
            } else {
                dotPaint.color = Color.WHITE; canvas.drawCircle(rx, ry, 8f, dotPaint)
                dotPaint.color = markerColor; canvas.drawCircle(rx, ry, 5.5f, dotPaint)
            }
        }

        if (rotate) canvas.restore()

        // ── ETA pill (screen-space, bottom-centre safe zone) ──
        f.etaPrimary?.let { primary ->
            val secondary = f.etaSecondary
            val padH = 18f; val padV = 8f; val gap = 1f
            val bigFm = etaBigPaint.fontMetrics
            val smallFm = etaSmallPaint.fontMetrics
            val bigH = bigFm.descent - bigFm.ascent
            val smallH = if (secondary != null) smallFm.descent - smallFm.ascent else 0f
            val contentW = maxOf(etaBigPaint.measureText(primary), secondary?.let { etaSmallPaint.measureText(it) } ?: 0f)
            val pillW = (contentW + padH * 2).coerceAtMost(w * 0.6f)
            val pillH = padV * 2 + bigH + (if (secondary != null) gap + smallH else 0f)
            val cxp = w / 2f
            val bottom = h - 26f
            val top = bottom - pillH
            pillRect.set(cxp - pillW / 2f, top, cxp + pillW / 2f, bottom)
            val r = pillH / 2f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            var baseline = top + padV - bigFm.ascent
            canvas.drawText(primary, cxp, baseline, etaBigPaint)
            if (secondary != null) {
                baseline += bigFm.descent + gap - smallFm.ascent
                canvas.drawText(secondary, cxp, baseline, etaSmallPaint)
            }
        }

        // ── GPS-health pill (top-centre safe zone) — only when degraded ──
        if (f.gpsLost || f.gpsWeak) {
            val label = if (f.gpsLost) "GPS lost" else "GPS weak"
            gpsPillText.color = if (f.gpsLost) googleRed else Color.rgb(251, 188, 5)
            val padH = 14f; val padV = 6f
            val fm = gpsPillText.fontMetrics
            val textH = fm.descent - fm.ascent
            val pillW = gpsPillText.measureText(label) + padH * 2
            val cxp = w / 2f
            val top = 14f
            val bottom = top + padV * 2 + textH
            pillRect.set(cxp - pillW / 2f, top, cxp + pillW / 2f, bottom)
            val r = (bottom - top) / 2f
            canvas.drawRoundRect(pillRect, r, r, etaBgPaint)
            canvas.drawRoundRect(pillRect, r, r, etaBorderPaint)
            canvas.drawText(label, cxp, top + padV - fm.ascent, gpsPillText)
        }

        // ── Faixa de notificação (some sozinha após DISPLAY_MS; desenhada por cima do mapa) ──
        com.example.northstar.media.NotificationInfoProvider.current.value?.let { notif ->
            val age = System.currentTimeMillis() - notif.postedAt
            if (age in 0..com.example.northstar.media.NotificationInfoProvider.DISPLAY_MS) {
                val padH = 16f; val padV = 11f; val gap = 3f
                val boxW = w * 0.60f
                val innerW = boxW - padH * 2
                val l1 = ellipsize(
                    if (notif.title.isNotBlank()) "${notif.app} · ${notif.title}" else notif.app,
                    notifTitlePaint, innerW
                )
                val l2 = ellipsize(notif.text, notifTextPaint, innerW)
                val fm1 = notifTitlePaint.fontMetrics; val fm2 = notifTextPaint.fontMetrics
                val h1 = fm1.descent - fm1.ascent
                val h2 = if (l2.isBlank()) 0f else (fm2.descent - fm2.ascent) + gap
                val boxH = padV * 2 + h1 + h2
                val leftX = (w - boxW) / 2f
                val topY = h / 2f - boxH / 2f
                pillRect.set(leftX, topY, leftX + boxW, topY + boxH)
                canvas.drawRoundRect(pillRect, 16f, 16f, etaBgPaint)
                canvas.drawRoundRect(pillRect, 16f, 16f, etaBorderPaint)
                var baseline = topY + padV - fm1.ascent
                canvas.drawText(l1, leftX + padH, baseline, notifTitlePaint)
                if (l2.isNotBlank()) {
                    baseline += fm1.descent + gap - fm2.ascent
                    canvas.drawText(l2, leftX + padH, baseline, notifTextPaint)
                }
            }
        }

        // ── Standby when nothing to show ──
        if (f.riderLat == null && f.destLat == null) {
            val msg = "NORTHSTAR · waiting for GPS"
            standbyPaint.getTextBounds(msg, 0, msg.length, textBounds)
            canvas.drawText(msg, (w - textBounds.width()) / 2f, h / 2f, standbyPaint)
        }
    }

    /** Corta o texto com "…" pra caber na largura disponível. */
    private fun ellipsize(s: String, p: Paint, maxW: Float): String {
        if (s.isBlank() || p.measureText(s) <= maxW) return s
        var end = s.length
        while (end > 0 && p.measureText(s.substring(0, end) + "…") > maxW) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    private fun drawTileBestEffort(canvas: Canvas, z: Int, tx: Int, ty: Int, dst: RectF) {
        tiles.get(z, tx, ty)?.let { canvas.drawBitmap(it, null, dst, tilePaint); return }

        var up = 1
        while (up <= 4 && z - up >= 0) {
            val anc = tiles.getCached(z - up, tx shr up, ty shr up)
            if (anc != null) {
                val cells = 1 shl up
                val sub = anc.width / cells
                val sxOff = (tx and (cells - 1)) * sub
                val syOff = (ty and (cells - 1)) * sub
                tileSrc.set(sxOff, syOff, sxOff + sub, syOff + sub)
                canvas.drawBitmap(anc, tileSrc, dst, tilePaint)
                return
            }
            up++
        }

        val halfW = (dst.right - dst.left) / 2f
        val halfH = (dst.bottom - dst.top) / 2f
        for (qx in 0..1) for (qy in 0..1) {
            val child = tiles.getCached(z + 1, tx * 2 + qx, ty * 2 + qy) ?: continue
            childDst.set(
                dst.left + qx * halfW, dst.top + qy * halfH,
                dst.left + qx * halfW + halfW, dst.top + qy * halfH + halfH,
            )
            canvas.drawBitmap(child, null, childDst, tilePaint)
        }
    }
}
