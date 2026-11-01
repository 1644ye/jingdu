package org.quietreader

import android.test.AndroidTestCase
import org.quietreader.update.AppUpdater
import java.io.File

@Suppress("DEPRECATION")
class AppUpdaterTest:AndroidTestCase(){
    fun testOtherApplicationCannotBeInstalledAsReaderUpdate(){
        val apk=context.packageManager.getApplicationInfo("org.quietreader.test",0).sourceDir
        try{AppUpdater(context).validateApk(File(apk));fail("Other package should be rejected")}
        catch(error:IllegalArgumentException){assertTrue(error.message.orEmpty().contains("不是静读"))}
    }
    fun testCurrentApkCannotBeInstalledAsANewerVersion(){
        val updater=AppUpdater(context)
        val packageInfo=context.packageManager.getPackageInfo(context.packageName,0)
        assertEquals(packageInfo.versionName,updater.installedVersion)
        try{updater.validateApk(File(context.applicationInfo.sourceDir));fail("Same version should be rejected")}
        catch(error:IllegalArgumentException){assertTrue(error.message.orEmpty().contains("版本不高于"))}
    }
    fun testUnreadableInstallerIsRejected(){
        val file=File(context.cacheDir,"invalid-update-test.apk");file.writeText("not an APK")
        try{AppUpdater(context).validateApk(file);fail("Invalid package should be rejected")}
        catch(error:IllegalStateException){assertTrue(error.message.orEmpty().contains("无法读取安装包"))}
        finally{file.delete()}
    }
}
