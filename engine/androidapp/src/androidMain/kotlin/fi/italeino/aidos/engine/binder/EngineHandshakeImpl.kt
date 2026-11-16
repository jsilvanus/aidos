package fi.italeino.aidos.engine.binder



import android.content.Context

import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build

import android.os.Bundle

import android.os.IBinder

import fi.italeino.aidos.engine.HandshakeResult

import fi.italeino.aidos.engine.approval.AppApprovalManager

import fi.italeino.aidos.engine.handshake.HandshakeCore

import fi.italeino.aidos.engine.handshake.HandshakeWire

import fi.italeino.aidos.engine.handshake.handshakeCapabilities

import fi.italeino.aidos.engine.http.TokenManager

import kotlinx.coroutines.runBlocking
import java.security.MessageDigest



/**

 * Implementation of the Aidos Engine handshake Binder interface (RFC-0103).

 *

 * Only the Android-specific parts live here: resolving the caller from the Binder UID and

 * attaching the deep-link `PendingIntent`. The decision itself (unknown → PENDING + notify,

 * approved → per-app token, denied, revoked) is [HandshakeCore], which is unit-tested on the JVM.

 *

 * This is the one Binder surface Engine exposes. All other traffic goes via HTTP.

 */

class EngineHandshakeImpl(

    private val context: Context,

    tokenManager: TokenManager,

    boundPort: suspend () -> Int?,

    private val approvalManager: AppApprovalManager,

    private val modelRuntime: suspend () -> dev.aidos.kernel.ModelRuntime?

) : fi.italeino.aidos.engine.IEngineHandshake.Stub() {



    private val core = HandshakeCore(

        store = approvalManager.store,

        tokenManager = tokenManager,

        boundPort = boundPort,

        capabilities = { (modelRuntime() ?: throw IllegalStateException("Model runtime not ready")).handshakeCapabilities() },

        resolveDisplayName = approvalManager::displayNameOf,

        onFirstRequest = approvalManager::notifyFirstRequest,

        engineVersion = engineVersionName()

    )



    override fun performHandshake(): Bundle {

        val callerPackageName = getPackageNameForUid(Binder.getCallingUid())



        return try {

            // runBlocking is necessary because Binder calls are synchronous but the core is suspend.

            val signer = callerPackageName?.let(::signerSha256Of)
            val reply = runBlocking { core.handshake(callerPackageName, signer) }



            HandshakeResult(

                status = reply.status,

                port = reply.port,

                token = reply.token,

                apiVersion = reply.apiVersion,

                capabilitiesJson = reply.capabilitiesJson,

                engineVersion = reply.engineVersion,

                minApiVersion = reply.minApiVersion,

                deepLinkPendingIntent =

                    if (reply.status == HandshakeWire.STATUS_PENDING_APPROVAL) approvalManager.deepLinkIntent() else null

            ).toBundle()

        } catch (_: Exception) {

            HandshakeResult(

                status = HandshakeWire.STATUS_DENIED,

                port = 0,

                token = "",

                apiVersion = HandshakeWire.API_VERSION,

                capabilitiesJson = "{}",

                deepLinkPendingIntent = null

            ).toBundle()

        }

    }



    private fun engineVersionName(): String =

        try {

            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

        } catch (_: Exception) {

            ""

        }



    // A shared user id puts several packages behind one UID, in no promised order. Sorting makes
    // the same caller map to the same approval record every time (RFC-0103, amendment 2026-11-16).
    private fun getPackageNameForUid(uid: Int): String? =
        context.packageManager.getPackagesForUid(uid)?.sorted()?.firstOrNull()

    /** SHA-256 of the package's signing certificate (its oldest one, so a key rotation keeps the pin), or null. */
    @Suppress("DEPRECATION")
    private fun signerSha256Of(packageName: String): String? = try {
        val pm = context.packageManager
        val certificate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            // signingCertificateHistory lists the original certificate first when the key was rotated.
            (if (info?.hasMultipleSigners() == true) info.apkContentsSigners else info?.signingCertificateHistory)?.firstOrNull()
        } else {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures?.firstOrNull()
        }
        certificate?.toByteArray()?.let { bytes ->
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        }
    } catch (_: Exception) {
        null
    }

    override fun asBinder(): IBinder = this

}

