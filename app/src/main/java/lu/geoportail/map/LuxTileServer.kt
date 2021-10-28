package lu.geoportail.map

import android.content.Context
import android.content.res.Resources
import android.database.Cursor
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteDatabase
import com.koushikdutta.async.ByteBufferList
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.HttpServerRequestCallback
import com.koushikdutta.async.http.server.AsyncHttpServerRequest
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.pow
import android.content.res.AssetManager
import android.util.Log
import java.io.IOException


class LuxTileServer(
    private val context: Context,
    private val resources: Resources,
    private val assets: AssetManager
) {
    private lateinit var db: Map<String, SQLiteDatabase>
    private val staticsMap = mapOf(
        "omt_geoportail_lu" to "mbtiles/omt_geoportail_lu.mbtiles",
        "omt_topo_geoportail_lu" to "mbtiles/omt_topo_geoportail_lu.mbtiles",
        "data_omt_geoportail_lu" to "static/data/omt-geoportail-lu.json",
        "data_omt_topo_geoportail_lu" to "static/data/omt-topo-geoportail-lu.json",
        "roadmap_style" to "static/styles/roadmap/style.json",
        "topomap_style" to "static/styles/topomap/style.json",
        "topomap_gray_style" to "static/styles/topomap_gray/style.json",
        "fonts_noto_sans_0_255" to "static/fonts/NotoSansBold/0-255.pbf",
        "fonts_noto_sans_256_511" to "static/fonts/NotoSansBold/256-511.pbf",
        "fonts_noto_regular_0_255" to "static/fonts/NotoSansRegular/0-255.pbf",
        "fonts_noto_regular_256_511" to "static/fonts/NotoSansRegular/256-511.pbf",
        "fonts_noto_regular_8192_8447" to "static/fonts/NotoSansRegular/8192-8447.pbf"
    )
    private val reverseStaticsMap = HashMap<String, String>()
    private val packageName = context.packageName

    private fun copyAssets(assetPath: String, toPathRoot: File) {
        val assetManager: AssetManager = this.assets
        var files: Array<String>? = null
        try {
            files = assetManager.list(assetPath)
        } catch (e: IOException) {
            Log.e("tag", "Failed to get asset file list.", e)
        }
        if(!toPathRoot.exists()) Files.createDirectory(toPathRoot.toPath())
        if (files != null) for (filename in files) {
            val file = File(toPathRoot, filename)
            if (file.exists()) continue

            val sourceFileBytes = assetManager.open("$assetPath/$filename").readBytes()
            FileOutputStream(file).use {
                it.write(sourceFileBytes)
            }
        }
    }

    fun start(filePath: File) {
        copyAssets("offline_tiles/mbtiles", File(filePath, "mbtiles"))
        copyAssets("offline_tiles/styles", File(filePath, "styles"))
        copyAssets("offline_tiles/sprites", File(filePath, "sprites"))

        val server = AsyncHttpServer()
        server["/", HttpServerRequestCallback { _, response -> response.send("Hello!!!") }]
        server.get("/hello") { request, response -> response.send("Hello!!!$request") }
        server.get("/mbtiles", getMbTile)
        server.get("/static/.*", getStaticFile)

        for ((key, value) in staticsMap) {
            reverseStaticsMap[value] = key
        }
        this.db = mapOf(
            "road" to SQLiteDatabase.openDatabase("${filePath}/mbtiles/omt_geoportail_lu.mbtiles", null, SQLiteDatabase.OPEN_READONLY),
            "topo" to SQLiteDatabase.openDatabase("${filePath}/mbtiles/omt_topo_geoportail_lu.mbtiles", null, SQLiteDatabase.OPEN_READONLY)
        )
        // listen on port 8766
        server.listen(8766)
        // browsing http://localhost:5001 will return Hello!!!
    }
    private val getStaticFile =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            val resourceName = reverseStaticsMap[request.path.substring(1)]
            if (resourceName == null) {
                response.code(404)
                response.send("")
                return@HttpServerRequestCallback
            }
            response.headers.add("Access-Control-Allow-Origin","*")
            val resourceBytes = resources.openRawResource(
                context.resources.getIdentifier(resourceName, "raw", packageName)
            ).readBytes()
            response.headers.add("Content-Length", resourceBytes.size.toString())
            response.write(ByteBufferList(resourceBytes))
        }

    private val getMbTile =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            var layer = "road"
            if (request.query.getString("layer") == "topo") layer = "topo"
            val z: Int = request.query.getString("z").toInt()
            val x: Int = request.query.getString("x").toInt()
            var y: Int = request.query.getString("y").toInt()

            response.headers.add("Access-Control-Allow-Origin","*")

            // MBTiles by default use TMS for the tiles. Most mapping apps use slippy maps: XYZ schema.
            // We need to handle both.
            y = (if (y > 0) 2.0.pow(z.toDouble()).toInt() - abs(y) - 1 else abs(y))

            val curs = db[layer]?.query(
                "tiles",
                Array(1) { _ -> "tile_data" },
                "zoom_level = $z AND tile_column = $x AND tile_row = $y",
                null,
                null,
                null,
                null
            )
            if (curs is Cursor) {
                try {
                    curs.moveToFirst()
                    if (curs.isAfterLast) {
                        // return 404 if cursor contains no results
                        response.code(404)
                        response.send("")
                        return@HttpServerRequestCallback
                    }
                    val buf = curs.getBlob(0)
                    response.setContentType("application/x-protobuf")
                    response.headers.add("Content-Encoding", "gzip")
                    response.sendStream(ByteArrayInputStream(buf), buf.size.toLong())
                    return@HttpServerRequestCallback
                }
                catch (e: SQLiteBlobTooBigException) {
                    val curs = db[layer]?.rawQuery(
                        "select length(tile_data) from tiles where zoom_level = $z AND tile_column = $x AND tile_row = $y",
                        null
                    )
                    curs?.moveToFirst()
                    // query sqlite DB by 1MB chunks because SQLiteCursor is limited to 2MB
                    // in the current DB, this case only occurs for one tile (x=264&y=174&z=9&layer=topo)
                    val ll = (if (curs != null) curs.getInt(0) else 0)
                    val buf: ByteArray = ByteArray(ll)
                    for (i in 0..ll.div(1000*1000)) {
                        val curs = db[layer]?.rawQuery(
                            "select substr(tile_data, ${1 + i*1000*1000}, ${(i+1)*1000*1000}) from tiles where zoom_level = $z AND tile_column = $x AND tile_row = $y",
                            null
                        )
                        curs?.moveToFirst()
                        curs?.getBlob(0)?.copyInto(buf, i*1000*1000)
                    }
                    response.setContentType("application/x-protobuf")
                    response.headers.add("Content-Encoding", "gzip")
                    response.sendStream(ByteArrayInputStream(buf), buf.size.toLong())
                    return@HttpServerRequestCallback
                }
            }
            response.code(404)
            response.send("")
        }
}
