package lu.geoportail.map

import android.content.Context
import android.content.res.Resources
import android.database.sqlite.SQLiteDatabase
import com.koushikdutta.async.ByteBufferList
import com.koushikdutta.async.http.WebSocket
import com.koushikdutta.async.http.server.AsyncHttpServer
import com.koushikdutta.async.http.server.HttpServerRequestCallback
import com.koushikdutta.async.http.server.AsyncHttpServerRequest
import com.koushikdutta.async.http.server.AsyncHttpServerResponse
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.ArrayList

class LuxTileServer (context:Context, resources:Resources) {
    private var db: SQLiteDatabase? = null
    val staticsMap = mapOf(
        "omt_geoportail_lu" to "mbtiles/omt_geoportail_lu.mbtiles",
        "data_omt_geoportail_lu" to "static/data/omt-geoportail-lu.json",
        "roadmap_style" to "static/styles/roadmap/style.json",
        "topomap_style" to "static/styles/topomap/style.json",
        "topomap_gray_style" to "static/styles/topomap_gray/style.json",
        "fonts_noto_sans_0_255" to "static/fonts/NotoSansBold/0-255.pbf",
        "fonts_noto_sans_256_511" to "static/fonts/NotoSansBold/256-511.pbf",
        "fonts_noto_regular_0_255" to "static/fonts/NotoSansRegular/0-255.pbf",
        "fonts_noto_regular_256_511" to "static/fonts/NotoSansRegular/256-511.pbf"
    )
    val reverseStaticsMap = HashMap<String, String>()
    val context : Context = context
    val resources : Resources = resources
    val packageName = context.getPackageName()
    var rootDir : String = ""

    fun copyResToFile(resourceName:String, fileName:String) {

    }

    fun start(rootDir: String) {
        val server = AsyncHttpServer()
        val _sockets: List<WebSocket> = ArrayList()
        server["/", HttpServerRequestCallback { request, response -> response.send("Hello!!!") }]
        server.get("/hello", HttpServerRequestCallback { request, response -> response.send("Hello!!!" + request.toString()) })
        server.get("/mbtiles", this.getMbTile)
        server.get("/static/.*", this.getStaticFile)

        // val reverseStaticsMap = HashMap<String, String>()
        for ((key, value) in staticsMap) {
            reverseStaticsMap.put(value, key)
        }
        this.rootDir = rootDir
        this.db = SQLiteDatabase.openDatabase(this.rootDir + "/mbtiles/omt_geoportail_lu.mbtiles", null, SQLiteDatabase.OPEN_READONLY)
        // listen on port 5000
        server.listen(5001)
        // browsing http://localhost:5000 will return Hello!!!
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
            val inputStream: java.io.InputStream = resources.openRawResource(
                context.getResources().getIdentifier(resourceName, "raw", packageName)
            )
            //response.send("Hello!!! " + resourceName + "\n")
            val buf: ByteArray = ByteArray(1024)
            val bufL: ByteBufferList = ByteBufferList()
            var len: Int
            response.headers.add("Content-Length", inputStream.available().toString())
            while (inputStream.read(buf).also { len = it } > 0) {
                //response.sendStream(ByteArrayInputStream(buf), buf.size.toLong())
                bufL.addFirst(ByteBuffer.wrap(buf, 0, len))
                response.write(bufL)
            }
            response.end()
            inputStream.close()

            //response.sendFile(inputStream)
        }

    private val getMbTile =
        HttpServerRequestCallback { request: AsyncHttpServerRequest, response: AsyncHttpServerResponse ->

            val z: Int = request.query.getString("z").toInt()
            val x: Int = request.query.getString("x").toInt()
            var y: Int = request.query.getString("y").toInt()

            // MBTiles by default use TMS for the tiles. Most mapping apps use slippy maps: XYZ schema.
            // We need to handle both.
            y = if (y > 0) {
                Math.pow(2.0, z.toDouble()).toInt() - Math.abs(y) - 1
            } else {
                Math.abs(y)
            }

            var curs = this.db?.query(
                "tiles",
                Array<String>(1) { i -> "tile_data" },
                "zoom_level = " + z + " AND tile_column = " + x + " AND tile_row = " + y,
                null,
                null,
                null,
                null
            )

            curs?.moveToFirst()
            if (!curs?.isAfterLast()!!) {
                // val bufL: ByteBufferList = ByteBufferList(curs.getBlob(0))
                val buf = curs.getBlob(0)
                response.setContentType("application/x-protobuf")
                response.headers.add("Access-Control-Allow-Origin","*")
                response.headers.add("Content-Encoding", "gzip")
                response.sendStream(ByteArrayInputStream(buf), buf.size.toLong())
                // response.write(bufL)
            }
        }
}
