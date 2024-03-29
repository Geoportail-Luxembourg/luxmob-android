package lu.geoportail.map

import android.database.Cursor
import android.database.sqlite.SQLiteBlobTooBigException
import android.database.sqlite.SQLiteDatabase
import com.koushikdutta.async.ByteBufferList
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.HttpServerRequestCallback
import com.koushikdutta.async.http.server.AsyncHttpServerRequest
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.pow
import android.database.sqlite.SQLiteCantOpenDatabaseException
import android.util.Log
import com.koushikdutta.async.http.AsyncHttpDelete
import com.koushikdutta.async.http.AsyncHttpPut
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.NoSuchFileException
import java.net.ConnectException
import java.net.URL
import java.net.UnknownHostException
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.asContextElement
import kotlin.text.Regex

enum class DlState { UNKNOWN, IN_PROGRESS, FAILED, DONE }

class LuxTileServer(
    private val filePath: File
) {
    private var db: MutableMap<String, SQLiteDatabase?> = mutableMapOf()
    private var dlJobs: MutableMap<String, Job?> = mutableMapOf()

    private fun downloadAssetsFromMeta(json: JSONObject, toPathRoot: File) {
        val sources = json.getJSONArray("sources")
        for (i in 0 until sources.length()) {
            downloadAssets(sources.getString(i), toPathRoot)
        }
    }

    private fun downloadAssets(assetUrl: String, toPathRoot: File) {
        val url = URL(assetUrl)
        val filename =url.file
        val file = File(toPathRoot, filename)
        if(!file.parentFile.exists()) Files.createDirectories(file.parentFile.toPath())

        val conn = url.openConnection()
        // conn.setReadTimeout(0)
        val sourceStream = conn.getInputStream()
        val fileOutputStream = FileOutputStream(file)
        val buf = ByteArray(1024)
        var len: Int
        while (sourceStream.read(buf).also { len = it } > 0) {
            fileOutputStream.write(buf, 0, len)
        }
        sourceStream.close()
        fileOutputStream.close()
    }

    private fun deleteAssets(mapName: String, basePath: File) {
        val sourcePaths = getPaths(mapName)
        for (i in 0 until sourcePaths.length()) {
            val url = URL(sourcePaths.getString(i))
            val filename =url.file
            val file = File(basePath, filename)
            file.delete()
        }
    }

    private fun moveAssets(sourcePaths: JSONArray, fromPath: File, toPath: File) {
        for (i in 0 until sourcePaths.length()) {
            val url = URL(sourcePaths.getString(i))
            val filename =url.file
            val toFile = File(toPath, filename)
            if(!toFile.parentFile.exists()) Files.createDirectories(toFile.parentFile.toPath())
            File(fromPath, filename).renameTo(toFile)
        }
    }

    private fun getPaths(ressourceName: String): JSONArray {
        // metadata will be read from local file until it is available online
        return try {
            val url = URL("file:${this.filePath}/dl/versions/$ressourceName.meta")
            val json = JSONObject(url.openStream().use { it.readBytes() }.toString(Charsets.UTF_8))
            val paths = json.getJSONArray("sources") ?: JSONArray()
            paths.put("file:/versions/$ressourceName.meta")
        } catch (e: FileNotFoundException) {
            JSONArray()
        }
    }

    private fun getVer(ressourceName: String): String? {
        return try {
            val url = URL("file:${this.filePath}/dl/versions/$ressourceName.meta")
            val json = JSONObject(url.openStream().use { it.readBytes()}.toString(Charsets.UTF_8))
            json.getString("version")
        }
        catch (e: FileNotFoundException) {
            null
        }
    }

    private fun getSize(ressourceName: String): String? {
        return try {
            val dlPath = when(getStatus(ressourceName)) {
                DlState.IN_PROGRESS -> "tmp"
                else -> "dl"
            }
            val url = URL("file:${this.filePath}/$dlPath/versions/$ressourceName.meta")
            val json = JSONObject(url.openStream().use { it.readBytes()}.toString(Charsets.UTF_8))
            val src = json.getJSONArray("sources")
            var totalSize: Long = 0
            for (i in 0 until src.length()) {
                try {
                    val filename = URL(src.getString(i)).file
                    totalSize += Files.size(
                        File(
                            File(this.filePath, dlPath),
                            filename
                        ).toPath()
                    )
                }
                catch (e: NoSuchFileException) {}
            }
            totalSize.toString()
        }
        catch (e: FileNotFoundException) {
            null
        }
    }

    private fun getStatus(resName: String): DlState {
        val job = dlJobs[resName]
        return when {
            job?.isActive ?: false -> DlState.IN_PROGRESS
            job?.isCancelled ?: false -> DlState.FAILED
            job?.isCompleted ?: false -> DlState.DONE
            else -> DlState.UNKNOWN
        }
    }

    private fun saveMeta(meta: JSONObject, pathName: String) {
        val parentFolder = File(pathName).parentFile
        if(!parentFolder.exists()) Files.createDirectories(parentFolder.toPath())
        val outputStream = FileOutputStream(File(pathName))
        outputStream.use { it.write(meta.toString().toByteArray())}
    }

    private fun getAllMeta(): JSONObject? {
        return try {
            val url = URL("https://vectortiles-sync.geoportail.lu/metadata/resources.meta")
            JSONObject(url.openStream().readBytes().toString(Charsets.UTF_8))
        } catch (e: FileNotFoundException) {
            null
        }
    }

    private fun getMeta(ressourceName: String): JSONObject? {
        return getAllMeta()?.getJSONObject(ressourceName)
    }

    fun start() {

        val server = AsyncHttpServer()
        server["/", HttpServerRequestCallback { _, response -> response.send("Hello!!!") }]
        server.get("/hello") { request, response -> response.send("Hello!!!$request") }
        server.get("/check", checkData)
        // REST methods
        server.addAction(AsyncHttpPut.METHOD, "/map/.*", updateData)
        server.addAction(AsyncHttpDelete.METHOD, "/map/.*", deleteData)
        server.addAction("OPTIONS", "/map/.*", checkPreflight)
        server.addAction(AsyncHttpDelete.METHOD, "/map/.*", deleteData)
        // legacy methods
        server.post("/delete", deleteData)
        server.post("/update", updateData)
        // tile server
        server.get("/mbtiles", getMbTile)
        // static files
        server.get("/static/.*", getStaticFile)

        // listen on port 8766
        server.listen(8766)
    }

    private fun openDB(name: String): SQLiteDatabase? {
        // temporary name mapping until mbtile names are corrected on server
        val new_name = when (name) {
            "omt-geoportail" -> "tiles_luxembourg"
            "omt-topo-geoportail" -> "topo_tiles_luxembourg"
            "topo" -> "topo_tiles_luxembourg"
            else -> name
        }
        return SQLiteDatabase.openDatabase("${this.filePath}/dl/mbtiles/$new_name.mbtiles", null, SQLiteDatabase.OPEN_READONLY)
    }
    private fun replaceUrls(resBytes: ByteArray, resourcePath: String): ByteArray {
        var resString = String(resBytes)
        if (resourcePath.contains("styles/")) {
            val files = File("${this.filePath}/dl/data").listFiles()

            // replace in style definition:
            // mbtiles://{xxx} by
            //  - http://127.0.0.1:8766/static/data/xxx.json if xxx is found offline
            //  - https://vectortiles.geoportail.lu/data/xxx.json if xxx is not found offline
            resString = resString.replace(Regex("mbtiles://\\{(.*)\\}")) {
                    mm -> mm.groups[1]?.value?.let {
                    groupValue: CharSequence -> when {
                files.any {f -> "$groupValue.json" == f.name } -> "http://127.0.0.1:8766/static/data/$groupValue.json"
                else -> "https://vectortiles.geoportail.lu/data/$groupValue.json"
            }
            }!!
            }
            // serve local static fonts
            resString = resString.replace("\"{fontstack}/{range}.pbf", "\"http://127.0.0.1:8766/static/fonts/{fontstack}/{range}.pbf")
            resString = resString.replace("https://sprites.geoportail.lu/sprites", "http://127.0.0.1:8766/static/sprites/sprites")
            // remove spaces in fonts path until better solution is found
            // resString = resString.replace("Noto Sans Regular", "NotoSansRegular")
            // resString = resString.replace("Noto Sans Bold", "NotoSansBold")
        }

        // adapt tile query schema to local server
        if (resourcePath.contains("data/")) {
            val tilesPath = File(this.filePath,"dl/mbtiles")
            val mapName = resourcePath.substringAfterLast("/").replace(".json", "")
            // temporary mapping until files are renamed
            var tilesName = when (mapName) {
            "omt-geoportail" -> "tiles_luxembourg"
            "omt-topo-geoportail" -> "topo_tiles_luxembourg"
            "topo" -> "topo_tiles_luxembourg"
            else -> mapName
        }
            if (File(tilesPath, "$tilesName.mbtiles").exists()) {
                resString = resString.replace(
                    "https://vectortiles.geoportail.lu/data/${mapName}/\\{z\\}/\\{x\\}/\\{y\\}.(pbf|png)".toRegex(),
                    {mm -> "http://localhost:8766/mbtiles?layer=${mapName}&z={z}&x={x}&y={y}&format=${mm.groups[1]?.value}"}
                )
            }
        }
        return resString.toByteArray()
    }

    private val getStaticFile =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            val relativePath = request.path.replace("/static/", "")
            val resourcePath = relativePath
                // repair paths to roadmap/style.json to roadmap_style.json etc.
                .replace("/style.json", ".json")
            response.headers.add("Access-Control-Allow-Origin","*")
            if (resourcePath.contains("data/") || resourcePath.contains("styles/")) {
                response.headers.add("Cache-Control","no-store")
            }
            try {
                val file = File("${this.filePath}/dl", resourcePath)
                var resourceBytes = FileInputStream(file).use{ it.readBytes()}
                // file.close()
                // rewrite URLs in json data, skip binary data such as fonts
                if (resourcePath.contains(".json")) resourceBytes = replaceUrls(resourceBytes, resourcePath)

                response.headers.add("Content-Length", resourceBytes.size.toString())
                response.write(ByteBufferList(resourceBytes))
            } catch (e: IOException) {
                if (relativePath.contains("style.json")) {
                    try {
                        val onlineUrl = URL("https", "vectortiles.geoportail.lu", relativePath)
                        val conn = onlineUrl.openConnection()
                        val sourceStream = conn.getInputStream()
                        val resourceBytes = sourceStream.use{ it.readBytes()}
                        response.headers.add("Content-Length", resourceBytes.size.toString())
                        response.write(ByteBufferList(resourceBytes))
                        return@HttpServerRequestCallback
                    }
                    catch (e: java.lang.Exception) {}
                }
                response.code(404)
                response.send("")
                return@HttpServerRequestCallback
            }
        }

    private val getMbTile =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            var layer = request.query.getString("layer") ?: "omt-geoportail"
            var fileFormat = request.query.getString("format") ?: "pbf"
            val z: Int = request.query.getString("z").toInt()
            val x: Int = request.query.getString("x").toInt()
            var y: Int = request.query.getString("y").toInt()

            response.headers.add("Access-Control-Allow-Origin","*")

            if (!db.containsKey(layer)) {
                try {
                    db[layer] = openDB(layer)
                }
                catch (e: SQLiteCantOpenDatabaseException) {
                    response.code(404)
                    response.send("")
                    return@HttpServerRequestCallback
                }
            }

            // MBTiles by default use TMS for the tiles. Most mapping apps use slippy maps: XYZ schema.
            // We need to handle both.
            y = (if (y > 0) 2.0.pow(z.toDouble()).toInt() - abs(y) - 1 else abs(y))

            val curs = db[layer]?.query(
                "tiles",
                Array(1) { "tile_data" },
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
                    if (fileFormat == "png") {
                        response.setContentType("image/png")
                    }
                    else {
                        response.setContentType("application/x-protobuf")
                        response.headers.add("Content-Encoding", "gzip")
                    }
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
                    val ll = (curs?.getInt(0) ?: 0)
                    val buf = ByteArray(ll)
                    for (i in 0..ll.div(1000*1000)) {
                        val curs = db[layer]?.rawQuery(
                            "select substr(tile_data, ${1 + i*1000*1000}, ${(i+1)*1000*1000}) from tiles where zoom_level = $z AND tile_column = $x AND tile_row = $y",
                            null
                        )
                        curs?.moveToFirst()
                        curs?.getBlob(0)?.copyInto(buf, i*1000*1000)
                    }
                    if (fileFormat == "png") {
                        response.setContentType("image/png")
                    }
                    else {
                        response.setContentType("application/x-protobuf")
                        response.headers.add("Content-Encoding", "gzip")
                    }
                    response.sendStream(ByteArrayInputStream(buf), buf.size.toLong())
                    return@HttpServerRequestCallback
                }
            }
            response.code(404)
            response.send("")
        }

    private val checkData =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->

            response.headers.add("Access-Control-Allow-Origin", "*")
            response.headers.add("Cache-Control","no-store")
            response.setContentType("application/json")

            val json = JSONObject()

            val allMeta: JSONObject?
            try {
                allMeta = getAllMeta()
            }
            catch (e: UnknownHostException) {
                response.code(504)
                response.send("Online resource catalog not found.\n")
                return@HttpServerRequestCallback

            }

            for (resName in allMeta!!.keys()) {
                json.put(resName, JSONObject(mapOf(
                    "status" to getStatus(resName).toString(),
                    "filesize" to getSize(resName),
                    "current" to getVer(resName),
                    "available" to allMeta.getJSONObject(resName)?.getString("version")
                )))
            }

            response.send(json.toString(4))
        }

    private val checkPreflight =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            response.headers.add("Access-Control-Allow-Origin", "*")
            response.headers.add("Access-Control-Allow-Methods", "PUT, DELETE")
            response.code(200)
            response.send("")
        }

    private val deleteData =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            val mapName: String? = (if (request.path.contains("/map/")) request.path.substringAfterLast("/")
            else request.query.getString("map"))
            response.headers.add("Access-Control-Allow-Origin", "*")
            response.headers.add("Cache-Control","no-store")

            if (mapName == null) {
                response.code(404)
                response.send("Map not found.\n")
                return@HttpServerRequestCallback
            }
            deleteAssets(mapName, File(this.filePath,"dl"))
            File(this.filePath,"dl/versions/$mapName.meta").delete()
            response.code(200)
            response.send("Deleted package $mapName.\n")
        }

    private val updateData =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->
            val mapName: String? = (if (request.path.contains("/map/")) request.path.substringAfterLast("/")
            else request.query.getString("map"))

            response.headers.add("Access-Control-Allow-Origin", "*")
            response.headers.add("Cache-Control","no-store")

            val meta: JSONObject
            try {
                val runningJob = dlJobs[mapName]
                if (runningJob?.isActive ?: false) {
                    response.code(409)
                    response.send("Download already in progress - cannot launch simultaneous DL.\n")
                    return@HttpServerRequestCallback
                }

                meta = getMeta(mapName!!)!!
                val scope = CoroutineScope(Dispatchers.IO)
                val threadLocal = ThreadLocal<LuxTileServer>()
                val job = scope.launch(threadLocal.asContextElement(this)) {
                    val response = "bla"
                    try {
                        val filePath = threadLocal.get()?.filePath
                        saveMeta(meta, "${filePath}/tmp/versions/$mapName.meta")
                        downloadAssetsFromMeta(meta, File(filePath, "tmp"))
                        // move all data to dl folder
                        deleteAssets(mapName, File(filePath,"dl"))
                        moveAssets(meta.getJSONArray("sources"), File(filePath, "tmp"), File(filePath, "dl"))
                        // version is saved only if update has been successful (no exception occurred)
                        saveMeta(meta, "${filePath}/dl/versions/$mapName.meta")
                    }
                    catch (e: Exception) {
                        if (e is FileNotFoundException) {
                            Log.i("MetaDL","File not found : ${e.message}")// log file not found
                        }
                        else if (e is ConnectException) {
                            Log.i("MetaDL","Connection Failed : ${e.message}")
                        }

                    }
                    Log.i("MetaDL", "Terminated")
                }
                dlJobs[mapName] = job
                // there are 2 possibilities here: async launch of DL and response code 202 (accepted)
                // problem here is to check for return value (404 hard to implement)
                // or waiting for dl then return 204 (no content)
                // the used http server seems to be single threaded, at least other requests seem to remain
                // blocked until this transaction return => might be better to use 202
                response.code(202)
            }
            catch (e: Exception) {
                response.code(404)
            }

            response.send("")
        }
}

