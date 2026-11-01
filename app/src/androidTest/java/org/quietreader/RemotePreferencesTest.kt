package org.quietreader

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import org.quietreader.remote.GitHubConnection
import org.quietreader.remote.RemotePreferences
import java.io.File

@Suppress("DEPRECATION")
class RemotePreferencesTest:AndroidTestCase(){
    private lateinit var isolated:Context
    private lateinit var directory:File
    override fun setUp(){super.setUp();directory=File(context.cacheDir,"account-test-"+java.util.UUID.randomUUID()).apply{mkdirs()}
        isolated=object:ContextWrapper(context){override fun getNoBackupFilesDir()=directory;override fun getApplicationContext():Context=this;override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(directory.name+name,mode)}
    }
    override fun tearDown(){isolated.getSharedPreferences("github-connections",0).edit().clear().commit();isolated.getSharedPreferences("progress-device",0).edit().clear().commit();directory.walkBottomUp().forEach{it.delete()};super.tearDown()}
    fun testFreshInstallContainsNoAccountOrRepository(){
        val preferences=RemotePreferences(isolated)
        listOf(false,true).forEach{sync->assertEquals("",preferences.connection(sync).owner);assertEquals("",preferences.connection(sync).repo);assertFalse(preferences.validated(sync));assertFalse(preferences.loggedIn(sync));try{preferences.requireLogin(sync);fail("Login is required")}catch(e:IllegalArgumentException){assertTrue(e.message!!.contains("自己的 GitHub"))}}
    }
    fun testStoredAccountTokenAndConnectionSurviveReopenAndSameAccountAuthorization(){
        val preferences=RemotePreferences(isolated)
        listOf(false,true).forEach{sync->val account=if(sync)"saved-sync-reader"else"saved-reader";preferences.login(sync,"saved-fixture-token",account);val connection=GitHubConnection("saved-owner",if(sync)"saved-progress"else"saved-books","custom-branch",if(sync)""else"library","catalog.json");preferences.save(sync,connection)
            val reopened=RemotePreferences(isolated);assertEquals(connection,reopened.connection(sync));assertEquals(account,reopened.user(sync));assertEquals("saved-fixture-token",reopened.token(sync));assertTrue(reopened.validated(sync))
            reopened.login(sync,"renewed-fixture-token",account);assertEquals(connection,reopened.connection(sync));assertTrue(reopened.validated(sync))
        }
    }
    fun testDifferentAccountMustSelectItsOwnRepositoryAndOtherRoleIsPreserved(){
        val preferences=RemotePreferences(isolated);preferences.login(false,"old-fixture","old-reader");preferences.save(false,GitHubConnection("old-owner","old-books"))
        preferences.login(true,"sync-fixture","sync-reader");val syncConnection=GitHubConnection("sync-owner","private-progress");preferences.save(true,syncConnection)
        File(directory,"github-catalog.json").writeText("old catalog")
        preferences.login(false,"new-fixture","new-reader")
        assertEquals("new-reader",preferences.connection(false).owner);assertEquals("",preferences.connection(false).repo);assertFalse(preferences.validated(false));assertFalse(File(directory,"github-catalog.json").exists())
        assertEquals(syncConnection,preferences.connection(true));assertEquals("sync-fixture",preferences.token(true))
    }
    fun testSyncAccountSwitchPausesSynchronizationAndClearsOldRepository(){
        val preferences=RemotePreferences(isolated);preferences.login(true,"old-fixture","old-reader");preferences.save(true,GitHubConnection("old-owner","private-progress"))
        isolated.getSharedPreferences("progress-device",0).edit().putBoolean("enabled",true).putBoolean("paused",false).commit()
        preferences.login(true,"new-fixture","new-reader")
        assertFalse(preferences.validated(true));assertEquals("",preferences.connection(true).repo)
        assertTrue(isolated.getSharedPreferences("progress-device",0).getBoolean("paused",false));assertFalse(isolated.getSharedPreferences("progress-device",0).getBoolean("enabled",true))
    }
    fun testLogoutRemovesAccountAndRepository(){
        val preferences=RemotePreferences(isolated);preferences.login(false,"fixture-token","reader");preferences.save(false,GitHubConnection("owner","books"));preferences.logout(false)
        val reopened=RemotePreferences(isolated);assertFalse(reopened.loggedIn(false));assertFalse(reopened.validated(false));assertEquals("",reopened.connection(false).owner);assertEquals("",reopened.connection(false).repo)
    }
}
