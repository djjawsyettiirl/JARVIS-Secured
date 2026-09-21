package com.jarvis.secured

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AdultModeManager {
    private const val PREFS = "jarvis_adult_mode"
    private val forbidden = Regex("(?i)\\b(child|kid|minor|underage|teen|schoolgirl|schoolboy|rape|forced|unconscious|incest|bestial|animal sex|undress|deepfake)\\b")
    fun isConfigured(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("age_confirmed", false)
    fun isEnabled(context: Context) = isConfigured(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("enabled", false)
    fun hasPin(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("pin_hash")
    fun configure(context: Context, pin: String?) {
        val editor=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("age_confirmed",true).putBoolean("enabled",true)
        if(pin.isNullOrBlank())editor.remove("pin_salt").remove("pin_hash") else {
            require(pin.length>=4){"PIN must contain at least four characters"};val salt=ByteArray(16).also(SecureRandom()::nextBytes)
            editor.putString("pin_salt",Base64.encodeToString(salt,Base64.NO_WRAP)).putString("pin_hash",Base64.encodeToString(hash(pin,salt),Base64.NO_WRAP))
        };editor.apply()
    }
    fun unlock(context:Context,pin:String):Boolean {
        val prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE)
        if(System.currentTimeMillis()<prefs.getLong("locked_until",0))return false
        val expected=prefs.getString("pin_hash",null)?:return isConfigured(context)
        val salt=Base64.decode(prefs.getString("pin_salt",""),Base64.NO_WRAP)
        val valid=MessageDigest.isEqual(hash(pin,salt),Base64.decode(expected,Base64.NO_WRAP))
        if(valid)prefs.edit().putBoolean("enabled",true).putInt("failed_attempts",0).remove("locked_until").apply()
        else {val failures=prefs.getInt("failed_attempts",0)+1;val edit=prefs.edit().putInt("failed_attempts",failures);if(failures>=5)edit.putLong("locked_until",System.currentTimeMillis()+30_000);edit.apply()}
        return valid
    }
    fun lock(context:Context)=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
    fun validateImagePrompt(prompt:String,adultMode:Boolean){if(forbidden.containsMatchIn(prompt))error("That image request is not allowed");if(!adultMode&&Regex("(?i)\\b(nude|naked|explicit|sex|porn)\\b").containsMatchIn(prompt))error("Unlock the private mode first")}
    fun systemInstruction()="Private adult mode is enabled for consensual fictional adults only. Never generate minors or ambiguous ages, coercion, incest, bestiality, exploitation, or sexualized real-person likenesses."
    private fun hash(pin:String,salt:ByteArray):ByteArray{val spec=PBEKeySpec(pin.toCharArray(),salt,120_000,256);return try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}finally{spec.clearPassword()}}
}
