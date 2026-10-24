package party.qwer.iris

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

/**
 * AndroidHiddenApi — system_server(binder) reflection helper.
 *
 * ISSUE-26: 기존 구현은 companion object 초기화 시점에 startService/startActivity/
 * broadcastIntent 시그니처를 resolve 하고 없으면 throw 했다. OS 마다 시그니처가
 * 다르면(신규 major, OEM fork) 최초 class touch 한 번으로 ExceptionInInitializerError
 * — 이후 참조마다 NoClassDefFoundError 데몬 프로세스 전체가 죽는다. ROOT_ADB 모드에서
 * 서비스 on/off 토글이 곧 프로세스 사망이라는 폭탄.
 *
 * 개선:
 *   - static 초기화에서는 reflection 하지 않는다. resolve 는 호출 시 lazy + runCatching,
 *     resolve 실패는 Exception initializer가 아니라 callable 마다 명시적 IllegalStateException.
 *   - 하드코딩 userId=-3 대신 런타임 user id(UserHandle.myUserId, 루트 daemon 은 uid 0 → 0).
 *   - caller 가 미리_probe_할 수 있는 availability() 추가.
 *
 * API 호환: startService/startActivity/broadcastIntent 는 그대로 (Intent) -> Unit.
 */
@SuppressLint("PrivateApi")
class AndroidHiddenApi {
    companion object {
        /**
         * Resolve 은 lazy (SYNCHRONIZED) — 최초 호출 때만, 스레드 안전. 실패해도
         * lazy state 에 null 로 캐시되어 class 초기화 실패(ExceptionInInitializerError)로
         * 번지지 않고, 이후 접근마다 조용히 재시도(=실패)하지 않는다.
         */
        private val startServiceRef: Lazy<((Intent) -> Unit)?> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            runCatching { getStartServiceMethod() }.getOrNull()
        }
        private val startActivityRef: Lazy<((Intent) -> Unit)?> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            runCatching { getStartActivityMethod() }.getOrNull()
        }
        private val broadcastIntentRef: Lazy<((Intent) -> Unit)?> = lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            runCatching { getBroadcastIntentMethod() }.getOrNull()
        }

        /**
         * 실제 user id — -3 (USER_CURRENT? ) 고정 대신 런타임 실측.
         * UserHandle.myUserId() 는 hidden 이라 reflection, uid 가 루트(0)면 user 0.
         */
        private val currentUserId: Int by lazy {
            runCatching {
                Class.forName("android.os.UserHandle")
                    .getMethod("myUserId")
                    .invoke(null) as Int
            }.getOrElse {
                runCatching { android.os.Process.myUid() / 100000 }.getOrDefault(0)
            }
        }

        private val callingPackageName: String by lazy {
            System.getenv("IRIS_RUNNER") ?: "com.android.shell"
        }

        /** API surface — 기존 call site(`AndroidHiddenApi.startService(i)`)와 호환. */
        val startService: (Intent) -> Unit = { intent ->
            (startServiceRef.value
                ?: throw IllegalStateException("AndroidHiddenApi.startService unavailable on API ${android.os.Build.VERSION.SDK_INT}"))
                .invoke(intent)
        }

        val startActivity: (Intent) -> Unit = { intent ->
            (startActivityRef.value
                ?: throw IllegalStateException("AndroidHiddenApi.startActivity unavailable on API ${android.os.Build.VERSION.SDK_INT}"))
                .invoke(intent)
        }

        val broadcastIntent: (Intent) -> Unit = { intent ->
            (broadcastIntentRef.value
                ?: throw IllegalStateException("AndroidHiddenApi.broadcastIntent unavailable on API ${android.os.Build.VERSION.SDK_INT}"))
                .invoke(intent)
        }

        /** Caller 가 미리 상태를 살필 수 있게 — resolve 시도시 true, 미resolve/실패 false. */
        fun availability(): Map<String, Boolean> = mapOf(
            "startService" to (startServiceRef.isInitialized() && startServiceRef.value != null),
            "startActivity" to (startActivityRef.isInitialized() && startActivityRef.value != null),
            "broadcastIntent" to (broadcastIntentRef.isInitialized() && broadcastIntentRef.value != null)
        )

        private fun getStartServiceMethod(): (Intent) -> Unit {
            val IActivityManagerStub = Class.forName("android.app.IActivityManager\$Stub")
            val IActivityManager = Class.forName("android.app.IActivityManager")
            val IApplicationThread = Class.forName("android.app.IApplicationThread")

            val activityManager =
                IActivityManagerStub.getMethod("asInterface", IBinder::class.java).invoke(
                    null, getService("activity")
                )


            try {
                // IApplicationThread caller, Intent service, String resolvedType,
                // boolean requireForeground, String callingPackage, String callingFeatureId, int userId
                val method = IActivityManager.getMethod(
                    "startService",
                    IApplicationThread,
                    Intent::class.java,
                    java.lang.String::class.java,
                    java.lang.Boolean.TYPE,
                    java.lang.String::class.java,
                    java.lang.String::class.java,
                    java.lang.Integer.TYPE,
                )

                return { intent ->
                    method.invoke(
                        activityManager, null, intent, null, false, callingPackageName, null, currentUserId
                    )
                }
            } catch (_: Exception) {
            }

            try {
                // IApplicationThread caller, Intent service, String resolvedType,
                // boolean requireForeground, in String callingPackage, int userId);
                val method = IActivityManager.getMethod(
                    "startService",
                    IApplicationThread,
                    Intent::class.java,
                    java.lang.String::class.java,
                    java.lang.Boolean.TYPE,
                    java.lang.String::class.java,
                    java.lang.Integer.TYPE,
                )

                return { intent ->
                    method.invoke(
                        activityManager, null, intent, null, false, callingPackageName, currentUserId
                    )
                }
            } catch (_: Exception) {
            }


            val sdk = android.os.Build.VERSION.SDK_INT
            val methods = IActivityManager.methods.map {
                it.toString().trim()
            }.filter {
                it.contains("startService")
            }.joinToString("\n")

            val errorMsg = """
                failed to get startService Method. Please report
                SDK: $sdk
                METHODS: $methods
            """.trimIndent()

            println(errorMsg)
            throw Exception(errorMsg)
        }

        private fun getStartActivityMethod(): (Intent) -> Unit {
            val IActivityManagerStub = Class.forName("android.app.IActivityManager\$Stub")
            val IActivityManager = Class.forName("android.app.IActivityManager")
            val IApplicationThread = Class.forName("android.app.IApplicationThread")

            val activityManager =
                IActivityManagerStub.getMethod("asInterface", IBinder::class.java).invoke(
                    null, getService("activity")
                )


            try {
                // IApplicationThread caller, String callingPackage, String callingFeatureId,
                // Intent intent, String resolvedType, IBinder resultTo, String resultWho,
                // int requestCode, int flags, ProfilerInfo profilerInfo, Bundle options, int userId
                val ProfilerInfo = Class.forName("android.app.ProfilerInfo")
                val method = IActivityManager.getMethod(
                    "startActivity",
                    IApplicationThread,
                    String::class.java,
                    String::class.java,
                    Intent::class.java,
                    String::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    ProfilerInfo,
                    Bundle::class.java,
                    Integer.TYPE
                )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        callingPackageName,
                        null,
                        intent,
                        intent.type,
                        null,
                        null,
                        0,
                        0,
                        null,
                        null,
                        currentUserId
                    )
                }
            } catch (_: Exception) {
            }

            try {
                // IApplicationThread, java.lang.String, android.content.Intent,
                // java.lang.String, android.os.IBinder, java.lang.String, int, int, android.app.ProfilerInfo, android.os.Bundle, int
                val ProfilerInfo = Class.forName("android.app.ProfilerInfo")
                val method = IActivityManager.getMethod(
                    "startActivityAsUser",
                    IApplicationThread,
                    String::class.java,
                    Intent::class.java,
                    String::class.java,
                    IBinder::class.java,
                    String::class.java,
                    Integer.TYPE,
                    Integer.TYPE,
                    ProfilerInfo,
                    Bundle::class.java,
                    Integer.TYPE
                )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        callingPackageName,
                        intent,
                        intent.type,
                        null,
                        null,
                        0,
                        0,
                        null,
                        null,
                        currentUserId
                    )
                }
            } catch (_: Exception) {
            }

            val sdk = android.os.Build.VERSION.SDK_INT
            val methods = IActivityManager.methods.map {
                it.toString().trim()
            }.filter {
                it.contains("startActivity")
            }.joinToString("\n")

            val errorMsg = """
                failed to get startActivity Method. Please report
                SDK: $sdk
                METHODS: $methods
            """.trimIndent()

            println(errorMsg)
            throw Exception(errorMsg)
        }

        private fun getBroadcastIntentMethod(): (Intent) -> Unit {
            val IActivityManagerStub = Class.forName("android.app.IActivityManager\$Stub")
            val IActivityManager = Class.forName("android.app.IActivityManager")
            val IApplicationThread = Class.forName("android.app.IApplicationThread")

            val activityManager =
                IActivityManagerStub.getMethod("asInterface", IBinder::class.java).invoke(
                    null, getService("activity")
                )


            try {
                // IApplicationThread caller, Intent intent, String resolvedType,
                // IIntentReceiver resultTo, int resultCode, String resultData,
                // Bundle map, String[] requiredPermissions, int appOp, Bundle options,
                // boolean serialized, boolean sticky, int userId
                val IIntentReceiver = Class.forName("android.content.IIntentReceiver")
                val method = IActivityManager.getMethod(
                    "broadcastIntent",
                    IApplicationThread,
                    Intent::class.java,
                    String::class.java,
                    IIntentReceiver,
                    Integer.TYPE,
                    String::class.java,
                    Bundle::class.java,
                    Array<String>::class.java,
                    Integer.TYPE,
                    Bundle::class.java,
                    Boolean::class.java,
                    Boolean::class.java,
                    Int::class.java
                )

                return { intent ->
                    method.invoke(
                        activityManager,
                        null,
                        intent,
                        null,
                        null,
                        0,
                        null,
                        null,
                        null,
                        -1,
                        null,
                        false,
                        false,
                        currentUserId
                    )
                }
            } catch (_: Exception) {
            }


            val sdk = android.os.Build.VERSION.SDK_INT
            val methods = IActivityManager.methods.map {
                it.toString().trim()
            }.filter {
                it.contains("broadcastIntent")
            }.joinToString("\n")

            val errorMsg = """
                failed to get broadcastIntent Method. Please report
                SDK: $sdk
                METHODS: $methods
            """.trimIndent()

            println(errorMsg)
            throw Exception(errorMsg)
        }

        private fun getService(name: String): IBinder {
            val method = Class.forName("android.os.ServiceManager")
                .getMethod("getService", String::class.java)

            return method.invoke(null, name) as IBinder
        }
    }
}
