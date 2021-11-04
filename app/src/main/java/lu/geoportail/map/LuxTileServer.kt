package lu.geoportail.map

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
import kotlin.text.MatchResult
import kotlin.text.Regex


class LuxTileServer(
    private val assets: AssetManager,
    private val filePath: File
) {
    private lateinit var db: Map<String, SQLiteDatabase>

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

    fun start() {
        copyAssets("offline_tiles/mbtiles", File(this.filePath, "mbtiles"))
//        copyAssets("offline_tiles/styles", File(filePath, "styles"))
//        copyAssets("offline_tiles/sprites", File(filePath, "sprites"))

        val server = AsyncHttpServer()
        server["/", HttpServerRequestCallback { _, response -> response.send("Hello!!!") }]
        server.get("/hello") { request, response -> response.send("Hello!!!$request") }
        server.get("/mbtiles", getMbTile)
        server.get("/static/.*", getStaticFile)

        this.db = mapOf(
            "road" to SQLiteDatabase.openDatabase("${this.filePath}/mbtiles/tiles_luxembourg.mbtiles", null, SQLiteDatabase.OPEN_READONLY),
            "topo" to SQLiteDatabase.openDatabase("${this.filePath}/mbtiles/topo_tiles_luxembourg.mbtiles", null, SQLiteDatabase.OPEN_READONLY)
        )
        // listen on port 8766
        server.listen(8766)
    }
    private fun replaceUrls(resBytes: ByteArray, resourcePath: String): ByteArray {
        var resString = String(resBytes)
        if (resourcePath.contains("_style.json")) {
            val files = assets.list("offline_tiles/data")
            // replace in style definition:
            // mbtiles://{xxx} by
            //  - http://127.0.0.1:8766/static/data/xxx.json if xxx is found offline
            //  - https://vectortiles.geoportail.lu/data/xxx.json if xxx is not found offline
            resString = resString.replace(Regex("mbtiles://\\{(.*)\\}")) {
                    mm -> mm.groups[1]?.value?.let {
                        groupValue: CharSequence -> when {
                            files.any {f -> "$groupValue.json" == f } -> "http://127.0.0.1:8766/static/data/$groupValue.json"
                            else -> "https://vectortiles.geoportail.lu/data/$groupValue.json"
                        }
                    }!!
            }
            // serve local static fonts
            resString = resString.replace("\"{fontstack}/{range}.pbf", "\"http://127.0.0.1:8766/static/fonts/{fontstack}/{range}.pbf")
            // remove spaces in fonts path until better solution is found
            resString = resString.replace("Noto Sans Regular", "NotoSansRegular")
            resString = resString.replace("Noto Sans Bold", "NotoSansBold")
        }

        // adapt tile query schema to local server
        if (resourcePath.contains("omt-geoportail-lu.json")) {
            resString = resString.replace(
                "https://vectortiles.geoportail.lu/data/omt-geoportail-lu/{z}/{x}/{y}.pbf",
                "http://localhost:8766/mbtiles?z={z}&x={x}&y={y}"
            )
        }
        if (resourcePath.contains("omt-topo-geoportail-lu.json")) {
            resString = resString.replace(
                "https://vectortiles.geoportail.lu/data/omt-topo-geoportail-lu/{z}/{x}/{y}.pbf",
                "http://localhost:8766/mbtiles?layer=topo&z={z}&x={x}&y={y}"
            )
        }
        return resString.toByteArray()
    }

    private val getStaticFile =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            val resourcePath = request.path.replace("/static/", "")
                // repair paths to roadmap/style.json to roadmap_style.json etc.
                .replace("/style.json", "_style.json")
            try {
                val file = this.assets.open("offline_tiles/$resourcePath")
                var resourceBytes = file.readBytes()
                // rewrite URLs in json data, skip binary data such as fonts
                if (resourcePath.contains(".json")) resourceBytes = replaceUrls(resourceBytes, resourcePath)

                response.headers.add("Access-Control-Allow-Origin","*")
                response.headers.add("Content-Length", resourceBytes.size.toString())
                response.write(ByteBufferList(resourceBytes))
            } catch (e: IOException) {
                response.code(404)
                response.send("")
                return@HttpServerRequestCallback
            }
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
