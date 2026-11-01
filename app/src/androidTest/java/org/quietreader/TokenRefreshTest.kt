package org.quietreader

import android.content.Context
import android.content.ContextWrapper
import android.test.AndroidTestCase
import com.google.gson.JsonObject
import org.quietreader.remote.GitHubApi
import org.quietreader.remote.GitHubConnection
import org.quietreader.remote.RemotePreferences
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@Suppress("DEPRECATION")
class TokenRefreshTest:AndroidTestCase(){
    private lateinit var isolated:Context
    private lateinit var directory:File
    private var clock=1_000_000L
    override fun setUp(){super.setUp();directory=File(context.cacheDir,"refresh-test-"+java.util.UUID.randomUUID()).apply{mkdirs()};isolated=object:ContextWrapper(context){override fun getApplicationContext():Context=this;override fun getNoBackupFilesDir()=directory;override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(directory.name+name,mode)}}
    override fun tearDown(){isolated.getSharedPreferences("github-connections",0).edit().clear().commit();isolated.getSharedPreferences("progress-device",0).edit().clear().commit();directory.walkBottomUp().forEach{it.delete()};super.tearDown()}
    private fun response(token:String="fresh-access",refresh:String="fresh-refresh")=JsonObject().apply{addProperty("access_token",token);addProperty("refresh_token",refresh);addProperty("expires_in",120);addProperty("refresh_token_expires_in",3600)}
    private fun preferences(refresh:(Map<String,String>)->JsonObject)=RemotePreferences(isolated,refresh,{clock})
    fun testRotationIsEncryptedDurableAndPreservesRepositoryAndEnabledSync(){
        val requests=mutableListOf<Map<String,String>>()
        val p=preferences{requests.add(it);response()}
        p.login(true,response("old-access","old-refresh"),"fixture-reader");val connection=GitHubConnection("fixture-owner","private-progress");p.save(true,connection)
        val sync=isolated.getSharedPreferences("progress-device",0);sync.edit().putBoolean("enabled",true).putBoolean("paused",false).commit()
        clock+=61_000;assertEquals("fresh-access",p.accessToken(true));assertEquals(1,requests.size)
        assertEquals(GitHubApi.STATE_CLIENT,requests.single()["client_id"]);assertEquals("old-refresh",requests.single()["refresh_token"]);assertFalse(requests.single().containsKey("client_secret"))
        assertEquals(connection,p.connection(true));assertTrue(p.validated(true));assertTrue(sync.getBoolean("enabled",false));assertFalse(sync.getBoolean("paused",true))
        val raw=isolated.getSharedPreferences("github-connections",0).all.values.joinToString();assertFalse(raw.contains("fresh-access"));assertFalse(raw.contains("fresh-refresh"))
        val reopened=preferences{fail("No refresh before expiry");response()};assertEquals("fresh-access",reopened.accessToken(true))
        clock+=61_000;p.accessToken(true);assertEquals("fresh-refresh",requests.last()["refresh_token"])
    }
    fun testRolesRefreshIndependentlyAndUseCorrectClientIds(){
        val calls=mutableListOf<Map<String,String>>();val p=preferences{calls.add(it);response("new-${it["client_id"]}","new-refresh")}
        p.login(false,response("source","source-refresh"),"source-reader");p.login(true,response("state","state-refresh"),"state-reader")
        assertEquals("source",p.accessToken(false));assertTrue(calls.isEmpty())
        clock+=61_000;p.accessToken(false);assertEquals("state",p.token(true));p.accessToken(true)
        assertEquals(listOf(GitHubApi.LIBRARY_CLIENT,GitHubApi.STATE_CLIENT),calls.map{it["client_id"]})
    }
    fun testConcurrentInstancesConsumeRotatingRefreshTokenOnlyOnce(){
        val count=AtomicInteger();val started=CountDownLatch(1);val release=CountDownLatch(1)
        val action:(Map<String,String>)->JsonObject={count.incrementAndGet();started.countDown();check(release.await(10,TimeUnit.SECONDS));response()}
        val a=preferences(action);a.login(false,response("old","old-refresh"),"reader");clock+=61_000
        val b=preferences(action);val pool=Executors.newFixedThreadPool(2)
        try{val one=pool.submit(Callable{a.accessToken(false)});assertTrue(started.await(5,TimeUnit.SECONDS));val two=pool.submit(Callable{b.accessToken(false)});release.countDown();assertEquals("fresh-access",one.get(5,TimeUnit.SECONDS));assertEquals("fresh-access",two.get(5,TimeUnit.SECONDS));assertEquals(1,count.get())}finally{release.countDown();pool.shutdownNow()}
    }
    fun testLogoutDuringRefreshDoesNotResurrectCredentials(){
        val started=CountDownLatch(1);val release=CountDownLatch(1)
        val p=preferences{started.countDown();check(release.await(10,TimeUnit.SECONDS));response()};p.login(false,response(),"reader");clock+=61_000
        val pool=Executors.newSingleThreadExecutor()
        try{val task=pool.submit(Callable{runCatching{p.accessToken(false)}.exceptionOrNull()});assertTrue(started.await(5,TimeUnit.SECONDS));p.logout(false);release.countDown();assertNotNull(task.get(5,TimeUnit.SECONDS));assertEquals("",p.token(false));assertFalse(p.loggedIn(false))}finally{release.countDown();pool.shutdownNow()}
    }
    fun testNetworkFailureKeepsCredentialsAndCanRetry(){
        var calls=0;val p=preferences{if(++calls==1)throw IOException("fixture offline");response()};p.login(false,response("old","old-refresh"),"reader");clock+=61_000
        try{p.accessToken(false);fail("Network failure must be reported")}catch(_:IOException){}
        assertEquals("old",p.token(false));assertTrue(p.sessionStatus(false).contains("自动续期"));assertEquals("fresh-access",p.accessToken(false));assertEquals(2,calls)
    }
    fun testRevocationRequiresLoginWithoutErasingRepositoryOrRepeatedRefresh(){
        var calls=0;val p=preferences{calls++;JsonObject().apply{addProperty("error","bad_refresh_token")}};p.login(false,response(),"reader");val connection=GitHubConnection("owner","books");p.save(false,connection);clock+=61_000
        repeat(2){try{p.accessToken(false);fail("Revocation must require login")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("重新登录"))}}
        assertEquals(1,calls);assertEquals(connection,p.connection(false));assertTrue(p.validated(false));assertTrue(p.sessionStatus(false).contains("已失效"))
        p.login(false,response("reauthorized","reauthorized-refresh"),"reader");assertEquals(connection,p.connection(false));assertEquals("reauthorized",p.accessToken(false))
    }
    fun testExpiredRefreshAndLegacyLoginHaveActionableMessages(){
        val p=preferences{fail("No HTTP call expected");response()};p.login(false,response(),"reader");clock+=3_600_001
        try{p.accessToken(false);fail("Expired refresh must fail")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("重新登录"))}
        p.login(false,"legacy-access","reader");isolated.getSharedPreferences("github-connections",0).edit().remove("library-auth-metadata").commit()
        assertEquals("legacy-access",p.accessToken(false));assertTrue(p.sessionStatus(false).contains("旧版登录"))
        try{p.accessToken(false,"legacy-access");fail("Legacy token cannot refresh")}catch(e:IllegalStateException){assertTrue(e.message!!.contains("重新登录一次"))}
        p.login(false,"non-expiring","reader");clock+=999_999_999;assertEquals("non-expiring",p.accessToken(false));assertTrue(p.sessionStatus(false).contains("不自动过期"))
    }
    fun testIncompleteRefreshDoesNotOverwriteStoredPair(){
        val p=preferences{JsonObject().apply{addProperty("access_token","incomplete")}};p.login(false,response("old","old-refresh"),"reader");clock+=61_000
        try{p.accessToken(false);fail("Incomplete pair must be rejected")}catch(_:IllegalArgumentException){}
        assertEquals("old",p.token(false));assertFalse(p.sessionStatus(false).contains("已失效"))
    }
    fun testHttpRefreshRejectionRequiresLoginAndTransientFailureKeepsCredentials(){
        for(error in listOf(GitHubApi.Failure(400,oauthError="bad_refresh_token"),GitHubApi.Failure(503))){
            val p=preferences{throw error};p.login(false,response("old","old-refresh"),"reader");clock+=61_000
            try{p.accessToken(false);fail("Failure must propagate")}catch(e:Exception){if(error.status==400)assertTrue(e is IllegalStateException)else assertTrue(e is IOException)}
            assertEquals(error.status==400,p.sessionStatus(false).contains("已失效"));assertEquals("old",p.token(false))
        }
    }
    private class Response(url:URL,private val status:Int,private val body:String):HttpURLConnection(url){
        override fun connect(){};override fun disconnect(){};override fun usingProxy()=false
        override fun getResponseCode()=status
        override fun getInputStream()=body.byteInputStream()
        override fun getErrorStream()="{}".byteInputStream()
        override fun getOutputStream()=java.io.ByteArrayOutputStream()
        override fun getHeaderField(name:String?)=if(name=="Content-Range")"bytes 3-5/6"else null
    }
    fun testGetAndPutRetryOnceWithFreshAuthorization(){
        for(method in listOf("GET","PUT")){
            var refreshes=0;val requests=mutableListOf<Response>();val p=preferences{refreshes++;response()};p.login(false,response("old","old-refresh"),"reader")
            val client=GitHubApi(accessToken={p.accessToken(false)},refreshUnauthorized={p.accessToken(false,it)},openRequest={url->Response(url,if(requests.isEmpty())401 else 200,"{\"ok\":true}").also{requests.add(it)}})
            assertTrue(client.request("https://api.github.com/repos/owner/books",method,if(method=="PUT")"{}"else null).contains("true"));assertEquals(1,refreshes);assertEquals(2,requests.size)
            assertEquals("Bearer old",requests[0].getRequestProperty("Authorization"));assertEquals("Bearer fresh-access",requests[1].getRequestProperty("Authorization"))
        }
    }
    fun testSecondUnauthorizedAndOtherFailuresDoNotLoop(){
        for(status in listOf(401,403)){
            var calls=0;var refreshes=0
            val client=GitHubApi(accessToken={"old"},refreshUnauthorized={refreshes++;"new"},openRequest={url->calls++;Response(url,status,"")})
            try{client.get("/user");fail("Request must fail")}catch(e:GitHubApi.Failure){assertEquals(status,e.status)}
            assertEquals(if(status==401)2 else 1,calls);assertEquals(if(status==401)1 else 0,refreshes)
        }
    }
    fun testDownload401RefreshKeepsPartialContentAndRange(){
        var calls=0;val requests=mutableListOf<Response>();val target=File(directory,"book.part").apply{writeText("abc")}
        val client=GitHubApi(accessToken={"old"},refreshUnauthorized={"fresh"},openDownload={url->Response(url,if(calls++==0)401 else 206,"def").also{requests.add(it)}})
        client.downloadFile(GitHubConnection("owner","books"),"book.json","commit",target,{false},{})
        assertEquals("abcdef",target.readText());assertEquals("bytes=3-",requests.last().getRequestProperty("Range"));assertEquals("Bearer fresh",requests.last().getRequestProperty("Authorization"))
    }
}
