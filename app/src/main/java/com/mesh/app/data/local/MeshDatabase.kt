package com.mesh.app.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mesh.app.data.local.dao.PlaylistDao
import com.mesh.app.data.local.dao.TrackDao
import com.mesh.app.data.local.entity.PlaylistEntity
import com.mesh.app.data.local.entity.PlaylistTrackCrossRef
import com.mesh.app.data.local.entity.TrackEntity
import java.util.UUID

@Database(
    entities = [
        TrackEntity::class,
        PlaylistEntity::class,
        PlaylistTrackCrossRef::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MeshDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao
    abstract fun playlistDao(): PlaylistDao

    companion object {
        private const val FAVORITES_NAME = "Любимое"

        fun create(context: Context): MeshDatabase =
            Room.databaseBuilder(context, MeshDatabase::class.java, "mesh.db")
                .addCallback(
                    object : Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            val now = System.currentTimeMillis()
                            db.execSQL(
                                """
                                INSERT INTO playlists (id, name, isSystem, createdAt)
                                VALUES ('${UUID.randomUUID()}', '$FAVORITES_NAME', 1, $now)
                                """.trimIndent(),
                            )
                        }
                    },
                )
                .build()
    }
}
