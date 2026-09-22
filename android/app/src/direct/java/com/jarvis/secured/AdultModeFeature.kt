package com.jarvis.secured

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

object AdultModeFeature {
    fun createSettingsView(activity:Activity):View {
        fun dp(v:Int)=(v*activity.resources.displayMetrics.density).toInt()
        val root=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(18),dp(18),dp(18),dp(18));background=GradientDrawable().apply{setColor(Color.parseColor("#101826"));setStroke(dp(1),Color.parseColor("#24334A"));cornerRadius=dp(20).toFloat()}}
        root.addView(TextView(activity).apply{text="Private 18+ mode · direct edition";textSize=20f;setTextColor(Color.WHITE)})
        root.addView(TextView(activity).apply{text="For consenting adults only. An optional PIN can lock the mode.";textSize=14f;setTextColor(Color.LTGRAY)})
        val status=TextView(activity).apply{textSize=14f;setTextColor(Color.LTGRAY);setPadding(0,dp(8),0,dp(8))}
        fun refresh(){status.text=when{!AdultModeManager.isConfigured(activity)->"Not configured";AdultModeManager.isEnabled(activity)->"Enabled${if(AdultModeManager.hasPin(activity))" · PIN protected" else " · no PIN"}";else->"Locked · PIN protected"}}
        val setup=Button(activity).apply{text="Set up or unlock";isAllCaps=false;setOnClickListener{
            if(AdultModeManager.isConfigured(activity)&&AdultModeManager.hasPin(activity)&&!AdultModeManager.isEnabled(activity)){
                val pin=EditText(activity).apply{hint="PIN";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
                AlertDialog.Builder(activity).setTitle("Unlock private mode").setView(pin).setNegativeButton("Cancel",null).setPositiveButton("Unlock"){_,_->if(!AdultModeManager.unlock(activity,pin.text.toString()))status.text="Incorrect PIN or temporarily locked" else refresh()}.show()
            }else{
                val pin=EditText(activity).apply{hint="Optional PIN (4+ characters)";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD}
                AlertDialog.Builder(activity).setTitle("Confirm adult access").setMessage("By continuing, you confirm that you are at least 18. This private mode permits consensual fictional-adult chat and images. It never permits minors, ambiguous ages, coercion, exploitation, incest, bestiality, or sexualized real-person likenesses. Leave the PIN blank only if you want it unsecured.").setView(pin).setNegativeButton("Cancel",null).setPositiveButton("I am 18+ · enable"){_,_->runCatching{AdultModeManager.configure(activity,pin.text.toString())}.onFailure{status.text=it.message}.onSuccess{refresh()}}.show()
            }
        }}
        val lock=Button(activity).apply{text="Lock private mode";isAllCaps=false;setOnClickListener{AdultModeManager.lock(activity);refresh()}}
        root.addView(status);root.addView(setup,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));root.addView(lock,ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));refresh();return root
    }
}
