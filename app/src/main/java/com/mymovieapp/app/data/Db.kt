package com.mymovieapp.app.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "folders")
data class LibraryFolder(@PrimaryKey val uri: String, val name: String, val addedAt: Long)

@Entity(tableName = "titles", indices = [Index(value = ["titleKey"], unique = true)])
data class Title(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val titleKey: String,
    val kind: String,              // "film" | "series"
    val name: String,
    val year: Int? = null,
    val tmdbId: Int? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val overview: String? = null,
    val castList: String? = null,
    val rating: Double? = null,
    val matched: Boolean = false,
    val hidden: Boolean = false,
    val addedAt: Long
)

val Title.isSeries: Boolean get() = kind == "series"

@Entity(
    tableName = "videos",
    indices = [Index(value = ["uri"], unique = true), Index("titleId"), Index("folderUri")]
)
data class Video(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uri: String,
    val folderUri: String?,        // null = individually chosen file
    val titleId: Long,
    val fileName: String,
    val season: Int?,
    val episode: Int?,
    val subtitleUri: String?,
    val sizeBytes: Long,
    val addedAt: Long,
    val durationMs: Long = 0,      // 0 = not read yet, -1 = couldn't be read
    val subPath: String? = null    // folder inside the source, e.g. "Mike Adriano/True Anal" (null = top level)
)

@Entity(tableName = "progress")
data class Progress(
    @PrimaryKey val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long
)

fun Progress.isWatched(): Boolean = durationMs > 0 && positionMs >= durationMs * 0.92

@Entity(tableName = "channels", indices = [Index(value = ["url"], unique = true)])
data class Channel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,
    val grp: String,
    val logo: String?,
    val addedAt: Long,
    val playlistId: Long = 0,        // 0 = pasted links
    val number: Int = 0,             // position in the original playlist
    val favorite: Boolean = false,
    val lastPlayed: Long = 0,
    val tvgId: String? = null        // kept for a future program guide
)

@Entity(tableName = "playlists")
data class Playlist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val url: String,                 // blank when imported from a file
    val addedAt: Long,
    val groupList: String            // groups the user chose to keep, newline-separated
)

data class GroupCount(val name: String, val n: Int)
data class PlaylistCount(val playlistId: Long, val n: Int)

@Entity(tableName = "smb_servers")
data class SmbServer(@PrimaryKey val host: String, val user: String, val domain: String, val pass: String)

data class VideoRef(val id: Long, val uri: String)
data class VideoSub(val id: Long, val uri: String, val subPath: String?)
data class VideoWithTitle(@Embedded val video: Video, val titleName: String)

@Dao
interface LibraryDao {
    // folders
    @Query("SELECT * FROM folders ORDER BY addedAt") fun folders(): Flow<List<LibraryFolder>>
    @Query("SELECT * FROM folders ORDER BY addedAt") suspend fun foldersNow(): List<LibraryFolder>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFolder(f: LibraryFolder)
    @Query("DELETE FROM folders WHERE uri = :uri") suspend fun deleteFolder(uri: String)

    // moving a source to a new address: every stored path that starts with oldPrefix is re-pointed to newPrefix
    @Query("UPDATE videos SET uri = :newPrefix || substr(uri, length(:oldPrefix) + 1) WHERE uri = :oldPrefix OR substr(uri, 1, length(:oldPrefix) + 1) = :oldPrefix || '/'")
    suspend fun moveVideoUris(oldPrefix: String, newPrefix: String)
    @Query("UPDATE videos SET folderUri = :newPrefix || substr(folderUri, length(:oldPrefix) + 1) WHERE folderUri = :oldPrefix OR substr(folderUri, 1, length(:oldPrefix) + 1) = :oldPrefix || '/'")
    suspend fun moveVideoFolders(oldPrefix: String, newPrefix: String)
    @Query("UPDATE videos SET subtitleUri = :newPrefix || substr(subtitleUri, length(:oldPrefix) + 1) WHERE subtitleUri = :oldPrefix OR substr(subtitleUri, 1, length(:oldPrefix) + 1) = :oldPrefix || '/'")
    suspend fun moveSubtitleUris(oldPrefix: String, newPrefix: String)
    @Query("UPDATE OR REPLACE progress SET uri = :newPrefix || substr(uri, length(:oldPrefix) + 1) WHERE uri = :oldPrefix OR substr(uri, 1, length(:oldPrefix) + 1) = :oldPrefix || '/'")
    suspend fun moveProgressUris(oldPrefix: String, newPrefix: String)
    @Query("UPDATE folders SET uri = :newPrefix || substr(uri, length(:oldPrefix) + 1) WHERE uri = :oldPrefix OR substr(uri, 1, length(:oldPrefix) + 1) = :oldPrefix || '/'")
    suspend fun moveFolderUris(oldPrefix: String, newPrefix: String)
    @Query("UPDATE folders SET name = :name WHERE uri = :uri") suspend fun renameFolder(uri: String, name: String)


    // titles
    @Query("SELECT * FROM titles WHERE hidden = 0") fun visibleTitles(): Flow<List<Title>>
    @Query("SELECT * FROM titles WHERE matched = 0 AND hidden = 0 ORDER BY name") fun unmatched(): Flow<List<Title>>
    @Query("SELECT * FROM titles WHERE matched = 0 AND hidden = 0") suspend fun unmatchedNow(): List<Title>
    @Query("SELECT * FROM titles WHERE id = :id") fun title(id: Long): Flow<Title?>
    @Query("SELECT * FROM titles WHERE id = :id") suspend fun titleNow(id: Long): Title?
    @Query("SELECT * FROM titles WHERE titleKey = :key LIMIT 1") suspend fun titleByKey(key: String): Title?
    @Insert suspend fun insertTitle(t: Title): Long
    @Update suspend fun updateTitle(t: Title)
    @Query("UPDATE titles SET hidden = :h WHERE id = :id") suspend fun setHidden(id: Long, h: Boolean)
    @Query("DELETE FROM titles WHERE id NOT IN (SELECT DISTINCT titleId FROM videos)") suspend fun deleteOrphanTitles()
    @Query("SELECT COUNT(*) FROM titles") fun countTitles(): Flow<Int>
    @Query("UPDATE titles SET hidden = 0 WHERE hidden = 1") suspend fun unhideAll()

    // videos
    @Query("SELECT * FROM videos") fun allVideos(): Flow<List<Video>>
    @Query("SELECT * FROM videos WHERE titleId = :titleId ORDER BY season, episode, fileName") fun videosOf(titleId: Long): Flow<List<Video>>
    @Query("SELECT * FROM videos WHERE titleId = :titleId ORDER BY season, episode, fileName") suspend fun videosOfNow(titleId: Long): List<Video>
    @Query("SELECT * FROM videos WHERE id = :id") suspend fun video(id: Long): Video?
    @Query("SELECT * FROM videos WHERE uri = :uri LIMIT 1") suspend fun videoByUri(uri: String): Video?
    @Query("SELECT uri FROM videos WHERE uri LIKE 'smb://%' LIMIT 1") suspend fun anySmbVideo(): String?
    @Query("SELECT id, uri FROM videos WHERE folderUri = :folder") suspend fun videoRefsInFolder(folder: String): List<VideoRef>
    @Query("SELECT id, uri, subPath FROM videos WHERE folderUri = :folder") suspend fun videoSubsInFolder(folder: String): List<VideoSub>
    @Query("UPDATE videos SET subPath = :p WHERE id = :id") suspend fun setSubPath(id: Long, p: String?)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertVideo(v: Video): Long
    @Query("DELETE FROM videos WHERE id IN (:ids)") suspend fun deleteVideos(ids: List<Long>)
    @Query("DELETE FROM videos WHERE folderUri = :folder") suspend fun deleteVideosOfFolder(folder: String)
    @Query("DELETE FROM videos WHERE folderUri IS NULL") suspend fun deleteLooseVideos()
    @Query("SELECT COUNT(*) FROM videos WHERE folderUri IS NULL") fun looseCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM videos") fun countVideos(): Flow<Int>
    @Query("SELECT * FROM videos WHERE folderUri = :folder ORDER BY fileName COLLATE NOCASE") suspend fun videosInFolder(folder: String): List<Video>
    @Query("UPDATE videos SET uri = :uri, fileName = :name, subtitleUri = :sub WHERE id = :id")
    suspend fun updateVideoFile(id: Long, uri: String, name: String, sub: String?)
    @Query("UPDATE OR REPLACE progress SET uri = :newUri WHERE uri = :oldUri")
    suspend fun moveProgress(oldUri: String, newUri: String)
    @Query("UPDATE titles SET name = :name, year = :year, titleKey = :key WHERE id = :id")
    suspend fun retitle(id: Long, name: String, year: Int?, key: String)
    @Query("SELECT COUNT(*) FROM videos WHERE titleId = :id") suspend fun videoCountOf(id: Long): Int
    @Query("UPDATE videos SET titleId = :to WHERE titleId = :from") suspend fun moveVideos(from: Long, to: Long)
    @Query("SELECT id, uri FROM videos WHERE durationMs = 0") suspend fun videosWithoutDuration(): List<VideoRef>
    @Query("SELECT id, uri FROM videos WHERE durationMs = 0 AND folderUri = :folder") suspend fun videosWithoutDurationIn(folder: String): List<VideoRef>
    @Query("UPDATE videos SET durationMs = :d WHERE id = :id") suspend fun setDuration(id: Long, d: Long)

    // Stash (performers imported from the user's Stash library)
    @Query(
        "SELECT s.id AS sceneId, COALESCE(l.performerId, 0) AS performerId, v.id AS videoId, v.uri AS uri, v.folderUri AS folderUri, " +
            "v.fileName AS fileName, v.durationMs AS durationMs, s.title AS title, s.studio AS studio, s.date AS date, s.rating AS rating, s.tags AS tags " +
            "FROM stash_scenes s " +
            "JOIN videos v ON v.sizeBytes = s.size AND (lower(v.fileName) = s.nameKey OR s.sizeUnique = 1) " +
            "JOIN titles t ON t.id = v.titleId AND t.hidden = 0 " +
            "LEFT JOIN stash_links l ON l.sceneId = s.id"
    )
    fun stashRows(): Flow<List<StashRow>>
    @Query("SELECT * FROM stash_performers") fun stashPerformers(): Flow<List<StashPerformer>>
    @Query("SELECT COUNT(*) FROM stash_scenes") fun stashSceneCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM stash_performers") fun stashPerformerCount(): Flow<Int>
    @Query("DELETE FROM stash_scenes") suspend fun clearStashScenes()
    @Query("DELETE FROM stash_performers") suspend fun clearStashPerformers()
    @Query("DELETE FROM stash_links") suspend fun clearStashLinks()
    @Query("DELETE FROM stash_links WHERE sceneId IN (:ids)") suspend fun deleteStashLinksOf(ids: List<Int>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStashScenes(l: List<StashScene>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStashPerformers(l: List<StashPerformer>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertStashLinks(l: List<StashLink>)

    // search
    @Query("SELECT * FROM titles WHERE hidden = 0 AND name LIKE :q ORDER BY name LIMIT 60")
    suspend fun searchTitles(q: String): List<Title>

    @Query(
        "SELECT v.*, t.name AS titleName FROM videos v JOIN titles t ON t.id = v.titleId " +
            "WHERE t.hidden = 0 AND (v.fileName LIKE :q OR t.name LIKE :q) " +
            "ORDER BY t.name, v.season, v.episode LIMIT 100"
    )
    suspend fun searchVideos(q: String): List<VideoWithTitle>

    // progress
    @Query("SELECT * FROM progress") fun allProgress(): Flow<List<Progress>>
    @Query("SELECT * FROM progress WHERE uri = :uri") suspend fun progressOf(uri: String): Progress?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProgress(p: Progress)
    @Query("DELETE FROM progress") suspend fun clearProgress()

    // channels & links
    @Query("SELECT * FROM channels ORDER BY grp, name") fun channels(): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE id = :id") suspend fun channel(id: Long): Channel?
    @Query("SELECT * FROM channels WHERE url = :url LIMIT 1") suspend fun channelByUrl(url: String): Channel?
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertChannel(c: Channel): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertChannels(list: List<Channel>)
    @Query("DELETE FROM channels WHERE id = :id") suspend fun deleteChannel(id: Long)
    @Query("DELETE FROM channels") suspend fun clearChannels()
    @Query("SELECT COUNT(*) FROM channels") fun countChannels(): Flow<Int>

    // live tv
    // Big playlists can hold hundreds of thousands of entries, so lists are filtered in SQL and capped.
    @Query("SELECT * FROM channels WHERE name LIKE :q AND (:sel < 0 OR playlistId = :sel) AND playlistId NOT IN (:hp) AND grp NOT IN (:hg) ORDER BY (number = 0), number, name LIMIT 5000")
    fun allChannels(q: String, sel: Long, hp: List<Long>, hg: List<String>): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE grp = :g AND playlistId NOT IN (:hp) AND grp NOT IN (:hg) ORDER BY (number = 0), number, name LIMIT 5000")
    suspend fun groupNow(g: String, hp: List<Long>, hg: List<String>): List<Channel>
    @Query("SELECT id FROM channels WHERE favorite = 1") fun favoriteIds(): Flow<List<Long>>
    @Query("SELECT grp AS name, COUNT(*) AS n FROM channels WHERE (:sel < 0 OR playlistId = :sel) AND playlistId NOT IN (:hp) AND grp NOT IN (:hg) GROUP BY grp ORDER BY n DESC")
    fun groupCountsFor(sel: Long, hp: List<Long>, hg: List<String>): Flow<List<GroupCount>>
    @Query("SELECT * FROM channels ORDER BY (number = 0), number, name") suspend fun channelsNow(): List<Channel>
    @Query("SELECT * FROM channels WHERE grp = :g AND name LIKE :q AND (:sel < 0 OR playlistId = :sel) AND playlistId NOT IN (:hp) ORDER BY (number = 0), number, name LIMIT 5000")
    fun channelsInGroup(g: String, q: String, sel: Long, hp: List<Long>): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE grp IN (:gs) AND name LIKE :q AND (:sel < 0 OR playlistId = :sel) AND playlistId NOT IN (:hp) AND grp NOT IN (:hg) ORDER BY (number = 0), number, name LIMIT 5000")
    fun channelsInGroups(gs: List<String>, q: String, sel: Long, hp: List<Long>, hg: List<String>): Flow<List<Channel>>
    @Query("UPDATE playlists SET name = :name WHERE id = :id") suspend fun renamePlaylist(id: Long, name: String)
    @Query("SELECT * FROM channels WHERE favorite = 1 AND name LIKE :q ORDER BY name") fun favoriteChannels(q: String): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE lastPlayed > 0 AND name LIKE :q ORDER BY lastPlayed DESC LIMIT 40") fun recentChannels(q: String): Flow<List<Channel>>
    @Query("SELECT * FROM channels WHERE lastPlayed > 0 ORDER BY lastPlayed DESC LIMIT 1") fun lastPlayedChannel(): Flow<Channel?>
    @Query("SELECT grp AS name, COUNT(*) AS n FROM channels GROUP BY grp ORDER BY n DESC") fun groupCounts(): Flow<List<GroupCount>>
    @Query("SELECT playlistId, COUNT(*) AS n FROM channels GROUP BY playlistId") fun playlistCounts(): Flow<List<PlaylistCount>>
    @Query("UPDATE channels SET favorite = 1 - favorite WHERE id = :id") suspend fun toggleFavorite(id: Long)
    @Query("UPDATE channels SET lastPlayed = :t WHERE id = :id") suspend fun markPlayed(id: Long, t: Long)
    @Query("SELECT url FROM channels WHERE playlistId = :pid AND favorite = 1") suspend fun favoriteUrlsOf(pid: Long): List<String>
    @Query("UPDATE channels SET grp = :g WHERE id = :id") suspend fun setChannelGroup(id: Long, g: String)
    @Query("UPDATE channels SET grp = :newName WHERE grp = :oldName") suspend fun renameGroup(oldName: String, newName: String)
    @Query("SELECT DISTINCT playlistId FROM channels WHERE grp = :g") suspend fun playlistsOfGroup(g: String): List<Long>
    @Query("SELECT DISTINCT playlistId FROM channels WHERE grp = :g AND (:sel < 0 OR playlistId = :sel)") suspend fun playlistsOfGroupIn(g: String, sel: Long): List<Long>
    @Query("DELETE FROM channels WHERE grp = :g AND (:sel < 0 OR playlistId = :sel)") suspend fun deleteGroup(g: String, sel: Long)
    @Query("UPDATE channels SET grp = :to WHERE grp = :from AND (:sel < 0 OR playlistId = :sel)") suspend fun renameGroupIn(from: String, to: String, sel: Long)
    @Query("SELECT * FROM channels WHERE id IN (:ids)") suspend fun channelsByIds(ids: List<Long>): List<Channel>
    @Query("UPDATE channels SET grp = :g WHERE id IN (:ids)") suspend fun setGroupOfIds(ids: List<Long>, g: String)
    @Query("SELECT DISTINCT grp FROM channels WHERE playlistId = :pid") suspend fun groupsOfPlaylist(pid: Long): List<String>
    @Query("SELECT * FROM playlists WHERE id = :id") suspend fun playlistRow(id: Long): Playlist?
    @Query("UPDATE playlists SET groupList = :gl WHERE id = :id") suspend fun setGroupList(id: Long, gl: String)
    @Query("DELETE FROM channels WHERE playlistId = :pid") suspend fun deleteChannelsOfPlaylist(pid: Long)
    @Query("SELECT COUNT(*) FROM channels WHERE playlistId = :pid") suspend fun channelCountOf(pid: Long): Int
    @Query("DELETE FROM channels WHERE id IN (:ids)") suspend fun deleteChannelsByIds(ids: List<Long>)
    // sync to TV
    @Query("SELECT * FROM channels WHERE playlistId = 0") suspend fun linkChannels(): List<Channel>
    @Query("SELECT url FROM channels WHERE favorite = 1") suspend fun favoriteUrls(): List<String>
    @Query("UPDATE channels SET favorite = 0 WHERE favorite = 1") suspend fun clearFavorites()
    @Query("UPDATE channels SET favorite = 1 WHERE url IN (:urls)") suspend fun setFavoriteByUrls(urls: List<String>)
    @Query("UPDATE channels SET grp = :g WHERE url = :url") suspend fun setGroupByUrl(url: String, g: String)
    @Query("DELETE FROM channels WHERE url IN (:urls)") suspend fun deleteChannelsByUrls(urls: List<String>)

    // playlists
    @Query("SELECT * FROM playlists ORDER BY addedAt DESC") fun playlists(): Flow<List<Playlist>>
    @Insert suspend fun insertPlaylist(p: Playlist): Long
    @Query("DELETE FROM playlists WHERE id = :id") suspend fun deletePlaylist(id: Long)
    @Query("DELETE FROM playlists") suspend fun clearPlaylists()
    @Query("SELECT * FROM playlists WHERE url = :url LIMIT 1") suspend fun playlistByUrl(url: String): Playlist?

    // network shares
    @Query("SELECT * FROM smb_servers") fun smbServers(): Flow<List<SmbServer>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSmb(s: SmbServer)

    // reset
    @Query("DELETE FROM videos") suspend fun resetVideos()
    @Query("DELETE FROM titles") suspend fun resetTitles()
    @Query("DELETE FROM folders") suspend fun resetFolders()
}

@Database(
    entities = [LibraryFolder::class, Title::class, Video::class, Progress::class, Channel::class, SmbServer::class, Playlist::class,
        StashScene::class, StashPerformer::class, StashLink::class],
    version = 7,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        @Volatile private var inst: AppDb? = null
        fun get(ctx: Context): AppDb = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "library.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
                .build().also { inst = it }
        }
    }
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `smb_servers` (`host` TEXT NOT NULL, `user` TEXT NOT NULL, `domain` TEXT NOT NULL, `pass` TEXT NOT NULL, PRIMARY KEY(`host`))")

    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `playlistId` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `number` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `favorite` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `lastPlayed` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE `channels` ADD COLUMN `tvgId` TEXT")
        db.execSQL("CREATE TABLE IF NOT EXISTS `playlists` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `url` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, `groupList` TEXT NOT NULL)")
    }
}
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `stash_performers` ADD COLUMN `gender` TEXT")
    }
}
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `videos` ADD COLUMN `subPath` TEXT")
    }
}
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `stash_scenes` (`id` INTEGER NOT NULL, `title` TEXT, `date` TEXT, `rating` INTEGER, `studio` TEXT, `tags` TEXT NOT NULL, `fileName` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `size` INTEGER NOT NULL, `sizeUnique` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stash_scenes_nameKey` ON `stash_scenes` (`nameKey`)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `stash_performers` (`id` INTEGER NOT NULL, `name` TEXT NOT NULL, `hasPhoto` INTEGER NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE TABLE IF NOT EXISTS `stash_links` (`sceneId` INTEGER NOT NULL, `performerId` INTEGER NOT NULL, PRIMARY KEY(`sceneId`, `performerId`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_stash_links_performerId` ON `stash_links` (`performerId`)")
    }
}
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `videos` ADD COLUMN `durationMs` INTEGER NOT NULL DEFAULT 0")
    }
}