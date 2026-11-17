package dev.anilbeesetti.nextplayer.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.anilbeesetti.nextplayer.core.database.dao.AudioFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.dao.ComicFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.dao.ComicProgressDao
import dev.anilbeesetti.nextplayer.core.database.dao.HiddenVideoDao
import dev.anilbeesetti.nextplayer.core.database.dao.ImageFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediaFileIndexDao
import dev.anilbeesetti.nextplayer.core.database.dao.MediumStateDao
import dev.anilbeesetti.nextplayer.core.database.dao.NetworkConnectionDao
import dev.anilbeesetti.nextplayer.core.database.dao.PlaylistDao
import dev.anilbeesetti.nextplayer.core.database.entities.AudioFileIndexEntity
import dev.anilbeesetti.nextplayer.core.database.entities.ComicFileIndexEntity
import dev.anilbeesetti.nextplayer.core.database.entities.ComicProgressEntity
import dev.anilbeesetti.nextplayer.core.database.entities.HiddenVideoEntity
import dev.anilbeesetti.nextplayer.core.database.entities.ImageFileIndexEntity
import dev.anilbeesetti.nextplayer.core.database.entities.MediaFileIndexEntity
import dev.anilbeesetti.nextplayer.core.database.entities.MediumStateEntity
import dev.anilbeesetti.nextplayer.core.database.entities.NetworkConnectionEntity
import dev.anilbeesetti.nextplayer.core.database.entities.PlaylistEntity
import dev.anilbeesetti.nextplayer.core.database.entities.PlaylistItemEntity

@Database(
    entities = [
        MediumStateEntity::class,
        HiddenVideoEntity::class,
        NetworkConnectionEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        MediaFileIndexEntity::class,
        AudioFileIndexEntity::class,
        ImageFileIndexEntity::class,
        ComicFileIndexEntity::class,
        ComicProgressEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
abstract class MediaDatabase : RoomDatabase() {

    abstract fun mediumStateDao(): MediumStateDao

    abstract fun mediaFileIndexDao(): MediaFileIndexDao

    abstract fun audioFileIndexDao(): AudioFileIndexDao

    abstract fun imageFileIndexDao(): ImageFileIndexDao

    abstract fun comicFileIndexDao(): ComicFileIndexDao

    abstract fun comicProgressDao(): ComicProgressDao

    abstract fun hiddenVideoDao(): HiddenVideoDao

    abstract fun networkConnectionDao(): NetworkConnectionDao

    abstract fun playlistDao(): PlaylistDao

    companion object {
        const val DATABASE_NAME = "media_db"

        val MIGRATION_1_2 = object : Migration(
            1,
            2,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Create the new media_state table
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `media_state` (
                        `uri` TEXT NOT NULL, 
                        `playback_position` INTEGER NOT NULL DEFAULT 0, 
                        `audio_track_index` INTEGER, 
                        `subtitle_track_index` INTEGER, 
                        `playback_speed` REAL, 
                        `last_played_time` INTEGER, 
                        `external_subs` TEXT NOT NULL DEFAULT '', 
                        `video_scale` REAL NOT NULL DEFAULT 1, 
                        PRIMARY KEY(`uri`)
                    )
                    """,
                )

                // Create index for the uri column
                db.execSQL(
                    sql = """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_media_state_uri` ON `media_state` (`uri`)
                    """,
                )

                // Copy data from media table to media_state table
                db.execSQL(
                    sql = """
                    INSERT INTO `media_state` (
                        `uri`, 
                        `playback_position`, 
                        `audio_track_index`, 
                        `subtitle_track_index`, 
                        `playback_speed`, 
                        `last_played_time`, 
                        `external_subs`, 
                        `video_scale`
                    ) 
                    SELECT 
                        `uri`, 
                        `playback_position`, 
                        `audio_track_index`, 
                        `subtitle_track_index`, 
                        `playback_speed`, 
                        `last_played_time`, 
                        `external_subs`, 
                        `video_scale` 
                    FROM `media`
                    """,
                )

                // Create a temporary table for the new media schema
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `media_new` (
                        `uri` TEXT NOT NULL, 
                        `path` TEXT NOT NULL, 
                        `filename` TEXT NOT NULL, 
                        `parent_path` TEXT NOT NULL, 
                        `last_modified` INTEGER NOT NULL, 
                        `size` INTEGER NOT NULL, 
                        `width` INTEGER NOT NULL, 
                        `height` INTEGER NOT NULL, 
                        `duration` INTEGER NOT NULL, 
                        `media_store_id` INTEGER NOT NULL, 
                        `format` TEXT, 
                        `thumbnail_path` TEXT, 
                        PRIMARY KEY(`uri`)
                    )
                    """,
                )

                // Copy data from the old media table to the new one
                db.execSQL(
                    sql = """
                    INSERT INTO `media_new` (
                        `uri`, 
                        `path`, 
                        `filename`, 
                        `parent_path`, 
                        `last_modified`, 
                        `size`, 
                        `width`, 
                        `height`, 
                        `duration`, 
                        `media_store_id`, 
                        `format`, 
                        `thumbnail_path`
                    ) 
                    SELECT 
                        `uri`, 
                        `path`, 
                        `filename`, 
                        `parent_path`, 
                        `last_modified`, 
                        `size`, 
                        `width`, 
                        `height`, 
                        `duration`, 
                        `media_store_id`, 
                        `format`, 
                        `thumbnail_path` 
                    FROM `media`
                    """,
                )

                // Drop the old media table
                db.execSQL(sql = "DROP TABLE `media`")

                // Rename the new media table to media
                db.execSQL(sql = "ALTER TABLE `media_new` RENAME TO `media`")

                // Recreate the indices for the media table
                db.execSQL(
                    sql = """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_media_uri` ON `media` (`uri`)
                    """,
                )
                db.execSQL(
                    sql = """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_media_path` ON `media` (`path`)
                    """,
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(
            2,
            3,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Drop the unique index on path
                db.execSQL(sql = "DROP INDEX IF EXISTS `index_media_path`")

                // Recreate the index without unique constraint
                db.execSQL(
                    sql = """
                    CREATE INDEX IF NOT EXISTS `index_media_path` ON `media` (`path`)
                    """,
                )
            }
        }

        val MIGRATION_3_4 = object : Migration(
            3,
            4,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = "ALTER TABLE `media_state` ADD COLUMN `subtitle_delay` " +
                        "INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    sql = "ALTER TABLE `media_state` ADD COLUMN `subtitle_speed` " +
                        "REAL NOT NULL DEFAULT 1",
                )
            }
        }

        val MIGRATION_4_5 = object : Migration(
            4,
            5,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(sql = "DROP TABLE IF EXISTS `directories`")
                db.execSQL(sql = "DROP TABLE IF EXISTS `media`")
                db.execSQL(sql = "DROP TABLE IF EXISTS `audio_stream_info`")
                db.execSQL(sql = "DROP TABLE IF EXISTS `video_stream_info`")
                db.execSQL(sql = "DROP TABLE IF EXISTS `subtitle_stream_info`")
            }
        }

        val MIGRATION_5_6 = object : Migration(
            5,
            6,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `hidden_video` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `vault_path` TEXT NOT NULL,
                        `original_path` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `duration` INTEGER NOT NULL DEFAULT 0,
                        `size` INTEGER NOT NULL DEFAULT 0,
                        `width` INTEGER NOT NULL DEFAULT 0,
                        `height` INTEGER NOT NULL DEFAULT 0,
                        `hidden_at` INTEGER NOT NULL
                    )
                    """,
                )
                db.execSQL(
                    sql = """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_hidden_video_vault_path` ON `hidden_video` (`vault_path`)
                    """,
                )
            }
        }

        val MIGRATION_6_7 = object : Migration(
            6,
            7,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Column definitions (order, types, nullability) must match the schema Room
                // generates from NetworkConnectionEntity exactly, or migration validation fails.
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `network_connection` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `protocol` TEXT NOT NULL,
                        `host` TEXT NOT NULL,
                        `port` INTEGER,
                        `path` TEXT NOT NULL,
                        `username` TEXT NOT NULL,
                        `password` TEXT NOT NULL,
                        `use_https` INTEGER NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """,
                )
            }
        }

        val MIGRATION_7_8 = object : Migration(
            7,
            8,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `playlist` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `created_at` INTEGER NOT NULL
                    )
                    """,
                )
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `playlist_item` (
                        `playlist_id` INTEGER NOT NULL,
                        `uri` TEXT NOT NULL,
                        `position` INTEGER NOT NULL,
                        `last_played_at` INTEGER,
                        PRIMARY KEY(`playlist_id`, `uri`),
                        FOREIGN KEY(`playlist_id`) REFERENCES `playlist`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """,
                )
                db.execSQL(
                    sql = """
                    CREATE INDEX IF NOT EXISTS `index_playlist_item_playlist_id`
                    ON `playlist_item` (`playlist_id`)
                    """,
                )
                db.execSQL(
                    sql = """
                    CREATE UNIQUE INDEX IF NOT EXISTS `index_playlist_item_playlist_id_position`
                    ON `playlist_item` (`playlist_id`, `position`)
                    """,
                )
            }
        }

        val MIGRATION_8_9 = object : Migration(
            8,
            9,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = "ALTER TABLE `network_connection` " +
                        "ADD COLUMN `authentication` TEXT NOT NULL DEFAULT 'PASSWORD'",
                )
                db.execSQL(
                    sql = "ALTER TABLE `network_connection` " +
                        "ADD COLUMN `private_key_file_name` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    sql = "ALTER TABLE `network_connection` " +
                        "ADD COLUMN `private_key_passphrase` TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    sql = "ALTER TABLE `network_connection` " +
                        "ADD COLUMN `host_key_fingerprint` TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_9_10 = object : Migration(
            9,
            10,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = "ALTER TABLE `playlist` ADD COLUMN `type` TEXT NOT NULL DEFAULT 'LOCAL'",
                )
                db.execSQL(sql = "ALTER TABLE `playlist` ADD COLUMN `source` TEXT")
                db.execSQL(sql = "ALTER TABLE `playlist` ADD COLUMN `last_refreshed_at` INTEGER")
                db.execSQL(sql = "ALTER TABLE `playlist_item` ADD COLUMN `title` TEXT")
                db.execSQL(sql = "ALTER TABLE `playlist_item` ADD COLUMN `tvg_logo` TEXT")
                db.execSQL(
                    sql = "ALTER TABLE `playlist_item` ADD COLUMN `duration` " +
                        "INTEGER NOT NULL DEFAULT -1",
                )
                db.execSQL(sql = "ALTER TABLE `playlist_item` ADD COLUMN `group_title` TEXT")
            }
        }

        val MIGRATION_11_12 = object : Migration(
            11,
            12,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = "ALTER TABLE `media_state` ADD COLUMN `external_audio` " +
                        "TEXT NOT NULL DEFAULT ''",
                )
            }
        }

        val MIGRATION_10_11 = object : Migration(
            10,
            11,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(sql = "ALTER TABLE `media_state` ADD COLUMN `duration` INTEGER")
            }
        }

        val MIGRATION_12_13 = object : Migration(
            12,
            13,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Rebuild the table because older Android SQLite versions cannot drop columns.
                db.execSQL(
                    sql = """
                    CREATE TABLE `media_state_new` (
                        `uri` TEXT NOT NULL,
                        `playback_position` INTEGER NOT NULL,
                        `audio_track_index` INTEGER,
                        `subtitle_track_index` INTEGER,
                        `last_played_time` INTEGER,
                        `duration` INTEGER,
                        `external_subs` TEXT NOT NULL,
                        `external_audio` TEXT NOT NULL DEFAULT '',
                        `video_scale` REAL NOT NULL,
                        `subtitle_delay` INTEGER NOT NULL,
                        `subtitle_speed` REAL NOT NULL,
                        PRIMARY KEY(`uri`)
                    )
                    """,
                )
                db.execSQL(
                    sql = """
                    INSERT INTO `media_state_new` (
                        `uri`, `playback_position`, `audio_track_index`, `subtitle_track_index`,
                        `last_played_time`, `duration`, `external_subs`, `external_audio`,
                        `video_scale`, `subtitle_delay`, `subtitle_speed`
                    )
                    SELECT
                        `uri`, `playback_position`, `audio_track_index`, `subtitle_track_index`,
                        `last_played_time`, `duration`, `external_subs`, `external_audio`,
                        `video_scale`, `subtitle_delay`, `subtitle_speed`
                    FROM `media_state`
                    """,
                )
                db.execSQL(sql = "DROP TABLE `media_state`")
                db.execSQL(sql = "ALTER TABLE `media_state_new` RENAME TO `media_state`")
                db.execSQL(
                    sql = "CREATE UNIQUE INDEX `index_media_state_uri` ON `media_state` (`uri`)",
                )
            }
        }

        val MIGRATION_13_14 = object : Migration(
            13,
            14,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `media_file_index` (
                        `path` TEXT NOT NULL,
                        `parent_path` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `date_modified` INTEGER NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `metadata_loaded` INTEGER NOT NULL,
                        `no_media_hidden` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """,
                )
            }
        }

        /** 音频索引表与视频索引表结构对应，是纯新增表，不触碰任何已有数据。 */
        val MIGRATION_14_15 = object : Migration(
            14,
            15,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `audio_file_index` (
                        `path` TEXT NOT NULL,
                        `parent_path` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `date_modified` INTEGER NOT NULL,
                        `duration` INTEGER NOT NULL,
                        `artist` TEXT NOT NULL,
                        `album` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `metadata_loaded` INTEGER NOT NULL,
                        `no_media_hidden` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """,
                )
            }
        }

        /** 图片索引是纯新增表，白名单与视频/音频排除目录互不影响。 */
        val MIGRATION_15_16 = object : Migration(
            15,
            16,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `image_file_index` (
                        `path` TEXT NOT NULL,
                        `parent_path` TEXT NOT NULL,
                        `whitelist_root` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `date_modified` INTEGER NOT NULL,
                        `no_media_hidden` INTEGER NOT NULL,
                        `width` INTEGER NOT NULL,
                        `height` INTEGER NOT NULL,
                        `dimensions_loaded` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """,
                )
            }
        }

        /** 漫画压缩包索引是纯新增表，不触碰任何已有数据。 */
        val MIGRATION_16_17 = object : Migration(
            16,
            17,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `comic_file_index` (
                        `path` TEXT NOT NULL,
                        `parent_path` TEXT NOT NULL,
                        `whitelist_root` TEXT NOT NULL,
                        `display_name` TEXT NOT NULL,
                        `size` INTEGER NOT NULL,
                        `date_modified` INTEGER NOT NULL,
                        `image_count` INTEGER NOT NULL,
                        PRIMARY KEY(`path`)
                    )
                    """,
                )
            }
        }

        /** 漫画阅读进度表是纯新增表，与索引分开，扫描刷新不会覆盖进度。 */
        val MIGRATION_17_18 = object : Migration(
            17,
            18,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    CREATE TABLE IF NOT EXISTS `comic_progress` (
                        `path` TEXT NOT NULL,
                        `last_page` INTEGER NOT NULL DEFAULT 0,
                        `reading_direction` TEXT NOT NULL DEFAULT 'RIGHT_TO_LEFT',
                        PRIMARY KEY(`path`)
                    )
                    """,
                )
            }
        }
        /** 漫画索引补 .nomedia 标记列，让漫画库也能跟随「忽略 .nomedia」设置。 */
        val MIGRATION_18_19 = object : Migration(
            18,
            19,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    ALTER TABLE `comic_file_index`
                    ADD COLUMN `no_media_hidden` INTEGER NOT NULL DEFAULT 0
                    """,
                )
            }
        }

        /** 漫画进度补最近阅读时间列，「更多」页历史记录按它排序。 */
        val MIGRATION_19_20 = object : Migration(
            19,
            20,
        ) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    sql = """
                    ALTER TABLE `comic_progress`
                    ADD COLUMN `updated_at` INTEGER NOT NULL DEFAULT 0
                    """,
                )
            }
        }
    }
}
