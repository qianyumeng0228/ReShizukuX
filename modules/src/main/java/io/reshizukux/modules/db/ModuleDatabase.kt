package io.reshizukux.modules.db

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 已安装模块元数据（设计方案 §4.4）。
 */
@Entity(tableName = "installed_modules")
data class InstalledModule(
    @PrimaryKey val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val state: String,
    val permissionLevel: String,
    val customPermissions: Int,
    val sha256: String,
    val publicKey: String?,
    val installTime: Long,
    val lastUpdateTime: Long,
    val lastActionExitCode: Int?,
    val servicePid: Int?
)

/**
 * 仓库元数据（设计方案 §3.7 / §4.4）。
 */
@Entity(tableName = "repos")
data class Repo(
    @PrimaryKey val url: String,
    val name: String,
    val publicKey: String?,
    val enabled: Boolean,
    val lastRefresh: Long
)

/**
 * 仓库缓存的模块列表（P5 商店）。
 *
 * 字段与 [io.reshizukux.modules.repository.RepoModuleInfo] 对齐；
 * 可选字段在 modules.json 缺失时落库为 null。
 */
@Entity(tableName = "repo_modules", primaryKeys = ["repoUrl", "id"])
data class RepoModule(
    val repoUrl: String,
    val id: String,
    val name: String,
    val version: String,
    val versionCode: Int,
    val author: String,
    val description: String,
    val downloadUrl: String,
    val sha256: String?,
    val signature: String?,
    val publicKey: String?,
    val minSdk: Int?,
    val requiresRoot: Boolean?,
    val usesWebUI: Boolean?,
    val changelog: String?,
    val lastUpdated: Long
)

@Dao
interface ModuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(module: InstalledModule)

    @Delete
    fun delete(module: InstalledModule)

    @Query("DELETE FROM installed_modules WHERE id = :moduleId")
    fun deleteById(moduleId: String)

    @Query("SELECT * FROM installed_modules ORDER BY name ASC")
    fun getAll(): List<InstalledModule>

    @Query("SELECT * FROM installed_modules WHERE id = :moduleId LIMIT 1")
    fun getById(moduleId: String): InstalledModule?

    @Query("UPDATE installed_modules SET state = :state WHERE id = :moduleId")
    fun updateState(moduleId: String, state: String)

    @Query("UPDATE installed_modules SET servicePid = :pid WHERE id = :moduleId")
    fun updateServicePid(moduleId: String, pid: Int?)
}

@Dao
interface RepoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(repo: Repo)

    @Delete
    fun delete(repo: Repo)

    @Query("DELETE FROM repos WHERE url = :url")
    fun deleteByUrl(url: String)

    @Query("SELECT * FROM repos ORDER BY name ASC")
    fun getAll(): List<Repo>

    @Query("SELECT * FROM repos WHERE url = :url LIMIT 1")
    fun getByUrl(url: String): Repo?

    @Query("SELECT * FROM repos WHERE enabled = 1 ORDER BY name ASC")
    fun getEnabled(): List<Repo>

    @Query("SELECT COUNT(*) FROM repos")
    fun count(): Int
}

@Dao
interface RepoModuleDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsertAll(modules: List<RepoModule>)

    @Query("DELETE FROM repo_modules WHERE repoUrl = :repoUrl")
    fun deleteByRepo(repoUrl: String)

    @Query("SELECT * FROM repo_modules WHERE repoUrl = :repoUrl ORDER BY name ASC")
    fun getByRepo(repoUrl: String): List<RepoModule>

    @Query("SELECT * FROM repo_modules WHERE repoUrl = :repoUrl AND id = :moduleId LIMIT 1")
    fun getByRepoAndId(repoUrl: String, moduleId: String): RepoModule?

    @Query("SELECT * FROM repo_modules ORDER BY name ASC")
    fun getAll(): List<RepoModule>

    @Query("SELECT COUNT(*) FROM repo_modules WHERE repoUrl = :repoUrl")
    fun countByRepo(repoUrl: String): Int
}

/**
 * 模块系统 Room 数据库。
 *
 * P1 为骨架阶段：开启 allowMainThreadQueries 以便同步门面 [io.reshizukux.modules.core.ModuleManager]
 * 直接读写；P6 接入 UI 时会迁移到 Flow/协程。
 */
@Database(
    entities = [InstalledModule::class, Repo::class, RepoModule::class],
    version = 1,
    exportSchema = false
)
abstract class ModuleDatabase : RoomDatabase() {

    abstract fun moduleDao(): ModuleDao
    abstract fun repoDao(): RepoDao
    abstract fun repoModuleDao(): RepoModuleDao

    companion object {
        private const val DATABASE_NAME = "reshizukux_modules.db"

        @Volatile
        private var instance: ModuleDatabase? = null
        private val lock = ReentrantLock()

        fun getInstance(context: Context): ModuleDatabase {
            return instance ?: lock.withLock {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): ModuleDatabase =
            Room.databaseBuilder(context, ModuleDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration()
                .allowMainThreadQueries()
                .build()

        fun resetInstance() {
            lock.withLock {
                instance?.close()
                instance = null
            }
        }
    }
}
