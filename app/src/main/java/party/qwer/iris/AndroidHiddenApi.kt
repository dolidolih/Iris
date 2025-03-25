package party.qwer.iris

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.IBinder

@SuppressLint("PrivateApi")
class AndroidHiddenApi {
    companion object {
        val startService = getStartServiceMethod()
        val startActivity = getStartActivityMethod()
        val broadcastIntent = getBroadcastIntentMethod()

        private val callingPackageName: String by lazy {
            System.getenv("IRIS_RUNNER") ?: "com.android.shell"
        }

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
                        activityManager, null, intent, null, false, callingPackageName, null, -3
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
                        activityManager, null, intent, null, false, callingPackageName, -3
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
                        -3
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
                        -3
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
                        -3
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