package lu.geoportail.map.backends

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject

object OfflineContract {
    object OfflineEntry {
        const val TABLE_NAME = "offline"
        const val COLUMN_NAME_KEY = "key"
        const val COLUMN_NAME_VALUE = "value"
    }

    private const val SQL_CREATE_ENTRIES =
        "CREATE TABLE ${OfflineEntry.TABLE_NAME} (" +
                "${OfflineEntry.COLUMN_NAME_KEY} TEXT PRIMARY KEY," +
                "${OfflineEntry.COLUMN_NAME_VALUE} TEXT)"

    private const val SQL_DELETE_ENTRIES = "DROP TABLE IF EXISTS ${OfflineEntry.TABLE_NAME}"

    class OfflineDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(SQL_CREATE_ENTRIES)
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Simply discard the data and start over
            db.execSQL(SQL_DELETE_ENTRIES)
            onCreate(db)
        }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            onUpgrade(db, oldVersion, newVersion)
        }
        companion object {
            // If you change the database schema, you must increment the database version.
            const val DATABASE_VERSION = 1
            const val DATABASE_NAME = "Offline.db"
        }
    }
}



class LocalforageSqliteBackend(private val context: Context): IBackend {

    private var db: SQLiteDatabase? = null
    private val entry = OfflineContract.OfflineEntry

    override fun setItem(key: String, base64: String, action: JSONObject) {
        val values = ContentValues().apply {
            put(entry.COLUMN_NAME_KEY, key)
            put(entry.COLUMN_NAME_VALUE, base64)
        }

        db?.insertWithOnConflict(entry.TABLE_NAME, null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    override fun removeItem(key: String, action: JSONObject) {
        val selection = "${entry.COLUMN_NAME_KEY} = ?"
        val selectionArgs = arrayOf(key)

        db?.delete(entry.TABLE_NAME, selection, selectionArgs)
    }

    override fun clear(action: JSONObject) {
        db?.delete(entry.TABLE_NAME, "1 = 1", arrayOf())
    }

    override fun config(action: JSONObject) {
        val dbHelper = OfflineContract.OfflineDbHelper(context)
        db = dbHelper.writableDatabase
    }

    override fun getItem(key: String, action: JSONObject): String? {
        val projection = arrayOf(entry.COLUMN_NAME_VALUE)
        val selection = "${entry.COLUMN_NAME_KEY} = ?"
        val selectionArgs = arrayOf(key)
        val groupBy = null
        val having = null
        val orderBy = null

        val myDB = db
        if (myDB != null) {
            val cursor = myDB.query(entry.TABLE_NAME, projection, selection, selectionArgs, groupBy, having, orderBy)

            with(cursor) {
                if (moveToNext()) {
                    return getString(getColumnIndexOrThrow(entry.COLUMN_NAME_VALUE))
                }
            }
        }
        return null
    }
}