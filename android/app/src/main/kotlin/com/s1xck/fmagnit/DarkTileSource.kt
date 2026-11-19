package com.s1xck.fmagnit

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.MapView

/**
 * Тайлы OSM без ключа. Имя "Mapnik" совпадает со стандартным источником
 * osmdroid, поэтому старый оффлайн-кэш регионов переиспользуется.
 * Тёмная тема делается на отрисовке (DarkTileProvider), а не тайлами:
 * в кэш и оффлайн ложатся обычные OSM-тайлы.
 */
object DarkTileSource : OnlineTileSourceBase(
    "Mapnik", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/")
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
}

/**
 * Прозрачный для кэша провайдер: отдаёт тайлы, перекрашенные
 * в моно-тёмную подложку (grayscale + инверсия). Подписи становятся белыми.
 */
class DarkTileProvider(ctx: Context) : MapTileProviderBasic(ctx, DarkTileSource) {

    private val res = ctx.applicationContext.resources
    private val cache = LruCache<Long, Bitmap>(32)

    override fun getMapTile(pMapTileIndex: Long): Drawable? {
        val d = super.getMapTile(pMapTileIndex) ?: return null
        if (d !is BitmapDrawable) return d
        val src = d.bitmap ?: return d

        synchronized(cache) {
            cache.get(pMapTileIndex)?.let { return BitmapDrawable(res, it) }
        }
        val out = darkCopy(src) ?: return d
        synchronized(cache) { cache.put(pMapTileIndex, out) }
        return BitmapDrawable(res, out)
    }

    private fun darkCopy(src: Bitmap): Bitmap? {
        return try {
            val w = src.width
            val h = src.height
            if (w <= 0 || h <= 0) return null
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            for (i in px.indices) {
                val c = px[i]
                val a = (c ushr 24) and 0xFF
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                val lum = 255 - (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
                px[i] = (a shl 24) or (lum shl 16) or (lum shl 8) or lum
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        fun createMapView(ctx: Context): MapView {
            val map = MapView(ctx, DarkTileProvider(ctx.applicationContext))
            try {
                map.setBackgroundColor(0xFF0D0D0D.toInt())
                val to = map.overlayManager.tilesOverlay
                try {
                    val m = to.javaClass.getMethod(
                        "setLoadingBackgroundColor",
                        Int::class.javaPrimitiveType
                    )
                    m.invoke(to, 0xFF0D0D0D.toInt())
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            return map
        }
    }
}