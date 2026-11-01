package org.quietreader.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.*
import org.junit.Test

class ReleaseUpdatesTest {
    private fun asset(name:String,url:String="https://github.com/1644ye/jingdu/releases/download/v1.10.0/$name")=JsonObject().apply{addProperty("name",name);addProperty("browser_download_url",url);addProperty("size",1000)}
    private fun release(vararg assets:JsonObject)=JsonObject().apply{addProperty("tag_name","v1.10.0");addProperty("body","更新说明");addProperty("draft",false);addProperty("prerelease",false);add("assets",JsonArray().apply{assets.forEach{add(it)}})}
    private fun rejected(block:()->Unit){try{block();fail("Should reject invalid release")}catch(_:IllegalArgumentException){}catch(_:IllegalStateException){}}
    @Test fun numericVersionsDoNotUseLexicalOrTagDates(){
        assertTrue(ReleaseUpdates.newer("v1.10.0","1.9.9"));assertTrue(ReleaseUpdates.newer("2.0.0","1.99.99"))
        assertFalse(ReleaseUpdates.newer("1.3.0","1.3.0"));assertFalse(ReleaseUpdates.newer("1.2.9","1.3.0"))
        listOf("1.3.0-beta","latest","1.3","../1.3.0").forEach{rejected{ReleaseUpdates.version(it)}}
    }
    @Test fun selectsCurrentDebugDistributionWhenReleaseHasOtherAssets(){
        val parsed=ReleaseUpdates.parse(release(asset("QuietReader-1.10.0-debug.apk"),asset("release.apk"),asset("sources.zip")))
        assertEquals("1.10.0",parsed.version);assertEquals("更新说明",parsed.notes);assertTrue(parsed.url.endsWith("-debug.apk"))
    }
    @Test fun aSingleApkIsSupportedButAmbiguousAndMissingPackagesAreRejected(){
        assertEquals(1000L,ReleaseUpdates.parse(release(asset("app.apk"))).bytes)
        rejected{ReleaseUpdates.parse(release(asset("one.apk"),asset("two.apk")))};rejected{ReleaseUpdates.parse(release(asset("source.zip")))}
    }
    @Test fun draftAndPreviewDoNotBecomeAnAutomaticUpdate(){
        listOf("draft","prerelease").forEach{flag->rejected{ReleaseUpdates.parse(release(asset("app.apk")).apply{addProperty(flag,true)})}}
    }
    @Test fun unrelatedHostsAndRepositoriesCannotProvideAnInstaller(){
        listOf("http://github.com/1644ye/jingdu/releases/download/v1.10.0/a.apk","https://example.com/a.apk","https://github.com/other/repo/releases/download/v1/a.apk","https://github.com@evil.example/1644ye/jingdu/releases/download/v1/a.apk").forEach{url->rejected{ReleaseUpdates.parse(release(asset("app.apk",url)))}}
    }
}
