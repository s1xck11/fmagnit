package com.s1xck.fmagnit

import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.MapTileIndex

/**
 * Тёмная подложка CARTO Dark Matter (без ключа).
 * Attribution обязателен и показывается на карте постоянно.
 * Используется и в оверлей-карте, и в оффлайн-загрузке.
 */
object DarkTileSource : OnlineTileSourceBase(
    "CARTO_DARK", 0, 20, 256, ".png",
    arrayOf(
        "https://a.basemaps.cartocdn.com/dark_all/",
        "https://b.basemaps.cartocdn.com/dark_all/",
        "https://c.basemaps.cartocdn.com/dark_all/"
    )
) {
    override fun getTileURLString(pMapTileIndex: Long): String =
        baseUrl + MapTileIndex.getZoom(pMapTileIndex) + "/" +
                MapTileIndex.getX(pMapTileIndex) + "/" +
                MapTileIndex.getY(pMapTileIndex) + mImageFilenameEnding
}