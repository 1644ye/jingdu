package org.quietreader.remote

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Credentials stay in device preferences, outside library backups. Rotation never changes repositories. */
class RemotePreferences(
    private val context:Context,
    private val refreshRequest:(Map<String,String>)->JsonObject={GitHubApi.oauth("oauth/access_token",it)},
    private val now:()->Long={System.currentTimeMillis()}
) {
    companion object {
        private val stateLock=Any()
        private val refreshLocks=arrayOf(Any(),Any())
    }
    private val preferences=context.getSharedPreferences("github-connections",Context.MODE_PRIVATE)
    private val gson=Gson()
    private fun role(sync:Boolean)=if(sync)"state"else"library"
    fun connection(sync:Boolean):GitHubConnection=gson.fromJson(preferences.getString(role(sync),null),GitHubConnection::class.java)?:GitHubConnection(owner=preferences.getString("${role(sync)}-user","").orEmpty())
    fun loggedIn(sync:Boolean)=token(sync).isNotEmpty()
    fun requireLogin(sync:Boolean):String=token(sync).also{require(it.isNotEmpty()){"请先登录自己的 GitHub 账号"}}
    fun validated(sync:Boolean)=preferences.getBoolean("${role(sync)}-valid",false)
    private fun pauseSync(){context.getSharedPreferences("progress-device",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).putBoolean("paused",true).commit()}
    fun save(sync:Boolean,value:GitHubConnection){value.validate();require(!value.owner.equals(connection(!sync).owner,true)||!value.repo.equals(connection(!sync).repo,true)){"书源和同步必须使用不同仓库"};if(!sync&&connection(false)!=value)(context.applicationContext as? org.quietreader.ReaderApp)?.downloads?.cancelAll();preferences.edit().putString(role(sync),gson.toJson(value)).putBoolean("${role(sync)}-valid",true).commit();if(sync)pauseSync()}
    private fun key():SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply{load(null)}
        return (store.getKey("jingdu-github",null) as? SecretKey)?:KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply{init(KeyGenParameterSpec.Builder("jingdu-github",KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())}.generateKey()
    }
    private fun encrypt(value:String):String {
        val cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key())
        return Base64.encodeToString(cipher.iv,Base64.NO_WRAP)+":"+Base64.encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)),Base64.NO_WRAP)
    }
    private fun decrypt(value:String):String {
        val parts=value.split(':');val cipher=Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE,key(),GCMParameterSpec(128,Base64.decode(parts[0],Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(parts[1],Base64.NO_WRAP)),Charsets.UTF_8)
    }
    fun token(sync:Boolean):String=preferences.getString("${role(sync)}-token",null)?.let(::decrypt).orEmpty()
    private data class Credentials(val token:String,val refresh:String?,val expires:Long,val refreshExpires:Long)
    private fun credentials(response:JsonObject,rotating:Boolean=false):Credentials {
        val token=response.get("access_token")?.asString.orEmpty()
        val refresh=response.get("refresh_token")?.asString?.takeIf{it.isNotBlank()}
        require(token.isNotBlank()){"GitHub 未返回有效访问令牌，请重新登录"}
        fun expiry(name:String):Long {
            val seconds=response.get(name)?.asLong?:return 0
            require(seconds>0){"GitHub 返回了无效令牌有效期"}
            return Math.addExact(now(),Math.multiplyExact(seconds,1000L))
        }
        val expires=expiry("expires_in");val refreshExpires=expiry("refresh_token_expires_in")
        require((expires==0L&&!rotating)||(refresh!=null&&expires>0&&refreshExpires>0)){"GitHub 未返回完整续期凭据，请重新登录"}
        return Credentials(token,refresh,expires,refreshExpires)
    }
    private fun storeCredentials(sync:Boolean,value:Credentials,editor:android.content.SharedPreferences.Editor) {
        val prefix=role(sync)
        editor.putString("$prefix-token",encrypt(value.token)).putLong("$prefix-expires",value.expires).putLong("$prefix-refresh-expires",value.refreshExpires).putBoolean("$prefix-auth-metadata",true).remove("$prefix-reauth")
        if(value.refresh==null)editor.remove("$prefix-refresh-token")else editor.putString("$prefix-refresh-token",encrypt(value.refresh))
    }
    fun login(sync:Boolean,token:String,login:String)=login(sync,JsonObject().apply{addProperty("access_token",token)},login)
    fun login(sync:Boolean,response:JsonObject,login:String){
        require(login.isNotBlank()){"登录结果缺少账号"};val value=credentials(response)
        synchronized(stateLock){
            val changed=user(sync)!=login
            if(!sync&&changed)(context.applicationContext as? org.quietreader.ReaderApp)?.downloads?.cancelAll()
            val editor=preferences.edit()
            if(changed)editor.remove(role(sync)).remove("${role(sync)}-valid")
            storeCredentials(sync,value,editor)
            check(editor.putString("${role(sync)}-user",login).commit()){"保存登录凭据失败，请重新登录"}
            if(!sync&&changed)File(context.noBackupFilesDir,"github-catalog.json").delete()
            if(sync)pauseSync()
        }
    }
    fun user(sync:Boolean)=preferences.getString("${role(sync)}-user","未登录").orEmpty()
    fun logout(sync:Boolean){synchronized(stateLock){
        if(!sync)(context.applicationContext as? org.quietreader.ReaderApp)?.downloads?.cancelAll()
        val editor=preferences.edit();val prefix=role(sync)
        listOf("token","user","valid","refresh-token","expires","refresh-expires","auth-metadata","reauth").forEach{editor.remove("$prefix-$it")}
        editor.remove(prefix).commit()
        if(sync)pauseSync()else File(context.noBackupFilesDir,"github-catalog.json").delete()
    }}
    fun sessionStatus(sync:Boolean):String {
        val prefix=role(sync)
        return when {
            !preferences.contains("$prefix-token")->"请登录自己的 GitHub 账号"
            preferences.getBoolean("$prefix-reauth",false)->"续期凭据已失效，请重新登录；仓库配置保留"
            preferences.contains("$prefix-refresh-token")->"已启用自动续期"
            !preferences.getBoolean("$prefix-auth-metadata",false)->"旧版登录：重新登录一次后可启用自动续期，保留原仓库"
            else->"当前令牌不自动过期"
        }
    }
    /** Must be used on the request's worker thread; UI checks only read the stored token. */
    fun accessToken(sync:Boolean,rejected:String?=null):String=synchronized(refreshLocks[if(sync)1 else 0]){
        val prefix=role(sync)
        val snapshot=synchronized(stateLock){
            check(!preferences.getBoolean("$prefix-reauth",false)){"续期凭据已失效，请重新登录；仓库配置保留"}
            Triple(preferences.getString("$prefix-token",null),preferences.getString("$prefix-refresh-token",null),preferences.getLong("$prefix-expires",0))
        }
        val encoded=snapshot.first?:throw IllegalStateException("请先登录自己的 GitHub 账号")
        val current=decrypt(encoded)
        if(rejected!=null&&current!=rejected)return@synchronized current
        if(rejected==null&&(snapshot.third==0L||now()<snapshot.third-60_000))return@synchronized current
        val refresh=snapshot.second?:throw IllegalStateException("当前登录无法自动续期，请重新登录一次；账号和仓库配置保留")
        val refreshExpiry=preferences.getLong("$prefix-refresh-expires",0)
        fun requireReauthentication():Nothing {
            synchronized(stateLock){if(preferences.getString("$prefix-token",null)==encoded&&preferences.getString("$prefix-refresh-token",null)==refresh)preferences.edit().putBoolean("$prefix-reauth",true).commit()}
            throw IllegalStateException("续期凭据已失效，请重新登录；仓库配置保留")
        }
        if(refreshExpiry>0&&now()>=refreshExpiry)requireReauthentication()
        val response=try{refreshRequest(mapOf("client_id" to if(sync)GitHubApi.STATE_CLIENT else GitHubApi.LIBRARY_CLIENT,"grant_type" to "refresh_token","refresh_token" to decrypt(refresh)))}catch(error:GitHubApi.Failure){
            if(error.status==401||error.oauthError in listOf("bad_refresh_token","invalid_grant","expired_token","access_denied"))requireReauthentication()
            if(error.oauthError in listOf("incorrect_client_credentials","invalid_client","device_flow_disabled"))throw IllegalStateException(GitHubApi.authorizationError(error.oauthError))
            throw IOException("GitHub 暂时无法完成续期，请稍后重试",error)
        }
        if(response.has("error")){
            when(response.get("error").asString){"bad_refresh_token","invalid_grant","expired_token","access_denied"->requireReauthentication();else->throw IOException("GitHub 暂时无法完成续期，请稍后重试")}
        }
        val updated=credentials(response,rotating=true)
        synchronized(stateLock){
            check(preferences.getString("$prefix-token",null)==encoded&&preferences.getString("$prefix-refresh-token",null)==refresh){"登录状态已变化，请重试当前操作"}
            val editor=preferences.edit();storeCredentials(sync,updated,editor)
            check(editor.commit()){"保存续期凭据失败，请重新登录"}
        }
        updated.token
    }
    fun api(sync:Boolean):GitHubApi {
        val account=user(sync)
        fun checked(rejected:String?=null):String {
            check(user(sync)==account){"账号已更换，请重试当前操作"}
            return accessToken(sync,rejected).also{check(user(sync)==account){"账号已更换，请重试当前操作"}}
        }
        return GitHubApi(accessToken={checked()},refreshUnauthorized={checked(it)})
    }
}
