package com.shadowdevelopers.i230529_i230723

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth

/**
 * Splash (01). Visible for exactly 5 seconds. The profile check starts immediately in parallel,
 * so at t = 5 s the screen can decide without waiting:
 *   logged out -> Log in
 *   logged in with a finished profile (from the database, or remembered on this phone) -> Home
 *   logged in but the profile is missing or unfinished -> Edit profile (setup step)
 *   logged in but the profile could not be read and nothing is remembered -> sign out, Log in
 *     (never show an empty Home to someone whose account the app cannot load)
 */
class MainActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private var profileComplete: Boolean? = null
    private var checkFailed = false
    private val navigate = Runnable { route() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) {
            profileComplete = Session.profileComplete(this, user.uid)
            FirebaseData.users.child(user.uid).child("profileSetupComplete").get()
                .addOnSuccessListener { snapshot ->
                    // A missing value means the profile was never saved: treat it as unfinished.
                    val complete = snapshot.getValue(Boolean::class.java) == true
                    profileComplete = complete
                    Session.setProfileComplete(this, user.uid, complete)
                }
                .addOnFailureListener { checkFailed = true }
        }
        handler.postDelayed(navigate, 5000)
    }

    private fun route() {
        val user = FirebaseAuth.getInstance().currentUser
        val target = when {
            user == null -> LoginActivity::class.java
            profileComplete == true -> HomeActivity::class.java
            profileComplete == false -> EditProfileActivity::class.java
            checkFailed -> {
                // Could not read the profile (for example the database refused access): start again from Log in.
                FirebaseAuth.getInstance().signOut()
                android.widget.Toast.makeText(this, "Could not load your account. Please log in again.", android.widget.Toast.LENGTH_LONG).show()
                LoginActivity::class.java
            }
            // Still waiting on a slow network after 5 s and nothing remembered: the setup screen loads the profile itself.
            else -> EditProfileActivity::class.java
        }
        Session.startFresh(this, target)
    }

    override fun onDestroy() {
        // If the user leaves during the 5 s, don't open another screen later.
        handler.removeCallbacks(navigate)
        super.onDestroy()
    }
}
