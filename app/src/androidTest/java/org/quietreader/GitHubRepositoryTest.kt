package org.quietreader

import android.test.AndroidTestCase
import com.google.gson.JsonParser
import org.quietreader.remote.GitHubApi
import org.quietreader.remote.GitHubConnection

@Suppress("DEPRECATION")
class GitHubRepositoryTest:AndroidTestCase(){
    private fun api(commit:()->String)=GitHubApi(readJson={path->if(path.endsWith("/commits/main"))JsonParser.parseString(commit())else JsonParser.parseString("""{"default_branch":"main"}""")})
    fun testEmptyRepositoryCanBeRecognizedWithoutTreatingItAsWriteConflict(){
        val client=api{throw GitHubApi.Failure(409,"Git Repository is empty.")}
        assertNull(client.revisionOrNull(GitHubConnection()))
        try{client.revision(GitHubConnection());fail("Browsing empty repository must explain missing content")}catch(e:GitHubApi.EmptyRepository){assertTrue(e.message!!.contains("catalog.json"))}
    }
    fun testExistingRepositoryReturnsCommit(){
        assertEquals("fixture-commit",api{"""{"sha":"fixture-commit"}"""}.revisionOrNull(GitHubConnection()))
    }
    fun testMissingBranchIsNotAcceptedAsEmptyRepository(){
        for(status in listOf(404,422)){
            try{api{throw GitHubApi.Failure(status)}.revisionOrNull(GitHubConnection());fail("Missing branch must fail")}
            catch(e:IllegalStateException){assertTrue(e.message!!.contains("main"));assertTrue(e.message!!.contains(status.toString()));assertFalse(e.message!!.contains("重新同步"))}
        }
    }
    fun testUnknownConflictIsNotSilentlyAcceptedAsEmpty(){
        try{api{throw GitHubApi.Failure(409,"Another conflict")}.revisionOrNull(GitHubConnection());fail("Unknown conflict must fail")}
        catch(e:GitHubApi.Failure){assertEquals(409,e.status)}
    }
    fun testOnlyWriteConflictRecommendsResync(){
        assertTrue(GitHubApi.Failure(409,method="PUT").message!!.contains("重新同步"))
        assertFalse(GitHubApi.Failure(409).message!!.contains("重新同步"))
        assertFalse(GitHubApi.Failure(422).message!!.contains("远端版本发生变化"))
    }
}
