package dev.openhealthbridge

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.work.*
import java.util.concurrent.TimeUnit

class BridgeApp : Application() {
    val database by lazy { Room.databaseBuilder(this, BridgeDatabase::class.java, "bridge.db").build() }
    val store by lazy { TokenStore(this) }
    val engine by lazy { SyncEngine(this, database, store, HealthGateway(this), WebhookClient()) }
    override fun onCreate() { super.onCreate(); engine.schedule() }
}
fun Context.bridge() = applicationContext as BridgeApp
class HealthReadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext.bridge()
        return try { app.engine.read(background = true); Result.success() }
        catch (_: SecurityException) { Result.success() }
        catch (error: Exception) { app.engine.log(if (error is IllegalStateException && error.message?.startsWith("Storage payload budget") == true) "Storage payload budget reached; deliver pending data or clear local data" else "Background read failed; open the app to check permissions"); Result.retry() }
    }
}
class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try {
        if (applicationContext.bridge().engine.deliver()) Result.retry() else Result.success()
    } catch (_: Exception) { Result.retry() }
}
